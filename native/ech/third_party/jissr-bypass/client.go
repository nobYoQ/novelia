// Modified by Novelia; see README.novelia.md for the local changes and source.
//
// Package jissrbypass provides an HTTP transport that resolves HTTPS and ECH
// DNS records over DNS-over-HTTPS, then performs TLS 1.3 with Encrypted Client
// Hello. It is intended for native applications whose ordinary DNS or
// plaintext ClientHello is interfered with.
package jissrbypass

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"

	"golang.org/x/net/http/httpguts"
	"golang.org/x/net/idna"
)

const (
	defaultUserAgent = "Jissr-Bypass/0.1"
	maxResponseBytes = 4 << 20
)

// Config controls resolver selection and network deadlines. Empty resolver
// slices use the built-in Cloudflare and Google endpoints.
type Config struct {
	WireResolvers        []string
	JSONResolvers        []string
	ResolveTimeout       time.Duration
	ResolutionTTL        time.Duration
	DialTimeout          time.Duration
	RequestTimeout       time.Duration
	NativeAttemptTimeout time.Duration
	RouteTTL             time.Duration
	NativeFirst          bool
	UserAgent            string
	EventHandler         EventHandler
}

// DefaultConfig returns conservative defaults suitable for mobile and desktop.
func DefaultConfig() Config {
	return Config{
		WireResolvers: []string{
			"https://cloudflare-dns.com/dns-query",
			"https://1.1.1.1/dns-query",
			"https://dns.google/dns-query",
			"https://8.8.8.8/dns-query",
		},
		JSONResolvers: []string{
			"https://cloudflare-dns.com/dns-query",
			"https://1.1.1.1/dns-query",
			"https://8.8.8.8/resolve",
			"https://dns.google/resolve",
		},
		ResolveTimeout:       7 * time.Second,
		ResolutionTTL:        5 * time.Minute,
		DialTimeout:          10 * time.Second,
		RequestTimeout:       30 * time.Second,
		NativeAttemptTimeout: 750 * time.Millisecond,
		RouteTTL:             10 * time.Minute,
		UserAgent:            defaultUserAgent,
	}
}

// Result contains the complete bounded response returned by Execute.
// Headers is a defensive copy and Body is limited to 4 MiB.
type Result struct {
	StatusCode int
	Protocol   string
	Headers    http.Header
	Body       []byte
}

// NewClient validates and normalizes config.
func NewClient(config Config) (*Client, error) {
	defaults := DefaultConfig()
	if len(config.WireResolvers) == 0 {
		config.WireResolvers = defaults.WireResolvers
	}
	if len(config.JSONResolvers) == 0 {
		config.JSONResolvers = defaults.JSONResolvers
	}
	if config.ResolveTimeout <= 0 {
		config.ResolveTimeout = defaults.ResolveTimeout
	}
	if config.ResolutionTTL <= 0 {
		config.ResolutionTTL = defaults.ResolutionTTL
	}
	if config.DialTimeout <= 0 {
		config.DialTimeout = defaults.DialTimeout
	}
	if config.RequestTimeout <= 0 {
		config.RequestTimeout = defaults.RequestTimeout
	}
	if config.NativeAttemptTimeout <= 0 {
		config.NativeAttemptTimeout = defaults.NativeAttemptTimeout
	}
	if config.RouteTTL <= 0 {
		config.RouteTTL = defaults.RouteTTL
	}
	if config.UserAgent == "" {
		config.UserAgent = defaults.UserAgent
	}
	for _, endpoint := range append(append([]string{}, config.WireResolvers...), config.JSONResolvers...) {
		u, err := url.Parse(endpoint)
		if err != nil || u.Scheme != "https" || u.Host == "" {
			return nil, fmt.Errorf("invalid DoH endpoint %q", endpoint)
		}
	}
	return newClient(config), nil
}

// NewDefaultClient constructs a client with Cloudflare and Google fallbacks.
// Its signature is deliberately gomobile-friendly.
func NewDefaultClient() *Client {
	client, _ := NewClient(DefaultConfig())
	return client
}

// NewAutoClient constructs a client that tries the ordinary native transport
// once per host/network decision, then remembers whether native or ECH should
// be used. The bypass-only default remains available through NewDefaultClient.
func NewAutoClient() *Client {
	config := DefaultConfig()
	config.NativeFirst = true
	client, _ := NewClient(config)
	return client
}

// Resolution identifies the address and resolver selected for an ECH request.
type Resolution struct {
	IP       string
	IPs      []string
	Resolver string
	ECH      []byte
	// ExpiresAt is the earliest selected DNS record expiry. A zero or expired
	// value is usable for this lookup, but must never be put in the cache.
	ExpiresAt time.Time
	version   *resolutionVersion
}

// Resolve retrieves mutually consistent A, AAAA and HTTPS/ECH answers from one
// resolver. It never combines an address from one provider with ECH data from
// another.
func (c *Client) Resolve(ctx context.Context, host string) (Resolution, error) {
	host, err := canonicalHost(host)
	if err != nil {
		return Resolution{}, err
	}
	return c.resolveCached(ctx, host)
}

func canonicalHost(host string) (string, error) {
	host = strings.TrimSuffix(strings.TrimSpace(host), ".")
	if host == "" || net.ParseIP(host) != nil {
		return "", fmt.Errorf("host must be a DNS name")
	}
	ascii, err := idna.Lookup.ToASCII(host)
	if err != nil || len(ascii) > 253 {
		return "", fmt.Errorf("invalid DNS hostname")
	}
	ascii = strings.ToLower(ascii)
	for _, label := range strings.Split(ascii, ".") {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return "", fmt.Errorf("invalid DNS hostname")
		}
		for _, character := range label {
			if (character < 'a' || character > 'z') &&
				(character < '0' || character > '9') &&
				character != '-' {
				return "", fmt.Errorf("invalid DNS hostname")
			}
		}
	}
	return ascii, nil
}

// Do sends an HTTPS request through ECH. Redirects are returned to the caller
// rather than followed implicitly, preventing a redirect from escaping the ECH
// transport.
func (c *Client) Do(request *http.Request) (*http.Response, error) {
	if request == nil || request.URL == nil {
		return nil, errors.New("request is required")
	}
	if request.URL.Scheme != "https" {
		return nil, errors.New("Jissr Bypass only permits https URLs")
	}
	if request.URL.User != nil {
		return nil, errors.New("credentials in URLs are not permitted")
	}
	if port := request.URL.Port(); port != "" && port != "443" {
		return nil, errors.New("only HTTPS port 443 is supported")
	}
	host, err := canonicalHost(request.URL.Hostname())
	if err != nil {
		return nil, err
	}
	request = request.Clone(request.Context())
	request.Header = request.Header.Clone()
	return c.doPrepared(request, host)
}

// Execute creates and sends an ECH-protected HTTPS request. Unlike Fetch, it
// returns every HTTP status so applications can handle their own API contract.
func (c *Client) Execute(method, rawURL string, body []byte, headers http.Header) (*Result, error) {
	method = strings.ToUpper(strings.TrimSpace(method))
	if method == "" {
		return nil, errors.New("HTTP method is required")
	}
	ctx, cancel := context.WithTimeout(context.Background(), c.config.RequestTimeout)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, method, rawURL, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	if err := copyRequestHeaders(request.Header, headers); err != nil {
		return nil, err
	}
	response, err := c.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	responseBody, err := io.ReadAll(io.LimitReader(response.Body, maxResponseBytes+1))
	if err != nil {
		return nil, err
	}
	if len(responseBody) > maxResponseBytes {
		return nil, fmt.Errorf("response exceeds %d bytes", maxResponseBytes)
	}
	return &Result{
		StatusCode: response.StatusCode,
		Protocol:   response.Proto,
		Headers:    response.Header.Clone(),
		Body:       responseBody,
	}, nil
}

func copyRequestHeaders(destination, source http.Header) error {
	for name, values := range source {
		if !httpguts.ValidHeaderFieldName(name) {
			return fmt.Errorf("invalid HTTP header name %q", name)
		}
		switch http.CanonicalHeaderKey(name) {
		case "Host", "Content-Length", "Transfer-Encoding", "Connection":
			return fmt.Errorf("HTTP header %q is managed by the transport", name)
		}
		for _, value := range values {
			if !httpguts.ValidHeaderFieldValue(value) {
				return fmt.Errorf("invalid value for HTTP header %q", name)
			}
			destination.Add(name, value)
		}
	}
	return nil
}

// Fetch is the portable mobile/desktop API. It returns the response body only
// for a successful 2xx response.
func (c *Client) Fetch(method, rawURL string, body []byte) ([]byte, error) {
	headers := make(http.Header)
	if len(body) > 0 {
		headers.Set("Content-Type", "application/json")
	}
	response, err := c.Execute(method, rawURL, body, headers)
	if err != nil {
		return nil, err
	}
	if response.StatusCode < 200 || response.StatusCode > 299 {
		return nil, fmt.Errorf("http %d: %.1000s", response.StatusCode, response.Body)
	}
	return response.Body, nil
}

// ProbeResult gives applications actionable diagnostics without exposing
// resolver response bodies or other sensitive data.
type ProbeResult struct {
	OK       bool
	Host     string
	IP       string
	Resolver string
	Stage    string
	Detail   string
}

// Probe resolves and handshakes with host without making an HTTP request.
func (c *Client) Probe(host string) *ProbeResult {
	result := &ProbeResult{Host: host, Stage: "resolve"}
	ctx, cancel := context.WithTimeout(context.Background(), c.config.RequestTimeout)
	defer cancel()
	resolution, err := c.Resolve(ctx, host)
	if err != nil {
		result.Detail = err.Error()
		return result
	}
	result.IP = resolution.IP
	result.Resolver = resolution.Resolver
	result.Stage = "ech"
	connection, err := dialECH(
		ctx,
		&net.Dialer{Timeout: c.config.DialTimeout},
		"tcp",
		resolution.IP,
		host,
		resolution.ECH,
		nil,
		nil,
	)
	if err != nil {
		if strings.HasPrefix(err.Error(), "tcp:") {
			result.Stage = "tcp"
		}
		result.Detail = err.Error()
		return result
	}
	defer connection.Close()
	result.OK = true
	result.Stage = "complete"
	return result
}

// dialECH retries once when the authenticated rejection carries a fresh
// ECHConfigList. This handles normal provider key rotation without falling back
// to a plaintext ClientHello.
func dialECH(
	ctx context.Context,
	dialer *net.Dialer,
	network, ip, host string,
	echConfig []byte,
	protocols []string,
	onRetryConfig func([]byte),
) (net.Conn, error) {
	config := append([]byte(nil), echConfig...)
	for attempt := 0; attempt < 2; attempt++ {
		raw, err := dialer.DialContext(ctx, network, net.JoinHostPort(ip, "443"))
		if err != nil {
			return nil, fmt.Errorf("tcp: %w", err)
		}
		connection := tls.Client(raw, &tls.Config{
			ServerName:                     host,
			EncryptedClientHelloConfigList: config,
			MinVersion:                     tls.VersionTLS13,
			NextProtos:                     protocols,
		})
		err = connection.HandshakeContext(ctx)
		if err == nil {
			state := connection.ConnectionState()
			if !state.ECHAccepted || state.Version != tls.VersionTLS13 {
				_ = raw.Close()
				return nil, errors.New("ECH was not accepted")
			}
			if attempt > 0 && onRetryConfig != nil {
				onRetryConfig(config)
			}
			return connection, nil
		}
		_ = raw.Close()
		var rejection *tls.ECHRejectionError
		if attempt == 0 && errors.As(err, &rejection) && validECHConfigList(rejection.RetryConfigList) {
			config = append([]byte(nil), rejection.RetryConfigList...)
			continue
		}
		return nil, fmt.Errorf("ECH handshake: %w", err)
	}
	return nil, errors.New("ECH handshake failed")
}

// CanaryResult is returned only after the server echoes the unpredictable
// nonce generated by this client.
type CanaryResult struct {
	OK       bool   `json:"ok"`
	Protocol string `json:"protocol"`
	Host     string `json:"host"`
	Nonce    string `json:"nonce"`
	IssuedAt string `json:"issued_at"`
}

// VerifyCanary checks the standard Jissr Bypass canary protocol.
func (c *Client) VerifyCanary(rawURL string) (*CanaryResult, error) {
	parsed, err := url.Parse(rawURL)
	if err != nil || parsed.Scheme != "https" || parsed.Host == "" {
		return nil, fmt.Errorf("invalid HTTPS canary URL")
	}
	nonceBytes := make([]byte, 24)
	if _, err := rand.Read(nonceBytes); err != nil {
		return nil, fmt.Errorf("create nonce: %w", err)
	}
	nonce := base64.RawURLEncoding.EncodeToString(nonceBytes)
	query := parsed.Query()
	query.Set("nonce", nonce)
	parsed.RawQuery = query.Encode()
	body, err := c.Fetch(http.MethodGet, parsed.String(), nil)
	if err != nil {
		return nil, err
	}
	var result CanaryResult
	if err := json.Unmarshal(body, &result); err != nil {
		return nil, fmt.Errorf("decode canary: %w", err)
	}
	if !result.OK || result.Protocol != "jissr-bypass/1" || result.Nonce != nonce {
		return nil, fmt.Errorf("canary verification failed")
	}
	if !strings.EqualFold(result.Host, parsed.Hostname()) {
		return nil, fmt.Errorf("canary host mismatch")
	}
	issuedAt, err := time.Parse(time.RFC3339, result.IssuedAt)
	if err != nil || time.Since(issuedAt) > 5*time.Minute || time.Until(issuedAt) > time.Minute {
		return nil, fmt.Errorf("canary timestamp is invalid or stale")
	}
	return &result, nil
}
