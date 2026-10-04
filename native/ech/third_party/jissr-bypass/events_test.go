package jissrbypass

import (
	"errors"
	"strings"
	"testing"
)

func TestEventErrorDetailRedactsURLsAndBoundsLength(t *testing.T) {
	secret := strings.Repeat("x", 600)
	detail := eventErrorDetail(errors.New(
		`Get "https://api.example/private?token=secret": failed` + "\n" + secret,
	))
	if strings.Contains(detail, "/private") ||
		strings.Contains(detail, "token=secret") ||
		strings.ContainsAny(detail, "\r\n") {
		t.Fatalf("sensitive or multiline diagnostic detail: %q", detail)
	}
	if len(detail) > 515 {
		t.Fatalf("diagnostic detail was not bounded: %d", len(detail))
	}
}
