package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"time"
)

// java backend url

const javaBackendURL = "http://localhost:8080/receive"

// Payload Structure
type LogPayload struct {
	Level    string `json:"level"`
	Messages string `json:"msg"`
	Time     string `json:"timestamp"`
}

func logHandler(w http.ResponseWriter, r *http.Request) {
	//allow only the get method
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed",
			http.StatusMethodNotAllowed)
		return
	}

	//Extract the parameters from the http Request
	level := r.URL.Query().Get("level")
	msg := r.URL.Query().Get("msg")

	if level == "" {
		level = "INFO"
	}
	if msg == "" {
		msg = "Default cloud event"
	}

	fmt.Printf("[%s]  🐹 Go Ingress: Captured log (%s , %s)\n", time.Now().Format("15:04:05"), level, msg)

	//creating the payload for the respones
	payload := LogPayload{
		Level:    level,
		Messages: msg,
		Time:     time.Now().Format(time.RFC3339),
	}
	//convert the go struct into a raw JSON byte array

	jsonBytes, err := json.Marshal(payload)

	if err != nil {
		fmt.Printf("❌ Error creating JSON: %v\n", err)
		http.Error(w, "Internal payload error", http.StatusInternalServerError)
		return
	}

	//send the data to java as "application/json"
	resp, err := http.Post(javaBackendURL, "application/json", bytes.NewBuffer(jsonBytes))

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

	for i := 0; i < 500; i++ {
		errorMsg := fakeErrors[i%3]

		payload := LogPayload{
			Level:    "ERROR",
			Messages: fmt.Sprintf("%s (Log #%d)", errorMsg, i),
			Time:     time.Now().Format(time.RFC3339),
		}
		jsonBytes, _ := json.Marshal(payload)
		go func(data []byte) {
			resp, err := http.Post(javaBackendURL, "application/json", bytes.NewBuffer(data))

			if err == nil {
				resp.Body.Close()
			}
		}(jsonBytes)
	}
	w.WriteHeader(http.StatusOK)
	w.Write([]byte("⚡ 500 Mass logs fired at the Analytics Engine! Check your Java terminal!"))
}

func main() {
	// Set up the Go server
	http.HandleFunc("/capture", logHandler)

	http.HandleFunc("/stress", stressHandler)

	fmt.Println("🐹 Go Cloud Log Collector is running on http://localhost:8082")
	fmt.Println("Ready to capture cloud traffic.... Ready when you are")

	if err := http.ListenAndServe(":8082", nil); err != nil {
		fmt.Printf("Server failed to start: %v\n", err)
	}
}
