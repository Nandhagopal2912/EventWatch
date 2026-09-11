package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// backendStub stands in for the Java analytics service.
type backendStub struct {
	server        *httptest.Server
	requests      atomic.Int64
	status        atomic.Int64
	lastBody      atomic.Value
	lastKey       atomic.Value
	lastTraceID   atomic.Value
	failuresLeft  atomic.Int64
	failureStatus atomic.Int64
}

func newBackendStub(t *testing.T) *backendStub {
	t.Helper()
	stub := &backendStub{}
	stub.status.Store(int64(http.StatusOK))
	stub.failureStatus.Store(int64(http.StatusInternalServerError))
	stub.server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := os.ReadFile(os.DevNull)
		_ = body
		decoded := make([]byte, r.ContentLength)
		if r.ContentLength > 0 {
			_, _ = r.Body.Read(decoded)
		}
		stub.requests.Add(1)
		stub.lastBody.Store(string(decoded))
		stub.lastKey.Store(r.Header.Get("X-EventWatch-Key"))
		stub.lastTraceID.Store(r.Header.Get("X-Correlation-ID"))

		if stub.failuresLeft.Load() > 0 {
			stub.failuresLeft.Add(-1)
			w.WriteHeader(int(stub.failureStatus.Load()))
			return
		}
		w.WriteHeader(int(stub.status.Load()))
	}))
	t.Cleanup(stub.server.Close)
	return stub
}

func (s *backendStub) header(value atomic.Value) string {
	if stored, ok := value.Load().(string); ok {
		return stored
	}
	return ""
}

// withCollector points the package globals at a stub backend and a temporary queue.
func withCollector(t *testing.T, stub *backendStub) {
	t.Helper()
	originalURL, originalKey, originalClient := configuredBackendURL, configuredAPIKey, backendClient
	originalDirectory, originalCapacity, originalMetrics := queueDirectory, queueCapacity, metrics
	t.Cleanup(func() {
		configuredBackendURL, configuredAPIKey, backendClient = originalURL, originalKey, originalClient
		queueDirectory, queueCapacity, metrics = originalDirectory, originalCapacity, originalMetrics
	})

	if stub != nil {
		configuredBackendURL = stub.server.URL + "/receive"
	} else {
		// A closed listener address so every attempt fails at the transport layer.
		configuredBackendURL = "http://127.0.0.1:1/receive"
	}
	configuredAPIKey = "test-secret"
	backendClient = &http.Client{Timeout: 2 * time.Second}
	queueDirectory = t.TempDir()
	queueCapacity = 100
	metrics = &collectorMetrics{
		capturedByLevel:  make(map[string]uint64),
		forwardByOutcome: make(map[string]uint64),
	}
	configureLogging("json")
}

func queuedFiles(t *testing.T) []string {
	t.Helper()
	matches, err := filepath.Glob(filepath.Join(queueDirectory, "*.json"))
	if err != nil {
		t.Fatalf("unable to list queue: %v", err)
	}
	return matches
}

func rejectedFiles(t *testing.T) []string {
	t.Helper()
	matches, err := filepath.Glob(filepath.Join(queueDirectory, "rejected-events", "*.json"))
	if err != nil {
		t.Fatalf("unable to list rejected events: %v", err)
	}
	return matches
}

func TestIsRetryableStatus(t *testing.T) {
	retryable := []int{500, 502, 503, 504, http.StatusTooManyRequests}
	for _, status := range retryable {
		if !isRetryableStatus(status) {
			t.Errorf("status %d should be retryable", status)
		}
	}
	permanent := []int{200, 201, 400, 401, 403, 404, 413, 415}
	for _, status := range permanent {
		if isRetryableStatus(status) {
			t.Errorf("status %d should be permanent", status)
		}
	}
}

func TestForwardSendsContractHeaders(t *testing.T) {
	stub := newBackendStub(t)
	withCollector(t, stub)

	response, err := forwardToJava([]byte(`{"event_id":"e1"}`), "trace-1")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	response.Body.Close()

	if got := stub.header(stub.lastKey); got != "test-secret" {
		t.Errorf("expected the api key to be sent, got %q", got)
	}
	if got := stub.header(stub.lastTraceID); got != "trace-1" {
		t.Errorf("expected the correlation id to be sent, got %q", got)
	}
}

func TestForwardRetriesServerErrorsAndSucceeds(t *testing.T) {
	stub := newBackendStub(t)
	withCollector(t, stub)
	stub.failuresLeft.Store(2)

	response, err := forwardToJava([]byte(`{"event_id":"e1"}`), "trace-1")
	if err != nil {
		t.Fatalf("expected the third attempt to succeed: %v", err)
	}
	response.Body.Close()

	if stub.requests.Load() != 3 {
		t.Errorf("expected three attempts, got %d", stub.requests.Load())
	}
}

func TestForwardGivesUpAfterTheAttemptLimit(t *testing.T) {
	stub := newBackendStub(t)
	withCollector(t, stub)
	stub.status.Store(http.StatusInternalServerError)

	if _, err := forwardToJava([]byte(`{"event_id":"e1"}`), "trace-1"); err == nil {
		t.Fatal("expected an error once the attempts are exhausted")
	}
	if stub.requests.Load() != int64(maxBackendAttempts) {
		t.Errorf("expected %d attempts, got %d", maxBackendAttempts, stub.requests.Load())
	}
}

func TestForwardDoesNotRetryClientErrors(t *testing.T) {
	stub := newBackendStub(t)
	withCollector(t, stub)
	stub.status.Store(http.StatusBadRequest)

	response, err := forwardToJava([]byte(`{"event_id":"e1"}`), "trace-1")
	if err != nil {
		t.Fatalf("a client error should be returned, not retried: %v", err)
	}
	response.Body.Close()

	if stub.requests.Load() != 1 {
		t.Errorf("expected a single attempt, got %d", stub.requests.Load())
	}
	if response.StatusCode != http.StatusBadRequest {
		t.Errorf("expected the client status to reach the caller, got %d", response.StatusCode)
	}
}

func TestCaptureDeliversAndReportsSuccess(t *testing.T) {
	stub := newBackendStub(t)
	withCollector(t, stub)

	recorder := httptest.NewRecorder()
	logHandler(recorder, httptest.NewRequest(http.MethodGet, "/capture?level=ERROR&msg=disk%20full", nil))

	if recorder.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", recorder.Code, recorder.Body.String())
	}
	if len(queuedFiles(t)) != 0 {
		t.Error("a delivered event must not be queued")
	}

	var payload LogPayload
	if err := json.Unmarshal([]byte(stub.header(stub.lastBody)), &payload); err != nil {
		t.Fatalf("backend received invalid JSON: %v", err)
	}
	if payload.Level != "ERROR" || payload.Messages != "disk full" {
		t.Errorf("unexpected payload: %+v", payload)
	}
	if payload.EventID == "" || payload.CorrelationID == "" {
		t.Error("every event needs an event id and a correlation id")
	}
	if !strings.HasSuffix(payload.Time, "Z") {
		t.Errorf("timestamps must be UTC so ordering matches lexical order, got %q", payload.Time)
	}
}

func TestCaptureAppliesDefaults(t *testing.T) {
	stub := newBackendStub(t)
	withCollector(t, stub)

	recorder := httptest.NewRecorder()
	logHandler(recorder, httptest.NewRequest(http.MethodGet, "/capture", nil))

	var payload LogPayload
	if err := json.Unmarshal([]byte(stub.header(stub.lastBody)), &payload); err != nil {
		t.Fatalf("backend received invalid JSON: %v", err)
	}
	if payload.Level != "INFO" || payload.Messages != "Default cloud event" {
		t.Errorf("expected the documented defaults, got %+v", payload)
	}
}

func TestCaptureHonoursAnInboundCorrelationID(t *testing.T) {
	stub := newBackendStub(t)
	withCollector(t, stub)

	request := httptest.NewRequest(http.MethodGet, "/capture?msg=traced", nil)
	request.Header.Set("X-Correlation-ID", "caller-supplied")
	recorder := httptest.NewRecorder()
	logHandler(recorder, request)

	if got := recorder.Header().Get("X-Correlation-ID"); got != "caller-supplied" {
		t.Errorf("expected the id to be echoed, got %q", got)
	}
	if got := stub.header(stub.lastTraceID); got != "caller-supplied" {
		t.Errorf("expected the id to be forwarded, got %q", got)
	}
}

func TestCaptureRejectsNonGetMethods(t *testing.T) {
	withCollector(t, newBackendStub(t))
	recorder := httptest.NewRecorder()
	logHandler(recorder, httptest.NewRequest(http.MethodPost, "/capture", nil))
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Errorf("expected 405, got %d", recorder.Code)
	}
}

func TestCaptureQueuesWhenTheBackendIsUnreachable(t *testing.T) {
	withCollector(t, nil)

	recorder := httptest.NewRecorder()
	logHandler(recorder, httptest.NewRequest(http.MethodGet, "/capture?msg=queued", nil))

	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected 503, got %d", recorder.Code)
	}
	if len(queuedFiles(t)) != 1 {
		t.Fatalf("expected the event to be queued, found %d files", len(queuedFiles(t)))
	}
}

func TestCaptureQueuesRateLimitedEvents(t *testing.T) {
	// Regression: a 429 used to be dropped instead of queued for retry.
	stub := newBackendStub(t)
	withCollector(t, stub)
	stub.status.Store(http.StatusTooManyRequests)

	recorder := httptest.NewRecorder()
	logHandler(recorder, httptest.NewRequest(http.MethodGet, "/capture?msg=throttled", nil))

	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected 503, got %d", recorder.Code)
	}
	if len(queuedFiles(t)) != 1 {
		t.Fatalf("a rate-limited event must be queued, found %d files", len(queuedFiles(t)))
	}
}

func TestCaptureDoesNotQueuePermanentRejections(t *testing.T) {
	stub := newBackendStub(t)
	withCollector(t, stub)
	stub.status.Store(http.StatusBadRequest)

	recorder := httptest.NewRecorder()
	logHandler(recorder, httptest.NewRequest(http.MethodGet, "/capture?msg=invalid", nil))

	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("expected the backend status to be surfaced, got %d", recorder.Code)
	}
	if len(queuedFiles(t)) != 0 {
		t.Error("a permanently rejected event must not be queued")
	}
}

func TestPendingEventsAreDeliveredAndRemoved(t *testing.T) {
	stub := newBackendStub(t)
	withCollector(t, stub)

	if err := enqueueEvent([]byte(`{"event_id":"q1","correlation_id":"trace-q"}`), "trace-q"); err != nil {
		t.Fatalf("enqueue failed: %v", err)
	}
	processPendingEvents()

	if len(queuedFiles(t)) != 0 {
		t.Error("a delivered event must be removed from the queue")
	}
	if got := stub.header(stub.lastTraceID); got != "trace-q" {
		t.Errorf("a retry should reuse the stored correlation id, got %q", got)
	}
}

func TestPendingEventsStayQueuedOnRetryableFailures(t *testing.T) {
	stub := newBackendStub(t)
	withCollector(t, stub)

	for _, status := range []int{http.StatusInternalServerError, http.StatusTooManyRequests} {
		stub.status.Store(int64(status))
		if err := enqueueEvent([]byte(`{"event_id":"q1"}`), "trace-q"); err != nil {
			t.Fatalf("enqueue failed: %v", err)
		}
		processPendingEvents()

		if len(queuedFiles(t)) != 1 {
			t.Errorf("status %d: the event should still be pending", status)
		}
		if len(rejectedFiles(t)) != 0 {
			t.Errorf("status %d: a temporary failure must not be rejected", status)
		}
		for _, path := range queuedFiles(t) {
			_ = os.Remove(path)
		}
	}
}

func TestPendingEventsMoveAsideOnPermanentFailure(t *testing.T) {
	stub := newBackendStub(t)
	withCollector(t, stub)
	stub.status.Store(http.StatusBadRequest)

	if err := enqueueEvent([]byte(`{"event_id":"q1"}`), "trace-q"); err != nil {
		t.Fatalf("enqueue failed: %v", err)
	}
	processPendingEvents()

	if len(queuedFiles(t)) != 0 {
		t.Error("a permanently rejected event must leave the pending queue")
	}
	if len(rejectedFiles(t)) != 1 {
		t.Error("a permanently rejected event should be kept for inspection")
	}
}

func TestEnqueueWritesCompleteFilesOnly(t *testing.T) {
	withCollector(t, nil)
	payload := []byte(`{"event_id":"atomic","correlation_id":"trace-a"}`)
	if err := enqueueEvent(payload, "trace-a"); err != nil {
		t.Fatalf("enqueue failed: %v", err)
	}

	temporaries, _ := filepath.Glob(filepath.Join(queueDirectory, "*.tmp"))
	if len(temporaries) != 0 {
		t.Error("no temporary file should survive a successful write")
	}
	files := queuedFiles(t)
	if len(files) != 1 {
		t.Fatalf("expected one queued file, got %d", len(files))
	}
	contents, err := os.ReadFile(files[0])
	if err != nil {
		t.Fatalf("unable to read queued file: %v", err)
	}
	if string(contents) != string(payload) {
		t.Errorf("queued payload was altered: %s", contents)
	}
}

func TestCorrelationIDOfHandlesMissingAndInvalidPayloads(t *testing.T) {
	if got := correlationIDOf([]byte(`{"correlation_id":"trace-x"}`)); got != "trace-x" {
		t.Errorf("expected trace-x, got %q", got)
	}
	if got := correlationIDOf([]byte(`{"event_id":"only"}`)); got != "" {
		t.Errorf("expected an empty id, got %q", got)
	}
	if got := correlationIDOf([]byte(`not json`)); got != "" {
		t.Errorf("a corrupt file must not panic, got %q", got)
	}
}

func TestQueueDepthCountsOnlyPendingEvents(t *testing.T) {
	withCollector(t, nil)
	if queueDepth() != 0 {
		t.Fatalf("a new queue should be empty, got %d", queueDepth())
	}
	for index := 0; index < 3; index++ {
		if err := enqueueEvent([]byte(`{"event_id":"q"}`), "trace"); err != nil {
			t.Fatalf("enqueue failed: %v", err)
		}
	}
	if queueDepth() != 3 {
		t.Errorf("expected a depth of 3, got %d", queueDepth())
	}
}

func TestHealthEndpointReportsService(t *testing.T) {
	recorder := httptest.NewRecorder()
	healthHandler(recorder, httptest.NewRequest(http.MethodGet, "/health", nil))

	if recorder.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", recorder.Code)
	}
	var body map[string]string
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatalf("health must answer with JSON: %v", err)
	}
	if body["status"] != "ok" || body["service"] != "go-collector" {
		t.Errorf("unexpected health body: %v", body)
	}
}

func TestMetricsRenderInPrometheusFormat(t *testing.T) {
	withCollector(t, nil)
	metrics.recordCapture("ERROR")
	metrics.recordCapture("ERROR")
	metrics.recordCapture("INFO")
	metrics.recordForward("delivered")
	metrics.recordForward("queued")
	metrics.recordQueueWrite()
	metrics.observeForwardDuration(0.5)

	recorder := httptest.NewRecorder()
	metricsHandler(recorder, httptest.NewRequest(http.MethodGet, "/metrics", nil))
	if recorder.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", recorder.Code)
	}
	output := recorder.Body.String()

	expected := []string{
		`eventwatch_events_captured_total{level="ERROR"} 2`,
		`eventwatch_events_captured_total{level="INFO"} 1`,
		`eventwatch_forward_attempts_total{outcome="delivered"} 1`,
		`eventwatch_forward_attempts_total{outcome="queued"} 1`,
		"eventwatch_queue_writes_total 1",
		"eventwatch_queue_depth 0",
		"eventwatch_forward_duration_seconds_count 1",
		"# TYPE eventwatch_queue_depth gauge",
	}
	for _, line := range expected {
		if !strings.Contains(output, line) {
			t.Errorf("missing metric line %q in:\n%s", line, output)
		}
	}
}

func TestMetricsEscapeLabelValues(t *testing.T) {
	if got := escapeLabelValue(`odd"level`); got != `odd\"level` {
		t.Errorf("quotes must be escaped, got %q", got)
	}
	if got := escapeLabelValue(`back\slash`); got != `back\\slash` {
		t.Errorf("backslashes must be escaped, got %q", got)
	}
}

func TestMetricsRejectsNonGetMethods(t *testing.T) {
	withCollector(t, nil)
	recorder := httptest.NewRecorder()
	metricsHandler(recorder, httptest.NewRequest(http.MethodPost, "/metrics", nil))
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Errorf("expected 405, got %d", recorder.Code)
	}
}

func TestReadHostMetricsReturnsPercentages(t *testing.T) {
	cpuUsage, ramUsage, err := readHostMetrics()
	if err != nil {
		t.Fatalf("host metrics should be readable: %v", err)
	}
	if cpuUsage < 0 || cpuUsage > 100 {
		t.Errorf("cpu usage out of range: %f", cpuUsage)
	}
	if ramUsage < 0 || ramUsage > 100 {
		t.Errorf("ram usage out of range: %f", ramUsage)
	}
}
