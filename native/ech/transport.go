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
			return dialResolvedECH(ctx, resolver, network, host)
		},
		ForceAttemptHTTP2: true,
		MaxIdleConns:      64, MaxIdleConnsPerHost: 8,
		IdleConnTimeout: 90 * time.Second, ExpectContinueTimeout: time.Second,
	}
}

func dialResolvedECH(ctx context.Context, resolver *core.Client, network, host string) (net.Conn, error) {
	resolved := diagnosticPhase(ctx, "doh_ech")
	resolution, err := resolver.Resolve(ctx, host)
	resolved(err)
	if err != nil {
		diagnosticResolverFailures(ctx, err)
		return nil, fmt.Errorf("resolve: %w", err)
	}
	ips := resolution.IPs
	if len(ips) == 0 {
		ips = []string{resolution.IP}
	}
	connection, err := dialCandidates(ctx, interleaveAddresses(ips), 250*time.Millisecond,
		func(ctx context.Context, ip string) (net.Conn, error) {
			return dialECHAddress(ctx, network, host, ip, resolution.ECH, func(config []byte) {
				resolver.UpdateCachedECH(host, resolution, config)
			})
		})
	if err != nil {
		resolver.InvalidateResolution(host, resolution)
		return nil, err
	}
	ip, _, _ := net.SplitHostPort(connection.RemoteAddr().String())
	family := "IPv4"
	if net.ParseIP(ip).To4() == nil {
		family = "IPv6"
	}
	diagnostic(ctx, diagnosticEvent{Stage: "resolution", Outcome: "selected", Resolver: resolverLabel(resolution.Resolver), Family: family, ServerIP: diagnosticServerIP(ip)})
	return connection, nil
}

func dialECHAddress(ctx context.Context, network, host, ip string, config []byte, onAcceptedRetry func([]byte)) (net.Conn, error) {
	for attempt := 0; attempt < 2; attempt++ {
		tcpDone := diagnosticPhase(ctx, "tcp")
		raw, err := (&net.Dialer{KeepAlive: 30 * time.Second}).DialContext(ctx, network, net.JoinHostPort(ip, "443"))
		tcpDone(err)
		if err != nil {
			return nil, fmt.Errorf("tcp: %w", err)
		}
		connection := tls.Client(raw, &tls.Config{
			ServerName: host, MinVersion: tls.VersionTLS13,
			EncryptedClientHelloConfigList: config,
			NextProtos:                     []string{"h2", "http/1.1"},
		})
		tlsDone := diagnosticPhase(ctx, "tls")
		err = connection.HandshakeContext(ctx)
		tlsDone(err)
		if err == nil {
			state := connection.ConnectionState()
			if state.ECHAccepted && state.Version == tls.VersionTLS13 {
				if attempt > 0 {
					onAcceptedRetry(config)
				}
				diagnostic(ctx, diagnosticEvent{Stage: "ech", Outcome: "accepted", Protocol: state.NegotiatedProtocol})
				return connection, nil
			}
			raw.Close()
			return nil, errors.New("ECH was not accepted")
		}
		raw.Close()
		var rejection *tls.ECHRejectionError
		if attempt == 0 && errors.As(err, &rejection) && len(rejection.RetryConfigList) > 0 {
			diagnostic(ctx, diagnosticEvent{Stage: "ech", Outcome: "retry_config"})
			config = rejection.RetryConfigList
			continue // Authenticated rotation, before any HTTP bytes are written.
		}
		return nil, fmt.Errorf("ECH handshake: %w", err)
	}
	return nil, errors.New("ECH handshake failed")
}

// Alternate families without discarding the resolver's order within a family.
func interleaveAddresses(ips []string) []string {
	var first, second []string
	firstV4 := len(ips) > 0 && net.ParseIP(ips[0]).To4() != nil
	seen := map[string]bool{}
	for _, ip := range ips {
		parsed := net.ParseIP(ip)
		if parsed == nil || seen[parsed.String()] {
			continue
		}
		seen[parsed.String()] = true
		if (parsed.To4() != nil) == firstV4 {
			first = append(first, parsed.String())
		} else {
			second = append(second, parsed.String())
		}
	}
	result := make([]string, 0, len(first)+len(second))
	for i := 0; i < len(first) || i < len(second); i++ {
		if i < len(first) {
			result = append(result, first[i])
		}
		if i < len(second) {
			result = append(result, second[i])
		}
	}
	return result
}

// Race the whole TCP + TLS/ECH handshake: a reachable but stalled TLS endpoint
// must not block another address. This function never sends HTTP request bytes.
func dialCandidates(ctx context.Context, ips []string, delay time.Duration, dial func(context.Context, string) (net.Conn, error)) (net.Conn, error) {
	if len(ips) == 0 {
		return nil, errors.New("no usable ECH address")
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	type result struct {
		connection net.Conn
		err        error
	}
	results := make(chan result)
	launch := func(ip string) {
		go func() {
			connection, err := dial(ctx, ip)
			select {
			case results <- result{connection, err}:
			case <-ctx.Done():
				if connection != nil {
					connection.Close()
				}
			}
		}()
	}
	launch(ips[0])
	started, finished := 1, 0
	timer := time.NewTimer(delay)
	defer timer.Stop()
	var failures []error
	for {
		select {
		case <-ctx.Done():
			return nil, errors.Join(append(failures, ctx.Err())...)
		case value := <-results:
			finished++
			if value.err == nil {
				if ctx.Err() != nil {
					value.connection.Close()
					return nil, ctx.Err()
				}
				return value.connection, nil
			}
			if value.connection != nil {
				value.connection.Close()
			}
			failures = append(failures, value.err)
			if finished == len(ips) {
				return nil, errors.Join(failures...)
			}
			if started < len(ips) {
				launch(ips[started])
				started++
				timer.Reset(delay)
			}
		case <-timer.C:
			if started < len(ips) {
				launch(ips[started])
				started++
				timer.Reset(delay)
			}
		}
	}
}
