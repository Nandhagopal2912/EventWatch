package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"testing"
)

// contractFixture is shared with the Java test suite so both sides of the pipeline
// are checked against one canonical event.
const contractFixture = "../testdata/event-contract.json"

func loadContract(t *testing.T) map[string]any {
	t.Helper()
	contents, err := os.ReadFile(filepath.FromSlash(contractFixture))
	if err != nil {
		t.Fatalf("unable to read the shared contract fixture: %v", err)
	}
	var fixture map[string]any
	if err := json.Unmarshal(contents, &fixture); err != nil {
		t.Fatalf("the shared contract fixture is not valid JSON: %v", err)
	}
	return fixture
}

func TestCollectorPayloadMatchesTheSharedContract(t *testing.T) {
	fixture := loadContract(t)

	payload := LogPayload{
		EventID:       "8f14e45f-ceea-167a-5a36-dedd4bea2543",
		CorrelationID: "2b1f7c90-4d5e-4a11-9d3c-71b0f0a9c8e2",
		HostID:        "9c1f4b2e-7a35-4c88-b0d1-3e6a9f2c5d47",
		Hostname:      "web-01",
		Level:         "ERROR",
		Messages:      "High CPU Saturation Alert",
		Time:          "2026-09-04T18:46:00Z",
		CPUUsage:      88.4,
		RAMUsage:      12.1,
	}
	encoded, err := json.Marshal(payload)
	if err != nil {
		t.Fatalf("unable to encode the payload: %v", err)
	}
	var produced map[string]any
	if err := json.Unmarshal(encoded, &produced); err != nil {
		t.Fatalf("the collector produced invalid JSON: %v", err)
	}

	if !sameKeys(fixture, produced) {
		t.Fatalf("collector fields %v do not match the contract %v", keysOf(produced), keysOf(fixture))
	}
	for key, expected := range fixture {
		if produced[key] != expected {
			t.Errorf("field %q: contract has %v, collector produced %v", key, expected, produced[key])
		}
	}
}

func TestCorrelationIdIsTheOnlyOptionalField(t *testing.T) {
	// Everything else must always be sent, so Java validation cannot fail on a well-formed event.
	encoded, err := json.Marshal(LogPayload{
		EventID:  "e1",
		Level:    "INFO",
		Messages: "m",
		Time:     "2026-09-04T18:46:00Z",
	})
	if err != nil {
		t.Fatalf("unable to encode the payload: %v", err)
	}
	var produced map[string]any
	if err := json.Unmarshal(encoded, &produced); err != nil {
		t.Fatalf("invalid JSON: %v", err)
	}

	if _, present := produced["correlation_id"]; present {
		t.Error("an empty correlation id should be omitted, not sent as an empty string")
	}
	for _, required := range []string{
		"event_id", "host_id", "hostname", "level", "msg", "timestamp", "cpu_usage", "ram_usage"} {
		if _, present := produced[required]; !present {
			t.Errorf("required contract field %q was omitted", required)
		}
	}
}

func sameKeys(left, right map[string]any) bool {
	if len(left) != len(right) {
		return false
	}
	for key := range left {
		if _, present := right[key]; !present {
			return false
		}
	}
	return true
}

func keysOf(values map[string]any) []string {
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return keys
}
