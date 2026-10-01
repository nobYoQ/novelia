package ech

import (
	"bytes"
	"context"
	"crypto/tls"
	"errors"
	"io"
	"net/http"
	"net/http/httptrace"
	"testing"
	"time"
)

func gotConnection(r *http.Request) {
	if trace := httptrace.ContextClientTrace(r.Context()); trace != nil && trace.GotConn != nil {
		trace.GotConn(httptrace.GotConnInfo{})
	}
}

func TestConnectRetryRecoversBeforeSendingRequest(t *testing.T) {
	r, _ := http.NewRequest("GET", "https://forum.novelia.cc/", nil)
	attempts := 0
	_, err := doWithConnectRetry(func(r *http.Request) (*http.Response, error) {
		attempts++
		if attempts < 3 {
			return nil, io.EOF
		}
		gotConnection(r)
		return response(io.NopCloser(bytes.NewReader(nil))), nil
	}, r, time.Second)
	if err != nil || attempts != 3 {
		t.Fatalf("attempts=%d, error=%v", attempts, err)
	}
}

func TestConnectRetryNeverReplaysAfterConnectionOrWrite(t *testing.T) {
	for _, method := range []string{"GET", "POST", "PUT", "PATCH", "DELETE"} {
		r, _ := http.NewRequest(method, "https://forum.novelia.cc/", nil)
		attempts := 0
		_, _ = doWithConnectRetry(func(r *http.Request) (*http.Response, error) {
			attempts++
			gotConnection(r)
			return nil, io.EOF
		}, r, time.Second)
		if attempts != 1 {
			t.Fatalf("replayed %s after obtaining connection", method)
		}
	}
	r, _ := http.NewRequest("POST", "https://forum.novelia.cc/", bytes.NewBufferString("example"))
	attempts := 0
	_, _ = doWithConnectRetry(func(*http.Request) (*http.Response, error) { attempts++; return nil, io.EOF }, r, time.Second)
	if attempts != 1 {
		t.Fatal("retried upload")
	}
}

func TestConnectTimeoutIsBoundedAndDoesNotExpireSuccessfulBody(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	r, _ := http.NewRequestWithContext(ctx, "GET", "https://forum.novelia.cc/", nil)
	attempts := 0
	start := time.Now()
	_, err := doWithConnectRetry(func(r *http.Request) (*http.Response, error) {
		attempts++
		<-r.Context().Done()
		return nil, r.Context().Err()
	}, r, 15*time.Millisecond)
	if err == nil || attempts != 3 || time.Since(start) > time.Second {
		t.Fatal("connection budget failed")
	}
	var bodyContext context.Context
	_, err = doWithConnectRetry(func(r *http.Request) (*http.Response, error) {
		bodyContext = r.Context()
		gotConnection(r)
		time.Sleep(30 * time.Millisecond)
		return response(io.NopCloser(bytes.NewReader(nil))), nil
	}, r, 15*time.Millisecond)
	if err != nil || bodyContext.Err() != nil {
		t.Fatal("successful connection was expired")
	}
	cancel()
	if !errors.Is(bodyContext.Err(), context.Canceled) {
		t.Fatal("parent cancellation not propagated")
	}
}

func TestConnectCancellationStopsRetries(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	r, _ := http.NewRequestWithContext(ctx, "GET", "https://forum.novelia.cc/", nil)
	attempts := 0
	_, _ = doWithConnectRetry(func(*http.Request) (*http.Response, error) { attempts++; cancel(); return nil, io.EOF }, r, time.Second)
	if attempts != 1 {
		t.Fatal("retried cancelled call")
	}
}

func TestConnectTimeoutAlsoBoundsWriteMethodsBeforeConnection(t *testing.T) {
	r, _ := http.NewRequest("POST", "https://forum.novelia.cc/", bytes.NewBufferString("example"))
	attempts := 0
	_, err := doWithConnectRetry(func(r *http.Request) (*http.Response, error) {
		attempts++
		<-r.Context().Done()
		return nil, r.Context().Err()
	}, r, 20*time.Millisecond)
	if err == nil || attempts != 1 {
		t.Fatal("write method connection was not bounded once")
	}
}

func TestCertificateFailuresAreNeverRetried(t *testing.T) {
	r, _ := http.NewRequest("GET", "https://forum.novelia.cc/", nil)
	attempts := 0
	_, _ = doWithConnectRetry(func(*http.Request) (*http.Response, error) {
		attempts++
		return nil, &tls.CertificateVerificationError{Err: errors.New("untrusted certificate")}
	}, r, time.Second)
	if attempts != 1 {
		t.Fatal("retried certificate failure")
	}
}

func TestDetachedTLSDialStillHonorsCancellationAndConfiguredBudget(t *testing.T) {
	for _, timeout := range []time.Duration{0, 20 * time.Millisecond} {
		parent, cancel := context.WithCancel(context.Background())
		ctx := context.WithValue(parent, dialPolicyKey{}, dialPolicy{parent: parent, timeout: timeout})
		dial, stop := connectionContext(context.WithoutCancel(ctx))
		if timeout == 0 {
			cancel()
		}
		select {
		case <-dial.Done():
		case <-time.After(time.Second):
			t.Fatal("detached TLS dial ignored cancellation/deadline")
		}
		stop()
		cancel()
	}
}
