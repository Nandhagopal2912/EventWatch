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
	EventID  string  `json:"event_id"`
	Level    string  `json:"level"`
	Messages string  `json:"msg"`
	Time     string  `json:"timestamp"`
	CPUUsage float64 `json:"cpu_usage"`
	RAMUsage float64 `json:"ram_usage"`
}

const maxBackendAttempts = 3

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
		writeMessage(w, http.StatusInternalServerError, "host metrics unavailable")
		return
	}

	fmt.Printf("[%s]  🐹 Go Ingress: Captured log (%s , %s)\n", time.Now().Format("15:04:05"), level, msg)

	payload := LogPayload{
		EventID:  newEventID(),
		Level:    level,
		Messages: msg,
		Time:     time.Now().Format(time.RFC3339),
		CPUUsage: cpuUsage,
		RAMUsage: ramUsage,
	}
	jsonBytes, err := json.Marshal(payload)

	if err != nil {
		fmt.Printf("❌ Error creating JSON: %v\n", err)
		writeMessage(w, http.StatusInternalServerError, "internal payload error")
		return
	}

	resp, err := forwardToJava(jsonBytes)

	if err != nil {
		if queueErr := enqueueEvent(jsonBytes); queueErr != nil {
			fmt.Printf("❌ Error forwarding to Java: %v; queueing failed: %v\n", err, queueErr)
			writeMessage(w, http.StatusServiceUnavailable, "backend unavailable and local queue is full")
			return
		}
		fmt.Printf("⚠️ Java unavailable; event saved to local queue: %v\n", err)
		writeMessage(w, http.StatusServiceUnavailable, "backend unavailable; event queued for retry")
		return
	}

	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		if resp.StatusCode >= 500 {
			if queueErr := enqueueEvent(jsonBytes); queueErr != nil {
				writeMessage(w, http.StatusServiceUnavailable, "backend unavailable and local queue is full")
				return
			}
			writeMessage(w, http.StatusServiceUnavailable, "backend unavailable; event queued for retry")
			return
		}
		writeMessage(w, resp.StatusCode, "Java backend rejected the log")
		return
	}
	writeMessage(w, http.StatusOK, "log forwarded to analytics engine successfully")
}

func stressHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeMessage(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	fmt.Printf("⚡ STARTING MASS STRESS TEST: Firing 500 logs...")

	fakeErrors := []string{
		"Database transaction deadlock",
		"Unauthorized API access attempt",
		"Out of memory error in payment service",
	}
	cpuUsage, ramUsage, err := readHostMetrics()
	if err != nil {
		writeMessage(w, http.StatusInternalServerError, "host metrics unavailable")
		return
	}

	for i := 0; i < 500; i++ {
		errorMsg := fakeErrors[i%3]

		payload := LogPayload{
			EventID:  newEventID(),
			Level:    "ERROR",
			Messages: fmt.Sprintf("%s (Log #%d)", errorMsg, i),
			Time:     time.Now().Format(time.RFC3339),
			CPUUsage: cpuUsage,
			RAMUsage: ramUsage,
		}
		jsonBytes, _ := json.Marshal(payload)
		go func(data []byte) {
			resp, err := forwardToJava(data)

			if err != nil {
				if queueErr := enqueueEvent(data); queueErr != nil {
					fmt.Printf("❌ Stress event dropped: %v\n", queueErr)
				}
			} else if resp.StatusCode >= 500 {
				if queueErr := enqueueEvent(data); queueErr != nil {
					fmt.Printf("❌ Stress event dropped: %v\n", queueErr)
				}
				resp.Body.Close()
			} else {
				resp.Body.Close()
			}
		}(jsonBytes)
	}
	writeMessage(w, http.StatusOK, "500 logs queued for the analytics engine")
}

func main() {
	// Both services read the same root .env file for local configuration.
	_ = godotenv.Load("../.env", ".env")
	javaBackendURL := os.Getenv("JAVA_BACKEND_URL")
	if javaBackendURL == "" {
		javaBackendURL = "http://localhost:8080/receive"
	}
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
	go retryPendingEvents()

	http.HandleFunc("/capture", logHandler)
	http.HandleFunc("/health", healthHandler)

	http.HandleFunc("/stress", stressHandler)

	fmt.Println("🐹 Go Cloud Log Collector is running on http://localhost:8082")
	fmt.Println("Ready to capture cloud traffic.... Ready when you are")

	server := &http.Server{Addr: ":8082"}
	serverError := make(chan error, 1)
	go func() {
		serverError <- server.ListenAndServe()
	}()

	shutdownSignal := make(chan os.Signal, 1)
	signal.Notify(shutdownSignal, os.Interrupt, syscall.SIGTERM)
	select {
	case signalReceived := <-shutdownSignal:
		fmt.Printf("Shutting down Go collector after signal: %v\n", signalReceived)
		shutdownContext, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := server.Shutdown(shutdownContext); err != nil {
			fmt.Printf("Go collector shutdown error: %v\n", err)
		}
		processPendingEvents()
	case err := <-serverError:
		if err != nil && err != http.ErrServerClosed {
			fmt.Printf("Server failed to start: %v\n", err)
		}
	}
}

func forwardToJava(jsonBytes []byte) (*http.Response, error) {
	var lastError error
	for attempt := 1; attempt <= maxBackendAttempts; attempt++ {
		// Retry only transient transport/server failures; client errors are returned immediately.
		request, err := http.NewRequest(http.MethodPost, configuredBackendURL, bytes.NewReader(jsonBytes))
		if err != nil {
			return nil, err
		}
		request.Header.Set("Content-Type", "application/json")
		request.Header.Set("X-EventWatch-Key", configuredAPIKey)

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
			time.Sleep(time.Duration(attempt) * 100 * time.Millisecond)
		}
	}
	return nil, lastError
}

func healthHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeMessage(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{
		"status":  "ok",
		"service": "go-collector",
		"message": "service is healthy",
	})
}

func enqueueEvent(data []byte) error {
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
		select {
		case <-ticker.C:
		case <-queueWake:
		}
	}
}

func processPendingEvents() {
	entries, err := os.ReadDir(queueDirectory)
	if err != nil {
		fmt.Printf("Unable to read pending event queue: %v\n", err)
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
		response, err := forwardToJava(data)
		if err != nil {
			continue
		}
		status := response.StatusCode
		response.Body.Close()
		if status >= 200 && status < 300 {
			if err := os.Remove(path); err != nil {
				fmt.Printf("Unable to remove delivered event %s: %v\n", name, err)
			} else {
				fmt.Printf("✅ Queued event delivered successfully: %s (removed from pending queue)\n", name)
			}
		} else if status >= 400 && status < 500 && status != http.StatusTooManyRequests {
			moveToRejected(path, name)
		}
	}
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
