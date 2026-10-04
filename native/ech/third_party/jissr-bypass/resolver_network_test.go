package jissrbypass

import (
	"context"
	"encoding/base64"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"reflect"
	"sort"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

var testECH = []byte{0, 4, 0xfe, 0x0d, 0, 0}

func wireDNSServer(t *testing.T, answers func(context.Context, dnsmessage.Question) []dnsmessage.Resource) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil {
			return
		}
		var query dnsmessage.Message
		if err := query.Unpack(body); err != nil {
			t.Error(err)
			return
		}
		message := dnsmessage.Message{Header: dnsmessage.Header{ID: query.Header.ID, Response: true}, Questions: query.Questions,
			Answers: answers(r.Context(), query.Questions[0])}
		packed, err := message.Pack()
		if err != nil {
			t.Error(err)
			return
		}
		w.Header().Set("Content-Type", "application/dns-message")
		_, _ = w.Write(packed)
	}))
}

func fixtureAnswers(question dnsmessage.Question, ttl uint32) []dnsmessage.Resource {
	header := dnsmessage.ResourceHeader{Name: question.Name, Type: question.Type, Class: dnsmessage.ClassINET, TTL: ttl}
	var resources []dnsmessage.Resource
	switch question.Type {
	case dnsmessage.TypeA:
		for _, last := range []byte{1, 2} {
			resources = append(resources, dnsmessage.Resource{Header: header, Body: &dnsmessage.AResource{A: [4]byte{192, 0, 2, last}}})
		}
	case dnsmessage.TypeAAAA:
		resources = append(resources, dnsmessage.Resource{Header: header, Body: &dnsmessage.AAAAResource{AAAA: [16]byte{0x20, 1, 0xd, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}}})
	case dnsmessage.TypeHTTPS:
		resources = append(resources, dnsmessage.Resource{Header: header, Body: &dnsmessage.HTTPSResource{SVCBResource: dnsmessage.SVCBResource{
			Priority: 1, Target: dnsmessage.MustNewName("."), Params: []dnsmessage.SVCParam{{Key: dnsmessage.SVCParamECH, Value: testECH}},
		}}})
	}
	return resources
}

func TestWireCollectsAllAddressesAndMinimumTTL(t *testing.T) {
	for _, cnameTTL := range []uint32{0, 7} {
		t.Run(fmt.Sprint(cnameTTL), func(t *testing.T) {
			server := wireDNSServer(t, func(_ context.Context, question dnsmessage.Question) []dnsmessage.Resource {
				answers := fixtureAnswers(question, 60)
				if question.Type == dnsmessage.TypeHTTPS {
					answers = append(answers, dnsmessage.Resource{Header: dnsmessage.ResourceHeader{Name: question.Name, Type: dnsmessage.TypeCNAME, Class: dnsmessage.ClassINET, TTL: cnameTTL}, Body: &dnsmessage.CNAMEResource{CNAME: dnsmessage.MustNewName("alias.example.")}})
				}
				return answers
			})
			defer server.Close()
			before := time.Now()
			result, err := resolveWireEndpoint(context.Background(), server.Client(), "example.com", server.URL, "test")
			if err != nil {
				t.Fatal(err)
			}
			sort.Strings(result.ips)
			if !reflect.DeepEqual(result.ips, []string{"192.0.2.1", "192.0.2.2", "2001:db8::1"}) {
				t.Fatal(result.ips)
			}
			if string(result.ech) != string(testECH) || result.resolver != server.URL {
				t.Fatal("provider ECH changed")
			}
			minimum := before.Add(time.Duration(cnameTTL) * time.Second)
			maximum := time.Now().Add(time.Duration(cnameTTL) * time.Second)
			if result.expiresAt.Before(minimum) || result.expiresAt.After(maximum) {
				t.Fatal("did not honor minimum TTL")
			}
		})
	}
}

func TestWireDoesNotWaitForUnavailableAddressFamily(t *testing.T) {
	for _, stalled := range []dnsmessage.Type{dnsmessage.TypeA, dnsmessage.TypeAAAA} {
		t.Run(stalled.String(), func(t *testing.T) {
			stalledCancelled := make(chan struct{})
			server := wireDNSServer(t, func(ctx context.Context, question dnsmessage.Question) []dnsmessage.Resource {
				if question.Type == stalled {
					<-ctx.Done()
					close(stalledCancelled)
					return nil
				}
				return fixtureAnswers(question, 60)
			})
			defer server.Close()
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			result, err := resolveWireEndpoint(ctx, server.Client(), "example.com", server.URL, "test")
			if err != nil || len(result.ips) == 0 {
				t.Fatalf("usable family discarded: %+v %v", result, err)
			}
			select {
			case <-stalledCancelled:
			case <-time.After(time.Second):
				t.Fatal("unneeded family was not cancelled")
			}
		})
	}
}

func TestWireNeverCombinesProviders(t *testing.T) {
	addresses := wireDNSServer(t, func(_ context.Context, q dnsmessage.Question) []dnsmessage.Resource {
		if q.Type == dnsmessage.TypeHTTPS {
			return nil
		}
		return fixtureAnswers(q, 60)
	})
	defer addresses.Close()
	ech := wireDNSServer(t, func(_ context.Context, q dnsmessage.Question) []dnsmessage.Resource {
		if q.Type != dnsmessage.TypeHTTPS {
			return nil
		}
		return fixtureAnswers(q, 60)
	})
	defer ech.Close()
	if _, err := resolveWire(context.Background(), "example.com", []string{addresses.URL, ech.URL}, "test"); err == nil {
		t.Fatal("mixed A and ECH providers")
	}
}

func TestJSONIPv6OnlyAndTTL(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/dns-json")
		switch r.URL.Query().Get("type") {
		case "A":
			fmt.Fprint(w, `{"Status":2}`)
		case "AAAA":
			fmt.Fprint(w, `{"Status":0,"Answer":[{"type":28,"TTL":40,"data":"2001:db8::1"},{"type":28,"TTL":30,"data":"2001:db8::2"}]}`)
		case "HTTPS":
			fmt.Fprintf(w, `{"Status":0,"Answer":[{"type":65,"TTL":20,"data":"1 . ech=%s"}]}`, base64.StdEncoding.EncodeToString(testECH))
		}
	}))
	defer server.Close()
	before := time.Now()
	result, err := resolveJSONEndpoint(context.Background(), server.Client(), "example.com", server.URL, "test")
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(result.ips, []string{"2001:db8::1", "2001:db8::2"}) {
		t.Fatal(result.ips)
	}
	if result.expiresAt.Before(before.Add(20*time.Second)) || result.expiresAt.After(time.Now().Add(20*time.Second)) {
		t.Fatal("HTTPS TTL ignored")
	}
}

func TestJSONDoesNotWaitForUnavailableAddressFamily(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/dns-json")
		switch r.URL.Query().Get("type") {
		case "AAAA":
			<-r.Context().Done()
		case "A":
			fmt.Fprint(w, `{"Status":0,"Answer":[{"type":1,"TTL":40,"data":"192.0.2.1"}]}`)
		case "HTTPS":
			fmt.Fprintf(w, `{"Status":0,"Answer":[{"type":65,"TTL":0,"data":"1 . ech=%s"}]}`, base64.StdEncoding.EncodeToString(testECH))
		}
	}))
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	result, err := resolveJSONEndpoint(ctx, server.Client(), "example.com", server.URL, "test")
	if err != nil || result.ip != "192.0.2.1" {
		t.Fatalf("usable IPv4 discarded: %+v %v", result, err)
	}
	if result.expiresAt.After(time.Now()) {
		t.Fatal("zero TTL became cacheable")
	}
}
