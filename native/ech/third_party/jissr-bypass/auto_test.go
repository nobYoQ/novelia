// Modified by Novelia; see README.novelia.md for the local changes and source.
package jissrbypass

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestAutoClientCachesNativeRoute(t *testing.T) {
	client := NewAutoClient()
	var nativeCalls atomic.Int32
	var bypassCalls atomic.Int32
	client.nativeClient = &http.Client{Transport: roundTripFunc(func(request *http.Request) (*http.Response, error) {
		nativeCalls.Add(1)
		return testResponse(request, "native"), nil
	})}
	client.bypassOverride = func(request *http.Request) (*http.Response, error) {
		bypassCalls.Add(1)
		return testResponse(request, "bypass"), nil
	}

	for range 2 {
		request, _ := http.NewRequest(http.MethodGet, "https://api.example.com/value", nil)
		response, err := client.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		response.Body.Close()
	}
	if nativeCalls.Load() != 2 || bypassCalls.Load() != 0 {
		t.Fatalf("unexpected routing: native=%d bypass=%d", nativeCalls.Load(), bypassCalls.Load())
	}
	if route := client.stateForHost("api.example.com").routeAt(time.Now()); route != routeNative {
		t.Fatalf("route=%v want native", route)
	}
}

func TestAutoClientCachesBypassAfterSafeNativeFailure(t *testing.T) {
	client := NewAutoClient()
	var nativeCalls atomic.Int32
	var bypassCalls atomic.Int32
	client.nativeClient = &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
		nativeCalls.Add(1)
		return nil, errors.New("TLS reset before request")
	})}
	client.bypassOverride = func(request *http.Request) (*http.Response, error) {
		bypassCalls.Add(1)
		return testResponse(request, "bypass"), nil
	}

	for range 3 {
		request, _ := http.NewRequest(http.MethodGet, "https://blocked.example/value", nil)
		response, err := client.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		response.Body.Close()
	}
	if nativeCalls.Load() != 1 || bypassCalls.Load() != 3 {
		t.Fatalf("unexpected routing: native=%d bypass=%d", nativeCalls.Load(), bypassCalls.Load())
	}
}

func TestAutoClientEmitsFallbackDecisionEvents(t *testing.T) {
	client := NewAutoClient()
	var events []Event
	client.SetEventHandler(func(event Event) {
		events = append(events, event)
	})
	client.nativeClient = &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
		return nil, errors.New("TLS reset before request")
	})}
	client.bypassOverride = func(request *http.Request) (*http.Response, error) {
		return testResponse(request, "bypass"), nil
	}

	request, _ := http.NewRequest(http.MethodGet, "https://blocked.example/value", nil)
	response, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()

	want := []string{
		EventNativeAttempt,
		EventNativeFailed,
		EventFallbackStarted,
		EventBypassSelected,
	}
	if len(events) != len(want) {
		t.Fatalf("events=%#v want types=%#v", events, want)
	}
	for index, eventType := range want {
		if events[index].Type != eventType {
			t.Fatalf("event %d type=%q want %q", index, events[index].Type, eventType)
		}
	}
	if !events[1].SafeToFallback || !events[1].LikelyInterference {
		t.Fatalf("native failure was not identified as a safe likely-interference fallback: %#v", events[1])
	}

	events = nil
	request, _ = http.NewRequest(http.MethodGet, "https://blocked.example/value", nil)
	response, err = client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if len(events) != 1 || events[0].Type != EventBypassSelected || !events[0].Cached {
		t.Fatalf("cached bypass event=%#v", events)
	}
}

func TestEventHandlerPanicCannotBreakRequest(t *testing.T) {
	client := NewDefaultClient()
	client.SetEventHandler(func(Event) {
		panic("application callback")
	})
	client.bypassOverride = func(request *http.Request) (*http.Response, error) {
		return testResponse(request, "ok"), nil
	}
	request, _ := http.NewRequest(http.MethodGet, "https://api.example/value", nil)
	response, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
}

func TestAutoClientDoesNotFallbackAfterCallerCancellation(t *testing.T) {
	client := NewAutoClient()
	var bypassCalls atomic.Int32
	var events []Event
	client.SetEventHandler(func(event Event) {
		events = append(events, event)
	})
	client.nativeClient = &http.Client{Transport: roundTripFunc(func(request *http.Request) (*http.Response, error) {
		return nil, request.Context().Err()
	})}
	client.bypassOverride = func(request *http.Request) (*http.Response, error) {
		bypassCalls.Add(1)
		return testResponse(request, "bypass"), nil
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	request, _ := http.NewRequestWithContext(ctx, http.MethodGet, "https://api.example/value", nil)
	if _, err := client.Do(request); err == nil {
		t.Fatal("canceled request succeeded")
	}
	if bypassCalls.Load() != 0 {
		t.Fatalf("canceled request used fallback %d times", bypassCalls.Load())
	}
	if len(events) != 2 ||
		events[1].Type != EventNativeFailed ||
		events[1].SafeToFallback ||
		events[1].LikelyInterference {
		t.Fatalf("unexpected cancellation events: %#v", events)
	}
}

func TestAutoClientBoundsWholeNativeAttempt(t *testing.T) {
	config := DefaultConfig()
	config.NativeFirst = true
	config.NativeAttemptTimeout = 25 * time.Millisecond
	client, err := NewClient(config)
	if err != nil {
		t.Fatal(err)
	}
	client.nativeClient = &http.Client{Transport: roundTripFunc(func(request *http.Request) (*http.Response, error) {
		<-request.Context().Done()
		return nil, request.Context().Err()
	})}
	client.bypassOverride = func(request *http.Request) (*http.Response, error) {
		return testResponse(request, "bypass"), nil
	}

	started := time.Now()
	request, _ := http.NewRequest(http.MethodGet, "https://slow-native.example/value", nil)
	response, err := client.Do(request)
	elapsed := time.Since(started)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if elapsed > 250*time.Millisecond {
		t.Fatalf("native attempt exceeded total cutoff: %v", elapsed)
	}
}

func TestAutoClientUsesBypassForNonReplayableUnknownBody(t *testing.T) {
	client := NewAutoClient()
	var nativeCalls atomic.Int32
	var bypassCalls atomic.Int32
	client.nativeClient = &http.Client{Transport: roundTripFunc(func(request *http.Request) (*http.Response, error) {
		nativeCalls.Add(1)
		return testResponse(request, "native"), nil
	})}
	client.bypassOverride = func(request *http.Request) (*http.Response, error) {
		bypassCalls.Add(1)
		return testResponse(request, "bypass"), nil
	}

	request, _ := http.NewRequest(
		http.MethodPost,
		"https://api.example.com/value",
		io.NopCloser(strings.NewReader("body")),
	)
	if request.GetBody != nil {
		t.Fatal("test request unexpectedly replayable")
	}
	response, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if nativeCalls.Load() != 0 || bypassCalls.Load() != 1 {
		t.Fatalf("unexpected routing: native=%d bypass=%d", nativeCalls.Load(), bypassCalls.Load())
	}
}

func TestResolutionCacheDeduplicatesConcurrentRequests(t *testing.T) {
	config := DefaultConfig()
	config.ResolutionTTL = time.Minute
	client, err := NewClient(config)
	if err != nil {
		t.Fatal(err)
	}
	var calls atomic.Int32
	client.resolveOverride = func(context.Context, string) (Resolution, error) {
		calls.Add(1)
		time.Sleep(15 * time.Millisecond)
		return Resolution{
			IP:        "192.0.2.1",
			ExpiresAt: time.Now().Add(time.Minute),
			Resolver:  "test",
			ECH:       []byte{0, 1, 1},
		}, nil
	}

	var wait sync.WaitGroup
	for range 32 {
		wait.Add(1)
		go func() {
			defer wait.Done()
			if _, err := client.Resolve(context.Background(), "api.example.com"); err != nil {
				t.Error(err)
			}
		}()
	}
	wait.Wait()
	if calls.Load() != 1 {
		t.Fatalf("resolver called %d times, want 1", calls.Load())
	}

	if _, err := client.Resolve(context.Background(), "api.example.com"); err != nil {
		t.Fatal(err)
	}
	if calls.Load() != 1 {
		t.Fatalf("cached resolution missed; calls=%d", calls.Load())
	}
	client.ResetNetworkState()
	if _, err := client.Resolve(context.Background(), "api.example.com"); err != nil {
		t.Fatal(err)
	}
	if calls.Load() != 2 {
		t.Fatalf("network reset did not invalidate resolution; calls=%d", calls.Load())
	}
}

func TestClientReusesPerHostTransport(t *testing.T) {
	client := NewDefaultClient()
	first := client.stateForHost("api.example.com")
	second := client.stateForHost("api.example.com")
	other := client.stateForHost("other.example.com")
	if first != second || first.transport != second.transport {
		t.Fatal("same host did not reuse its transport")
	}
	if first == other || first.transport == other.transport {
		t.Fatal("different hosts shared a host-bound ECH transport")
	}
}
