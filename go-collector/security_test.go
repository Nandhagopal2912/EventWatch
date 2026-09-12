package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func restoreIngressSecurity(t *testing.T) {
	t.Helper()
	bind, required, key := configuredBindAddress, captureKeyRequired, configuredCaptureKey
	t.Cleanup(func() {
		configuredBindAddress, captureKeyRequired, configuredCaptureKey = bind, required, key
	})
}

func TestLoopbackBindDoesNotRequireAKey(t *testing.T) {
	restoreIngressSecurity(t)
	for _, bind := range []string{"127.0.0.1", "localhost", "::1", ""} {
		required, err := resolveIngressSecurity(bind, "", false)
		if err != nil {
			t.Fatalf("bind %q should be allowed without a key: %v", bind, err)
		}
		if required {
			t.Errorf("bind %q is loopback and should not force a key", bind)
		}
	}
}

func TestExposingTheAgentWithoutAKeyIsRefused(t *testing.T) {
	restoreIngressSecurity(t)
	// An open ingress on a network lets anyone forge telemetry for this host.
	if _, err := resolveIngressSecurity("0.0.0.0", "", false); err == nil {
		t.Fatal("binding beyond loopback without a key must be refused")
	}
	if _, err := resolveIngressSecurity("192.168.1.10", "", false); err == nil {
		t.Fatal("binding to a LAN address without a key must be refused")
	}
}

func TestExposingTheAgentWithAKeyIsAllowedAndForcesAuth(t *testing.T) {
	restoreIngressSecurity(t)
	required, err := resolveIngressSecurity("0.0.0.0", "agent-secret", false)
	if err != nil {
		t.Fatalf("a keyed public bind should be allowed: %v", err)
	}
	if !required {
		t.Error("a non-loopback bind must force authentication")
	}
}

func TestRequiringAKeyWithoutOneIsRefused(t *testing.T) {
	restoreIngressSecurity(t)
	if _, err := resolveIngressSecurity("127.0.0.1", "", true); err == nil {
		t.Fatal("CAPTURE_REQUIRE_KEY without a key must be refused")
	}
}

func TestCaptureRejectsAMissingOrWrongKey(t *testing.T) {
	stub := newBackendStub(t)
	withCollector(t, stub)
	restoreIngressSecurity(t)
	if _, err := resolveIngressSecurity("0.0.0.0", "agent-secret", false); err != nil {
		t.Fatalf("setup failed: %v", err)
	}

	for name, key := range map[string]string{"missing": "", "wrong": "nope", "prefix": "agent-secre"} {
		recorder := httptest.NewRecorder()
		request := httptest.NewRequest(http.MethodGet, "/capture?msg=denied", nil)
		if key != "" {
			request.Header.Set("X-EventWatch-Key", key)
		}
		logHandler(recorder, request)
		if recorder.Code != http.StatusUnauthorized {
			t.Errorf("%s key: expected 401, got %d", name, recorder.Code)
		}
	}
	if stub.requests.Load() != 0 {
		t.Error("an unauthorized request must not reach the analytics service")
	}
}

func TestCaptureAcceptsTheConfiguredKey(t *testing.T) {
	stub := newBackendStub(t)
	withCollector(t, stub)
	restoreIngressSecurity(t)
	if _, err := resolveIngressSecurity("0.0.0.0", "agent-secret", false); err != nil {
		t.Fatalf("setup failed: %v", err)
	}

	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/capture?msg=allowed", nil)
	request.Header.Set("X-EventWatch-Key", "agent-secret")
	logHandler(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", recorder.Code, recorder.Body.String())
	}
}

func TestStressRouteIsAuthorizedToo(t *testing.T) {
	withCollector(t, newBackendStub(t))
	restoreIngressSecurity(t)
	if _, err := resolveIngressSecurity("0.0.0.0", "agent-secret", false); err != nil {
		t.Fatalf("setup failed: %v", err)
	}

	recorder := httptest.NewRecorder()
	stressHandler(recorder, httptest.NewRequest(http.MethodGet, "/stress", nil))
	if recorder.Code != http.StatusUnauthorized {
		t.Errorf("the stress route must not be open when capture is keyed, got %d", recorder.Code)
	}
}

func TestBackendTLSTrustsAConfiguredAuthority(t *testing.T) {
	// A PEM with no certificate must be rejected rather than silently trusting nothing.
	empty := filepath.Join(t.TempDir(), "empty.pem")
	if err := os.WriteFile(empty, []byte("not a certificate"), 0600); err != nil {
		t.Fatalf("unable to write the test file: %v", err)
	}
	if _, err := backendTLSConfig(empty, false); err == nil {
		t.Error("a CA file with no certificates must be refused")
	}

	if _, err := backendTLSConfig(filepath.Join(t.TempDir(), "missing.pem"), false); err == nil {
		t.Error("a missing CA file must be refused")
	}

	config, err := backendTLSConfig("", false)
	if err != nil || config != nil {
		t.Errorf("no CA configured means the system trust store, got %v %v", config, err)
	}
}

func TestBackendTLSCanSkipVerificationExplicitly(t *testing.T) {
	configureLogging("json")
	config, err := backendTLSConfig("", true)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if config == nil || !config.InsecureSkipVerify {
		t.Error("skip verify must be honoured when explicitly requested")
	}
}

func TestResolveIngestionCredentialFallsBackToTheSharedKey(t *testing.T) {
	credential, warning, err := resolveIngestionCredential("fleet-secret", "", "web-01")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if credential != "fleet-secret" {
		t.Errorf("no AGENT_TOKEN set, expected the shared key, got %q", credential)
	}
	if warning != "" {
		t.Errorf("the shared key path needs no warning, got %q", warning)
	}
}

func TestResolveIngestionCredentialPrefersTheAgentToken(t *testing.T) {
	credential, warning, err := resolveIngestionCredential("fleet-secret", "agent-token", "web-01")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if credential != "agent-token" {
		t.Errorf("expected the per-agent token to take over, got %q", credential)
	}
	if warning != "" {
		t.Errorf("a pinned host id needs no warning, got %q", warning)
	}
}

func TestResolveIngestionCredentialWarnsWithoutAPinnedHost(t *testing.T) {
	_, warning, err := resolveIngestionCredential("fleet-secret", "agent-token", "")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if warning == "" {
		t.Error("an unbound self-generated identity might not match the token; this must be surfaced")
	}
}

func TestResolveIngestionCredentialRequiresOneOrTheOther(t *testing.T) {
	if _, _, err := resolveIngestionCredential("", "", "web-01"); err == nil {
		t.Error("with neither credential set, startup must refuse rather than send an empty header")
	}
}

func TestResolveIngestionCredentialAcceptsTheTokenAlone(t *testing.T) {
	// A deployment that has fully migrated off the shared key never sets EVENTWATCH_API_KEY.
	credential, _, err := resolveIngestionCredential("", "agent-token", "web-01")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if credential != "agent-token" {
		t.Errorf("expected the token alone to be sufficient, got %q", credential)
	}
}
