// Modified by Novelia; see README.novelia.md for the local changes and source.
package jissrbypass

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

type wireResolution struct {
	ip        string
	ips       []string
	ech       []byte
	resolver  string
	expiresAt time.Time
}

func (r wireResolution) public() Resolution {
	return Resolution{IP: r.ip, IPs: r.ips, ECH: r.ech, Resolver: r.resolver, ExpiresAt: r.expiresAt}
}

func resolveECH(ctx context.Context, host string, config Config) (Resolution, error) {
	wire, wireErr := resolveWire(ctx, host, config.WireResolvers, config.UserAgent)
	if wireErr == nil {
		return wire.public(), nil
	}
	jsonResolution, jsonErr := resolveJSON(ctx, host, config.JSONResolvers, config.UserAgent)
	if jsonErr == nil {
		return jsonResolution.public(), nil
	}
	return Resolution{}, fmt.Errorf("wire DoH: %v; JSON DoH: %w", wireErr, jsonErr)
}

func resolveWire(ctx context.Context, host string, endpoints []string, userAgent string) (wireResolution, error) {
	raceContext, cancel := context.WithCancel(ctx)
	defer cancel()
	type result struct {
		resolution wireResolution
		err        error
	}
	results := make(chan result, len(endpoints))
	client := &http.Client{Timeout: 6 * time.Second}
	for _, endpoint := range endpoints {
		go func(endpoint string) {
			resolution, err := resolveWireEndpoint(raceContext, client, host, endpoint, userAgent)
			results <- result{resolution: resolution, err: err}
		}(endpoint)
	}
	errorsFound := make([]string, 0, len(endpoints))
	for range endpoints {
		result := <-results
		if result.err == nil {
			cancel()
			return result.resolution, nil
		}
		errorsFound = append(errorsFound, result.err.Error())
	}
	return wireResolution{}, fmt.Errorf("%s", strings.Join(errorsFound, "; "))
}

func resolveWireEndpoint(ctx context.Context, client *http.Client, host, endpoint, userAgent string) (wireResolution, error) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	started := time.Now()
	type queryResult struct {
		message *dnsmessage.Message
		err     error
	}
	addressResult := make(chan queryResult, 2)
	httpsResult := make(chan queryResult, 1)
	for _, queryType := range []dnsmessage.Type{dnsmessage.TypeA, dnsmessage.TypeAAAA} {
		go func() {
			message, err := wireQuery(ctx, client, endpoint, host, queryType, userAgent)
			addressResult <- queryResult{message: message, err: err}
		}()
	}
	go func() {
		message, err := wireQuery(ctx, client, endpoint, host, dnsmessage.TypeHTTPS, userAgent)
		httpsResult <- queryResult{message: message, err: err}
	}()
	https := <-httpsResult
	if https.err != nil {
		return wireResolution{}, fmt.Errorf("%s HTTPS: %w", endpoint, https.err)
	}
	ips := []string{}
	ttl := ^uint32(0)
	var grace <-chan time.Time
	var graceTimer *time.Timer
	defer func() {
		if graceTimer != nil {
			graceTimer.Stop()
		}
	}()
addresses:
	for range 2 {
		var address queryResult
		select {
		case address = <-addressResult:
		case <-grace:
			break addresses
		case <-ctx.Done():
			return wireResolution{}, ctx.Err()
		}
		if address.err != nil {
			continue // One unavailable family must not discard the other.
		}
		for _, answer := range address.message.Answers {
			switch resource := answer.Body.(type) {
			case *dnsmessage.AResource:
				ips = appendUniqueIP(ips, net.IP(resource.A[:]).String())
				ttl = min(ttl, answer.Header.TTL)
			case *dnsmessage.AAAAResource:
				ips = appendUniqueIP(ips, net.IP(resource.AAAA[:]).String())
				ttl = min(ttl, answer.Header.TTL)
			case *dnsmessage.CNAMEResource:
				ttl = min(ttl, answer.Header.TTL)
			}
		}
		if len(ips) > 0 && graceTimer == nil {
			graceTimer = time.NewTimer(100 * time.Millisecond)
			grace = graceTimer.C
		}
	}
	if len(ips) == 0 {
		return wireResolution{}, fmt.Errorf("%s: no A/AAAA record", endpoint)
	}
	for _, answer := range https.message.Answers {
		if _, ok := answer.Body.(*dnsmessage.CNAMEResource); ok {
			ttl = min(ttl, answer.Header.TTL)
		}
	}
	for _, answer := range https.message.Answers {
		resource, ok := answer.Body.(*dnsmessage.HTTPSResource)
		if !ok {
			continue
		}
		ech, ok := resource.GetParam(dnsmessage.SVCParamECH)
		if ok && validECHConfigList(ech) {
			ttl = min(ttl, answer.Header.TTL)
			return wireResolution{ip: ips[0], ips: ips, ech: append([]byte(nil), ech...), resolver: endpoint,
				expiresAt: started.Add(time.Duration(ttl) * time.Second)}, nil
		}
	}
	return wireResolution{}, fmt.Errorf("%s: no valid ECH parameter", endpoint)
}

func wireQuery(ctx context.Context, client *http.Client, endpoint, host string, queryType dnsmessage.Type, userAgent string) (*dnsmessage.Message, error) {
	name, err := dnsmessage.NewName(strings.TrimSuffix(host, ".") + ".")
	if err != nil {
		return nil, err
	}
	query := dnsmessage.Message{
		Header: dnsmessage.Header{ID: randomDNSID(), RecursionDesired: true},
		Questions: []dnsmessage.Question{{
			Name: name, Type: queryType, Class: dnsmessage.ClassINET,
		}},
	}
	body, err := query.Pack()
	if err != nil {
		return nil, err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	request.Header.Set("Accept", "application/dns-message")
	request.Header.Set("Content-Type", "application/dns-message")
	request.Header.Set("User-Agent", userAgent)
	response, err := client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	responseBody, err := io.ReadAll(io.LimitReader(response.Body, 64<<10))
	if err != nil {
		return nil, err
	}
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("http %d", response.StatusCode)
	}
	if !strings.Contains(strings.ToLower(response.Header.Get("Content-Type")), "application/dns-message") {
		return nil, fmt.Errorf("unexpected content type %q", response.Header.Get("Content-Type"))
	}
	var message dnsmessage.Message
	if err := message.Unpack(responseBody); err != nil {
		return nil, err
	}
	if !message.Header.Response || message.Header.RCode != dnsmessage.RCodeSuccess {
		return nil, fmt.Errorf("dns status %s", message.Header.RCode)
	}
	if message.Header.ID != query.Header.ID {
		return nil, fmt.Errorf("DNS transaction ID mismatch")
	}
	if len(message.Questions) != 1 ||
		message.Questions[0].Name.String() != name.String() ||
		message.Questions[0].Type != queryType ||
		message.Questions[0].Class != dnsmessage.ClassINET {
		return nil, fmt.Errorf("DNS question mismatch")
	}
	return &message, nil
}

func randomDNSID() uint16 {
	var value [2]byte
	if _, err := rand.Read(value[:]); err != nil {
		return 0
	}
	return binary.BigEndian.Uint16(value[:])
}

type jsonDoHResponse struct {
	Status int `json:"Status"`
	Answer []struct {
		Type int    `json:"type"`
		Data string `json:"data"`
		TTL  uint32 `json:"TTL"`
	} `json:"Answer"`
}

func resolveJSON(ctx context.Context, host string, endpoints []string, userAgent string) (wireResolution, error) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	type result struct {
		resolution wireResolution
		err        error
	}
	results := make(chan result, len(endpoints))
	client := &http.Client{Timeout: 6 * time.Second}
	for _, endpoint := range endpoints {
		go func(endpoint string) {
			resolution, err := resolveJSONEndpoint(ctx, client, host, endpoint, userAgent)
			results <- result{resolution: resolution, err: err}
		}(endpoint)
	}
	errorsFound := make([]string, 0, len(endpoints))
	for range endpoints {
		result := <-results
		if result.err == nil {
			return result.resolution, nil
		}
		errorsFound = append(errorsFound, result.err.Error())
	}
	return wireResolution{}, fmt.Errorf("%s", strings.Join(errorsFound, "; "))
}

func resolveJSONEndpoint(ctx context.Context, client *http.Client, host, endpoint, userAgent string) (wireResolution, error) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	started := time.Now()
	type queryResult struct {
		response *jsonDoHResponse
		kind     string
		err      error
	}
	results := make(chan queryResult, 3)
	for _, kind := range []string{"A", "AAAA", "HTTPS"} {
		go func() {
			response, err := jsonQuery(ctx, client, endpoint, host, kind, userAgent)
			results <- queryResult{response, kind, err}
		}()
	}
	ips := []string{}
	ttl := ^uint32(0)
	var https *jsonDoHResponse
	var grace <-chan time.Time
	var graceTimer *time.Timer
	defer func() {
		if graceTimer != nil {
			graceTimer.Stop()
		}
	}()
queries:
	for range 3 {
		var result queryResult
		select {
		case result = <-results:
		case <-grace:
			break queries
		case <-ctx.Done():
			return wireResolution{}, ctx.Err()
		}
		if result.err != nil {
			if result.kind == "HTTPS" {
				return wireResolution{}, fmt.Errorf("%s HTTPS: %w", endpoint, result.err)
			}
			continue
		}
		if result.kind == "HTTPS" {
			https = result.response
		} else {
			for _, answer := range result.response.Answer {
				ip := net.ParseIP(answer.Data)
				if (result.kind == "A" && answer.Type == 1 && ip.To4() != nil) ||
					(result.kind == "AAAA" && answer.Type == 28 && ip != nil && ip.To4() == nil) {
					ips = appendUniqueIP(ips, ip.String())
					ttl = min(ttl, answer.TTL)
				} else if answer.Type == 5 {
					ttl = min(ttl, answer.TTL)
				}
			}
		}
		if len(ips) > 0 && https != nil && graceTimer == nil {
			graceTimer = time.NewTimer(100 * time.Millisecond)
			grace = graceTimer.C
		}
	}
	if len(ips) == 0 || https == nil {
		return wireResolution{}, fmt.Errorf("%s: incomplete A/AAAA/HTTPS answer", endpoint)
	}
	for _, answer := range https.Answer {
		if answer.Type == 5 {
			ttl = min(ttl, answer.TTL)
		}
	}
	for _, answer := range https.Answer {
		if answer.Type != 65 {
			continue
		}
		ech, err := extractECH(answer.Data)
		if err == nil && validECHConfigList(ech) {
			ttl = min(ttl, answer.TTL)
			return wireResolution{ip: ips[0], ips: ips, ech: ech, resolver: endpoint,
				expiresAt: started.Add(time.Duration(ttl) * time.Second)}, nil
		}
	}
	return wireResolution{}, fmt.Errorf("%s: no valid ECH parameter", endpoint)
}

func appendUniqueIP(ips []string, ip string) []string {
	for _, existing := range ips {
		if existing == ip {
			return ips
		}
	}
	return append(ips, ip)
}

func jsonQuery(ctx context.Context, client *http.Client, endpoint, host, queryType, userAgent string) (*jsonDoHResponse, error) {
	requestURL := fmt.Sprintf("%s?name=%s&type=%s", endpoint, host, queryType)
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, requestURL, nil)
	if err != nil {
		return nil, err
	}
	request.Header.Set("Accept", "application/dns-json")
	request.Header.Set("User-Agent", userAgent)
	response, err := client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, 64<<10))
	if err != nil {
		return nil, err
	}
	contentType := strings.ToLower(response.Header.Get("Content-Type"))
	if response.StatusCode != http.StatusOK || (!strings.Contains(contentType, "json") && !strings.Contains(contentType, "dns")) {
		return nil, fmt.Errorf("http %d content-type %q", response.StatusCode, contentType)
	}
	var result jsonDoHResponse
	if err := json.Unmarshal(body, &result); err != nil {
		return nil, err
	}
	if result.Status != 0 {
		return nil, fmt.Errorf("dns status %d", result.Status)
	}
	return &result, nil
}

func extractECH(value string) ([]byte, error) {
	value = strings.TrimSpace(value)
	if strings.HasPrefix(value, `\#`) {
		raw, err := parseRawRData(value)
		if err != nil {
			return nil, err
		}
		ech := extractECHFromSVCB(raw)
		if ech == nil {
			return nil, fmt.Errorf("ECH parameter absent")
		}
		return ech, nil
	}
	for _, field := range strings.Fields(value) {
		key, encoded, ok := strings.Cut(field, "=")
		if !ok || (strings.ToLower(key) != "ech" && strings.ToLower(key) != "echconfig") {
			continue
		}
		return base64.StdEncoding.DecodeString(strings.Trim(encoded, `"`))
	}
	return nil, fmt.Errorf("ECH parameter absent")
}

func validECHConfigList(ech []byte) bool {
	return len(ech) >= 3 && int(binary.BigEndian.Uint16(ech[:2])) == len(ech)-2
}

func parseRawRData(value string) ([]byte, error) {
	parts := strings.Fields(value)
	if len(parts) < 3 || parts[0] != `\#` {
		return nil, fmt.Errorf("malformed raw RDATA")
	}
	declaredLength, err := strconv.Atoi(parts[1])
	if err != nil || declaredLength < 0 || declaredLength > 64<<10 {
		return nil, fmt.Errorf("invalid raw RDATA length")
	}
	var result []byte
	for _, part := range parts[2:] {
		decoded, err := hex.DecodeString(part)
		if err != nil {
			return nil, err
		}
		result = append(result, decoded...)
	}
	if len(result) != declaredLength {
		return nil, fmt.Errorf("raw RDATA length mismatch")
	}
	return result, nil
}

func extractECHFromSVCB(data []byte) []byte {
	if len(data) < 3 {
		return nil
	}
	data = data[2:]
	for len(data) > 0 {
		length := int(data[0])
		data = data[1:]
		if length == 0 {
			break
		}
		if length > len(data) {
			return nil
		}
		data = data[length:]
	}
	for len(data) >= 4 {
		key := binary.BigEndian.Uint16(data[:2])
		length := int(binary.BigEndian.Uint16(data[2:4]))
		data = data[4:]
		if length > len(data) {
			return nil
		}
		if key == 5 {
			return append([]byte(nil), data[:length]...)
		}
		data = data[length:]
	}
	return nil
}
