package ech

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"
)

type proxyResolverFunc func(string) (string, error)

func (f proxyResolverFunc) ResolveProxy(value string) (string, error) { return f(value) }

func TestConnectionFailureLabelsKeepTransientFailuresDistinctFromTLS(t *testing.T) {
	for _, detail := range []string{"tcp: connection refused", "resolve: dial tcp: unreachable", "resolve: no such host"} {
		if got := failureReason(detail); got != "连接失败" {
			t.Fatalf("transient failure became %s", got)
		}
	}
	for detail, expected := range map[string]string{
		"proxy CONNECT rejected":               "系统代理连接失败",
		"proxy authentication required":        "系统代理需要认证",
		"unsupported system proxy":             "系统代理配置不受支持",
		"proxy TLS: x509 certificate rejected": "证书验证未通过",
		"ECH was not accepted":                 "TLS 协商未完成",
	} {
		if got := failureReason(detail); got != expected {
			t.Fatalf("got %s, want %s", got, expected)
		}
	}
}

func TestProxySelectionRejectsUnsupportedRoutes(t *testing.T) {
	for _, value := range []string{"socks5://127.0.0.1:8080", "https://127.0.0.1:8080", "http://127.0.0.1", "http://127.0.0.1:0", "http://127.0.0.1:8080/path"} {
		if _, err := selectedProxy(proxyResolverFunc(func(string) (string, error) { return value, nil }), "https://example.com"); err == nil {
			t.Fatalf("accepted unsupported proxy %s", value)
		}
	}
	if _, err := selectedProxy(proxyResolverFunc(func(string) (string, error) { return "", errors.New("private details") }), "https://example.com"); err == nil || strings.Contains(err.Error(), "private details") {
		t.Fatal("selection failure was ignored or leaked")
	}
}

func TestProxyConnectUsesResolvedIPAndPreservesBufferedBytes(t *testing.T) {
	requests := make(chan string, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests <- r.Method + " " + r.Host
		connection, rw, err := w.(http.Hijacker).Hijack()
		if err != nil {
			return
		}
		defer connection.Close()
		fmt.Fprint(rw, "HTTP/1.1 200 Connection Established\r\n\r\nhello")
		rw.Flush()
		io.Copy(connection, rw)
	}))
	defer server.Close()
	proxy, _ := url.Parse(server.URL)
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	connection, err := dialECHSocket(ctx, "tcp", "192.0.2.1:443", proxy)
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	connection.SetDeadline(time.Now().Add(time.Second))
	data := make([]byte, 5)
	if _, err := io.ReadFull(connection, data); err != nil || string(data) != "hello" {
		t.Fatalf("buffer lost: %q %v", data, err)
	}
	if _, err := connection.Write([]byte("world")); err != nil {
		t.Fatal(err)
	}
	if _, err := io.ReadFull(connection, data); err != nil || string(data) != "world" {
		t.Fatalf("tunnel failed: %q %v", data, err)
	}
	if got := <-requests; got != "CONNECT 192.0.2.1:443" {
		t.Fatal(got)
	}
	if connection.RemoteAddr().String() != "192.0.2.1:443" {
		t.Fatal("reported proxy as destination")
	}
}

func TestProxyRejectionNeverFallsBackToDirect(t *testing.T) {
	for _, status := range []int{407, 502} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			target := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { t.Error("fell back to direct") }))
			defer target.Close()
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(status) }))
			defer server.Close()
			proxy, _ := url.Parse(server.URL)
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			if conn, err := dialECHSocket(ctx, "tcp", strings.TrimPrefix(target.URL, "http://"), proxy); err == nil || conn != nil {
				t.Fatal("ignored proxy rejection")
			}
		})
	}
}

func TestProxyConnectCancellationClosesSocket(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	received := make(chan struct{})
	closed := make(chan struct{})
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		reader := bufio.NewReader(conn)
		http.ReadRequest(reader)
		close(received)
		io.Copy(io.Discard, reader)
		close(closed)
	}()
	proxy, _ := url.Parse("http://" + listener.Addr().String())
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { _, err := dialECHSocket(ctx, "tcp", "192.0.2.1:443", proxy); done <- err }()
	select {
	case <-received:
	case <-time.After(time.Second):
		t.Fatal("CONNECT not received")
	}
	cancel()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("ignored cancellation")
		}
	case <-time.After(time.Second):
		t.Fatal("cancel blocked")
	}
	select {
	case <-closed:
	case <-time.After(time.Second):
		t.Fatal("socket leaked")
	}
}

func TestResolverTransportSelectsEveryURLAndRotatesProxyPool(t *testing.T) {
	var mu sync.Mutex
	var selected []string
	proxy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { fmt.Fprint(w, "proxied") }))
	defer proxy.Close()
	transport := newResolverHTTPTransport(proxyResolverFunc(func(rawURL string) (string, error) {
		mu.Lock()
		defer mu.Unlock()
		selected = append(selected, rawURL)
		return proxy.URL, nil
	}))
	defer transport.current.CloseIdleConnections()
	for _, address := range []string{"http://resolver-a.invalid/dns-query", "http://resolver-b.invalid/resolve"} {
		request, _ := http.NewRequest(http.MethodGet, address, nil)
		response, err := transport.RoundTrip(request)
		if err != nil {
			t.Fatal(err)
		}
		data, _ := io.ReadAll(response.Body)
		response.Body.Close()
		if string(data) != "proxied" {
			t.Fatal("DoH bypassed proxy")
		}
	}
	if len(selected) != 2 || selected[0] == selected[1] {
		t.Fatal(selected)
	}
	old := transport.current
	transport.reset()
	defer transport.current.CloseIdleConnections()
	if old == transport.current {
		t.Fatal("reused old DoH pool")
	}
}
