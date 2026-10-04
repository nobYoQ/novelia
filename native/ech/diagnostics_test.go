package ech

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"
)

func TestDiagnosticsAreOptInAndNeverContainRequestData(t *testing.T) {
	client := NewClient()
	client.do = func(r *http.Request) (*http.Response, error) {
		gotConnection(r)
		return response(io.NopCloser(strings.NewReader("private-response-body"))), nil
	}
	for _, enabled := range []bool{false, true} {
		call, err := client.NewCall("GET", "https://n.novelia.cc/private-path?q=private-query", `{"Authorization":["private-token"]}`, nil, 0, 1000, 1000, 1000)
		if err != nil {
			t.Fatal(err)
		}
		if enabled {
			call.EnableDiagnostics()
		}
		if _, err := call.Execute(); err != nil {
			t.Fatal(err)
		}
		for {
			data, err := call.Read(4096)
			if err != nil {
				t.Fatal(err)
			}
			if len(data) == 0 {
				break
			}
		}
		data := call.DiagnosticsJSON()
		if strings.Contains(data, "private") || strings.Contains(data, "Authorization") || strings.Contains(data, "https://") {
			t.Fatal("sensitive request or response data leaked")
		}
		var events []diagnosticEvent
		if err := json.Unmarshal([]byte(data), &events); err != nil {
			t.Fatal(err)
		}
		if enabled && len(events) == 0 {
			t.Fatal("missing diagnostic events")
		}
		if !enabled && data != "[]" {
			t.Fatal("recorded a disabled request")
		}
	}
}

func TestDiagnosticTraceIsBoundedAndClassifiesWithoutRawErrors(t *testing.T) {
	d := &connectionDiagnostics{start: time.Now()}
	d.enabled.Store(true)
	ctx := context.WithValue(context.Background(), diagnosticKey{}, d)
	for i := 0; i < 1000; i++ {
		diagnostic(ctx, diagnosticEvent{Stage: "tcp", Outcome: "start"})
	}
	if len(d.events) != 128 {
		t.Fatal("trace grew beyond its bound")
	}
	if diagnosticReason(errors.New("read https://private:secret@example.invalid/: connection reset by peer")) != "reset" {
		t.Fatal("reset was not classified")
	}
	if resolverLabel("https://private:secret@example.invalid/") != "other" {
		t.Fatal("unexpected resolver was exposed")
	}
}

func TestConnectFailureDoesNotMisreportAllErrorsAsTimeout(t *testing.T) {
	d := &connectionDiagnostics{start: time.Now()}
	d.enabled.Store(true)
	ctx := context.WithValue(context.Background(), diagnosticKey{}, d)
	r, _ := http.NewRequestWithContext(ctx, "GET", "https://n.novelia.cc/", nil)
	_, _ = doWithConnectRetry(func(*http.Request) (*http.Response, error) { return nil, io.EOF }, r, time.Second)
	failures := 0
	for _, event := range d.events {
		if event.Outcome == "failed" {
			failures++
			if event.Reason != "eof" {
				t.Fatalf("wrong reason: %s", event.Reason)
			}
		}
	}
	if failures != 3 {
		t.Fatal("missing retry diagnostics")
	}
}

func TestMissingAddressClassificationSupportsBothResolverVersions(t *testing.T) {
	for _, detail := range []string{"no A record", "no A/AAAA record"} {
		if diagnosticReason(errors.New(detail)) != "dns_no_address" {
			t.Fatal("missing address was not classified")
		}
	}
}

func TestResolverFailureRecordsProviderAndClassificationOnly(t *testing.T) {
	d := &connectionDiagnostics{start: time.Now()}
	d.enabled.Store(true)
	ctx := context.WithValue(context.Background(), diagnosticKey{}, d)
	diagnosticResolverFailures(ctx, errors.New("wire DoH: https://dns.alidns.com/dns-query A: http 403; https://1.1.1.1/dns-query HTTPS: private-detail deadline exceeded"))
	if len(d.events) != 2 || d.events[0].Reason != "http_403" || d.events[1].Reason != "timeout" {
		t.Fatal("wrong provider summary")
	}
	data, _ := json.Marshal(d.events)
	if strings.Contains(string(data), "private") || strings.Contains(string(data), "https://") {
		t.Fatal("raw resolver error leaked")
	}
}
