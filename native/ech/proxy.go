package ech

import (
	"bufio"
	"context"
	"errors"
	"io"
	"math"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"sync"
	"time"
)

// ProxyResolver is implemented by Android's system ProxySelector, including PAC/exclusions.
// Empty means an explicit direct route; selection errors never fall back to direct.
type ProxyResolver interface {
	ResolveProxy(rawURL string) (string, error)
}

func selectedProxy(resolver ProxyResolver, rawURL string) (*url.URL, error) {
	if resolver == nil {
		return nil, nil
	}
	value, err := resolver.ResolveProxy(rawURL)
	if err != nil {
		return nil, errors.New("system proxy selection failed")
	}
	if value == "" {
		return nil, nil
	}
	proxy, err := url.Parse(value)
	if err != nil || proxy.Scheme != "http" || proxy.Hostname() == "" || proxy.User != nil ||
		proxy.RawQuery != "" || proxy.Fragment != "" || (proxy.Path != "" && proxy.Path != "/") {
		return nil, errors.New("unsupported system proxy")
	}
	port, err := strconv.Atoi(proxy.Port())
	if err != nil || port < 1 || port > 65535 {
		return nil, errors.New("invalid system proxy port")
	}
	return proxy, nil
}

type resolverHTTPTransport struct {
	mu      sync.RWMutex
	current *http.Transport
	proxy   ProxyResolver
}

func newResolverHTTPTransport(proxy ProxyResolver) *resolverHTTPTransport {
	t := &resolverHTTPTransport{proxy: proxy}
	t.reset()
	return t
}

func (t *resolverHTTPTransport) reset() {
	next := http.DefaultTransport.(*http.Transport).Clone()
	next.Proxy = func(request *http.Request) (*url.URL, error) { return selectedProxy(t.proxy, request.URL.String()) }
	t.mu.Lock()
	old := t.current
	t.current = next
	t.mu.Unlock()
	if old != nil {
		old.CloseIdleConnections()
	}
}

func (t *resolverHTTPTransport) RoundTrip(request *http.Request) (*http.Response, error) {
	t.mu.RLock()
	current := t.current
	t.mu.RUnlock()
	return current.RoundTrip(request)
}

type bufferedProxyConn struct {
	net.Conn
	reader *bufio.Reader
	target net.Addr
}

func (c *bufferedProxyConn) Read(data []byte) (int, error) { return c.reader.Read(data) }
func (c *bufferedProxyConn) RemoteAddr() net.Addr          { return c.target }

// CONNECT exposes the resolved IP, while the origin hostname stays inside the ECH handshake.
func dialECHSocket(ctx context.Context, network, target string, proxy *url.URL) (connection net.Conn, err error) {
	address := target
	if proxy != nil {
		address = proxy.Host
	}
	raw, err := (&net.Dialer{KeepAlive: 30 * time.Second}).DialContext(ctx, network, address)
	if err != nil {
		return nil, err
	}
	if proxy == nil {
		return raw, nil
	}
	stop := context.AfterFunc(ctx, func() { raw.Close() })
	defer func() {
		stop()
		if err != nil {
			raw.Close()
		}
	}()
	if deadline, ok := ctx.Deadline(); ok {
		raw.SetDeadline(deadline)
	}
	request := &http.Request{Method: http.MethodConnect, URL: &url.URL{Opaque: target}, Host: target, Header: make(http.Header)}
	if err = request.Write(raw); err != nil {
		return nil, errors.New("proxy CONNECT write failed")
	}
	limited := &io.LimitedReader{R: raw, N: 64 << 10}
	reader := bufio.NewReader(limited)
	response, readErr := http.ReadResponse(reader, request)
	if readErr != nil || limited.N <= 0 {
		return nil, errors.New("proxy CONNECT response failed")
	}
	if response.StatusCode == http.StatusProxyAuthRequired {
		return nil, errors.New("proxy authentication required")
	}
	if response.StatusCode != http.StatusOK {
		return nil, errors.New("proxy CONNECT rejected")
	}
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	limited.N = math.MaxInt64
	raw.SetDeadline(time.Time{})
	targetAddress, resolveErr := net.ResolveTCPAddr(network, target)
	if resolveErr != nil {
		return nil, errors.New("invalid CONNECT destination")
	}
	return &bufferedProxyConn{Conn: raw, reader: reader, target: targetAddress}, nil
}
