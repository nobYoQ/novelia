// Modified by Novelia; see README.novelia.md for the local changes and source.
package jissrbypass

import (
	"context"
	"encoding/base64"
	"encoding/binary"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"golang.org/x/net/dns/dnsmessage"
)

func TestExtractECHPresentation(t *testing.T) {
	ech := []byte{0, 3, 1, 2, 3}
	value := "1 . alpn=h2 ech=" + base64.StdEncoding.EncodeToString(ech)
	got, err := extractECH(value)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string(ech) {
		t.Fatalf("got %x want %x", got, ech)
	}
}

func TestExtractECHRawRData(t *testing.T) {
	ech := []byte{0, 3, 1, 2, 3}
	// priority=1, target=".", key=5, length, ECHConfigList.
	raw := []byte{0, 1, 0, 0, 5, 0, byte(len(ech))}
	raw = append(raw, ech...)
	value := `\# 12 00010000050005` + "0003010203"
	got, err := extractECH(value)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string(ech) {
		t.Fatalf("got %x want %x (raw=%x)", got, ech, raw)
	}
}

func TestExtractECHRejectsRawLengthMismatch(t *testing.T) {
	if _, err := extractECH(`\# 99 000100000500050003010203`); err == nil {
		t.Fatal("raw RDATA with a false declared length was accepted")
	}
}

func TestValidECHConfigList(t *testing.T) {
	valid := []byte{0, 3, 1, 2, 3}
	if !validECHConfigList(valid) {
		t.Fatal("valid ECHConfigList rejected")
	}
	binary.BigEndian.PutUint16(valid[:2], 4)
	if validECHConfigList(valid) {
		t.Fatal("invalid ECHConfigList accepted")
	}
}

func TestNewClientRejectsInsecureResolver(t *testing.T) {
	config := DefaultConfig()
	config.WireResolvers = []string{"http://resolver.example/dns-query"}
	if _, err := NewClient(config); err == nil {
		t.Fatal("insecure resolver accepted")
	}
}

func TestCanonicalHost(t *testing.T) {
	got, err := canonicalHost("BÜCHER.example.")
	if err != nil {
		t.Fatal(err)
	}
	if got != "xn--bcher-kva.example" {
		t.Fatalf("got %q", got)
	}
	for _, invalid := range []string{"1.1.1.1", "-bad.example", "bad_.example", "a..example"} {
		if _, err := canonicalHost(invalid); err == nil {
			t.Fatalf("invalid hostname %q accepted", invalid)
		}
	}
}

func TestCopyRequestHeaders(t *testing.T) {
	destination := make(http.Header)
	source := http.Header{
		"Authorization": {"Bearer test"},
		"X-Trace":       {"one", "two"},
	}
	if err := copyRequestHeaders(destination, source); err != nil {
		t.Fatal(err)
	}
	if destination.Get("Authorization") != "Bearer test" || len(destination.Values("X-Trace")) != 2 {
		t.Fatalf("headers were not copied: %#v", destination)
	}
}

func TestCopyRequestHeadersRejectsUnsafeValues(t *testing.T) {
	tests := []http.Header{
		{"Host": {"other.example"}},
		{"Connection": {"close"}},
		{"Bad Header": {"value"}},
		{"X-Test": {"safe\r\nInjected: yes"}},
	}
	for _, headers := range tests {
		if err := copyRequestHeaders(make(http.Header), headers); err == nil {
			t.Fatalf("unsafe headers accepted: %#v", headers)
		}
	}
}

func TestWireDoHResolvesCorrelatedAddressAndECH(t *testing.T) {
	ech := []byte{0, 4, 0xfe, 0x0d, 0, 0}
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		body, err := io.ReadAll(request.Body)
		if err != nil {
			t.Error(err)
			return
		}
		var query dnsmessage.Message
		if err := query.Unpack(body); err != nil {
			t.Error(err)
			return
		}
		question := query.Questions[0]
		header := dnsmessage.ResourceHeader{
			Name: question.Name, Type: question.Type, Class: dnsmessage.ClassINET, TTL: 300,
		}
		var answer dnsmessage.Resource
		switch question.Type {
		case dnsmessage.TypeA:
			answer = dnsmessage.Resource{
				Header: header,
				Body:   &dnsmessage.AResource{A: [4]byte{192, 0, 2, 10}},
			}
		case dnsmessage.TypeAAAA:
			answer = dnsmessage.Resource{Header: header, Body: &dnsmessage.AAAAResource{AAAA: [16]byte{0x20, 1, 0xd, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}}}
		case dnsmessage.TypeHTTPS:
			answer = dnsmessage.Resource{
				Header: header,
				Body: &dnsmessage.HTTPSResource{SVCBResource: dnsmessage.SVCBResource{
					Priority: 1,
					Target:   dnsmessage.MustNewName("."),
					Params: []dnsmessage.SVCParam{{
						Key: dnsmessage.SVCParamECH, Value: ech,
					}},
				}},
			}
		default:
			t.Errorf("unexpected query type %v", question.Type)
			return
		}
		response := dnsmessage.Message{
			Header: dnsmessage.Header{
				ID: query.Header.ID, Response: true, RecursionAvailable: true,
			},
			Questions: query.Questions,
			Answers:   []dnsmessage.Resource{answer},
		}
		packed, err := response.Pack()
		if err != nil {
			t.Error(err)
			return
		}
		writer.Header().Set("Content-Type", "application/dns-message")
		_, _ = writer.Write(packed)
	}))
	defer server.Close()

	result, err := resolveWireEndpoint(
		context.Background(), server.Client(), "example.com", server.URL, defaultUserAgent,
	)
	if err != nil {
		t.Fatal(err)
	}
	if len(result.ips) != 2 || string(result.ech) != string(ech) {
		t.Fatalf("unexpected result: ip=%q ech=%x", result.ip, result.ech)
	}
}

func TestWireDoHRejectsMismatchedTransaction(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		body, _ := io.ReadAll(request.Body)
		var query dnsmessage.Message
		_ = query.Unpack(body)
		response := dnsmessage.Message{
			Header:    dnsmessage.Header{ID: query.Header.ID + 1, Response: true},
			Questions: query.Questions,
		}
		packed, _ := response.Pack()
		writer.Header().Set("Content-Type", "application/dns-message")
		_, _ = writer.Write(packed)
	}))
	defer server.Close()

	_, err := wireQuery(
		context.Background(), server.Client(), server.URL, "example.com", dnsmessage.TypeA, defaultUserAgent,
	)
	if err == nil {
		t.Fatal("mismatched DNS transaction was accepted")
	}
}
