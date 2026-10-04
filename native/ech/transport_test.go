package ech

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"reflect"
	"sync/atomic"
	"testing"
	"time"
)

func TestAddressCandidatesInterleaveAndDeduplicate(t *testing.T) {
	for _, test := range []struct {
		name string
		ips  []string
		want []string
	}{
		{
			name: "IPv4 first",
			ips:  []string{"192.0.2.1", "192.0.2.2", "2001:db8::1", "2001:db8::2", "192.0.2.1", "invalid"},
			want: []string{"192.0.2.1", "2001:db8::1", "192.0.2.2", "2001:db8::2"},
		},
		{
			name: "IPv6 first and normalized duplicates",
			ips:  []string{"2001:db8::1", "2001:db8::2", "2001:0db8:0:0:0:0:0:1", "192.0.2.1", "::ffff:192.0.2.1", "2001:db8::3"},
			want: []string{"2001:db8::1", "192.0.2.1", "2001:db8::2", "2001:db8::3"},
		},
		{
			name: "one family",
			ips:  []string{"192.0.2.2", "192.0.2.1", "192.0.2.2"},
			want: []string{"192.0.2.2", "192.0.2.1"},
		},
		{name: "no usable address", ips: []string{"invalid", ""}, want: []string{}},
	} {
		t.Run(test.name, func(t *testing.T) {
			if got := interleaveAddresses(test.ips); !reflect.DeepEqual(got, test.want) {
				t.Fatalf("got %v, want %v", got, test.want)
			}
		})
	}
}

func TestAddressFallbackRacesWholeHandshakeAndCancelsLoser(t *testing.T) {
	firstStarted, firstCancelled := make(chan struct{}), make(chan struct{})
	winner, _ := newTrackedConnection(t)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	connection, err := dialCandidates(ctx, []string{"2001:db8::1", "192.0.2.1"}, 10*time.Millisecond,
		func(ctx context.Context, ip string) (net.Conn, error) {
			if ip == "2001:db8::1" {
				close(firstStarted) // TCP may already be connected, TLS is stalled.
				<-ctx.Done()
				close(firstCancelled)
				return nil, ctx.Err()
			}
			<-firstStarted
			return winner, nil
		})
	if err != nil || connection != winner {
		t.Fatalf("fallback failed: %v", err)
	}
	if winner.closes.Load() != 0 {
		t.Fatal("winner was closed before being returned")
	}
	connection.Close()
	waitTransportSignal(t, firstCancelled, "loser remained active")
	if winner.closes.Load() != 1 {
		t.Fatal("winner was not closed exactly once by its owner")
	}
}

func TestAddressFailureStartsNextWithoutFullDelay(t *testing.T) {
	winner, _ := newTrackedConnection(t)
	failed, failedPeer := newTrackedConnection(t)
	started := make(chan string, 3)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	connection, err := dialCandidates(ctx, []string{"192.0.2.1", "192.0.2.2", "2001:db8::1"}, time.Hour,
		func(_ context.Context, ip string) (net.Conn, error) {
			started <- ip
			if ip == "192.0.2.1" {
				return failed, errors.New("handshake failed after TCP connected")
			}
			if ip == "192.0.2.2" {
				return nil, errors.New("connection refused")
			}
			return winner, nil
		})
	if err != nil || connection != winner {
		t.Fatalf("failure did not advance before the one-hour stagger: %v", err)
	}
	for _, want := range []string{"192.0.2.1", "192.0.2.2", "2001:db8::1"} {
		if got := <-started; got != want {
			t.Fatalf("started %s, want %s", got, want)
		}
	}
	assertTransportClosed(t, failed, failedPeer)
	connection.Close()
}

func TestAllAddressErrorsRetainCertificateVerificationCause(t *testing.T) {
	certificate := &tls.CertificateVerificationError{Err: errors.New("untrusted certificate")}
	refused := errors.New("connection refused")
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	connection, err := dialCandidates(ctx, []string{"192.0.2.1", "192.0.2.2"}, time.Hour,
		func(_ context.Context, ip string) (net.Conn, error) {
			if ip == "192.0.2.1" {
				return nil, fmt.Errorf("ECH handshake: %w", certificate)
			}
			return nil, refused
		})
	var actual *tls.CertificateVerificationError
	if connection != nil || !errors.As(err, &actual) || actual != certificate || !errors.Is(err, refused) {
		t.Fatalf("address failures or certificate cause lost: %v", err)
	}
}

func TestCancelledRaceClosesLateSuccessfulConnection(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	started, release := make(chan struct{}), make(chan struct{}, 1)
	t.Cleanup(func() { close(release) })
	late, peer := newTrackedConnection(t)
	done := make(chan error, 1)
	go func() {
		_, err := dialCandidates(ctx, []string{"192.0.2.1"}, time.Hour, func(context.Context, string) (net.Conn, error) {
			close(started)
			<-release // Simulate a dial that returns success after cancellation.
			return late, nil
		})
		done <- err
	}()
	waitTransportSignal(t, started, "dial did not start")
	cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("caller cancellation was lost: %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("cancellation waited for the unresponsive dial")
	}
	release <- struct{}{}
	assertTransportClosed(t, late, peer)
}

func TestWinningRaceClosesLateSuccessfulConnection(t *testing.T) {
	started, cancelled := make(chan struct{}), make(chan struct{})
	release := make(chan struct{}, 1)
	t.Cleanup(func() { close(release) })
	late, peer := newTrackedConnection(t)
	winner, _ := newTrackedConnection(t)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	connection, err := dialCandidates(ctx, []string{"2001:db8::1", "192.0.2.1"}, 10*time.Millisecond,
		func(ctx context.Context, ip string) (net.Conn, error) {
			if ip == "2001:db8::1" {
				close(started)
				<-ctx.Done()
				close(cancelled)
				<-release
				return late, nil
			}
			<-started
			return winner, nil
		})
	if err != nil || connection != winner {
		t.Fatalf("winner not returned: %v", err)
	}
	waitTransportSignal(t, cancelled, "winner did not cancel the other handshake")
	release <- struct{}{}
	assertTransportClosed(t, late, peer)
	if winner.closes.Load() != 0 {
		t.Fatal("cleanup closed the winning connection")
	}
	connection.Close()
	if winner.closes.Load() != 1 {
		t.Fatal("winner was not closed exactly once by its owner")
	}
}

type trackedConnection struct {
	net.Conn
	closes atomic.Int32
	closed chan struct{}
}

func (c *trackedConnection) Close() error {
	count := c.closes.Add(1)
	err := c.Conn.Close()
	if count == 1 {
		close(c.closed)
	}
	return err
}

func newTrackedConnection(t *testing.T) (*trackedConnection, net.Conn) {
	t.Helper()
	connection, peer := net.Pipe()
	t.Cleanup(func() { connection.Close(); peer.Close() })
	return &trackedConnection{Conn: connection, closed: make(chan struct{})}, peer
}

func waitTransportSignal(t *testing.T, signal <-chan struct{}, message string) {
	t.Helper()
	select {
	case <-signal:
	case <-time.After(2 * time.Second):
		t.Fatal(message)
	}
}

func assertTransportClosed(t *testing.T, connection *trackedConnection, peer net.Conn) {
	t.Helper()
	waitTransportSignal(t, connection.closed, "late/failed connection leaked")
	if count := connection.closes.Load(); count != 1 {
		t.Fatalf("connection closed %d times, want once", count)
	}
	peer.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := peer.Read(make([]byte, 1)); !errors.Is(err, io.EOF) {
		t.Fatalf("Close did not release the underlying connection: %v", err)
	}
}
