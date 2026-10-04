// Modified by Novelia; see README.novelia.md for the local changes and source.
package jissrbypass

import (
	"context"
	"errors"
	"io"
	"net/http"
	"os"
	"sync/atomic"
	"testing"
	"time"
)

func BenchmarkResolutionCacheHit(b *testing.B) {
	client := NewDefaultClient()
	client.resolveOverride = func(context.Context, string) (Resolution, error) {
		return Resolution{IP: "192.0.2.1", ECH: []byte{0, 1, 1}, ExpiresAt: time.Now().Add(time.Minute)}, nil
	}
	if _, err := client.Resolve(context.Background(), "api.example.com"); err != nil {
		b.Fatal(err)
	}
	b.ReportAllocs()
	b.ResetTimer()
	for range b.N {
		if _, err := client.Resolve(context.Background(), "api.example.com"); err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkAutoCachedNativeRoute(b *testing.B) {
	client := NewAutoClient()
	client.nativeClient = &http.Client{Transport: roundTripFunc(func(request *http.Request) (*http.Response, error) {
		return testResponse(request, "native"), nil
	})}
	request, _ := http.NewRequest(http.MethodGet, "https://api.example.com/value", nil)
	response, err := client.Do(request)
	if err != nil {
		b.Fatal(err)
	}
	response.Body.Close()

	b.ReportAllocs()
	b.ResetTimer()
	for range b.N {
		response, err := client.Do(request)
		if err != nil {
			b.Fatal(err)
		}
		response.Body.Close()
	}
}

func BenchmarkAutoCachedBypassRoute(b *testing.B) {
	client := NewAutoClient()
	var nativeCalls atomic.Int32
	client.nativeClient = &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
		nativeCalls.Add(1)
		return nil, errors.New("TLS reset")
	})}
	client.bypassOverride = func(request *http.Request) (*http.Response, error) {
		return testResponse(request, "bypass"), nil
	}
	request, _ := http.NewRequest(http.MethodGet, "https://blocked.example/value", nil)
	response, err := client.Do(request)
	if err != nil {
		b.Fatal(err)
	}
	response.Body.Close()

	b.ReportAllocs()
	b.ResetTimer()
	for range b.N {
		response, err := client.Do(request)
		if err != nil {
			b.Fatal(err)
		}
		response.Body.Close()
	}
	b.StopTimer()
	if nativeCalls.Load() != 1 {
		b.Fatalf("native route re-probed %d times", nativeCalls.Load())
	}
}

func BenchmarkLiveTransports(b *testing.B) {
	target := os.Getenv("JISSR_BENCHMARK_URL")
	if target == "" {
		b.Skip("set JISSR_BENCHMARK_URL to run the live transport benchmark")
	}
	request, err := http.NewRequest(http.MethodGet, target, nil)
	if err != nil {
		b.Fatal(err)
	}
	run := func(b *testing.B, client *http.Client) {
		b.Helper()
		b.ResetTimer()
		for range b.N {
			response, err := client.Do(request)
			if err != nil {
				b.Fatal(err)
			}
			_, _ = io.Copy(io.Discard, response.Body)
			response.Body.Close()
		}
	}

	b.Run("native", func(b *testing.B) {
		run(b, http.DefaultClient)
	})
	b.Run("bypass-pooled", func(b *testing.B) {
		run(b, &http.Client{Transport: requestDoerTransport{client: NewDefaultClient()}})
	})
	b.Run("auto", func(b *testing.B) {
		run(b, &http.Client{Transport: requestDoerTransport{client: NewAutoClient()}})
	})
}

type requestDoerTransport struct {
	client RequestDoer
}

func (transport requestDoerTransport) RoundTrip(request *http.Request) (*http.Response, error) {
	return transport.client.Do(request)
}
