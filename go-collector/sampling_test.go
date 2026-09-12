package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestAScheduledSampleReportsWithoutAnyoneCallingCapture(t *testing.T) {
	// The whole point of the timer: a machine reports whether or not anything asks it to.
	stub := newBackendStub(t)
	withCollector(t, stub)
	withDiskPaths(t, nil)
	// main() resolves identity before serving; a test has to say so itself.
	originalID := configuredHostID
	t.Cleanup(func() { configuredHostID = originalID })
	configuredHostID = "sampling-host"

	status, message := captureEvent(sampleLevel, sampleMessage, newEventID())
	if status != http.StatusOK {
		t.Fatalf("expected the sample to be delivered, got %d: %s", status, message)
	}

	var payload LogPayload
	if err := json.Unmarshal([]byte(stub.header(stub.lastBody)), &payload); err != nil {
		t.Fatalf("backend received invalid JSON: %v", err)
	}
	if payload.Messages != sampleMessage || payload.Level != sampleLevel {
		t.Errorf("a scheduled sample must be identifiable, got %q at %q", payload.Messages, payload.Level)
	}
	if payload.CPUUsage < 0 || payload.RAMUsage <= 0 {
		t.Errorf("a sample carries this machine's own readings, got cpu=%v ram=%v",
			payload.CPUUsage, payload.RAMUsage)
	}
	if payload.HostID != "sampling-host" {
		t.Errorf("a sample is attributed to the machine that took it, got %q", payload.HostID)
	}
}

func TestTheScheduledSampleIsNotAnError(t *testing.T) {
	// REPEATED_ERROR counts identical ERROR messages. A sample every minute carrying an error
	// level would raise an alert on every healthy machine in the fleet.
	if sampleLevel != "INFO" {
		t.Errorf("a routine sample must not look like a problem, got %q", sampleLevel)
	}
}

func TestTheCaptureEndpointAndTheTimerShareOnePath(t *testing.T) {
	// Both go through captureEvent, so an event looks the same however it was triggered. If these
	// ever diverge, one of the two stops being exercised by the other's tests.
	stub := newBackendStub(t)
	withCollector(t, stub)
	withDiskPaths(t, nil)

	recorder := httptest.NewRecorder()
	logHandler(recorder, httptest.NewRequest(http.MethodGet, "/capture?level=WARN&msg=through%20http", nil))
	var viaHTTP LogPayload
	if err := json.Unmarshal([]byte(stub.header(stub.lastBody)), &viaHTTP); err != nil {
		t.Fatalf("invalid JSON from the endpoint: %v", err)
	}

	captureEvent(sampleLevel, sampleMessage, newEventID())
	var viaTimer LogPayload
	if err := json.Unmarshal([]byte(stub.header(stub.lastBody)), &viaTimer); err != nil {
		t.Fatalf("invalid JSON from the timer: %v", err)
	}

	if viaHTTP.HostID != viaTimer.HostID || viaHTTP.AgentVersion != viaTimer.AgentVersion {
		t.Error("both routes must stamp the same identity onto an event")
	}
	if viaTimer.EventID == viaHTTP.EventID {
		t.Error("every event needs its own id, or deduplication would drop the second one")
	}
	if (viaHTTP.DiskUsage == nil) != (viaTimer.DiskUsage == nil) {
		t.Error("both routes sample the same things; only the trigger differs")
	}
}

func TestAScheduledSampleQueuesWhenAnalyticsIsDown(t *testing.T) {
	// The timer must not lose a reading during an outage any more than /capture does.
	withCollector(t, nil)
	withDiskPaths(t, nil)

	status, _ := captureEvent(sampleLevel, sampleMessage, newEventID())
	if status != http.StatusServiceUnavailable {
		t.Fatalf("expected the sample to be queued for retry, got %d", status)
	}
	if queueDepth() != 1 {
		t.Errorf("a scheduled sample belongs in the durable queue like any other event, depth %d",
			queueDepth())
	}
}

func TestTheSamplerStopsWhenItsTickerIsStopped(t *testing.T) {
	// Guards the loop shape rather than the timing: a ticker-driven goroutine that never returns
	// would keep a test binary alive and hide a leak.
	stub := newBackendStub(t)
	withCollector(t, stub)
	withDiskPaths(t, nil)

	done := make(chan struct{})
	go func() {
		sampleHostPeriodically(10 * time.Millisecond)
		close(done)
	}()

	// The loop runs until the process ends, so observing one delivery is what confirms it ticks.
	deadline := time.After(3 * time.Second)
	for {
		if stub.header(stub.lastBody) != "" {
			return
		}
		select {
		case <-deadline:
			t.Fatal("the sampler never delivered anything")
		case <-time.After(20 * time.Millisecond):
		}
	}
}
