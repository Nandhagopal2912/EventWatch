package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestNewEventIDIsUnique(t *testing.T) {
	first := newEventID()
	second := newEventID()
	if first == second {
		t.Fatal("expected event IDs to be unique")
	}
}

func TestEnqueueEventHonorsCapacity(t *testing.T) {
	originalDirectory := queueDirectory
	originalCapacity := queueCapacity
	t.Cleanup(func() {
		queueDirectory = originalDirectory
		queueCapacity = originalCapacity
	})

	queueDirectory = t.TempDir()
	queueCapacity = 1
	payload := []byte(`{"event_id":"test-event","level":"INFO"}`)

	if err := enqueueEvent(payload); err != nil {
		t.Fatalf("first enqueue failed: %v", err)
	}
	if err := enqueueEvent(payload); err == nil {
		t.Fatal("expected second enqueue to respect queue capacity")
	}

	files, err := filepath.Glob(filepath.Join(queueDirectory, "*.json"))
	if err != nil {
		t.Fatalf("failed to inspect queue: %v", err)
	}
	if len(files) != 1 {
		t.Fatalf("expected one queued event, found %d", len(files))
	}
	if _, err := os.Stat(files[0]); err != nil {
		t.Fatalf("queued event was not written: %v", err)
	}
}
