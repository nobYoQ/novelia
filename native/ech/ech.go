// Package ech bridges a pinned ECH transport to Android without buffering entire files.
package ech

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptrace"
	"net/url"
	"strings"
	"sync"
	"time"

	core "github.com/inqadh/jissr-bypass"
)

// Client owns reusable TLS connections and DNS/ECH caches. All protected calls
// require accepted ECH; there is no automatic plaintext-SNI fallback.
type Client struct {
	core      *core.Client
	probe     *core.Client
	transport *http.Transport
	do        func(*http.Request) (*http.Response, error)
}

func NewClient() *Client {
	config := core.DefaultConfig()
	// Each provider supplies both A and HTTPS records. Do not mix their answers.
	// IP-literal endpoints also work when the system DNS resolver is unavailable.
	config.WireResolvers = []string{
		"https://dns.alidns.com/dns-query", "https://223.5.5.5/dns-query",
		"https://1.1.1.1/dns-query", "https://cloudflare-dns.com/dns-query",
		"https://8.8.8.8/dns-query",
	}
	config.JSONResolvers = []string{"https://cloudflare-dns.com/dns-query", "https://dns.google/resolve"}
	// This upstream client only supplies encrypted resolution. Actual HTTP calls
	// use the local transport below and the per-call phase deadlines.
	config.RequestTimeout = 24 * time.Hour
	config.UserAgent = "Novelia-ECH/1"
	c, err := core.NewClient(config)
	if err != nil {
		panic("invalid built-in ECH configuration")
	}
	config.RequestTimeout = 20 * time.Second
	p, err := core.NewClient(config)
	if err != nil {
		panic("invalid built-in ECH probe configuration")
	}
	transport := newHTTPTransport(c)
	httpClient := &http.Client{Transport: transport,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	return &Client{core: c, probe: p, transport: transport, do: httpClient.Do}
}

func protectedURL(raw string) bool {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "https" || u.User != nil || (u.Port() != "" && u.Port() != "443") {
		return false
	}
	switch u.Hostname() {
	case "n.novelia.cc", "auth.novelia.cc", "forum.novelia.cc":
		return true
	}
	return false
}

// Call can be cancelled while connecting, uploading, awaiting headers or reading.
type Call struct {
	client         *Client
	request        *http.Request
	connectTimeout time.Duration
	readTimeout    time.Duration
	writeTimeout   time.Duration
	ctx            context.Context
	cancel         context.CancelFunc
	mu             sync.Mutex
	readMu         sync.Mutex
	executed       bool
	body           io.ReadCloser
	failure        string
	diagnostics    *connectionDiagnostics
}

// Upload is fed by a bounded Android pipe; no request body is saved to disk.
type Upload interface {
	ReadChunk(maxBytes int) ([]byte, error)
	Close()
}

type uploadReader struct{ source Upload }

func (r *uploadReader) Read(p []byte) (int, error) {
	chunk, err := r.source.ReadChunk(len(p))
	if err != nil {
		return 0, errors.New("upload read failed")
	}
	if len(chunk) > len(p) {
		return 0, errors.New("upload chunk too large")
	}
	if len(chunk) == 0 {
		return 0, io.EOF
	}
	return copy(p, chunk), nil
}
func (r *uploadReader) Close() error { r.source.Close(); return nil }

func (c *Client) NewCall(method, rawURL, headersJSON string, upload Upload, contentLength, connectTimeoutMillis, readTimeoutMillis, writeTimeoutMillis int64) (*Call, error) {
	if !protectedURL(rawURL) {
		return nil, errors.New("ECH destination is outside the configured HTTPS hosts")
	}
	for _, value := range []int64{connectTimeoutMillis, readTimeoutMillis, writeTimeoutMillis} {
		if value < 0 || value > 2147483647 {
			return nil, errors.New("invalid phase timeout")
		}
	}
	var headers map[string][]string
	if err := json.Unmarshal([]byte(headersJSON), &headers); err != nil {
		return nil, errors.New("invalid request headers")
	}
	ctx, cancel := context.WithCancel(context.Background())
	diagnostics := &connectionDiagnostics{start: time.Now()}
	ctx = context.WithValue(ctx, diagnosticKey{}, diagnostics)
	request, err := http.NewRequestWithContext(ctx, method, rawURL, nil)
	if err != nil {
		cancel()
		return nil, errors.New("invalid request")
	}
	for name, values := range headers {
		switch strings.ToLower(name) {
		case "host", "content-length", "connection", "transfer-encoding":
			continue
		}
		for _, value := range values {
			request.Header.Add(name, value)
		}
	}
	if request.Header.Get("User-Agent") == "" {
		request.Header.Set("User-Agent", "Novelia-ECH/1")
	}
	if upload != nil {
		request.Body = &uploadReader{source: upload}
		request.ContentLength = contentLength
		// GetBody stays nil: a non-idempotent upload is never automatically replayed.
	}
	return &Call{client: c, request: request,
		connectTimeout: time.Duration(connectTimeoutMillis) * time.Millisecond,
		readTimeout:    time.Duration(readTimeoutMillis) * time.Millisecond,
		writeTimeout:   time.Duration(writeTimeoutMillis) * time.Millisecond,
		ctx:            ctx, cancel: cancel, diagnostics: diagnostics}, nil
}

// Reply contains headers only. The body remains streamed through Call.Read.
type Reply struct {
	code     int
	protocol string
	headers  string
	length   int64
}

func (r *Reply) StatusCode() int      { return r.code }
func (r *Reply) Protocol() string     { return r.protocol }
func (r *Reply) HeadersJSON() string  { return r.headers }
func (r *Reply) ContentLength() int64 { return r.length }

func (c *Call) Execute() (*Reply, error) {
	c.mu.Lock()
	if c.executed {
		c.mu.Unlock()
		return nil, errors.New("call has already been executed")
	}
	c.executed = true
	c.mu.Unlock()
	if err := c.ctx.Err(); err != nil {
		return nil, err
	}
	phase := newPhaseDeadline(func(reason string) {
		stage := "response_headers"
		if reason == "请求发送超时" {
			stage = "request_sent"
		}
		diagnostic(c.ctx, diagnosticEvent{Stage: stage, Outcome: "failed", Reason: "timeout"})
		c.fail(reason)
		c.Cancel()
	})
	defer phase.stop()
	trace := &httptrace.ClientTrace{
		GotConn: func(info httptrace.GotConnInfo) {
			outcome := "new"
			if info.Reused {
				outcome = "reused"
			}
			diagnostic(c.ctx, diagnosticEvent{Stage: "connection", Outcome: outcome})
			phase.start(c.writeTimeout, "请求发送超时")
		},
		WroteRequest: func(info httptrace.WroteRequestInfo) {
			diagnostic(c.ctx, diagnosticEvent{Stage: "request_sent", Outcome: "done", Reason: diagnosticReason(info.Err)})
			phase.start(c.readTimeout, "响应读取超时")
		},
		GotFirstResponseByte: func() { diagnostic(c.ctx, diagnosticEvent{Stage: "first_byte", Outcome: "ok"}) },
	}
	request := c.request.Clone(httptrace.WithClientTrace(c.request.Context(), trace))
	response, err := doWithConnectRetry(c.client.do, request, c.connectTimeout)
	phase.stop()
	if err != nil {
		diagnostic(c.ctx, diagnosticEvent{Stage: "request", Outcome: "failed", Reason: diagnosticReason(err)})
		c.fail(failureReason(err.Error()))
		if c.request.Body != nil {
			c.request.Body.Close()
		}
		c.Cancel()
		return nil, errors.New("ECH connection or request failed")
	}
	if response.TLS == nil || !response.TLS.ECHAccepted || response.TLS.Version != tls.VersionTLS13 {
		diagnostic(c.ctx, diagnosticEvent{Stage: "tls", Outcome: "failed", Reason: "ech_not_accepted"})
		response.Body.Close()
		c.Cancel()
		return nil, errors.New("server did not accept TLS 1.3 ECH")
	}
	c.mu.Lock()
	if c.ctx.Err() != nil {
		c.mu.Unlock()
		response.Body.Close()
		return nil, errors.New("call cancelled")
	}
	c.body = response.Body
	c.mu.Unlock()
	headers, err := json.Marshal(response.Header)
	if err != nil {
		c.Cancel()
		return nil, errors.New("invalid response headers")
	}
	diagnostic(c.ctx, diagnosticEvent{Stage: "response_headers", Outcome: "ok", Protocol: response.Proto})
	return &Reply{code: response.StatusCode, protocol: response.Proto, headers: string(headers), length: response.ContentLength}, nil
}

func (c *Call) fail(reason string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.failure == "" {
		c.failure = reason
	}
}

// FailureReason contains a fixed diagnostic label, never raw errors or URLs.
func (c *Call) FailureReason() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.failure
}

// Read returns an empty slice at EOF; each read is bounded to 64 KiB.
func (c *Call) Read(maxBytes int) ([]byte, error) {
	if maxBytes <= 0 {
		return nil, errors.New("invalid read size")
	}
	if maxBytes > 65536 {
		maxBytes = 65536
	}
	c.readMu.Lock()
	defer c.readMu.Unlock()
	c.mu.Lock()
	body := c.body
	c.mu.Unlock()
	if body == nil {
		return nil, errors.New("response is not open")
	}
	if err := c.ctx.Err(); err != nil {
		return nil, err
	}
	deadline := newPhaseDeadline(func(reason string) {
		diagnostic(c.ctx, diagnosticEvent{Stage: "body", Outcome: "failed", Reason: "timeout"})
		c.fail(reason)
		c.Cancel()
	})
	deadline.start(c.readTimeout, "响应读取超时")
	buffer := make([]byte, maxBytes)
	n, err := body.Read(buffer)
	deadline.stop()
	if n > 0 {
		return buffer[:n], nil
	}
	if err == io.EOF {
		diagnostic(c.ctx, diagnosticEvent{Stage: "body", Outcome: "complete"})
		c.Cancel()
		return []byte{}, nil
	}
	if err != nil {
		diagnostic(c.ctx, diagnosticEvent{Stage: "body", Outcome: "failed", Reason: diagnosticReason(err)})
		c.Cancel()
		return nil, errors.New("ECH response read failed")
	}
	// net/http response bodies do not return (0, nil) for a non-empty buffer.
	return nil, errors.New("empty response read")
}

func (c *Call) Cancel() {
	c.cancel()
	if c.request.Body != nil {
		c.request.Body.Close()
	}
	c.mu.Lock()
	body := c.body
	c.body = nil
	c.mu.Unlock()
	if body != nil {
		body.Close()
	}
}

// Probe creates a fresh handshake, without account data or an HTTP request.
func (c *Client) Probe(host string) string {
	if !protectedURL("https://" + host + "/") {
		return "不支持的诊断域名"
	}
	result := c.probe.Probe(host)
	if result.OK {
		return "TLS 1.3 / ECH 握手成功"
	}
	switch result.Stage {
	case "resolve":
		return "DNS / ECH 配置获取失败"
	case "tcp":
		return "TCP 连接失败：" + failureReason(result.Detail)
	default:
		return "ECH 握手失败：" + failureReason(result.Detail)
	}
}

func (c *Client) ResetNetworkState() {
	c.core.ResetNetworkState()
	c.probe.ResetNetworkState()
	c.transport.CloseIdleConnections()
}

// Map diagnostics to fixed labels; never display local addresses or raw errors.
func failureReason(detail string) string {
	text := strings.ToLower(detail)
	switch {
	case strings.Contains(text, "x509"), strings.Contains(text, "certificate"):
		return "证书验证未通过"
	case strings.Contains(text, "deadline"), strings.Contains(text, "timeout"), strings.Contains(text, "context canceled"):
		return "连接超时"
	case strings.Contains(text, "reset"), strings.Contains(text, "forcibly closed"), strings.Contains(text, "eof"):
		return "连接被中断"
	case strings.Contains(text, "rejected"):
		return "服务器拒绝 ECH 配置"
	default:
		return "TLS 协商未完成"
	}
}
