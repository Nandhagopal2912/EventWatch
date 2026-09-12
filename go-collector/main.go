package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"math"
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
	"github.com/shirou/gopsutil/v4/disk"
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
	// A pointer so an unreadable disk is absent rather than zero: an empty disk and an unknown
	// disk are not the same claim, and 0% would read as the healthiest possible machine.
	DiskUsage *float64 `json:"disk_usage,omitempty"`
	DiskPath  string   `json:"disk_path,omitempty"`
}

const maxBackendAttempts = 3

// Overridable at build time: go build -ldflags "-X main.agentVersion=1.2.3"
var agentVersion = "0.15.0"

var (
	backendClient        *http.Client
	configuredBackendURL string
	// The value sent as X-EventWatch-Key to Java: an AGENT_TOKEN if one is set, otherwise the
	// fleet-wide EVENTWATCH_API_KEY. The name says what it is for, not which phase issued it.
	configuredIngestionCredential string
	queueDirectory                string
	queueCapacity                 int
	queueRetryInterval            time.Duration
	queueWake                     = make(chan struct{}, 1)
	queueMutex                    sync.Mutex
	eventSequence                 uint64
	// Empty means every real filesystem; DISK_PATHS narrows it to the mounts that matter.
	configuredDiskPaths []string
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

// readFullestDisk reports the most-used filesystem on this machine, and which mount that is.
//
// A machine has several mounts and the contract carries one reading, so the fullest is the one
// worth reporting: it is the one that stops the machine working first. The mount travels with it
// because "95% full" is only actionable once you know which disk. DISK_PATHS narrows the search
// when only certain mounts matter — a containerised agent, for instance, sees its own overlay
// filesystem rather than the host's disks unless the host is mounted in and named here.
//
// found is false when nothing can be read, and the agent then omits the field entirely.
func readFullestDisk() (usedPercent float64, mountpoint string, found bool) {
	paths := configuredDiskPaths
	if len(paths) == 0 {
		partitions, err := disk.Partitions(false)
		if err != nil {
			return 0, "", false
		}
		for _, partition := range partitions {
			paths = append(paths, partition.Mountpoint)
		}
	}

	for _, path := range paths {
		usage, err := disk.Usage(path)
		// A pseudo-filesystem reports no size; its "percent used" would be noise or a divide by zero.
		if err != nil || usage == nil || usage.Total == 0 {
			continue
		}
		percent := usage.UsedPercent
		if math.IsNaN(percent) || math.IsInf(percent, 0) {
			continue
		}
		// Reserved blocks can push a filesystem just past 100%. Java validates this field as a
		// percentage and rejects the whole event otherwise, so one disk quirk must not cost an
		// event that is otherwise fine.
		percent = math.Min(math.Max(percent, 0), 100)
		if !found || percent > usedPercent {
			usedPercent, mountpoint, found = percent, path, true
		}
	}
	return usedPercent, mountpoint, found
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
	status, message := captureEvent(level, msg, correlationID)
	writeMessage(w, status, message)
}

// captureEvent samples this machine, builds one event, and delivers or queues it.
//
// The HTTP endpoint and the timer both call this, so an event takes the same path no matter what
// triggered it. It returns the status and message the capture endpoint answers with, which is
// also the vocabulary the queue already classifies outcomes by.
func captureEvent(level, msg, correlationID string) (int, string) {
	cpuUsage, ramUsage, err := readHostMetrics()
	if err != nil {
		metrics.recordHostFailure()
		logError("host metrics unavailable", logFields{"correlation_id": correlationID, "error": err.Error()})
		return http.StatusInternalServerError, "host metrics unavailable"
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
	// A machine with no readable filesystem still reports everything else it knows.
	diskUsage, diskPath, diskFound := readFullestDisk()
	if diskFound {
		payload.DiskUsage = &diskUsage
		payload.DiskPath = diskPath
	}
	metrics.recordCapture(level)
	captured := logFields{
		"host_id":        configuredHostID,
		"correlation_id": correlationID,
		"event_id":       payload.EventID,
		"event_level":    level,
		"cpu_usage":      cpuUsage,
		"ram_usage":      ramUsage,
	}
	if diskFound {
		// The value, not the pointer: the text formatter would print an address.
		captured["disk_usage"] = diskUsage
		captured["disk_path"] = diskPath
	}
	logInfo("captured event", captured)

	jsonBytes, err := json.Marshal(payload)
	if err != nil {
		logError("event serialization failed", logFields{"correlation_id": correlationID, "error": err.Error()})
		return http.StatusInternalServerError, "internal payload error"
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
			return http.StatusServiceUnavailable, "backend unavailable and local queue is full"
		}
		metrics.recordForward("queued")
		logWarn("analytics unavailable; event queued", logFields{
			"correlation_id": correlationID,
			"error":          err.Error(),
		})
		return http.StatusServiceUnavailable, "backend unavailable; event queued for retry"
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
				return http.StatusServiceUnavailable, "backend unavailable and local queue is full"
			}
			metrics.recordForward("queued")
			logWarn("analytics returned a retryable status; event queued", logFields{
				"correlation_id": correlationID,
				"status":         resp.StatusCode,
			})
			return http.StatusServiceUnavailable, "backend unavailable; event queued for retry"
		}
		metrics.recordForward("rejected")
		logWarn("analytics rejected the event", logFields{
			"correlation_id": correlationID,
			"status":         resp.StatusCode,
		})
		return resp.StatusCode, "Java backend rejected the log"
	}
	metrics.recordForward("delivered")
	logInfo("event delivered to analytics", logFields{
		"correlation_id": correlationID,
		"event_id":       payload.EventID,
	})
	return http.StatusOK, "log forwarded to analytics engine successfully"
}

// The level and message every scheduled sample carries. Stable on purpose: a changing message
// would look like distinct errors to the repeated-error rule, and INFO keeps samples out of it
// entirely.
const (
	sampleLevel   = "INFO"
	sampleMessage = "host sample"
)

// sampleHostPeriodically is what makes this a monitor rather than a log shipper.
//
// Without it the agent measures nothing unless something calls /capture, which means a CPU spike
// at three in the morning is invisible, the five-event window averages "the last five times
// someone ran curl", and AGENT_SILENT fires on a healthy machine that simply had nothing to say.
// A machine now reports on a timer whether or not anyone asks it to.
func sampleHostPeriodically(interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for range ticker.C {
		// captureEvent logs every outcome itself, including queueing through an outage, so there
		// is nothing useful to add here.
		_, _ = captureEvent(sampleLevel, sampleMessage, newEventID())
	}
}

func main() {
	// Both services read the same root .env file for local configuration.
	_ = godotenv.Load("../.env", ".env")
	javaBackendURL := os.Getenv("JAVA_BACKEND_URL")
	if javaBackendURL == "" {
		javaBackendURL = "http://localhost:8080/receive"
	}
	configureLogging(getEnv("LOG_FORMAT", "json"))
	ingestionCredential, credentialWarning, err := resolveIngestionCredential(
		os.Getenv("EVENTWATCH_API_KEY"), getEnv("AGENT_TOKEN", ""), getEnv("HOST_ID", ""))
	if err != nil {
		fmt.Println(err.Error())
		return
	}
	if credentialWarning != "" {
		logWarn(credentialWarning, logFields{})
	}

	backendClient = &http.Client{Timeout: 5 * time.Second}
	configuredBackendURL = javaBackendURL
	configuredIngestionCredential = ingestionCredential
	configuredDiskPaths = splitPaths(getEnv("DISK_PATHS", ""))
	sampleInterval := time.Duration(getIntEnv("SAMPLE_INTERVAL_SECONDS", 60)) * time.Second
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

	// time.NewTicker panics on a non-positive duration, so the guard is load-bearing rather than
	// defensive - the same lesson as B13 on the Java side. Zero disables sampling deliberately.
	if sampleInterval > 0 {
		go sampleHostPeriodically(sampleInterval)
		logInfo("periodic host sampling enabled", logFields{"interval_seconds": int(sampleInterval.Seconds())})
	} else {
		logWarn("periodic host sampling is disabled; this agent only reports when /capture is called",
			logFields{"setting": "SAMPLE_INTERVAL_SECONDS"})
	}

	http.HandleFunc("/capture", logHandler)
	http.HandleFunc("/health", healthHandler)
	http.HandleFunc("/metrics", metricsHandler)

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
		request.Header.Set("X-EventWatch-Key", configuredIngestionCredential)
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

// splitPaths reads a comma-separated setting, dropping blanks so a trailing comma is harmless.
func splitPaths(raw string) []string {
	var paths []string
	for _, candidate := range strings.Split(raw, ",") {
		if trimmed := strings.TrimSpace(candidate); trimmed != "" {
			paths = append(paths, trimmed)
		}
	}
	return paths
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
