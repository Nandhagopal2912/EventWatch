// Command loadtest measures EventWatch ingestion throughput and latency.
//
// Phase 11 decisions — a message broker, splitting the services, moving off SQLite —
// are supposed to be driven by measurement rather than guesswork. This is the measurement.
//
//	go run . -mode analytics -events 5000 -concurrency 32
//	go run . -mode collector -events 500 -concurrency 8
//
// Analytics mode posts events straight to the Java service, which isolates storage and
// alert evaluation. Collector mode drives the public Go ingress, which includes host
// sampling and forwarding, and is bounded by the analytics rate limit.
package main

import (
	"bytes"
	"crypto/rand"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"math"
	"net/http"
	"os"
	"sort"
	"sync"
	"sync/atomic"
	"time"
)

type payload struct {
	EventID       string  `json:"event_id"`
	CorrelationID string  `json:"correlation_id"`
	Level         string  `json:"level"`
	Messages      string  `json:"msg"`
	Time          string  `json:"timestamp"`
	CPUUsage      float64 `json:"cpu_usage"`
	RAMUsage      float64 `json:"ram_usage"`
}

type result struct {
	latency time.Duration
	status  int
	err     error
}

func main() {
	mode := flag.String("mode", "analytics", "analytics (POST /receive) or collector (GET /capture)")
	target := flag.String("target", "", "base URL; defaults to the standard port for the mode")
	apiKey := flag.String("api-key", os.Getenv("EVENTWATCH_API_KEY"), "value for the X-EventWatch-Key header")
	events := flag.Int("events", 1000, "total events to send")
	concurrency := flag.Int("concurrency", 16, "in-flight requests")
	distinct := flag.Int("distinct-messages", 50, "distinct message bodies, to exercise error grouping")
	timeout := flag.Duration("timeout", 10*time.Second, "per-request timeout")
	flag.Parse()

	if *mode != "analytics" && *mode != "collector" {
		exit("mode must be analytics or collector")
	}
	if *events < 1 || *concurrency < 1 {
		exit("events and concurrency must be positive")
	}
	if *mode == "analytics" && *apiKey == "" {
		exit("analytics mode needs -api-key or EVENTWATCH_API_KEY")
	}
	url := *target
	if url == "" {
		if *mode == "analytics" {
			url = "http://localhost:8080/receive"
		} else {
			url = "http://localhost:8082/capture"
		}
	}

	client := &http.Client{
		Timeout: *timeout,
		Transport: &http.Transport{
			MaxIdleConns:        *concurrency * 2,
			MaxIdleConnsPerHost: *concurrency * 2,
		},
	}

	fmt.Printf("EventWatch load test\n  mode        %s\n  target      %s\n  events      %d\n  concurrency %d\n\n",
		*mode, url, *events, *concurrency)

	results := make([]result, *events)
	var dispatched atomic.Int64
	work := make(chan int, *concurrency)
	var waitGroup sync.WaitGroup

	started := time.Now()
	for worker := 0; worker < *concurrency; worker++ {
		waitGroup.Add(1)
		go func() {
			defer waitGroup.Done()
			for index := range work {
				results[index] = send(client, *mode, url, *apiKey, index, *distinct)
				if current := dispatched.Add(1); current%500 == 0 {
					fmt.Printf("  %d/%d sent\n", current, *events)
				}
			}
		}()
	}
	for index := 0; index < *events; index++ {
		work <- index
	}
	close(work)
	waitGroup.Wait()
	elapsed := time.Since(started)

	report(results, elapsed)
}

func send(client *http.Client, mode, url, apiKey string, index, distinct int) result {
	started := time.Now()

	var request *http.Request
	var err error
	if mode == "collector" {
		request, err = http.NewRequest(http.MethodGet,
			fmt.Sprintf("%s?level=ERROR&msg=load+test+message+%d", url, index%distinct), nil)
	} else {
		body, marshalErr := json.Marshal(payload{
			EventID:       newID(),
			CorrelationID: "loadtest",
			Level:         "ERROR",
			Messages:      fmt.Sprintf("load test message %d", index%distinct),
			Time:          time.Now().UTC().Format(time.RFC3339),
			CPUUsage:      float64(index%100) / 2.0,
			RAMUsage:      float64(index%80) / 2.0,
		})
		if marshalErr != nil {
			return result{latency: time.Since(started), err: marshalErr}
		}
		request, err = http.NewRequest(http.MethodPost, url, bytes.NewReader(body))
		if err == nil {
			request.Header.Set("Content-Type", "application/json")
		}
	}
	if err != nil {
		return result{latency: time.Since(started), err: err}
	}
	if apiKey != "" {
		request.Header.Set("X-EventWatch-Key", apiKey)
	}

	response, err := client.Do(request)
	if err != nil {
		return result{latency: time.Since(started), err: err}
	}
	defer response.Body.Close()
	// Drain to EOF: a short read leaves the connection unreusable, so the harness would
	// measure TCP handshakes instead of server time.
	_, _ = io.Copy(io.Discard, response.Body)
	return result{latency: time.Since(started), status: response.StatusCode}
}

func report(results []result, elapsed time.Duration) {
	statuses := make(map[int]int)
	failures := 0
	latencies := make([]time.Duration, 0, len(results))
	accepted := 0

	for _, item := range results {
		if item.err != nil {
			failures++
			continue
		}
		statuses[item.status]++
		latencies = append(latencies, item.latency)
		if item.status >= 200 && item.status < 300 {
			accepted++
		}
	}
	sort.Slice(latencies, func(a, b int) bool { return latencies[a] < latencies[b] })

	fmt.Printf("\nresults\n")
	fmt.Printf("  elapsed            %s\n", elapsed.Round(time.Millisecond))
	fmt.Printf("  throughput         %.1f events/second\n", float64(len(results))/elapsed.Seconds())
	fmt.Printf("  accepted (2xx)     %d/%d\n", accepted, len(results))
	if failures > 0 {
		fmt.Printf("  transport failures %d\n", failures)
	}

	codes := make([]int, 0, len(statuses))
	for code := range statuses {
		codes = append(codes, code)
	}
	sort.Ints(codes)
	for _, code := range codes {
		fmt.Printf("  status %-3d         %d\n", code, statuses[code])
	}

	if len(latencies) > 0 {
		fmt.Printf("  latency p50        %s\n", percentile(latencies, 0.50).Round(time.Microsecond))
		fmt.Printf("  latency p95        %s\n", percentile(latencies, 0.95).Round(time.Microsecond))
		fmt.Printf("  latency p99        %s\n", percentile(latencies, 0.99).Round(time.Microsecond))
		fmt.Printf("  latency max        %s\n", latencies[len(latencies)-1].Round(time.Microsecond))
	}

	if statuses[http.StatusTooManyRequests] > 0 {
		fmt.Printf("\nnote: %d requests were rate limited. The analytics service allows 100 per minute\n"+
			"per client address, so a burst larger than that is expected to be throttled.\n",
			statuses[http.StatusTooManyRequests])
	}
}

// Nearest-rank, so a percentile is never reported lower than the sample it names.
func percentile(sorted []time.Duration, fraction float64) time.Duration {
	if len(sorted) == 0 {
		return 0
	}
	index := int(math.Ceil(fraction*float64(len(sorted)))) - 1
	if index < 0 {
		index = 0
	}
	if index >= len(sorted) {
		index = len(sorted) - 1
	}
	return sorted[index]
}

func newID() string {
	var id [16]byte
	if _, err := rand.Read(id[:]); err != nil {
		return fmt.Sprintf("loadtest-%d", time.Now().UnixNano())
	}
	return fmt.Sprintf("%x-%x-%x-%x-%x", id[0:4], id[4:6], id[6:8], id[8:10], id[10:16])
}

func exit(message string) {
	fmt.Fprintf(os.Stderr, "loadtest: %s\n", message)
	flag.Usage()
	os.Exit(2)
}
