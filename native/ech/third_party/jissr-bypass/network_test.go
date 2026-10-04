package jissrbypass

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"
)

func cachedFixture(ip string, expiry time.Time) Resolution {
	return Resolution{IP: ip, IPs: []string{ip}, ECH: []byte{0, 1, 1}, Resolver: "test", ExpiresAt: expiry}
}

func TestNetworkResetCancelsAndSeparatesPendingLookup(t *testing.T) {
	client := NewDefaultClient()
	started, cancelled, release, oldFinished := make(chan struct{}), make(chan struct{}), make(chan struct{}), make(chan struct{})
	var calls atomic.Int32
	client.resolveOverride = func(ctx context.Context, host string) (Resolution, error) {
		if calls.Add(1) == 1 {
			close(started)
			<-ctx.Done()
			close(cancelled)
			<-release // Simulate a resolver returning late despite cancellation.
			defer close(oldFinished)
			return cachedFixture("192.0.2.1", time.Now().Add(time.Hour)), nil
		}
		return cachedFixture("192.0.2.2", time.Now().Add(time.Hour)), nil
	}
	oldResult := make(chan error, 1)
	go func() { _, err := client.Resolve(context.Background(), "example.com"); oldResult <- err }()
	<-started
	client.ResetNetworkState()
	select {
	case <-cancelled:
	case <-time.After(time.Second):
		t.Fatal("old lookup was not cancelled")
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	fresh, err := client.Resolve(ctx, "example.com")
	if err != nil || fresh.IP != "192.0.2.2" {
		t.Fatalf("new network reused old flight: %+v, %v", fresh, err)
	}
	close(release)
	<-oldFinished
	if err := <-oldResult; !errors.Is(err, context.Canceled) {
		t.Fatalf("old lookup succeeded: %v", err)
	}
	result, err := client.Resolve(ctx, "example.com")
	if err != nil || result.IP != fresh.IP || calls.Load() != 2 {
		t.Fatalf("late result poisoned new cache: %+v %v calls=%d", result, err, calls.Load())
	}
}

func TestCancelledWaiterDoesNotCancelSharedLookup(t *testing.T) {
	client := NewDefaultClient()
	started, release := make(chan struct{}), make(chan struct{})
	var calls atomic.Int32
	client.resolveOverride = func(ctx context.Context, host string) (Resolution, error) {
		calls.Add(1)
		close(started)
		select {
		case <-release:
			return cachedFixture("192.0.2.1", time.Now().Add(time.Minute)), nil
		case <-ctx.Done():
			return Resolution{}, ctx.Err()
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	first := make(chan error, 1)
	go func() { _, err := client.Resolve(ctx, "example.com"); first <- err }()
	<-started
	cancel()
	if err := <-first; !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	close(release)
	if _, err := client.Resolve(context.Background(), "example.com"); err != nil || calls.Load() != 1 {
		t.Fatalf("waiter poisoned lookup: %v calls=%d", err, calls.Load())
	}
}

func TestCacheHonorsExpiryZeroTTLAndConfiguredCap(t *testing.T) {
	for _, test := range []struct {
		name      string
		ttl       time.Duration
		wantCalls int
	}{
		{"zero", 0, 2}, {"expired", -time.Second, 2}, {"dnsTTL", time.Minute, 1}, {"configuredCap", time.Hour, 1},
	} {
		t.Run(test.name, func(t *testing.T) {
			client := NewDefaultClient()
			var calls atomic.Int32
			expiry := time.Now().Add(test.ttl)
			client.resolveOverride = func(context.Context, string) (Resolution, error) {
				calls.Add(1)
				return cachedFixture("192.0.2.1", expiry), nil
			}
			before := time.Now()
			first, _ := client.Resolve(context.Background(), "example.com")
			_, _ = client.Resolve(context.Background(), "example.com")
			if calls.Load() != int32(test.wantCalls) {
				t.Fatalf("calls=%d", calls.Load())
			}
			if first.ExpiresAt.After(expiry) || first.ExpiresAt.After(time.Now().Add(client.config.ResolutionTTL)) {
				t.Fatal("cache exceeded TTL")
			}
			if test.name == "configuredCap" && first.ExpiresAt.Before(before.Add(client.config.ResolutionTTL)) {
				t.Fatal("unexpectedly shortened cap")
			}
		})
	}
}

func TestRetryCacheUpdateAndInvalidationAreSnapshotScoped(t *testing.T) {
	client := NewDefaultClient()
	client.resolveOverride = func(context.Context, string) (Resolution, error) {
		return cachedFixture("192.0.2.1", time.Now().Add(time.Minute)), nil
	}
	first, _ := client.Resolve(context.Background(), "example.com")
	retry := []byte{0, 1, 2}
	client.UpdateCachedECH("example.com", first, retry)
	updated, _ := client.Resolve(context.Background(), "example.com")
	if string(updated.ECH) != string(retry) || !updated.ExpiresAt.Equal(first.ExpiresAt) {
		t.Fatal("retry not cached or extended TTL")
	}
	updated.IPs[0] = "mutated"
	updated.ECH[2] = 9
	intact, _ := client.Resolve(context.Background(), "example.com")
	if intact.IPs[0] != first.IP || string(intact.ECH) != string(retry) {
		t.Fatal("cache leaked mutable slices")
	}
	client.ResetNetworkState()
	fresh, _ := client.Resolve(context.Background(), "example.com")
	client.UpdateCachedECH("example.com", first, retry)
	client.InvalidateResolution("example.com", first)
	after, _ := client.Resolve(context.Background(), "example.com")
	if after.version != fresh.version || string(after.ECH) != string(fresh.ECH) {
		t.Fatal("old network altered fresh cache")
	}
	client.InvalidateResolution("example.com", fresh)
	replacement, _ := client.Resolve(context.Background(), "example.com")
	client.UpdateCachedECH("example.com", fresh, retry)
	client.InvalidateResolution("example.com", fresh)
	after, _ = client.Resolve(context.Background(), "example.com")
	if after.version != replacement.version {
		t.Fatal("old lookup invalidated replacement")
	}
}
