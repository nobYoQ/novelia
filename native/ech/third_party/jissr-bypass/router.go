package jissrbypass

import (
	"errors"
	"net/http"
)

// RequestDoer is implemented by Client and by compatible protected transports.
type RequestDoer interface {
	Do(*http.Request) (*http.Response, error)
}

// HostRoutingTransport sends configured hosts through Jissr Bypass and every
// other request through an ordinary RoundTripper. Host matching is exact after
// IDNA and lowercase normalization; it never expands to subdomains implicitly.
type HostRoutingTransport struct {
	bypass   RequestDoer
	fallback http.RoundTripper
	hosts    map[string]struct{}
}

// NewHostRoutingTransport constructs an immutable, concurrency-safe router.
// A nil fallback uses http.DefaultTransport.
func NewHostRoutingTransport(
	bypass RequestDoer,
	fallback http.RoundTripper,
	protectedHosts ...string,
) (*HostRoutingTransport, error) {
	if bypass == nil {
		return nil, errors.New("bypass client is required")
	}
	if fallback == nil {
		fallback = http.DefaultTransport
	}
	hosts := make(map[string]struct{}, len(protectedHosts))
	for _, host := range protectedHosts {
		canonical, err := canonicalHost(host)
		if err != nil {
			return nil, err
		}
		hosts[canonical] = struct{}{}
	}
	if len(hosts) == 0 {
		return nil, errors.New("at least one protected host is required")
	}
	return &HostRoutingTransport{
		bypass:   bypass,
		fallback: fallback,
		hosts:    hosts,
	}, nil
}

// RoundTrip implements http.RoundTripper.
func (transport *HostRoutingTransport) RoundTrip(request *http.Request) (*http.Response, error) {
	if transport == nil || transport.bypass == nil || transport.fallback == nil {
		return nil, errors.New("host-routing transport is not initialized")
	}
	if request == nil || request.URL == nil {
		return nil, errors.New("request is required")
	}
	host, err := canonicalHost(request.URL.Hostname())
	if err != nil {
		return nil, err
	}
	if _, protected := transport.hosts[host]; protected {
		return transport.bypass.Do(request)
	}
	return transport.fallback.RoundTrip(request)
}

// NewHostRoutingHTTPClient clones base and installs a HostRoutingTransport.
// Passing nil clones the default http.Client behavior. The original client is
// never mutated.
func NewHostRoutingHTTPClient(
	base *http.Client,
	bypass RequestDoer,
	protectedHosts ...string,
) (*http.Client, error) {
	var result http.Client
	var fallback http.RoundTripper
	if base != nil {
		result = *base
		fallback = base.Transport
	}
	transport, err := NewHostRoutingTransport(bypass, fallback, protectedHosts...)
	if err != nil {
		return nil, err
	}
	result.Transport = transport
	return &result, nil
}
