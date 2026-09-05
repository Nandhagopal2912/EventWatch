package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"time"

	"github.com/joho/godotenv"
	"github.com/shirou/gopsutil/v4/cpu"
	"github.com/shirou/gopsutil/v4/mem"
)

// LogPayload is the JSON contract shared with the Java analytics service.
type LogPayload struct {
	Level    string  `json:"level"`
	Messages string  `json:"msg"`
	Time     string  `json:"timestamp"`
	CPUUsage float64 `json:"cpu_usage"`
	RAMUsage float64 `json:"ram_usage"`
}

const maxBackendAttempts = 3

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
		http.Error(w, "method not allowed",
			http.StatusMethodNotAllowed)
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
		http.Error(w, "Host metrics unavailable", http.StatusInternalServerError)
		return
	}

	fmt.Printf("[%s]  🐹 Go Ingress: Captured log (%s , %s)\n", time.Now().Format("15:04:05"), level, msg)

	payload := LogPayload{
		Level:    level,
		Messages: msg,
		Time:     time.Now().Format(time.RFC3339),
		CPUUsage: cpuUsage,
		RAMUsage: ramUsage,
	}
	jsonBytes, err := json.Marshal(payload)

	if err != nil {
		fmt.Printf("❌ Error creating JSON: %v\n", err)
		http.Error(w, "Internal payload error", http.StatusInternalServerError)
		return
	}

	resp, err := forwardToJava(jsonBytes)

	if err != nil {
		fmt.Printf("❌ Error forwarding to Java: %v\n", err)
		http.Error(w, "Backend Communication failure", http.StatusInternalServerError)
		return
	}

	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		http.Error(w, "Java backend rejected the log", resp.StatusCode)
		return
	}
	w.WriteHeader(http.StatusOK)
	w.Write([]byte("Log forwarded to analytics engine successfully!"))
}

func stressHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed",
			http.StatusMethodNotAllowed)
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
		http.Error(w, "Host metrics unavailable", http.StatusInternalServerError)
		return
	}

	for i := 0; i < 500; i++ {
		errorMsg := fakeErrors[i%3]

		payload := LogPayload{
			Level:    "ERROR",
			Messages: fmt.Sprintf("%s (Log #%d)", errorMsg, i),
			Time:     time.Now().Format(time.RFC3339),
			CPUUsage: cpuUsage,
			RAMUsage: ramUsage,
		}
		jsonBytes, _ := json.Marshal(payload)
		go func(data []byte) {
			resp, err := forwardToJava(data)

			if err == nil {
				resp.Body.Close()
			}
		}(jsonBytes)
	}
	w.WriteHeader(http.StatusOK)
	w.Write([]byte("⚡ 500 Mass logs fired at the Analytics Engine! Check your Java terminal!"))
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

	http.HandleFunc("/capture", logHandler)

	http.HandleFunc("/stress", stressHandler)

	fmt.Println("🐹 Go Cloud Log Collector is running on http://localhost:8082")
	fmt.Println("Ready to capture cloud traffic.... Ready when you are")

	if err := http.ListenAndServe(":8082", nil); err != nil {
		fmt.Printf("Server failed to start: %v\n", err)
	}
}

var backendClient *http.Client
var configuredBackendURL string
var configuredAPIKey string

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
