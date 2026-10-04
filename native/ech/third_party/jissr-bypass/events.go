package jissrbypass

import (
	"regexp"
	"strings"
)

var diagnosticURL = regexp.MustCompile(`https?://[^\s"'<>]+`)

const (
	EventNativeAttempt         = "native.attempt"
	EventNativeSelected        = "native.selected"
	EventNativeFailed          = "native.failed"
	EventFallbackStarted       = "fallback.started"
	EventBypassSelected        = "bypass.selected"
	EventBypassFailed          = "bypass.failed"
	EventResolutionSelected    = "resolution.selected"
	EventNetworkStateReset     = "network.reset"
	EventIdleConnectionsClosed = "connections.idle_closed"
)

// Event reports a major transport decision without including request paths,
// headers, bodies, credentials, or response data.
type Event struct {
	Type               string
	Host               string
	Route              string
	Stage              string
	Reason             string
	Detail             string
	Resolver           string
	Cached             bool
	SafeToFallback     bool
	LikelyInterference bool
}

// EventHandler receives transport events synchronously. It must return
// promptly and must not make another request with the same Client.
type EventHandler func(Event)

type eventHandlerHolder struct {
	handler EventHandler
}

// SetEventHandler replaces the event handler. Passing nil disables events.
// Panics from application handlers are recovered and cannot break requests.
func (c *Client) SetEventHandler(handler EventHandler) {
	if c == nil {
		return
	}
	if handler == nil {
		c.eventHandler.Store(nil)
		return
	}
	c.eventHandler.Store(&eventHandlerHolder{handler: handler})
}

func (c *Client) emit(event Event) {
	if c == nil {
		return
	}
	holder := c.eventHandler.Load()
	if holder == nil {
		return
	}
	func() {
		defer func() {
			_ = recover()
		}()
		holder.handler(event)
	}()
}

func eventErrorDetail(err error) string {
	if err == nil {
		return ""
	}
	const maxLength = 512
	detail := strings.ReplaceAll(err.Error(), "\r", " ")
	detail = strings.ReplaceAll(detail, "\n", " ")
	detail = diagnosticURL.ReplaceAllString(detail, "https://[redacted]")
	if len(detail) > maxLength {
		return detail[:maxLength] + "..."
	}
	return detail
}
