package jissrbypass

import (
	"context"
	"crypto/tls"
	"net/http"
	"net/http/httptrace"
	"sync/atomic"
	"time"
)

type nativeTrace struct {
	tlsComplete atomic.Bool
	reused      atomic.Bool
}

func (c *Client) doPrepared(request *http.Request, host string) (*http.Response, error) {
	if !c.config.NativeFirst {
		c.emit(Event{
			Type:   EventBypassSelected,
			Host:   host,
			Route:  "bypass",
			Stage:  "route",
			Reason: "bypass_only_policy",
		})
		return c.doBypass(request, host)
	}

	state := c.stateForHost(host)
	switch state.routeAt(time.Now()) {
	case routeNative:
		c.emit(Event{
			Type:   EventNativeSelected,
			Host:   host,
			Route:  "native",
			Stage:  "route",
			Reason: "cached_route",
			Cached: true,
		})
		return c.doNativeWithFallback(request, host, state)
	case routeBypass:
		c.emit(Event{
			Type:   EventBypassSelected,
			Host:   host,
			Route:  "bypass",
			Stage:  "route",
			Reason: "cached_route",
			Cached: true,
		})
		return c.doBypass(request, host)
	}

	if request.Body != nil && request.GetBody == nil {
		c.emit(Event{
			Type:   EventBypassSelected,
			Host:   host,
			Route:  "bypass",
			Stage:  "route",
			Reason: "non_replayable_body",
		})
		return c.doBypass(request, host)
	}

	state.probeMu.Lock()
	defer state.probeMu.Unlock()
	switch state.routeAt(time.Now()) {
	case routeNative:
		c.emit(Event{
			Type:   EventNativeSelected,
			Host:   host,
			Route:  "native",
			Stage:  "route",
			Reason: "cached_route",
			Cached: true,
		})
		return c.doNativeWithFallback(request, host, state)
	case routeBypass:
		c.emit(Event{
			Type:   EventBypassSelected,
			Host:   host,
			Route:  "bypass",
			Stage:  "route",
			Reason: "cached_route",
			Cached: true,
		})
		return c.doBypass(request, host)
	default:
		return c.probeNativeRoute(request, host, state)
	}
}

func (c *Client) probeNativeRoute(
	request *http.Request,
	host string,
	state *hostState,
) (*http.Response, error) {
	c.emit(Event{
		Type:   EventNativeAttempt,
		Host:   host,
		Route:  "native",
		Stage:  "route",
		Reason: "unknown_route",
	})
	response, safeToFallback, err := c.doNative(request)
	if err == nil {
		state.setRoute(routeNative, c.config.RouteTTL)
		c.emit(Event{
			Type:   EventNativeSelected,
			Host:   host,
			Route:  "native",
			Stage:  "route",
			Reason: "native_succeeded",
		})
		return response, nil
	}
	c.emitNativeFailure(host, safeToFallback, err)
	if !safeToFallback {
		return nil, err
	}
	c.emit(Event{
		Type:               EventFallbackStarted,
		Host:               host,
		Route:              "bypass",
		Stage:              "route",
		Reason:             "native_pre_tls_failure",
		Detail:             eventErrorDetail(err),
		SafeToFallback:     true,
		LikelyInterference: true,
	})
	response, bypassErr := c.doBypass(request, host)
	if bypassErr == nil {
		state.setRoute(routeBypass, c.config.RouteTTL)
		c.emit(Event{
			Type:   EventBypassSelected,
			Host:   host,
			Route:  "bypass",
			Stage:  "route",
			Reason: "fallback_succeeded",
		})
	} else {
		c.emit(Event{
			Type:   EventBypassFailed,
			Host:   host,
			Route:  "bypass",
			Stage:  "request",
			Reason: "fallback_failed",
			Detail: eventErrorDetail(bypassErr),
		})
	}
	return response, bypassErr
}

func (c *Client) doNativeWithFallback(
	request *http.Request,
	host string,
	state *hostState,
) (*http.Response, error) {
	response, safeToFallback, err := c.doNative(request)
	if err == nil {
		state.setRoute(routeNative, c.config.RouteTTL)
		return response, nil
	}
	c.emitNativeFailure(host, safeToFallback, err)
	if !safeToFallback || (request.Body != nil && request.GetBody == nil) {
		return nil, err
	}
	c.emit(Event{
		Type:               EventFallbackStarted,
		Host:               host,
		Route:              "bypass",
		Stage:              "route",
		Reason:             "cached_native_route_failed",
		Detail:             eventErrorDetail(err),
		SafeToFallback:     true,
		LikelyInterference: true,
	})
	response, bypassErr := c.doBypass(request, host)
	if bypassErr == nil {
		state.setRoute(routeBypass, c.config.RouteTTL)
		c.emit(Event{
			Type:   EventBypassSelected,
			Host:   host,
			Route:  "bypass",
			Stage:  "route",
			Reason: "fallback_succeeded",
		})
	} else {
		c.emit(Event{
			Type:   EventBypassFailed,
			Host:   host,
			Route:  "bypass",
			Stage:  "request",
			Reason: "fallback_failed",
			Detail: eventErrorDetail(bypassErr),
		})
	}
	return response, bypassErr
}

func (c *Client) emitNativeFailure(host string, safeToFallback bool, err error) {
	reason := "native_request_failure"
	if safeToFallback {
		reason = "native_pre_tls_failure"
	}
	c.emit(Event{
		Type:               EventNativeFailed,
		Host:               host,
		Route:              "native",
		Stage:              "route",
		Reason:             reason,
		Detail:             eventErrorDetail(err),
		SafeToFallback:     safeToFallback,
		LikelyInterference: safeToFallback,
	})
}

func (c *Client) doNative(request *http.Request) (*http.Response, bool, error) {
	attemptContext, cancelAttempt := context.WithCancel(request.Context())
	defer cancelAttempt()
	attemptTimer := time.AfterFunc(c.config.NativeAttemptTimeout, cancelAttempt)
	defer attemptTimer.Stop()

	attempt, err := cloneRequest(request, attemptContext)
	if err != nil {
		return nil, false, err
	}
	traceState := &nativeTrace{}
	trace := &httptrace.ClientTrace{
		GotConn: func(info httptrace.GotConnInfo) {
			attemptTimer.Stop()
			if info.Reused {
				traceState.reused.Store(true)
			}
		},
		TLSHandshakeDone: func(_ tls.ConnectionState, err error) {
			if err == nil {
				attemptTimer.Stop()
				traceState.tlsComplete.Store(true)
			}
		},
	}
	attempt = attempt.WithContext(httptrace.WithClientTrace(attempt.Context(), trace))
	response, err := c.nativeClient.Do(attempt)
	if err == nil {
		return response, false, nil
	}
	safeToFallback := request.Context().Err() == nil &&
		!traceState.reused.Load() &&
		!traceState.tlsComplete.Load()
	return nil, safeToFallback, err
}

func cloneRequest(request *http.Request, ctx context.Context) (*http.Request, error) {
	clone := request.Clone(ctx)
	clone.Header = request.Header.Clone()
	if request.Body == nil {
		return clone, nil
	}
	if request.GetBody == nil {
		return clone, nil
	}
	body, err := request.GetBody()
	if err != nil {
		return nil, err
	}
	clone.Body = body
	return clone, nil
}

func (c *Client) doBypass(request *http.Request, host string) (*http.Response, error) {
	attempt, err := cloneRequest(request, request.Context())
	if err != nil {
		return nil, err
	}
	if attempt.Header == nil {
		attempt.Header = make(http.Header)
	}
	if attempt.Header.Get("User-Agent") == "" {
		attempt.Header.Set("User-Agent", c.config.UserAgent)
	}
	if c.bypassOverride != nil {
		return c.bypassOverride(attempt)
	}
	return c.stateForHost(host).client.Do(attempt)
}
