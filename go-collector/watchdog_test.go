package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// alertSink stands in for the endpoint that receives agent alerts.
type alertSink struct {
	server *httptest.Server
	mutex  sync.Mutex
	bodies []string
	status int
}

func newAlertSink(t *testing.T) *alertSink {
	t.Helper()
	sink := &alertSink{status: http.StatusOK}
	sink.server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		sink.mutex.Lock()
		sink.bodies = append(sink.bodies, string(body))
		status := sink.status
		sink.mutex.Unlock()
		w.WriteHeader(status)
	}))
	t.Cleanup(sink.server.Close)
	return sink
}

func (s *alertSink) count() int {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	return len(s.bodies)
}

func (s *alertSink) last() map[string]any {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	if len(s.bodies) == 0 {
		return nil
	}
	var decoded map[string]any
	_ = json.Unmarshal([]byte(s.bodies[len(s.bodies)-1]), &decoded)
	return decoded
}

// withDeliveryWatchdog points the watchdog at a sink and restores the globals afterwards.
func withDeliveryWatchdog(t *testing.T, webhookURL string, stallAfter time.Duration) {
	t.Helper()
	originalURL, originalStall, originalClient := agentAlertWebhookURL, deliveryStallAfter, agentAlertClient
	deliveryMutex.Lock()
	originalOK, originalFailed, originalSent := lastDeliveryOK, lastAttemptFailed, deliveryAlertSent
	deliveryMutex.Unlock()
	t.Cleanup(func() {
		agentAlertWebhookURL, deliveryStallAfter, agentAlertClient = originalURL, originalStall, originalClient
		deliveryMutex.Lock()
		lastDeliveryOK, lastAttemptFailed, deliveryAlertSent = originalOK, originalFailed, originalSent
		deliveryMutex.Unlock()
	})
	configureDeliveryWatchdog(webhookURL, stallAfter, 2*time.Second)
}

func TestAnIdleAgentIsNeverConsideredStalled(t *testing.T) {
	sink := newAlertSink(t)
	withCollector(t, nil)
	withDeliveryWatchdog(t, sink.server.URL, time.Minute)

	// No failures recorded at all: an agent with no traffic must not look broken.
	if !checkDeliveryHealth(time.Now().Add(time.Hour)) {
		t.Error("an agent that has never failed a delivery is healthy")
	}
	if sink.count() != 0 {
		t.Errorf("silence alone must not alert, got %d alerts", sink.count())
	}
}

func TestAStalledDeliveryAlertsOnceAfterTheThreshold(t *testing.T) {
	sink := newAlertSink(t)
	withCollector(t, nil)
	withDeliveryWatchdog(t, sink.server.URL, 5*time.Minute)

	recordDeliveryOutcome("queued")
	later := time.Now().Add(6 * time.Minute)

	if checkDeliveryHealth(later) {
		t.Error("delivery should be reported as stalled")
	}
	if sink.count() != 1 {
		t.Fatalf("expected one alert, got %d", sink.count())
	}
	// Repeated checks must not re-alert while the same outage continues.
	checkDeliveryHealth(later.Add(time.Minute))
	checkDeliveryHealth(later.Add(2 * time.Minute))
	if sink.count() != 1 {
		t.Errorf("an ongoing outage must alert once, got %d", sink.count())
	}
}

func TestDeliveryBelowTheThresholdDoesNotAlert(t *testing.T) {
	sink := newAlertSink(t)
	withCollector(t, nil)
	withDeliveryWatchdog(t, sink.server.URL, 10*time.Minute)

	recordDeliveryOutcome("failed")
	if !checkDeliveryHealth(time.Now().Add(2 * time.Minute)) {
		t.Error("two minutes is inside a ten minute threshold")
	}
	if sink.count() != 0 {
		t.Errorf("expected no alert, got %d", sink.count())
	}
}

func TestRecoveryAlertsWhenDeliveryReturns(t *testing.T) {
	sink := newAlertSink(t)
	withCollector(t, nil)
	withDeliveryWatchdog(t, sink.server.URL, 5*time.Minute)

	recordDeliveryOutcome("queued")
	checkDeliveryHealth(time.Now().Add(6 * time.Minute))
	if sink.count() != 1 {
		t.Fatalf("expected the stall alert first, got %d", sink.count())
	}

	recordDeliveryOutcome("delivered")
	if !checkDeliveryHealth(time.Now().Add(7 * time.Minute)) {
		t.Error("a successful delivery makes it healthy again")
	}
	if sink.count() != 2 {
		t.Fatalf("expected a recovery alert, got %d alerts", sink.count())
	}
	if event := sink.last()["event"]; event != "delivery_recovered" {
		t.Errorf("expected delivery_recovered, got %v", event)
	}
}

func TestAPermanentRejectionIsNotAnOutage(t *testing.T) {
	sink := newAlertSink(t)
	withCollector(t, nil)
	withDeliveryWatchdog(t, sink.server.URL, time.Minute)

	// A 4xx means the service answered and refused that event; it is reachable.
	recordDeliveryOutcome("rejected")
	if !checkDeliveryHealth(time.Now().Add(time.Hour)) {
		t.Error("a rejected event says nothing about reachability")
	}
	if sink.count() != 0 {
		t.Errorf("expected no alert, got %d", sink.count())
	}
}

func TestTheStallAlertDescribesTheAgentAndOutage(t *testing.T) {
	sink := newAlertSink(t)
	withCollector(t, nil)
	withDeliveryWatchdog(t, sink.server.URL, time.Minute)

	originalID, originalHostname := configuredHostID, configuredHostname
	t.Cleanup(func() { configuredHostID, configuredHostname = originalID, originalHostname })
	configuredHostID = "web-01"
	configuredHostname = "web-01.local"

	recordDeliveryOutcome("queued")
	checkDeliveryHealth(time.Now().Add(2 * time.Minute))

	payload := sink.last()
	if payload == nil {
		t.Fatal("no alert was delivered")
	}
	if payload["schema_version"] != "eventwatch.agent_alert.v1" {
		t.Errorf("the payload is a published contract, got %v", payload["schema_version"])
	}
	if payload["event"] != "delivery_stalled" {
		t.Errorf("unexpected event %v", payload["event"])
	}
	if payload["host_id"] != "web-01" || payload["hostname"] != "web-01.local" {
		t.Errorf("the alert must name the machine, got %v", payload)
	}
	if payload["agent_version"] != agentVersion {
		t.Errorf("expected the agent version, got %v", payload["agent_version"])
	}
	if seconds, ok := payload["stalled_seconds"].(float64); !ok || seconds < 100 {
		t.Errorf("expected the outage length, got %v", payload["stalled_seconds"])
	}
}

func TestNoWebhookMeansNoAlertButStillTracksHealth(t *testing.T) {
	withCollector(t, nil)
	withDeliveryWatchdog(t, "", time.Minute)

	recordDeliveryOutcome("queued")
	if checkDeliveryHealth(time.Now().Add(2 * time.Minute)) {
		t.Error("health is still tracked without a webhook configured")
	}
	if !metricsContains(t, "eventwatch_delivery_healthy 0") {
		t.Error("the gauge should report an unhealthy delivery path")
	}
}

func TestDeliveryHealthIsExposedAsMetrics(t *testing.T) {
	sink := newAlertSink(t)
	withCollector(t, nil)
	withDeliveryWatchdog(t, sink.server.URL, time.Minute)

	if !metricsContains(t, "eventwatch_delivery_healthy 1") {
		t.Error("a fresh agent reports a healthy delivery path")
	}

	recordDeliveryOutcome("queued")
	checkDeliveryHealth(time.Now().Add(2 * time.Minute))
	recordDeliveryOutcome("delivered")
	checkDeliveryHealth(time.Now().Add(3 * time.Minute))

	for _, line := range []string{
		"eventwatch_delivery_stall_alerts_total 1",
		"eventwatch_delivery_recovery_alerts_total 1",
		"eventwatch_delivery_healthy 1",
	} {
		if !metricsContains(t, line) {
			t.Errorf("missing metric line %q", line)
		}
	}
}

func TestAFailingWebhookIsCountedNotFatal(t *testing.T) {
	sink := newAlertSink(t)
	sink.mutex.Lock()
	sink.status = http.StatusInternalServerError
	sink.mutex.Unlock()
	withCollector(t, nil)
	withDeliveryWatchdog(t, sink.server.URL, time.Minute)

	recordDeliveryOutcome("queued")
	checkDeliveryHealth(time.Now().Add(2 * time.Minute))

	if !metricsContains(t, "eventwatch_agent_alert_failures_total 1") {
		t.Error("a rejected agent alert should be counted")
	}
}

func metricsContains(t *testing.T, line string) bool {
	t.Helper()
	recorder := httptest.NewRecorder()
	metricsHandler(recorder, httptest.NewRequest(http.MethodGet, "/metrics", nil))
	return strings.Contains(recorder.Body.String(), line)
}
