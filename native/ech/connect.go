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
	if request.Body != nil || (request.Method != http.MethodGet && request.Method != http.MethodHead) {
		return do(request)
	}
	var last error
	for attempt := 0; attempt < 3; attempt++ {
		if err := request.Context().Err(); err != nil {
			return nil, err
		}
		ctx, cancel := context.WithCancel(request.Context())
		var connected atomic.Bool
		timer := time.AfterFunc(budget, cancel)
		trace := &httptrace.ClientTrace{GotConn: func(httptrace.GotConnInfo) {
			connected.Store(true)
			timer.Stop()
		}}
		response, err := do(request.Clone(httptrace.WithClientTrace(ctx, trace)))
		timer.Stop()
		if err == nil {
			// Parent Call.Cancel closes this context at EOF/close, so a successful
			// response remains readable after the connect budget has elapsed.
			return response, nil
		}
		cancel()
		if response != nil && response.Body != nil {
			response.Body.Close()
		}
		last = err
		var certificate *tls.CertificateVerificationError
		if connected.Load() || errors.As(err, &certificate) {
			break
		}
	}
	return nil, last
}
