package ech

import (
	"encoding/json"
	"io"
	"net/http"
	"os"
	"testing"
)

// Explicit opt-in; reads only Cloudflare's public trace endpoint. Never prints
// the response body (which may include a client IP), cookies or headers.
func TestLiveECHReadOnly(t *testing.T) {
	if os.Getenv("NOVELIA_ECH_LIVE") != "1" {
		t.Skip("set NOVELIA_ECH_LIVE=1 for public network checks")
	}
	for _, host := range []string{"n.novelia.cc", "auth.novelia.cc", "forum.novelia.cc"} {
		t.Run(host, func(t *testing.T) {
			t.Parallel()
			client := NewClient()
			call, err := client.NewCall("GET", "https://"+host+"/cdn-cgi/trace", "{}", nil, 0, 8000, 20000, 20000)
			if err != nil {
				t.Fatal("cannot construct call")
			}
			defer call.Cancel()
			reply, err := call.Execute()
			if err != nil {
				// These fixed, anonymous requests contain no account data or URL parameters.
				request, _ := http.NewRequest("GET", "https://"+host+"/cdn-cgi/trace", nil)
				response, cause := client.core.Do(request)
				if response != nil {
					_, _ = io.Copy(io.Discard, response.Body)
					response.Body.Close()
				}
				t.Fatalf("first request failed; follow-up: %v; fresh probe: %s", cause, client.Probe(host))
			}
			total := 0
			for {
				data, err := call.Read(4096)
				if err != nil {
					t.Fatal("read failed")
				}
				if len(data) == 0 {
					break
				}
				total += len(data)
				if total > 65536 {
					t.Fatal("unexpected trace size")
				}
			}
			t.Logf("accepted TLS 1.3 ECH; HTTP %d; %s", reply.StatusCode(), reply.Protocol())
		})
	}
}

// Uses the forum's actual anonymous API routes, not just Cloudflare's trace page.
func TestLiveForumAPI(t *testing.T) {
	if os.Getenv("NOVELIA_ECH_LIVE") != "1" {
		t.Skip("public network checks are opt-in")
	}
	for _, path := range []string{"category/", "post/?page=1&page_size=20&category=announcements&q=&sort=active"} {
		client := NewClient()
		call, err := client.NewCall("GET", "https://forum.novelia.cc/api/v1/"+path, `{"Accept":["application/json"]}`, nil, 0, 8000, 20000, 20000)
		if err != nil {
			t.Fatal("cannot construct public API request")
		}
		defer call.Cancel()
		reply, err := call.Execute()
		if err != nil {
			t.Fatal("forum request failed; " + client.Probe("forum.novelia.cc"))
		}
		var body []byte
		for {
			chunk, err := call.Read(65536)
			if err != nil {
				t.Fatal("forum API response read failed")
			}
			if len(chunk) == 0 {
				break
			}
			body = append(body, chunk...)
			if len(body) > 4<<20 {
				t.Fatal("unexpected public API response size")
			}
		}
		t.Logf("forum public API: HTTP %d, %s, JSON=%v", reply.StatusCode(), reply.Protocol(), json.Valid(body))
		if reply.StatusCode() != 200 || !json.Valid(body) {
			t.Error("forum did not return successful JSON")
		}
	}
}
