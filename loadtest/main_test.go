package main

import (
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// TestSendDrainsTheResponseBody is a regression test: send() used to consume the response
// with a single 512-byte Read, which leaves the connection unreusable, so the harness
// measured TCP handshakes instead of server time.
func TestSendDrainsTheResponseBody(t *testing.T) {
	var mutex sync.Mutex
	opened := 0

	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Comfortably larger than any single Read the harness might do.
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"ok","message":"` + strings.Repeat("x", 4096) + `"}`))
	}))
	server.Config.ConnState = func(_ net.Conn, state http.ConnState) {
		if state == http.StateNew {
			mutex.Lock()
			opened++
			mutex.Unlock()
		}
	}
	server.Start()
	defer server.Close()

	client := &http.Client{
		Timeout:   5 * time.Second,
		Transport: &http.Transport{MaxIdleConns: 4, MaxIdleConnsPerHost: 4},
	}

	for index := 0; index < 5; index++ {
		outcome := send(client, "analytics", server.URL+"/receive", "test-key", index, 10)
		if outcome.err != nil {
			t.Fatalf("request %d failed: %v", index, outcome.err)
		}
		if outcome.status != http.StatusOK {
			t.Fatalf("request %d returned %d", index, outcome.status)
		}
	}

	mutex.Lock()
	defer mutex.Unlock()
	if opened != 1 {
		t.Errorf("expected the connection to be reused across 5 sequential requests, "+
			"but %d were opened; the response body is not being drained to EOF", opened)
	}
}

func TestSendReportsTransportFailures(t *testing.T) {
	client := &http.Client{Timeout: time.Second}
	// Port 1 is reserved and refuses connections.
	outcome := send(client, "analytics", "http://127.0.0.1:1/receive", "test-key", 0, 10)
	if outcome.err == nil {
		t.Fatal("expected an unreachable target to be reported as a transport failure")
	}
	if outcome.status != 0 {
		t.Errorf("a failed request should carry no status, got %d", outcome.status)
	}
}

func TestSendBuildsTheContractPayload(t *testing.T) {
	var received string
	var apiKey string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body := make([]byte, r.ContentLength)
		_, _ = r.Body.Read(body)
		received = string(body)
		apiKey = r.Header.Get("X-EventWatch-Key")
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	client := &http.Client{Timeout: 5 * time.Second}
	if outcome := send(client, "analytics", server.URL, "secret", 3, 10); outcome.err != nil {
		t.Fatalf("request failed: %v", outcome.err)
	}

	if apiKey != "secret" {
		t.Errorf("expected the api key to be sent, got %q", apiKey)
	}
	for _, field := range []string{`"event_id"`, `"level"`, `"msg"`, `"timestamp"`, `"cpu_usage"`, `"ram_usage"`} {
		if !strings.Contains(received, field) {
			t.Errorf("payload is missing contract field %s: %s", field, received)
		}
	}
	if !strings.Contains(received, `"timestamp":"`) || !strings.Contains(received, "Z\"") {
		t.Errorf("timestamps must be UTC so ordering matches lexical order: %s", received)
	}
}

func TestPercentilePicksOrderedValues(t *testing.T) {
	sorted := []time.Duration{
		10 * time.Millisecond, 20 * time.Millisecond, 30 * time.Millisecond,
		40 * time.Millisecond, 50 * time.Millisecond,
	}
	if got := percentile(sorted, 0.0); got != 10*time.Millisecond {
		t.Errorf("p0 should be the fastest sample, got %s", got)
	}
	if got := percentile(sorted, 0.5); got != 30*time.Millisecond {
		t.Errorf("p50 should be the median, got %s", got)
	}
	if got := percentile(sorted, 0.99); got != 50*time.Millisecond {
		t.Errorf("p99 should be the slowest sample here, got %s", got)
	}
}
