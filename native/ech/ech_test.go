package ech

import (
	"bytes"
	"crypto/tls"
	"io"
	"net/http"
	"net/http/httptrace"
	"sync"
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
	call, err := client.NewCall("GET", "https://n.novelia.cc/api/", "{}", nil, 0, 1000, 1000, 1000)
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
	call, _ := client.NewCall("GET", "https://n.novelia.cc/", "{}", nil, 0, 1000, 1000, 1000)
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
	call, _ := client.NewCall("GET", "https://n.novelia.cc/", "{}", nil, 0, 1000, 1000, 1000)
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
	call, _ := client.NewCall("GET", "https://n.novelia.cc/", "{}", nil, 0, 1000, 40, 1000)
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
	call, _ := client.NewCall("POST", "https://auth.novelia.cc/", `{"Content-Type":["text/plain"]}`, &testUpload{bytes.NewReader([]byte("example body"))}, 12, 1000, 1000, 1000)
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

func wroteRequest(r *http.Request) {
	if trace := httptrace.ContextClientTrace(r.Context()); trace != nil && trace.WroteRequest != nil {
		trace.WroteRequest(httptrace.WroteRequestInfo{})
	}
}

func TestReadTimeoutStartsAfterUploadAndBodyHasNoTotalDeadline(t *testing.T) {
	client := &Client{do: func(r *http.Request) (*http.Response, error) {
		gotConnection(r)
		// A slow but valid upload must not spend the shorter read budget.
		time.Sleep(70 * time.Millisecond)
		if r.Context().Err() != nil {
			t.Error("read deadline included upload time")
		}
		wroteRequest(r)
		return response(io.NopCloser(bytes.NewReader([]byte("abc")))), nil
	}}
	call, _ := client.NewCall("POST", "https://n.novelia.cc/", "{}", &testUpload{bytes.NewReader(nil)}, 0, 500, 40, 500)
	defer call.Cancel()
	if _, err := call.Execute(); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 3; i++ {
		time.Sleep(50 * time.Millisecond) // Caller-side processing is not read idle time.
		if chunk, err := call.Read(1); err != nil || len(chunk) != 1 {
			t.Fatalf("long transfer stopped: %v", err)
		}
	}
}

func TestHeaderReadTimeoutDoesNotRetryAfterSending(t *testing.T) {
	attempts := 0
	client := &Client{do: func(r *http.Request) (*http.Response, error) {
		attempts++
		gotConnection(r)
		wroteRequest(r)
		<-r.Context().Done()
		return nil, r.Context().Err()
	}}
	call, _ := client.NewCall("GET", "https://n.novelia.cc/", "{}", nil, 0, 500, 30, 500)
	if _, err := call.Execute(); err == nil {
		t.Fatal("headers did not time out")
	}
	if attempts != 1 || call.FailureReason() != "响应读取超时" {
		t.Fatal("header timeout was replayed or misclassified")
	}
}

type blockingUpload struct {
	closed chan struct{}
	once   sync.Once
}

func (u *blockingUpload) ReadChunk(int) ([]byte, error) { <-u.closed; return nil, io.ErrClosedPipe }
func (u *blockingUpload) Close()                        { u.once.Do(func() { close(u.closed) }) }

func TestWriteTimeoutClosesBlockedUploadWithoutReplay(t *testing.T) {
	upload := &blockingUpload{closed: make(chan struct{})}
	attempts := 0
	client := &Client{do: func(r *http.Request) (*http.Response, error) {
		attempts++
		gotConnection(r)
		_, err := r.Body.Read(make([]byte, 8))
		return nil, err
	}}
	call, _ := client.NewCall("POST", "https://n.novelia.cc/", "{}", upload, -1, 500, 500, 30)
	finished := make(chan error, 1)
	go func() { _, err := call.Execute(); finished <- err }()
	select {
	case err := <-finished:
		if err == nil || attempts != 1 || call.FailureReason() != "请求发送超时" {
			t.Fatal("write timeout did not close upload once")
		}
	case <-time.After(time.Second):
		call.Cancel()
		t.Fatal("blocked upload was not cancelled")
	}
}

func TestZeroTimeoutsKeepCallOpenUntilExplicitCancel(t *testing.T) {
	started := make(chan struct{})
	client := &Client{do: func(r *http.Request) (*http.Response, error) {
		gotConnection(r)
		wroteRequest(r)
		close(started)
		<-r.Context().Done()
		return nil, r.Context().Err()
	}}
	call, err := client.NewCall("GET", "https://n.novelia.cc/", "{}", nil, 0, 0, 0, 0)
	if err != nil {
		t.Fatal(err)
	}
	finished := make(chan error, 1)
	go func() { _, err := call.Execute(); finished <- err }()
	<-started
	select {
	case <-finished:
		t.Fatal("zero timeout expired")
	case <-time.After(60 * time.Millisecond):
	}
	call.Cancel()
	select {
	case <-finished:
	case <-time.After(time.Second):
		t.Fatal("explicit cancel failed")
	}
}

func TestTimeoutValidation(t *testing.T) {
	client := &Client{}
	for _, timeouts := range [][3]int64{{-1, 1, 1}, {1, -1, 1}, {1, 1, -1}, {2147483648, 1, 1}} {
		if _, err := client.NewCall("GET", "https://n.novelia.cc/", "{}", nil, 0, timeouts[0], timeouts[1], timeouts[2]); err == nil {
			t.Fatal("accepted invalid timeout")
		}
	}
}

func TestCancellingOneStreamingCallDoesNotCancelSibling(t *testing.T) {
	firstReader, firstWriter := io.Pipe()
	defer firstWriter.Close()
	client := &Client{do: func(r *http.Request) (*http.Response, error) {
		if r.URL.Path == "/first" {
			return response(firstReader), nil
		}
		return response(io.NopCloser(bytes.NewReader([]byte("second")))), nil
	}}
	first, _ := client.NewCall("GET", "https://n.novelia.cc/first", "{}", nil, 0, 500, 0, 500)
	second, _ := client.NewCall("GET", "https://n.novelia.cc/second", "{}", nil, 0, 500, 0, 500)
	defer first.Cancel()
	defer second.Cancel()
	if _, err := first.Execute(); err != nil {
		t.Fatal(err)
	}
	if _, err := second.Execute(); err != nil {
		t.Fatal(err)
	}
	finished := make(chan error, 1)
	go func() { _, err := first.Read(8); finished <- err }()
	first.Cancel()
	select {
	case err := <-finished:
		if err == nil {
			t.Fatal("cancelled read succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("cancelled read remained blocked")
	}
	if chunk, err := second.Read(8); err != nil || string(chunk) != "second" {
		t.Fatal("sibling call was cancelled", err)
	}
}
