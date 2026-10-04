// Modified by Novelia; see README.novelia.md for the local changes and source.
package jissrbypass

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/sync/singleflight"
)

type routeDecision uint8

const (
	routeUnknown routeDecision = iota
	routeNative
	routeBypass
)

type cachedResolution struct {
	value     Resolution
	expiresAt time.Time
}

type resolutionVersion struct{ network *networkGeneration }

type networkGeneration struct {
	ctx         context.Context
	cancel      context.CancelFunc
	resolutions singleflight.Group
}

func newNetworkGeneration() *networkGeneration {
	ctx, cancel := context.WithCancel(context.Background())
	return &networkGeneration{ctx: ctx, cancel: cancel}
}

type hostState struct {
	transport *http.Transport
	client    *http.Client

	cacheMu    sync.RWMutex
	resolution cachedResolution

	routeMu        sync.RWMutex
	route          routeDecision
	routeExpiresAt time.Time
	probeMu        sync.Mutex
}

// Client is safe for concurrent use. It retains per-host transports so HTTP/2
// connections and cached ECH resolution can be reused across requests.
type Client struct {
	config Config

	statesMu sync.RWMutex
	states   map[string]*hostState

	networkMu sync.RWMutex
	network   *networkGeneration

	nativeTransport *http.Transport
	nativeClient    *http.Client

	eventHandler atomic.Pointer[eventHandlerHolder]

	resolveOverride func(context.Context, string) (Resolution, error)
	bypassOverride  func(*http.Request) (*http.Response, error)
}

func newClient(config Config) *Client {
	nativeDialer := &net.Dialer{
		Timeout:   config.NativeAttemptTimeout,
		KeepAlive: 30 * time.Second,
	}
	nativeTransport := http.DefaultTransport.(*http.Transport).Clone()
	nativeTransport.DialContext = nativeDialer.DialContext
	nativeTransport.TLSHandshakeTimeout = config.NativeAttemptTimeout
	nativeTransport.ForceAttemptHTTP2 = true

	client := &Client{
		config:          config,
		states:          make(map[string]*hostState),
		network:         newNetworkGeneration(),
		nativeTransport: nativeTransport,
		nativeClient: &http.Client{
			Transport: nativeTransport,
			Timeout:   config.RequestTimeout,
			CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
				return http.ErrUseLastResponse
			},
		},
	}
	client.SetEventHandler(config.EventHandler)
	return client
}

func (c *Client) stateForHost(host string) *hostState {
	c.statesMu.RLock()
	state := c.states[host]
	c.statesMu.RUnlock()
	if state != nil {
		return state
	}

	c.statesMu.Lock()
	defer c.statesMu.Unlock()
	if state = c.states[host]; state != nil {
		return state
	}

	state = &hostState{}
	dialer := &net.Dialer{Timeout: c.config.DialTimeout, KeepAlive: 30 * time.Second}
	transport := &http.Transport{
		DialTLSContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
			resolution, err := c.resolveCached(ctx, host)
			if err != nil {
				return nil, fmt.Errorf("resolve %s: %w", host, err)
			}
			connection, err := dialECH(
				ctx,
				dialer,
				network,
				resolution.IP,
				host,
				resolution.ECH,
				[]string{"h2", "http/1.1"},
				func(retryConfig []byte) {
					c.UpdateCachedECH(host, resolution, retryConfig)
				},
			)
			if err != nil {
				c.InvalidateResolution(host, resolution)
			}
			return connection, err
		},
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          64,
		MaxIdleConnsPerHost:   8,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   c.config.DialTimeout,
		ExpectContinueTimeout: time.Second,
	}
	state.transport = transport
	state.client = &http.Client{
		Transport: transport,
		Timeout:   c.config.RequestTimeout,
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	c.states[host] = state
	return state
}

func (c *Client) resolveCached(ctx context.Context, host string) (Resolution, error) {
	c.networkMu.RLock()
	network := c.network
	state := c.stateForHost(host)
	now := time.Now()
	state.cacheMu.RLock()
	cached := state.resolution
	state.cacheMu.RUnlock()
	if cached.expiresAt.After(now) {
		c.networkMu.RUnlock()
		return cloneResolution(cached.value), nil
	}
	c.networkMu.RUnlock()

	resultChannel := network.resolutions.DoChan(host, func() (any, error) {
		now := time.Now()
		state.cacheMu.RLock()
		cached := state.resolution
		state.cacheMu.RUnlock()
		if cached.expiresAt.After(now) {
			return cloneResolution(cached.value), nil
		}

		resolveCtx, cancel := context.WithTimeout(network.ctx, c.config.ResolveTimeout)
		defer cancel()
		var (
			resolution Resolution
			err        error
		)
		if c.resolveOverride != nil {
			resolution, err = c.resolveOverride(resolveCtx, host)
		} else {
			resolution, err = resolveECH(resolveCtx, host, c.config)
		}
		if err != nil {
			return Resolution{}, err
		}
		c.networkMu.RLock()
		if c.network != network || resolveCtx.Err() != nil {
			c.networkMu.RUnlock()
			return Resolution{}, context.Canceled
		}
		resolution = cloneResolution(resolution)
		resolution.version = &resolutionVersion{network: network}
		expiresAt := resolution.ExpiresAt
		if limit := time.Now().Add(c.config.ResolutionTTL); limit.Before(expiresAt) {
			expiresAt = limit
		}
		resolution.ExpiresAt = expiresAt
		state.cacheMu.Lock()
		state.resolution = cachedResolution{
			value:     resolution,
			expiresAt: expiresAt,
		}
		state.cacheMu.Unlock()
		c.networkMu.RUnlock()
		c.emit(Event{
			Type:     EventResolutionSelected,
			Host:     host,
			Route:    "bypass",
			Stage:    "resolve",
			Reason:   "fresh_resolution",
			Resolver: resolution.Resolver,
		})
		return cloneResolution(resolution), nil
	})

	select {
	case result := <-resultChannel:
		c.networkMu.RLock()
		current := c.network == network
		c.networkMu.RUnlock()
		if !current {
			return Resolution{}, context.Canceled
		}
		if result.Err != nil {
			return Resolution{}, result.Err
		}
		return cloneResolution(result.Val.(Resolution)), nil
	case <-ctx.Done():
		return Resolution{}, ctx.Err()
	case <-network.ctx.Done():
		return Resolution{}, context.Canceled
	}
}

func cloneResolution(resolution Resolution) Resolution {
	resolution.ECH = append([]byte(nil), resolution.ECH...)
	resolution.IPs = append([]string(nil), resolution.IPs...)
	return resolution
}

// UpdateCachedECH remembers an authenticated retry only for the exact DNS
// snapshot used by the successful handshake. It never extends the DNS TTL.
func (c *Client) UpdateCachedECH(host string, previous Resolution, ech []byte) {
	if !validECHConfigList(ech) || previous.version == nil {
		return
	}
	c.networkMu.RLock()
	defer c.networkMu.RUnlock()
	if c.network != previous.version.network {
		return
	}
	state := c.stateForHost(host)
	state.cacheMu.Lock()
	if state.resolution.value.version == previous.version && state.resolution.expiresAt.After(time.Now()) {
		state.resolution.value.ECH = append([]byte(nil), ech...)
	}
	state.cacheMu.Unlock()
}

// InvalidateResolution cannot evict a newer lookup or a different network's
// cache when a late connection attempt fails.
func (c *Client) InvalidateResolution(host string, previous Resolution) {
	if previous.version == nil {
		return
	}
	c.networkMu.RLock()
	defer c.networkMu.RUnlock()
	if c.network != previous.version.network {
		return
	}
	state := c.stateForHost(host)
	state.cacheMu.Lock()
	if state.resolution.value.version == previous.version {
		state.resolution = cachedResolution{}
	}
	state.cacheMu.Unlock()
}

func (state *hostState) routeAt(now time.Time) routeDecision {
	state.routeMu.RLock()
	defer state.routeMu.RUnlock()
	if !state.routeExpiresAt.After(now) {
		return routeUnknown
	}
	return state.route
}

func (state *hostState) setRoute(route routeDecision, ttl time.Duration) {
	state.routeMu.Lock()
	state.route = route
	state.routeExpiresAt = time.Now().Add(ttl)
	state.routeMu.Unlock()
}

// CloseIdleConnections closes pooled native and ECH connections without
// clearing routing or DNS/ECH decisions.
func (c *Client) CloseIdleConnections() {
	if c == nil {
		return
	}
	c.nativeTransport.CloseIdleConnections()
	c.statesMu.RLock()
	defer c.statesMu.RUnlock()
	for _, state := range c.states {
		state.transport.CloseIdleConnections()
	}
	c.emit(Event{
		Type:   EventIdleConnectionsClosed,
		Stage:  "lifecycle",
		Reason: "application_requested",
	})
}

// ResetNetworkState clears route and resolution decisions and closes pooled
// connections. Mobile applications should call it after a network change.
func (c *Client) ResetNetworkState() {
	if c == nil {
		return
	}
	c.networkMu.Lock()
	c.network.cancel()
	c.network = newNetworkGeneration()
	c.statesMu.Lock()
	oldStates := c.states
	c.states = make(map[string]*hostState)
	c.statesMu.Unlock()
	c.networkMu.Unlock()
	c.nativeTransport.CloseIdleConnections()
	for _, state := range oldStates {
		state.transport.CloseIdleConnections()
	}
	c.emit(Event{
		Type:   EventNetworkStateReset,
		Stage:  "lifecycle",
		Reason: "network_changed",
	})
}
