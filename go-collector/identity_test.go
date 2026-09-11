package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestHostIdentityIsGeneratedAndPersisted(t *testing.T) {
	directory := t.TempDir()
	identityFile := filepath.Join(directory, "host-id")

	hostID, hostname, err := resolveHostIdentity(identityFile)
	if err != nil {
		t.Fatalf("unable to establish identity: %v", err)
	}
	if hostID == "" {
		t.Fatal("an agent must have an id")
	}
	if hostname == "" {
		t.Fatal("an agent must report a hostname")
	}

	stored, err := os.ReadFile(identityFile)
	if err != nil {
		t.Fatalf("the identity was not persisted: %v", err)
	}
	if len(stored) == 0 {
		t.Fatal("the persisted identity is empty")
	}

	// A restart must not look like a new machine.
	secondID, _, err := resolveHostIdentity(identityFile)
	if err != nil {
		t.Fatalf("unable to re-read identity: %v", err)
	}
	if secondID != hostID {
		t.Errorf("the id must survive a restart: first %q, then %q", hostID, secondID)
	}
}

func TestHostIdentityIsCreatedInAMissingDirectory(t *testing.T) {
	identityFile := filepath.Join(t.TempDir(), "nested", "deeper", "host-id")
	if _, _, err := resolveHostIdentity(identityFile); err != nil {
		t.Fatalf("the identity directory should be created: %v", err)
	}
	if _, err := os.Stat(identityFile); err != nil {
		t.Errorf("identity file missing: %v", err)
	}
}

func TestExplicitHostIDWins(t *testing.T) {
	t.Setenv("HOST_ID", "pinned-host")
	identityFile := filepath.Join(t.TempDir(), "host-id")

	hostID, _, err := resolveHostIdentity(identityFile)
	if err != nil {
		t.Fatalf("unable to establish identity: %v", err)
	}
	if hostID != "pinned-host" {
		t.Errorf("an explicit HOST_ID must win, got %q", hostID)
	}
	if _, err := os.Stat(identityFile); err == nil {
		t.Error("a pinned identity should not be persisted; the environment owns it")
	}
}

func TestStoredIdentityWinsOverGeneration(t *testing.T) {
	directory := t.TempDir()
	identityFile := filepath.Join(directory, "host-id")
	if err := os.WriteFile(identityFile, []byte("  previously-stored\n"), 0600); err != nil {
		t.Fatalf("unable to seed the identity: %v", err)
	}

	hostID, _, err := resolveHostIdentity(identityFile)
	if err != nil {
		t.Fatalf("unable to establish identity: %v", err)
	}
	if hostID != "previously-stored" {
		t.Errorf("a stored identity must be reused and trimmed, got %q", hostID)
	}
}

func TestHostnameOverrideIsHonoured(t *testing.T) {
	t.Setenv("HOSTNAME_OVERRIDE", "renamed-box")
	_, hostname, err := resolveHostIdentity(filepath.Join(t.TempDir(), "host-id"))
	if err != nil {
		t.Fatalf("unable to establish identity: %v", err)
	}
	if hostname != "renamed-box" {
		t.Errorf("expected the override to be used, got %q", hostname)
	}
}

func TestCapturedEventsCarryTheHostIdentity(t *testing.T) {
	stub := newBackendStub(t)
	withCollector(t, stub)

	originalID, originalHostname := configuredHostID, configuredHostname
	t.Cleanup(func() {
		configuredHostID, configuredHostname = originalID, originalHostname
	})
	configuredHostID = "test-host-id"
	configuredHostname = "test-hostname"

	recorder := httptest.NewRecorder()
	logHandler(recorder, httptest.NewRequest(http.MethodGet, "/capture?level=ERROR&msg=identified", nil))
	if recorder.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", recorder.Code, recorder.Body.String())
	}

	var payload LogPayload
	if err := json.Unmarshal([]byte(stub.header(stub.lastBody)), &payload); err != nil {
		t.Fatalf("backend received invalid JSON: %v", err)
	}
	if payload.HostID != "test-host-id" || payload.Hostname != "test-hostname" {
		t.Errorf("every event must name its machine, got %+v", payload)
	}
}

func TestHealthReportsTheAgentIdentity(t *testing.T) {
	originalID, originalHostname := configuredHostID, configuredHostname
	t.Cleanup(func() {
		configuredHostID, configuredHostname = originalID, originalHostname
	})
	configuredHostID = "health-host"
	configuredHostname = "health-name"

	recorder := httptest.NewRecorder()
	healthHandler(recorder, httptest.NewRequest(http.MethodGet, "/health", nil))

	var body map[string]string
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatalf("health must answer with JSON: %v", err)
	}
	if body["host_id"] != "health-host" || body["hostname"] != "health-name" {
		t.Errorf("health should identify the agent, got %v", body)
	}
}
