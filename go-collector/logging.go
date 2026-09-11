package main

import (
	"encoding/json"
	"fmt"
	"os"
	"sort"
	"strings"
	"sync"
	"time"
)

// logFields carries the structured context attached to one log line.
type logFields map[string]any

var (
	logAsJSON  = true
	logMutex   sync.Mutex
	logService = "go-collector"
)

func configureLogging(format string) {
	logAsJSON = !strings.EqualFold(format, "text")
}

func logInfo(message string, fields logFields)  { writeLog("INFO", message, fields) }
func logWarn(message string, fields logFields)  { writeLog("WARN", message, fields) }
func logError(message string, fields logFields) { writeLog("ERROR", message, fields) }

func writeLog(level, message string, fields logFields) {
	logMutex.Lock()
	defer logMutex.Unlock()
	if logAsJSON {
		entry := make(map[string]any, len(fields)+4)
		for key, value := range fields {
			entry[key] = value
		}
		entry["timestamp"] = time.Now().UTC().Format(time.RFC3339Nano)
		entry["level"] = level
		entry["service"] = logService
		entry["message"] = message
		encoded, err := json.Marshal(entry)
		if err != nil {
			fmt.Fprintf(os.Stdout, "{\"level\":\"ERROR\",\"message\":\"log encoding failed\"}\n")
			return
		}
		fmt.Fprintf(os.Stdout, "%s\n", encoded)
		return
	}
	// Human-readable fallback for local demonstrations.
	keys := make([]string, 0, len(fields))
	for key := range fields {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	var builder strings.Builder
	for _, key := range keys {
		fmt.Fprintf(&builder, " %s=%v", key, fields[key])
	}
	fmt.Fprintf(os.Stdout, "[%s] %-5s %s%s\n",
		time.Now().Format("15:04:05"), level, message, builder.String())
}
