package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/joho/godotenv"
	"github.com/shirou/gopsutil/v4/cpu"
	"github.com/shirou/gopsutil/v4/mem"
)

// LogPayload is the JSON contract shared with the Java analytics service.
type LogPayload struct {
	EventID       string  `json:"event_id"`
	CorrelationID string  `json:"correlation_id,omitempty"`
	HostID        string  `json:"host_id"`
	Hostname      string  `json:"hostname"`
	AgentVersion  string  `json:"agent_version"`
	QueueDepth    int     `json:"queue_depth"`
	Level         string  `json:"level"`
	Messages      string  `json:"msg"`
	Time          string  `json:"timestamp"`
	CPUUsage      float64 `json:"cpu_usage"`
	RAMUsage      float64 `json:"ram_usage"`
}

const (
	maxBackendAttempts = 3
	stressEventCount   = 500
	stressConcurrency  = 32
)

// Overridable at build time: go build -ldflags "-X main.agentVersion=1.2.3"
var agentVersion = "0.15.0"

var (
	backendClient        *http.Client
	configuredBackendURL string
	configuredAPIKey     string
	queueDirectory       string
	queueCapacity        int
	queueRetryInterval   time.Duration
	queueWake            = make(chan struct{}, 1)
	queueMutex           sync.Mutex
	eventSequence        uint64
)

func readHostMetrics() (float64, float64, error) {
	cpuPercent, err := cpu.Percent(100*time.Millisecond, false)
	if err != nil || len(cpuPercent) == 0 {
		return 0, 0, fmt.Errorf("read CPU usage: %w", err)
	}

	memory, err := mem.VirtualMemory()
	if err != nil {
		return 0, 0, fmt.Errorf("read memory usage: %w", err)
	}

	return cpuPercent[0], memory.UsedPercent, nil
}

func logHandler(w http.ResponseWriter, r *http.Request) {
	// The public collector endpoint accepts query parameters and creates a telemetry event.
	if r.Method != http.MethodGet {
		writeMessage(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if !authorizeCapture(r) {
		writeMessage(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	// An inbound correlation id is honoured so a caller can trace its own request.
	correlationID := r.Header.Get("X-Correlation-ID")
	if correlationID == "" {
		correlationID = newEventID()
	}
	w.Header().Set("X-Correlation-ID", correlationID)

	level := r.URL.Query().Get("level")
	msg := r.URL.Query().Get("msg")

	if level == "" {
		level = "INFO"
	}
	if msg == "" {
		msg = "Default cloud event"
	}
	cpuUsage, ramUsage, err := readHostMetrics()
	if err != nil {
		metrics.recordHostFailure()
		logError("host metrics unavailable", logFields{"correlation_id": correlationID, "error": err.Error()})
		writeMessage(w, http.StatusInternalServerError, "host metrics unavailable")
		return
	}

	payload := LogPayload{
		EventID:       newEventID(),
		CorrelationID: correlationID,
		HostID:        configuredHostID,
		Hostname:      configuredHostname,
		AgentVersion:  agentVersion,
		QueueDepth:    queueDepth(),
		Level:         level,
		Messages:      msg,
		Time:          time.Now().UTC().Format(time.RFC3339),
		CPUUsage:      cpuUsage,
		RAMUsage:      ramUsage,
	}
	metrics.recordCapture(level)
	logInfo("captured event", logFields{
		"host_id":        configuredHostID,
		"correlation_id": correlationID,
		"event_id":       payload.EventID,
		"event_level":    level,
		"cpu_usage":      cpuUsage,
		"ram_usage":      ramUsage,
	})

	jsonBytes, err := json.Marshal(payload)
	if err != nil {
		logError("event serialization failed", logFields{"correlation_id": correlationID, "error": err.Error()})
		writeMessage(w, http.StatusInternalServerError, "internal payload error")
		return
	}

	resp, err := forwardToJava(jsonBytes, correlationID)

	if err != nil {
		if queueErr := enqueueEvent(jsonBytes, correlationID); queueErr != nil {
			metrics.recordForward("failed")
			logError("analytics unreachable and queueing failed", logFields{
				"correlation_id": correlationID,
				"error":          err.Error(),
				"queue_error":    queueErr.Error(),
			})
			writeMessage(w, http.StatusServiceUnavailable, "backend unavailable and local queue is full")
			return
		}
		metrics.recordForward("queued")
		logWarn("analytics unavailable; event queued", logFields{
			"correlation_id": correlationID,
			"error":          err.Error(),
		})
		writeMessage(w, http.StatusServiceUnavailable, "backend unavailable; event queued for retry")
		return
	}

	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		// Rate limiting is temporary, so queue it for retry exactly like a server failure.
		if isRetryableStatus(resp.StatusCode) {
			if queueErr := enqueueEvent(jsonBytes, correlationID); queueErr != nil {
				metrics.recordForward("failed")
				logError("retryable rejection and queueing failed", logFields{
					"correlation_id": correlationID,
					"status":         resp.StatusCode,
					"queue_error":    queueErr.Error(),
				})
				writeMessage(w, http.StatusServiceUnavailable, "backend unavailable and local queue is full")
				return
			}
			metrics.recordForward("queued")
			logWarn("analytics returned a retryable status; event queued", logFields{
				"correlation_id": correlationID,
				"status":         resp.StatusCode,
			})
			writeMessage(w, http.StatusServiceUnavailable, "backend unavailable; event queued for retry")
			return
		}
		metrics.recordForward("rejected")
		logWarn("analytics rejected the event", logFields{
			"correlation_id": correlationID,
			"status":         resp.StatusCode,
		})
		writeMessage(w, resp.StatusCode, "Java backend rejected the log")
		return
	}
	metrics.recordForward("delivered")
	logInfo("event delivered to analytics", logFields{
		"correlation_id": correlationID,
		"event_id":       payload.EventID,
	})
	writeMessage(w, http.StatusOK, "log forwarded to analytics engine successfully")
}

func stressHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeMessage(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if !authorizeCapture(r) {
		writeMessage(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	correlationID := newEventID()
	logInfo("starting stress test", logFields{
		"correlation_id": correlationID,
		"events":         stressEventCount,
		"concurrency":    stressConcurrency,
	})

	fakeErrors := []string{
		"Database transaction deadlock",
		"Unauthorized API access attempt",
		"Out of memory error in payment service",
	}
	cpuUsage, ramUsage, err := readHostMetrics()
	if err != nil {
		metrics.recordHostFailure()
		writeMessage(w, http.StatusInternalServerError, "host metrics unavailable")
		return
	}

	// Bound the fan-out so the test measures pipeline throughput rather than goroutine spawning.
	slots := make(chan struct{}, stressConcurrency)
	for i := 0; i < stressEventCount; i++ {
		errorMsg := fakeErrors[i%len(fakeErrors)]

		payload := LogPayload{
			EventID:       newEventID(),
			CorrelationID: correlationID,
			HostID:        configuredHostID,
			Hostname:      configuredHostname,
			AgentVersion:  agentVersion,
			QueueDepth:    queueDepth(),
			Level:         "ERROR",
			Messages:      fmt.Sprintf("%s (Log #%d)", errorMsg, i),
			Time:          time.Now().UTC().Format(time.RFC3339),
			CPUUsage:      cpuUsage,
			RAMUsage:      ramUsage,
		}
		metrics.recordCapture(payload.Level)
		jsonBytes, marshalErr := json.Marshal(payload)
		if marshalErr != nil {
			logError("stress event dropped", logFields{
				"correlation_id": correlationID, "error": marshalErr.Error()})
			continue
		}
		slots <- struct{}{}
		go func(data []byte) {
			defer func() { <-slots }()
			resp, err := forwardToJava(data, correlationID)

			if err != nil {
				queueOrDrop(data, correlationID)
				return
			}
			defer resp.Body.Close()
			if isRetryableStatus(resp.StatusCode) {
				queueOrDrop(data, correlationID)
				return
			}
			if resp.StatusCode >= 200 && resp.StatusCode < 300 {
				metrics.recordForward("delivered")
			} else {
				metrics.recordForward("rejected")
			}
		}(jsonBytes)
	}
	writeMessage(w, http.StatusOK, fmt.Sprintf("%d logs queued for the analytics engine", stressEventCount))
}

func main() {
	// Both services read the same root .env file for local configuration.
	_ = godotenv.Load("../.env", ".env")
	javaBackendURL := os.Getenv("JAVA_BACKEND_URL")
	if javaBackendURL == "" {
		javaBackendURL = "http://localhost:8080/receive"
	}
	configureLogging(getEnv("LOG_FORMAT", "json"))
	apiKey := os.Getenv("EVENTWATCH_API_KEY")
	if apiKey == "" {
		fmt.Println("EVENTWATCH_API_KEY is required")
		return
	}

	backendClient = &http.Client{Timeout: 5 * time.Second}
	configuredBackendURL = javaBackendURL
	configuredAPIKey = apiKey
	queueDirectory = getEnv("PENDING_EVENTS_DIR", "pending-events")
	queueCapacity = getIntEnv("QUEUE_CAPACITY", 1000)
	queueRetryInterval = time.Duration(getIntEnv("QUEUE_RETRY_SECONDS", 5)) * time.Second
	if queueCapacity < 1 || queueRetryInterval < time.Second {
		fmt.Println("QUEUE_CAPACITY must be positive and QUEUE_RETRY_SECONDS must be at least 1")
		return
	}
	if err := os.MkdirAll(queueDirectory, 0755); err != nil {
		fmt.Printf("Unable to create pending event directory: %v\n", err)
		return
	}
	collectorPort := getEnv("COLLECTOR_PORT", "8082")

	identityFile := getEnv("HOST_ID_FILE", filepath.Join(queueDirectory, "host-id"))
	hostID, hostname, err := resolveHostIdentity(identityFile)
	if err != nil {
		fmt.Printf("Unable to establish host identity: %v\n", err)
		return
	}
	configuredHostID = hostID
	configuredHostname = hostname

	bindAddress := getEnv("COLLECTOR_BIND", "127.0.0.1")
	keyRequired, err := resolveIngressSecurity(
		bindAddress,
		getEnv("CAPTURE_API_KEY", ""),
		strings.EqualFold(getEnv("CAPTURE_REQUIRE_KEY", "false"), "true"))
	if err != nil {
		fmt.Printf("Ingress security misconfigured: %v\n", err)
		return
	}

	tlsConfig, err := backendTLSConfig(
		getEnv("BACKEND_CA_FILE", ""),
		strings.EqualFold(getEnv("BACKEND_TLS_SKIP_VERIFY", "false"), "true"))
	if err != nil {
		fmt.Printf("Backend TLS misconfigured: %v\n", err)
		return
	}
	if tlsConfig != nil {
		backendClient.Transport = &http.Transport{TLSClientConfig: tlsConfig}
	}

	configureDeliveryWatchdog(
		getEnv("AGENT_ALERT_WEBHOOK_URL", ""),
		time.Duration(getIntEnv("DELIVERY_STALL_MINUTES", 5))*time.Minute,
		time.Duration(getIntEnv("AGENT_ALERT_TIMEOUT_SECONDS", 5))*time.Second)

	go retryPendingEvents()

	http.HandleFunc("/capture", logHandler)
	http.HandleFunc("/health", healthHandler)
	http.HandleFunc("/metrics", metricsHandler)

	http.HandleFunc("/stress", stressHandler)

	logInfo("agent started", logFields{
		"host_id":              configuredHostID,
		"hostname":             configuredHostname,
		"address":              bindAddress + ":" + collectorPort,
		"capture_key_required": keyRequired,
		"backend_url":          configuredBackendURL,
		"queue_capacity":       queueCapacity,
		"queue_depth":          queueDepth(),
	})

	server := &http.Server{Addr: bindAddress + ":" + collectorPort}
	serverError := make(chan error, 1)
	go func() {
		serverError <- server.ListenAndServe()
	}()

	shutdownSignal := make(chan os.Signal, 1)
	signal.Notify(shutdownSignal, os.Interrupt, syscall.SIGTERM)
	select {
	case signalReceived := <-shutdownSignal:
		logInfo("shutting down agent", logFields{"signal": signalReceived.String()})
		shutdownContext, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := server.Shutdown(shutdownContext); err != nil {
			logError("agent shutdown error", logFields{"error": err.Error()})
		}
		processPendingEvents()
	case err := <-serverError:
		if err != nil && err != http.ErrServerClosed {
			logError("server failed to start", logFields{"error": err.Error()})
		}
	}
}

func forwardToJava(jsonBytes []byte, correlationID string) (*http.Response, error) {
	var lastError error
	started := time.Now()
	defer func() { metrics.observeForwardDuration(time.Since(started).Seconds()) }()
	for attempt := 1; attempt <= maxBackendAttempts; attempt++ {
		// Retry only transient transport/server failures; client errors are returned immediately.
		request, err := http.NewRequest(http.MethodPost, configuredBackendURL, bytes.NewReader(jsonBytes))
		if err != nil {
			return nil, err
		}
		request.Header.Set("Content-Type", "application/json")
		request.Header.Set("X-EventWatch-Key", configuredAPIKey)
		if correlationID != "" {
			request.Header.Set("X-Correlation-ID", correlationID)
		}

		response, err := backendClient.Do(request)
		if err == nil && response.StatusCode < http.StatusInternalServerError {
			return response, nil
		}
		if response != nil {
			response.Body.Close()
			lastError = fmt.Errorf("Java backend returned status %d", response.StatusCode)
		} else {
			lastError = err
		}

		if attempt < maxBackendAttempts {
			logWarn("retrying delivery to analytics", logFields{
				"correlation_id": correlationID,
				"attempt":        attempt,
				"error":          fmt.Sprint(lastError),
			})
			time.Sleep(time.Duration(attempt) * 100 * time.Millisecond)
		}
	}
	return nil, lastError
}

func queueOrDrop(data []byte, correlationID string) {
	if queueErr := enqueueEvent(data, correlationID); queueErr != nil {
		metrics.recordForward("failed")
		logError("stress event dropped", logFields{
			"correlation_id": correlationID, "error": queueErr.Error()})
		return
	}
	metrics.recordForward("queued")
}

// A queued event is only abandoned on a permanent client error.
func isRetryableStatus(status int) bool {
	return status >= 500 || status == http.StatusTooManyRequests
}

func healthHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeMessage(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":      "ok",
		"service":     "go-collector",
		"host_id":     configuredHostID,
		"hostname":    configuredHostname,
		"version":     agentVersion,
		"queue_depth": queueDepth(),
		"message":     "service is healthy",
	})
}

func enqueueEvent(data []byte, correlationID string) error {
	queueMutex.Lock()
	defer queueMutex.Unlock()

	entries, err := os.ReadDir(queueDirectory)
	if err != nil {
		return err
	}
	queued := 0
	for _, entry := range entries {
		if !entry.IsDir() && filepath.Ext(entry.Name()) == ".json" {
			queued++
		}
	}
	if queued >= queueCapacity {
		metrics.recordQueueDrop()
		return fmt.Errorf("pending event queue is full (%d)", queueCapacity)
	}

	sequence := atomic.AddUint64(&eventSequence, 1)
	name := fmt.Sprintf("event-%d-%d.json", time.Now().UnixNano(), sequence)
	temporaryPath := filepath.Join(queueDirectory, name+".tmp")
	finalPath := filepath.Join(queueDirectory, name)
	if err := os.WriteFile(temporaryPath, data, 0600); err != nil {
		return err
	}
	if err := os.Rename(temporaryPath, finalPath); err != nil {
		_ = os.Remove(temporaryPath)
		return err
	}
	metrics.recordQueueWrite()
	logInfo("event written to pending queue", logFields{
		"correlation_id": correlationID,
		"queue_file":     name,
		"queue_depth":    queued + 1,
	})
	select {
	case queueWake <- struct{}{}:
	default:
	}
	return nil
}

func retryPendingEvents() {
	ticker := time.NewTicker(queueRetryInterval)
	defer ticker.Stop()
	for {
		processPendingEvents()
		// The retry loop already runs on a timer, so it is where delivery health is judged.
		checkDeliveryHealth(time.Now())
		select {
		case <-ticker.C:
		case <-queueWake:
		}
	}
}

func processPendingEvents() {
	entries, err := os.ReadDir(queueDirectory)
	if err != nil {
		logError("unable to read pending event queue", logFields{"error": err.Error()})
		return
	}
	var names []string
	for _, entry := range entries {
		if !entry.IsDir() && filepath.Ext(entry.Name()) == ".json" {
			names = append(names, entry.Name())
		}
	}
	sort.Strings(names)
	for _, name := range names {
		path := filepath.Join(queueDirectory, name)
		data, err := os.ReadFile(path)
		if err != nil {
			continue
		}
		correlationID := correlationIDOf(data)
		response, err := forwardToJava(data, correlationID)
		if err != nil {
			continue
		}
		status := response.StatusCode
		response.Body.Close()
		if status >= 200 && status < 300 {
			metrics.recordForward("delivered")
			if err := os.Remove(path); err != nil {
				logError("unable to remove delivered event", logFields{
					"correlation_id": correlationID, "queue_file": name, "error": err.Error()})
			} else {
				logInfo("queued event delivered", logFields{
					"correlation_id": correlationID, "queue_file": name})
			}
		} else if !isRetryableStatus(status) {
			metrics.recordForward("rejected")
			metrics.recordQueueRejected()
			logWarn("queued event permanently rejected", logFields{
				"correlation_id": correlationID, "queue_file": name, "status": status})
			moveToRejected(path, name)
		}
	}
}

// A retried event keeps the correlation id it was captured with.
func correlationIDOf(data []byte) string {
	var payload LogPayload
	if err := json.Unmarshal(data, &payload); err != nil {
		return ""
	}
	return payload.CorrelationID
}

func moveToRejected(path, name string) {
	rejectedDirectory := filepath.Join(queueDirectory, "rejected-events")
	if err := os.MkdirAll(rejectedDirectory, 0755); err != nil {
		return
	}
	_ = os.Rename(path, filepath.Join(rejectedDirectory, name))
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	response, err := json.Marshal(body)
	if err != nil {
		status = http.StatusInternalServerError
		response = []byte(`{"status":"error","message":"response encoding failed"}`)
	}
	w.Header().Set("Content-Type", "application/json; charset=UTF-8")
	w.WriteHeader(status)
	_, _ = w.Write(response)
}

func writeMessage(w http.ResponseWriter, status int, message string) {
	responseStatus := "ok"
	if status >= 400 {
		responseStatus = "error"
	}
	writeJSON(w, status, map[string]string{"status": responseStatus, "message": message})
}

func getEnv(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

func getIntEnv(name string, fallback int) int {
	value, err := strconv.Atoi(getEnv(name, strconv.Itoa(fallback)))
	if err != nil {
		return fallback
	}
	return value
}

func newEventID() string {
	var id [16]byte
	if _, err := rand.Read(id[:]); err != nil {
		return fmt.Sprintf("event-%d", atomic.AddUint64(&eventSequence, 1))
	}
	return fmt.Sprintf("%x-%x-%x-%x-%x", id[0:4], id[4:6], id[6:8], id[8:10], id[10:16])
}
