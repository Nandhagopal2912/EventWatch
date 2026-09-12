package main

import (
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"net"
	"net/http"
	"os"
	"strings"
)

var (
	configuredBindAddress string
	captureKeyRequired    bool
	configuredCaptureKey  string
)

// resolveIngressSecurity decides how exposed the agent's own endpoint is.
//
// The default bind is loopback, because the agent exists to accept events from processes on
// its own machine. Exposing it to a network is allowed, but only with a key: an open ingress
// lets anyone on that network forge telemetry for this host and trigger its alerts.
func resolveIngressSecurity(bind string, captureKey string, requireKey bool) (bool, error) {
	configuredBindAddress = bind
	configuredCaptureKey = captureKey

	loopback := isLoopbackAddress(bind)
	captureKeyRequired = requireKey || !loopback

	if captureKeyRequired && captureKey == "" {
		if !loopback {
			return false, fmt.Errorf(
				"COLLECTOR_BIND=%s exposes the agent beyond loopback, which requires CAPTURE_API_KEY", bind)
		}
		return false, fmt.Errorf("CAPTURE_REQUIRE_KEY is set but CAPTURE_API_KEY is empty")
	}
	return captureKeyRequired, nil
}

func isLoopbackAddress(bind string) bool {
	host := strings.TrimSpace(bind)
	if host == "" {
		return true
	}
	if parsed := net.ParseIP(host); parsed != nil {
		return parsed.IsLoopback()
	}
	return strings.EqualFold(host, "localhost")
}

// authorizeCapture reports whether a request may submit telemetry for this host.
func authorizeCapture(r *http.Request) bool {
	if !captureKeyRequired {
		return true
	}
	presented := r.Header.Get("X-EventWatch-Key")
	// Constant time, so a wrong key cannot be narrowed down by timing.
	return len(presented) == len(configuredCaptureKey) &&
		subtle.ConstantTimeCompare([]byte(presented), []byte(configuredCaptureKey)) == 1
}

// resolveIngestionCredential decides what this agent presents to Java as X-EventWatch-Key.
//
// An AGENT_TOKEN, once minted per host, takes over from the fleet-wide key. The key stays
// required regardless, so a deployment that has not adopted tokens yet — or has the shared path
// deliberately kept on as a fallback — starts exactly as it always has. warning is non-empty when
// a token is set without a pinned host id, which the caller should log rather than fail on: an
// unbound self-generated identity might still be exactly what the token was minted for.
func resolveIngestionCredential(apiKey, agentToken, hostID string) (credential string, warning string, err error) {
	apiKey = strings.TrimSpace(apiKey)
	agentToken = strings.TrimSpace(agentToken)
	if apiKey == "" && agentToken == "" {
		return "", "", fmt.Errorf("EVENTWATCH_API_KEY or AGENT_TOKEN is required")
	}
	if agentToken == "" {
		return apiKey, "", nil
	}
	if strings.TrimSpace(hostID) == "" {
		warning = "AGENT_TOKEN is set without a pinned HOST_ID; if the token is bound to a host, " +
			"a self-generated identity will not match it"
	}
	return agentToken, warning, nil
}

// backendTLSConfig trusts a private certificate authority when one is configured, so an
// agent can verify a self-signed analytics service instead of skipping verification.
func backendTLSConfig(caFile string, skipVerify bool) (*tls.Config, error) {
	if skipVerify {
		// Encrypted but unauthenticated: useful for a first run, never for a real deployment.
		logWarn("TLS certificate verification is disabled", logFields{
			"setting": "BACKEND_TLS_SKIP_VERIFY"})
		return &tls.Config{InsecureSkipVerify: true}, nil
	}
	if caFile == "" {
		return nil, nil
	}
	pem, err := os.ReadFile(caFile)
	if err != nil {
		return nil, fmt.Errorf("read backend CA file: %w", err)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(pem) {
		return nil, fmt.Errorf("backend CA file %s contains no certificates", caFile)
	}
	return &tls.Config{RootCAs: pool, MinVersion: tls.VersionTLS12}, nil
}
