package ech

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"net/http"
	"time"

	core "github.com/inqadh/jissr-bypass"
)

type dialPolicyKey struct{}
type dialPolicy struct {
	parent  context.Context
	timeout time.Duration
}

func connectionContext(ctx context.Context) (context.Context, context.CancelFunc) {
	policy, ok := ctx.Value(dialPolicyKey{}).(dialPolicy)
	if !ok {
		return context.WithCancel(ctx)
	}
	var dialCtx context.Context
	var cancel context.CancelFunc
	if policy.timeout > 0 {
		dialCtx, cancel = context.WithTimeout(ctx, policy.timeout)
	} else {
		dialCtx, cancel = context.WithCancel(ctx)
	}
	stop := context.AfterFunc(policy.parent, cancel)
	return dialCtx, func() { stop(); cancel() }
}

// Keep the upstream encrypted resolver, but own the custom TLS dial so its
// deadline actually applies. net/http detaches in-flight dials from request
// cancellation; a request timer alone would leave stalled TLS dials running.
func newHTTPTransport(resolver *core.Client) *http.Transport {
	return &http.Transport{
		DialTLSContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			ctx, cancel := connectionContext(ctx)
			defer cancel()
			host, port, err := net.SplitHostPort(address)
			if err != nil || port != "443" || !protectedURL("https://"+host+"/") {
				return nil, errors.New("invalid ECH destination")
			}
			resolution, err := resolver.Resolve(ctx, host)
			if err != nil {
				return nil, fmt.Errorf("resolve: %w", err)
			}
			config := resolution.ECH
			for attempt := 0; attempt < 2; attempt++ {
				raw, err := (&net.Dialer{KeepAlive: 30 * time.Second}).DialContext(ctx, network, net.JoinHostPort(resolution.IP, "443"))
				if err != nil {
					resolver.ResetNetworkState()
					return nil, fmt.Errorf("tcp: %w", err)
				}
				connection := tls.Client(raw, &tls.Config{
					ServerName: host, MinVersion: tls.VersionTLS13,
					EncryptedClientHelloConfigList: config,
					NextProtos:                     []string{"h2", "http/1.1"},
				})
				err = connection.HandshakeContext(ctx)
				if err == nil {
					state := connection.ConnectionState()
					if state.ECHAccepted && state.Version == tls.VersionTLS13 {
						return connection, nil
					}
					raw.Close()
					return nil, errors.New("ECH was not accepted")
				}
				raw.Close()
				var rejection *tls.ECHRejectionError
				if attempt == 0 && errors.As(err, &rejection) && len(rejection.RetryConfigList) > 0 {
					config = rejection.RetryConfigList
					continue // Authenticated ECH key rotation; still before any HTTP bytes.
				}
				resolver.ResetNetworkState()
				return nil, fmt.Errorf("ECH handshake: %w", err)
			}
			return nil, errors.New("ECH handshake failed")
		},
		ForceAttemptHTTP2: true,
		MaxIdleConns:      64, MaxIdleConnsPerHost: 8,
		IdleConnTimeout: 90 * time.Second, ExpectContinueTimeout: time.Second,
	}
}
