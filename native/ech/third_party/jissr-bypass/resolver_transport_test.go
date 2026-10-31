package jissrbypass

import (
	"bytes"
	"context"
	"encoding/base64"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"testing"

	"golang.org/x/net/dns/dnsmessage"
)

type resolverRoundTripFunc func(*http.Request) (*http.Response, error)

func (f resolverRoundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestConfiguredTransportCoversWireAndJSONResolvers(t *testing.T) {
	for _, jsonFallback := range []bool{false, true} {
		t.Run(fmt.Sprint(jsonFallback), func(t *testing.T) {
			var mu sync.Mutex
			seenWire, seenJSON := false, false
			transport := resolverRoundTripFunc(func(r *http.Request) (*http.Response, error) {
				mu.Lock()
				defer mu.Unlock()
				status := http.StatusOK
				var data []byte
				if r.URL.Host == "wire.invalid" {
					seenWire = true
					if jsonFallback {
						status = 500
					} else {
						body, err := io.ReadAll(r.Body)
						if err != nil {
							return nil, err
						}
						var query dnsmessage.Message
						if err := query.Unpack(body); err != nil {
							return nil, err
						}
						message := dnsmessage.Message{Header: dnsmessage.Header{ID: query.Header.ID, Response: true}, Questions: query.Questions, Answers: fixtureAnswers(query.Questions[0], 60)}
						data, err = message.Pack()
						if err != nil {
							return nil, err
						}
					}
				} else if r.URL.Host == "json.invalid" {
					seenJSON = true
					switch r.URL.Query().Get("type") {
					case "A":
						data = []byte(`{"Status":0,"Answer":[{"type":1,"TTL":60,"data":"192.0.2.1"}]}`)
					case "AAAA":
						data = []byte(`{"Status":0,"Answer":[]}`)
					case "HTTPS":
						data = []byte(fmt.Sprintf(`{"Status":0,"Answer":[{"type":65,"TTL":60,"data":"1 . ech=%s"}]}`, base64.StdEncoding.EncodeToString(testECH)))
					}
				} else {
					return nil, fmt.Errorf("unexpected destination")
				}
				contentType := "application/dns-message"
				if r.URL.Host == "json.invalid" {
					contentType = "application/dns-json"
				}
				return &http.Response{StatusCode: status, Header: http.Header{"Content-Type": []string{contentType}}, Body: io.NopCloser(bytes.NewReader(data)), Request: r}, nil
			})
			result, err := resolveECH(context.Background(), "example.com", Config{WireResolvers: []string{"https://wire.invalid/dns-query"}, JSONResolvers: []string{"https://json.invalid/resolve"}, ResolverTransport: transport})
			if err != nil || len(result.IPs) == 0 {
				t.Fatalf("injected transport unused: %v", err)
			}
			mu.Lock()
			defer mu.Unlock()
			if !seenWire || seenJSON != jsonFallback {
				t.Fatal("transport not applied to both resolver formats")
			}
		})
	}
}

func TestDoHRedirectCannotDowngradeToHTTP(t *testing.T) {
	transport := resolverRoundTripFunc(func(r *http.Request) (*http.Response, error) {
		if r.URL.Scheme != "https" {
			t.Error("sent plaintext DoH")
		}
		return &http.Response{StatusCode: 302, Header: http.Header{"Location": []string{"http://resolver.invalid/dns-query"}}, Body: io.NopCloser(strings.NewReader("")), Request: r}, nil
	})
	ctx := context.WithValue(context.Background(), resolverTransportKey{}, transport)
	if _, err := resolverHTTPClient(ctx).Get("https://resolver.invalid/dns-query"); err == nil {
		t.Fatal("allowed HTTP downgrade")
	}
}
