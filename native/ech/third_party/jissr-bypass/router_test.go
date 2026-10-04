package jissrbypass

import (
	"io"
	"net/http"
	"strings"
	"testing"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (function roundTripFunc) RoundTrip(request *http.Request) (*http.Response, error) {
	return function(request)
}

type roundTripRecorder struct {
	function roundTripFunc
}

func (recorder *roundTripRecorder) RoundTrip(request *http.Request) (*http.Response, error) {
	return recorder.function(request)
}

type doFunc func(*http.Request) (*http.Response, error)

func (function doFunc) Do(request *http.Request) (*http.Response, error) {
	return function(request)
}

func testResponse(request *http.Request, marker string) *http.Response {
	return &http.Response{
		StatusCode: http.StatusOK,
		Header:     make(http.Header),
		Body:       io.NopCloser(strings.NewReader(marker)),
		Request:    request,
	}
}

func TestHostRoutingTransportRoutesExactHost(t *testing.T) {
	bypassCalls := 0
	fallbackCalls := 0
	transport, err := NewHostRoutingTransport(
		doFunc(func(request *http.Request) (*http.Response, error) {
			bypassCalls++
			return testResponse(request, "bypass"), nil
		}),
		roundTripFunc(func(request *http.Request) (*http.Response, error) {
			fallbackCalls++
			return testResponse(request, "fallback"), nil
		}),
		"API.Example.com.",
	)
	if err != nil {
		t.Fatal(err)
	}

	protected, _ := http.NewRequest(http.MethodGet, "https://api.example.com/v1", nil)
	if _, err := transport.RoundTrip(protected); err != nil {
		t.Fatal(err)
	}
	ordinary, _ := http.NewRequest(http.MethodGet, "https://www.example.com/", nil)
	if _, err := transport.RoundTrip(ordinary); err != nil {
		t.Fatal(err)
	}
	if bypassCalls != 1 || fallbackCalls != 1 {
		t.Fatalf("unexpected routing: bypass=%d fallback=%d", bypassCalls, fallbackCalls)
	}
}

func TestHostRoutingTransportDoesNotMatchSubdomains(t *testing.T) {
	bypassCalls := 0
	fallbackCalls := 0
	transport, err := NewHostRoutingTransport(
		doFunc(func(request *http.Request) (*http.Response, error) {
			bypassCalls++
			return testResponse(request, "bypass"), nil
		}),
		roundTripFunc(func(request *http.Request) (*http.Response, error) {
			fallbackCalls++
			return testResponse(request, "fallback"), nil
		}),
		"example.com",
	)
	if err != nil {
		t.Fatal(err)
	}

	request, _ := http.NewRequest(http.MethodGet, "https://api.example.com/", nil)
	if _, err := transport.RoundTrip(request); err != nil {
		t.Fatal(err)
	}
	if bypassCalls != 0 || fallbackCalls != 1 {
		t.Fatalf("subdomain matched unexpectedly: bypass=%d fallback=%d", bypassCalls, fallbackCalls)
	}
}

func TestNewHostRoutingHTTPClientDoesNotMutateBase(t *testing.T) {
	fallback := &roundTripRecorder{
		function: func(request *http.Request) (*http.Response, error) {
			return testResponse(request, "fallback"), nil
		},
	}
	base := &http.Client{Transport: fallback}
	routed, err := NewHostRoutingHTTPClient(
		base,
		doFunc(func(request *http.Request) (*http.Response, error) {
			return testResponse(request, "bypass"), nil
		}),
		"api.example.com",
	)
	if err != nil {
		t.Fatal(err)
	}
	if base.Transport != fallback {
		t.Fatal("base client transport was mutated")
	}
	if routed == base || routed.Transport == base.Transport {
		t.Fatal("routed client was not independently configured")
	}
}

func TestNewHostRoutingTransportRejectsInvalidConfiguration(t *testing.T) {
	if _, err := NewHostRoutingTransport(nil, nil, "api.example.com"); err == nil {
		t.Fatal("nil bypass was accepted")
	}
	if _, err := NewHostRoutingTransport(
		doFunc(func(request *http.Request) (*http.Response, error) {
			return nil, nil
		}),
		nil,
	); err == nil {
		t.Fatal("empty host set was accepted")
	}
}
