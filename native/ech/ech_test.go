package ech

import (
	"bytes"
	"crypto/tls"
	"io"
	"net/http"
	"testing"
	"time"
)

func response(body io.ReadCloser) *http.Response {
	return &http.Response{StatusCode: 200, Proto: "HTTP/2.0", Header: http.Header{"Set-Cookie": {"a=1", "b=2"}}, Body: body, ContentLength: -1, TLS: &tls.ConnectionState{ECHAccepted: true, Version: tls.VersionTLS13}}
}

func TestDestinationBoundary(t *testing.T) {
	for _, raw := range []string{"http://n.novelia.cc/", "https://n.novelia.cc.evil.test/", "https://user@n.novelia.cc/", "https://n.novelia.cc:444/", "https://example.com/"} {
		if protectedURL(raw) {
			t.Fatalf("accepted %s", raw)
		}
	}
	if !protectedURL("https://auth.novelia.cc/api/v1/auth/refresh?app=n") {
		t.Fatal("rejected auth URL")
	}
}

func TestLargeResponseIsStreamedAndHeadersPreserved(t *testing.T) {
	data := bytes.Repeat([]byte("0123456789"), 600000)
	client := &Client{do: func(*http.Request) (*http.Response, error) { return response(io.NopCloser(bytes.NewReader(data))), nil }}
	call, err := client.NewCall("GET", "https://n.novelia.cc/api/", "{}", nil, 0, 1000)
	if err != nil {
		t.Fatal(err)
	}
	reply, err := call.Execute()
	if err != nil {
		t.Fatal(err)
	}
	defer call.Cancel()
	if reply.HeadersJSON() != `{"Set-Cookie":["a=1","b=2"]}` {
		t.Fatal(reply.HeadersJSON())
	}
	var received []byte
	for {
		chunk, err := call.Read(100000)
		if err != nil {
			t.Fatal(err)
		}
		if len(chunk) == 0 {
			break
		}
		if len(chunk) > 65536 {
			t.Fatal("unbounded read")
		}
		received = append(received, chunk...)
	}
	if !bytes.Equal(data, received) {
		t.Fatal("body changed or truncated")
	}
}

func TestRejectsConnectionWithoutAcceptedECH(t *testing.T) {
	client := &Client{do: func(*http.Request) (*http.Response, error) {
		r := response(io.NopCloser(bytes.NewReader(nil)))
		r.TLS.ECHAccepted = false
		return r, nil
	}}
	call, _ := client.NewCall("GET", "https://n.novelia.cc/", "{}", nil, 0, 1000)
	if _, err := call.Execute(); err == nil {
		t.Fatal("accepted plaintext SNI")
	}
}

func TestCancellationDuringHeaders(t *testing.T) {
	started := make(chan struct{})
	client := &Client{do: func(r *http.Request) (*http.Response, error) {
		close(started)
		<-r.Context().Done()
		return nil, r.Context().Err()
	}}
	call, _ := client.NewCall("GET", "https://n.novelia.cc/", "{}", nil, 0, 1000)
	finished := make(chan error, 1)
	go func() { _, err := call.Execute(); finished <- err }()
	<-started
	call.Cancel()
	select {
	case err := <-finished:
		if err == nil {
			t.Fatal("cancel succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("cancel did not interrupt request")
	}
}

func TestIdleReadTimeoutClosesBody(t *testing.T) {
	reader, writer := io.Pipe()
	defer writer.Close()
	client := &Client{do: func(*http.Request) (*http.Response, error) { return response(reader), nil }}
	call, _ := client.NewCall("GET", "https://n.novelia.cc/", "{}", nil, 0, 40)
	if _, err := call.Execute(); err != nil {
		t.Fatal(err)
	}
	started := time.Now()
	if _, err := call.Read(64); err == nil {
		t.Fatal("idle read succeeded")
	}
	if time.Since(started) > time.Second {
		t.Fatal("idle timeout failed")
	}
}

func TestUploadStatusAndNoReplay(t *testing.T) {
	count := 0
	client := &Client{do: func(r *http.Request) (*http.Response, error) {
		count++
		defer r.Body.Close()
		body, _ := io.ReadAll(r.Body)
		if string(body) != "example body" || r.ContentLength != 12 || r.GetBody != nil || r.Header.Get("Content-Type") != "text/plain" {
			t.Fatal("upload contract changed")
		}
		result := response(io.NopCloser(bytes.NewReader(nil)))
		result.StatusCode = 401
		return result, nil
	}}
	call, _ := client.NewCall("POST", "https://auth.novelia.cc/", `{"Content-Type":["text/plain"]}`, &testUpload{bytes.NewReader([]byte("example body"))}, 12, 1000)
	defer call.Cancel()
	reply, err := call.Execute()
	if err != nil || reply.StatusCode() != 401 {
		t.Fatal("status lost", err)
	}
	if _, err := call.Execute(); err == nil || count != 1 {
		t.Fatal("replayed POST")
	}
}

type testUpload struct{ reader *bytes.Reader }

func (u *testUpload) ReadChunk(size int) ([]byte, error) {
	b := make([]byte, size)
	n, err := u.reader.Read(b)
	if err == io.EOF {
		err = nil
	}
	return b[:n], err
}
func (u *testUpload) Close() {}
