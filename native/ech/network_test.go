package ech

import (
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestNetworkResetReplacesPoolWithoutClosingActiveResponse(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, r.RemoteAddr)
	}))
	defer server.Close()
	client := NewClient()
	defer func() { client.transport.CloseIdleConnections() }()
	request, err := http.NewRequest(http.MethodGet, server.URL, nil)
	if err != nil {
		t.Fatal(err)
	}
	old, err := client.doCurrent(request)
	if err != nil {
		t.Fatal(err)
	}
	defer old.Body.Close()
	previous := client.transport
	client.ResetNetworkState()
	if client.transport == previous {
		t.Fatal("new requests must not reuse the old transport")
	}
	read := func() string {
		response, err := client.doCurrent(request.Clone(request.Context()))
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		body, err := io.ReadAll(response.Body)
		if err != nil {
			t.Fatal(err)
		}
		return string(body)
	}
	newAddress := read()
	oldAddress, err := io.ReadAll(old.Body)
	if err != nil {
		t.Fatalf("reset interrupted active response: %v", err)
	}
	if string(oldAddress) == newAddress {
		t.Fatal("new request used the old connection")
	}
	if read() != newAddress {
		t.Fatal("new generation failed to reuse its own connection")
	}
}
