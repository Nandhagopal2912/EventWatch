package main

import (
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// java backend url

const javaBackendURL = "http://localhost:8080/receive"

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

	// Send the message back to the java backend
	data := url.Values{}
	data.Set("level", level)
	data.Set("msg", msg)

	resp, err := http.Post(javaBackendURL, "application/x-www-form-urlencoded", strings.NewReader(data.Encode()))

	if err != nil {
		fmt.Printf("❌ Error forwarding to Java: %v\n", err)
		http.Error(w, "Backend Communication failure", http.StatusInternalServerError)
		return
	}

	defer resp.Body.Close()

	w.WriteHeader(http.StatusOK)
	w.Write([]byte("Log forwarded to analytics engine successfully!"))
}

func main() {
	// Set up the Go server
	http.HandleFunc("/capture", logHandler)

	fmt.Println("🐹 Go Cloud Log Collector is running on http://localhost:8082")
	fmt.Println("Ready to capture cloud traffic.... Ready when you are")

	if err := http.ListenAndServe(":8082", nil); err != nil {
		fmt.Printf("Server failed to start: %v\n", err)
	}
}
