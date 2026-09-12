package main

import (
	"encoding/json"
	"math"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

// withDiskPaths points the sampler at specific mounts and restores the global afterwards.
func withDiskPaths(t *testing.T, paths []string) {
	t.Helper()
	original := configuredDiskPaths
	t.Cleanup(func() { configuredDiskPaths = original })
	configuredDiskPaths = paths
}

func TestFullestDiskReportsAMountAndAPercentage(t *testing.T) {
	withDiskPaths(t, nil)

	usedPercent, mountpoint, found := readFullestDisk()
	if !found {
		t.Fatal("this machine has at least one readable filesystem")
	}
	if mountpoint == "" {
		t.Error("a percentage is only actionable once you know which disk it describes")
	}
	if usedPercent < 0 || usedPercent > 100 {
		t.Errorf("Java validates this as a percentage and would reject the event, got %v", usedPercent)
	}
}

func TestFullestDiskPrefersTheMostUsedMount(t *testing.T) {
	// Whichever filesystem is fuller has to win, because that is the one that stops the machine
	// working first. Asserted on the reading rather than the path: both of these can sit on one
	// filesystem, and then either mountpoint is a correct answer for the same disk.
	working, err := os.Getwd()
	if err != nil {
		t.Fatalf("unable to read the working directory: %v", err)
	}
	temporary := t.TempDir()

	withDiskPaths(t, []string{working})
	workingPercent, _, workingFound := readFullestDisk()
	withDiskPaths(t, []string{temporary})
	temporaryPercent, _, temporaryFound := readFullestDisk()
	if !workingFound || !temporaryFound {
		t.Skip("both paths have to be readable for this comparison to mean anything")
	}

	withDiskPaths(t, []string{working, temporary})
	chosenPercent, _, found := readFullestDisk()
	if !found {
		t.Fatal("two readable paths cannot produce no reading")
	}

	expected := math.Max(workingPercent, temporaryPercent)
	if chosenPercent != expected {
		t.Errorf("expected the fuller of %v and %v, got %v", workingPercent, temporaryPercent, chosenPercent)
	}
}

func TestAnUnreadablePathIsSkippedRatherThanReportedAsEmpty(t *testing.T) {
	withDiskPaths(t, []string{filepath.Join(t.TempDir(), "no-such-mount")})

	usedPercent, mountpoint, found := readFullestDisk()
	if found {
		t.Errorf("an unreadable path must not be reported, got %s at %v", mountpoint, usedPercent)
	}
}

func TestAReadablePathAmongUnreadableOnesStillReports(t *testing.T) {
	withDiskPaths(t, []string{filepath.Join(t.TempDir(), "absent"), t.TempDir()})

	_, mountpoint, found := readFullestDisk()
	if !found || mountpoint == "" {
		t.Error("one bad path must not hide the filesystems that can be read")
	}
}

func TestCapturedEventsCarryTheFullestDisk(t *testing.T) {
	stub := newBackendStub(t)
	withCollector(t, stub)
	withDiskPaths(t, nil)

	recorder := httptest.NewRecorder()
	logHandler(recorder, httptest.NewRequest(http.MethodGet, "/capture?level=INFO&msg=disk", nil))
	if recorder.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", recorder.Code, recorder.Body.String())
	}

	var payload LogPayload
	if err := json.Unmarshal([]byte(stub.header(stub.lastBody)), &payload); err != nil {
		t.Fatalf("backend received invalid JSON: %v", err)
	}
	if payload.DiskUsage == nil {
		t.Fatal("a machine with a readable filesystem must report it")
	}
	if *payload.DiskUsage < 0 || *payload.DiskUsage > 100 {
		t.Errorf("disk usage must be a percentage, got %v", *payload.DiskUsage)
	}
	if payload.DiskPath == "" {
		t.Error("the mount must travel with the reading")
	}
}

func TestAnUnreadableDiskOmitsTheFieldEntirely(t *testing.T) {
	stub := newBackendStub(t)
	withCollector(t, stub)
	withDiskPaths(t, []string{filepath.Join(t.TempDir(), "absent")})

	recorder := httptest.NewRecorder()
	logHandler(recorder, httptest.NewRequest(http.MethodGet, "/capture?level=INFO&msg=nodisk", nil))
	if recorder.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", recorder.Code, recorder.Body.String())
	}

	// Absent, not zero: the event still reports everything else the agent knows.
	var raw map[string]any
	if err := json.Unmarshal([]byte(stub.header(stub.lastBody)), &raw); err != nil {
		t.Fatalf("backend received invalid JSON: %v", err)
	}
	if _, present := raw["disk_usage"]; present {
		t.Error("an unreadable disk must be omitted; 0% would read as an empty disk")
	}
	if _, present := raw["cpu_usage"]; !present {
		t.Error("one unreadable sample must not cost the rest of the event")
	}
}

func TestDiskPathsSettingIgnoresBlanks(t *testing.T) {
	if paths := splitPaths("  /var , , /data  "); len(paths) != 2 || paths[0] != "/var" || paths[1] != "/data" {
		t.Errorf("a trailing comma must be harmless, got %#v", paths)
	}
	if paths := splitPaths(""); len(paths) != 0 {
		t.Errorf("an unset value means every real filesystem, got %#v", paths)
	}
}
