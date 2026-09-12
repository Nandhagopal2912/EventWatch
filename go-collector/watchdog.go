package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"sync"
	"time"
)

// When the analytics service is unreachable, the agent is the part still running — so it is
// the only part that can report it. This is one half of the watchdog; the other is the
// analytics heartbeat, which covers the case where the whole analytics host dies.
var (
	agentAlertWebhookURL string
	deliveryStallAfter   = 5 * time.Minute
	agentAlertClient     = &http.Client{Timeout: 5 * time.Second}

	deliveryMutex  sync.Mutex
	lastDeliveryOK time.Time
	// Whether the most recent attempt failed, as a fact rather than a comparison of two
	// timestamps: a success and a failure can land on the same clock tick, and then which
	// came last is unanswerable.
	lastAttemptFailed bool
	deliveryAlertSent bool
)

func configureDeliveryWatchdog(webhookURL string, stallAfter, timeout time.Duration) {
	agentAlertWebhookURL = webhookURL
	deliveryStallAfter = stallAfter
	agentAlertClient = &http.Client{Timeout: timeout}

	deliveryMutex.Lock()
	defer deliveryMutex.Unlock()
	// A fresh agent has not failed yet, so startup counts as the last known good moment.
	lastDeliveryOK = time.Now()
	lastAttemptFailed = false
	deliveryAlertSent = false
}

// recordDeliveryOutcome reads a forwarding outcome as evidence about the backend.
//
// "rejected" is deliberately ignored: a permanent 4xx means the service answered and refused
// that one event, which says nothing about whether it is reachable. Counting it would make a
// storm of malformed events look like an outage.
func recordDeliveryOutcome(outcome string) {
	deliveryMutex.Lock()
	defer deliveryMutex.Unlock()
	switch outcome {
	case "delivered":
		lastDeliveryOK = time.Now()
		lastAttemptFailed = false
	case "queued", "failed":
		lastAttemptFailed = true
	}
}

// checkDeliveryHealth reports whether delivery is currently healthy and alerts on each
// transition. An agent with no traffic never alerts: a stall needs a recent *failure*, not
// merely a long silence.
func checkDeliveryHealth(now time.Time) bool {
	deliveryMutex.Lock()
	stalled := lastAttemptFailed && now.Sub(lastDeliveryOK) >= deliveryStallAfter
	alreadyAlerted := deliveryAlertSent
	stalledFor := now.Sub(lastDeliveryOK)
	if stalled && !alreadyAlerted {
		deliveryAlertSent = true
	}
	recovered := !stalled && alreadyAlerted
	if recovered {
		deliveryAlertSent = false
	}
	deliveryMutex.Unlock()

	metrics.recordDeliveryHealthy(!stalled)
	if stalled && !alreadyAlerted {
		logError("no event delivered to analytics; alerting", logFields{
			"stalled_seconds": int(stalledFor.Seconds()),
			"backend_url":     configuredBackendURL,
			"queue_depth":     queueDepth(),
		})
		sendAgentAlert("delivery_stalled", stalledFor)
	}
	if recovered {
		logInfo("delivery to analytics recovered", logFields{"queue_depth": queueDepth()})
		sendAgentAlert("delivery_recovered", stalledFor)
	}
	return !stalled
}

// sendAgentAlert posts to an endpoint that does not depend on the analytics service.
func sendAgentAlert(event string, stalledFor time.Duration) {
	if agentAlertWebhookURL == "" {
		return
	}
	payload := map[string]any{
		"schema_version":  "eventwatch.agent_alert.v1",
		"event":           event,
		"host_id":         configuredHostID,
		"hostname":        configuredHostname,
		"agent_version":   agentVersion,
		"queue_depth":     queueDepth(),
		"stalled_seconds": int(stalledFor.Seconds()),
		"backend_url":     configuredBackendURL,
		"timestamp":       time.Now().UTC().Format(time.RFC3339),
	}
	body, err := json.Marshal(payload)
	if err != nil {
		logError("unable to encode the agent alert", logFields{"error": err.Error()})
		return
	}

	request, err := http.NewRequest(http.MethodPost, agentAlertWebhookURL, bytes.NewReader(body))
	if err != nil {
		metrics.recordAgentAlert(event, false)
		logError("agent alert webhook url is unusable", logFields{"error": err.Error()})
		return
	}
	request.Header.Set("Content-Type", "application/json")

	response, err := agentAlertClient.Do(request)
	if err != nil {
		metrics.recordAgentAlert(event, false)
		logWarn("agent alert delivery failed", logFields{"event": event, "error": err.Error()})
		return
	}
	defer response.Body.Close()
	_, _ = io.Copy(io.Discard, response.Body)

	delivered := response.StatusCode >= 200 && response.StatusCode < 300
	metrics.recordAgentAlert(event, delivered)
	if !delivered {
		logWarn("agent alert rejected", logFields{"event": event, "status": response.StatusCode})
	}
}
