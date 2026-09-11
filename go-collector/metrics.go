package main

import (
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
)

// collectorMetrics holds the counters exposed in Prometheus text format.
// Hand-rolled so the collector keeps its dependency-free footprint.
type collectorMetrics struct {
	mutex             sync.Mutex
	capturedByLevel   map[string]uint64
	forwardByOutcome  map[string]uint64
	queueWritten      uint64
	queueDropped      uint64
	queueRejected     uint64
	forwardSeconds    float64
	forwardObserved   uint64
	hostMetricFailure uint64
}

var metrics = &collectorMetrics{
	capturedByLevel:  make(map[string]uint64),
	forwardByOutcome: make(map[string]uint64),
}

func (m *collectorMetrics) recordCapture(level string) {
	m.mutex.Lock()
	defer m.mutex.Unlock()
	m.capturedByLevel[level]++
}

// outcome is one of delivered, rejected, queued, or failed.
func (m *collectorMetrics) recordForward(outcome string) {
	m.mutex.Lock()
	defer m.mutex.Unlock()
	m.forwardByOutcome[outcome]++
}

func (m *collectorMetrics) observeForwardDuration(seconds float64) {
	m.mutex.Lock()
	defer m.mutex.Unlock()
	m.forwardSeconds += seconds
	m.forwardObserved++
}

func (m *collectorMetrics) recordQueueWrite()    { m.increment(&m.queueWritten) }
func (m *collectorMetrics) recordQueueDrop()     { m.increment(&m.queueDropped) }
func (m *collectorMetrics) recordQueueRejected() { m.increment(&m.queueRejected) }
func (m *collectorMetrics) recordHostFailure()   { m.increment(&m.hostMetricFailure) }

func (m *collectorMetrics) increment(counter *uint64) {
	m.mutex.Lock()
	defer m.mutex.Unlock()
	*counter++
}

// queueDepth counts pending files directly so the gauge cannot drift from reality.
func queueDepth() int {
	entries, err := os.ReadDir(queueDirectory)
	if err != nil {
		return 0
	}
	depth := 0
	for _, entry := range entries {
		if !entry.IsDir() && filepath.Ext(entry.Name()) == ".json" {
			depth++
		}
	}
	return depth
}

func (m *collectorMetrics) render() string {
	m.mutex.Lock()
	defer m.mutex.Unlock()

	var builder strings.Builder
	counterFamily(&builder, "eventwatch_events_captured_total",
		"Events accepted at the collector ingress.", "level", m.capturedByLevel)
	counterFamily(&builder, "eventwatch_forward_attempts_total",
		"Forwarding attempts to the analytics service by outcome.", "outcome", m.forwardByOutcome)

	singleCounter(&builder, "eventwatch_queue_writes_total",
		"Events written to the durable pending queue.", m.queueWritten)
	singleCounter(&builder, "eventwatch_queue_drops_total",
		"Events lost because the pending queue could not accept them.", m.queueDropped)
	singleCounter(&builder, "eventwatch_queue_rejected_total",
		"Queued events moved aside after a permanent client error.", m.queueRejected)
	singleCounter(&builder, "eventwatch_host_metric_failures_total",
		"Failed host CPU or RAM samples.", m.hostMetricFailure)

	fmt.Fprintf(&builder, "# HELP eventwatch_queue_depth Events currently waiting in the pending queue.\n")
	fmt.Fprintf(&builder, "# TYPE eventwatch_queue_depth gauge\n")
	fmt.Fprintf(&builder, "eventwatch_queue_depth %d\n", queueDepth())

	fmt.Fprintf(&builder, "# HELP eventwatch_forward_duration_seconds Time spent forwarding events to analytics.\n")
	fmt.Fprintf(&builder, "# TYPE eventwatch_forward_duration_seconds summary\n")
	fmt.Fprintf(&builder, "eventwatch_forward_duration_seconds_sum %g\n", m.forwardSeconds)
	fmt.Fprintf(&builder, "eventwatch_forward_duration_seconds_count %d\n", m.forwardObserved)
	return builder.String()
}

func counterFamily(builder *strings.Builder, name, help, label string, values map[string]uint64) {
	fmt.Fprintf(builder, "# HELP %s %s\n", name, help)
	fmt.Fprintf(builder, "# TYPE %s counter\n", name)
	if len(values) == 0 {
		return
	}
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	for _, key := range keys {
		fmt.Fprintf(builder, "%s{%s=\"%s\"} %d\n", name, label, escapeLabelValue(key), values[key])
	}
}

func singleCounter(builder *strings.Builder, name, help string, value uint64) {
	fmt.Fprintf(builder, "# HELP %s %s\n", name, help)
	fmt.Fprintf(builder, "# TYPE %s counter\n", name)
	fmt.Fprintf(builder, "%s %d\n", name, value)
}

func escapeLabelValue(value string) string {
	replacer := strings.NewReplacer(`\`, `\\`, `"`, `\"`, "\n", `\n`)
	return replacer.Replace(value)
}

func metricsHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeMessage(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(metrics.render()))
}
