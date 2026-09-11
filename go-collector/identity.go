package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// The agent reports which machine an event came from. Without this a fleet writing to one
// analytics service is indistinguishable, and per-host alert rules have nothing to key on.
var (
	configuredHostID   string
	configuredHostname string
)

// resolveHostIdentity settles the agent identity once at startup.
//
// The id must survive restarts, reinstalls of the binary, and a hostname change, so it is
// persisted next to the durable queue. An explicit HOST_ID always wins, which is how a
// container or a config-managed host pins its own identity.
func resolveHostIdentity(identityFile string) (string, string, error) {
	hostname := getEnv("HOSTNAME_OVERRIDE", "")
	if hostname == "" {
		systemHostname, err := os.Hostname()
		if err != nil || systemHostname == "" {
			hostname = "unknown-host"
		} else {
			hostname = systemHostname
		}
	}

	if explicit := strings.TrimSpace(getEnv("HOST_ID", "")); explicit != "" {
		return explicit, hostname, nil
	}

	if stored, err := os.ReadFile(identityFile); err == nil {
		if trimmed := strings.TrimSpace(string(stored)); trimmed != "" {
			return trimmed, hostname, nil
		}
	}

	generated := newEventID()
	if err := os.MkdirAll(filepath.Dir(identityFile), 0755); err != nil {
		return "", "", fmt.Errorf("create identity directory: %w", err)
	}
	// Write then rename so a crash cannot leave a half-written identity behind.
	temporary := identityFile + ".tmp"
	if err := os.WriteFile(temporary, []byte(generated+"\n"), 0600); err != nil {
		return "", "", fmt.Errorf("write host identity: %w", err)
	}
	if err := os.Rename(temporary, identityFile); err != nil {
		_ = os.Remove(temporary)
		return "", "", fmt.Errorf("store host identity: %w", err)
	}
	return generated, hostname, nil
}
