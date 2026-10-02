package ech

import (
	"context"
	"crypto/tls"
	"errors"
	"net/http"
	"net/http/httptrace"
	"sync/atomic"
	"time"
)

// Only restart bodyless reads, and only before GotConn: at that point no HTTP
// request bytes have been sent. Never replay uploads, HTTP errors or body reads.
// The core uses DialTLSContext, so Transport.TLSHandshakeTimeout is ignored.
func doWithConnectRetry(do func(*http.Request) (*http.Response, error), request *http.Request, budget time.Duration) (*http.Response, error) {
	attempts := 1
	if request.Body == nil && (request.Method == http.MethodGet || request.Method == http.MethodHead) {
		attempts = 3
	}
	var last error
	for attempt := 0; attempt < attempts; attempt++ {
		if err := request.Context().Err(); err != nil {
			return nil, err
		}
		diagnostic(request.Context(), diagnosticEvent{Stage: "connect_attempt", Outcome: "start", Attempt: attempt + 1})
		ctx, cancel := context.WithCancel(request.Context())
		var connected atomic.Bool
		var timer *time.Timer
		if budget > 0 {
			timer = time.AfterFunc(budget, cancel)
		}
		trace := &httptrace.ClientTrace{GotConn: func(httptrace.GotConnInfo) {
			connected.Store(true)
			if timer != nil {
				timer.Stop()
			}
		}}
		// net/http detaches dial cancellation, but preserves context values.
		// Let our TLS dial observe this attempt's cancellation and budget too.
		ctx = context.WithValue(ctx, dialPolicyKey{}, dialPolicy{parent: ctx, timeout: budget})
		response, err := do(request.Clone(httptrace.WithClientTrace(ctx, trace)))
		if timer != nil {
			timer.Stop()
		}
		if err == nil {
			// Parent Call.Cancel closes this context at EOF/close, so a successful
			// response remains readable after the connect budget has elapsed.
			return response, nil
		}
		connectExpired := ctx.Err() != nil && request.Context().Err() == nil
		cancel()
		if response != nil && response.Body != nil {
			response.Body.Close()
		}
		last = err
		reason := diagnosticReason(err)
		if !connected.Load() && connectExpired {
			reason = "timeout"
		}
		diagnostic(request.Context(), diagnosticEvent{Stage: "connect_attempt", Outcome: "failed", Attempt: attempt + 1, Reason: reason})
		var certificate *tls.CertificateVerificationError
		if connected.Load() || errors.As(err, &certificate) {
			break
		}
	}
	return nil, last
}
