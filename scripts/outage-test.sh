#!/usr/bin/env bash
# Two real processes, one real outage: builds both images, sends events, kills the analytics
# container while the agent keeps running, sends more events into the durable queue, revives
# analytics, and asserts every event survived and the queue drained to empty.
#
# This is the thing that has been verified by hand at the end of nearly every phase since
# Phase 10 — Phase 20 turns that ritual into something that runs on every push instead of only
# when someone remembers to do it.
#
# Usage: scripts/outage-test.sh
# Requires: docker compose, curl, and python3 (or python) for JSON parsing.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

PYTHON="$(command -v python3 || command -v python || true)"
if [ -z "$PYTHON" ]; then
    echo "python3 (or python) is required to parse JSON responses" >&2
    exit 1
fi

export EVENTWATCH_API_KEY="${EVENTWATCH_API_KEY:-outage-test-secret}"
# The agent normally samples itself every 60 seconds, which would add an unpredictable number of
# events to the counts below. What this test measures is the durable queue - that every event handed
# to the agent survives an outage - so the timer is switched off to keep the arithmetic exact. The
# sampler has its own tests in go-collector/sampling_test.go.
export SAMPLE_INTERVAL_SECONDS=0
BEFORE_COUNT=5
DURING_COUNT=5
TOTAL_EXPECTED=$((BEFORE_COUNT + DURING_COUNT))

log() { echo "[outage-test] $*"; }

cleanup() {
    log "tearing down"
    docker compose down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_for_health() {
    local url=$1 label=$2 attempt=0
    until curl -fsS -m 2 "$url" >/dev/null 2>&1; do
        attempt=$((attempt + 1))
        if [ "$attempt" -ge 60 ]; then
            echo "timed out waiting for $label to become healthy" >&2
            docker compose logs >&2 || true
            exit 1
        fi
        sleep 2
    done
    log "$label is up (after ${attempt} checks)"
}

capture_event() {
    # The container image binds the agent to every interface, so its own security model
    # requires a key here exactly as it would for any other exposed deployment.
    curl -s -o /dev/null -w '%{http_code}' \
        -H "X-EventWatch-Key: $EVENTWATCH_API_KEY" \
        "http://localhost:8082/capture?level=ERROR&msg=outage-test-event"
}

total_events() {
    curl -s -H "X-EventWatch-Key: $EVENTWATCH_API_KEY" http://localhost:8080/summary \
        | "$PYTHON" -c "import sys, json; print(json.load(sys.stdin)['total_events'])"
}

pending_queue_size() {
    # The agent counts its own pending *.json files for exactly this reason: the gauge on
    # /health cannot drift from what is actually on disk, so it is a reliable thing to poll.
    curl -s http://localhost:8082/health \
        | "$PYTHON" -c "import sys, json; print(json.load(sys.stdin)['queue_depth'])"
}

log "building and starting the stack"
docker compose up -d --build

wait_for_health "http://localhost:8080/health" "analytics"
wait_for_health "http://localhost:8082/health" "collector"

log "sending $BEFORE_COUNT events before the outage"
for _ in $(seq 1 "$BEFORE_COUNT"); do
    status=$(capture_event)
    if [ "$status" != "200" ]; then
        echo "expected 200 before the outage, got $status" >&2
        exit 1
    fi
done

# The agent's forwarder is asynchronous relative to storage under load; give it a moment.
sleep 2
before_total=$(total_events)
if [ "$before_total" != "$BEFORE_COUNT" ]; then
    echo "expected $BEFORE_COUNT stored events before the outage, analytics reports $before_total" >&2
    exit 1
fi
log "confirmed: $before_total events stored before the outage"

log "stopping analytics — the collector keeps running through this"
docker compose stop analytics

log "sending $DURING_COUNT events while analytics is down"
for _ in $(seq 1 "$DURING_COUNT"); do
    status=$(capture_event)
    if [ "$status" != "503" ]; then
        echo "expected 503 (queued for retry) during the outage, got $status" >&2
        exit 1
    fi
done

queued=$(pending_queue_size)
if [ "$queued" -lt "$DURING_COUNT" ]; then
    echo "expected at least $DURING_COUNT files in the durable queue, found $queued" >&2
    exit 1
fi
log "confirmed: $queued events durably queued while analytics was down"

log "restarting analytics"
docker compose start analytics
wait_for_health "http://localhost:8080/health" "analytics (restarted)"

log "waiting for the queue to drain"
attempt=0
while true; do
    after_total=$(total_events)
    remaining=$(pending_queue_size)
    if [ "$after_total" = "$TOTAL_EXPECTED" ] && [ "$remaining" = "0" ]; then
        break
    fi
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 30 ]; then
        echo "queue did not drain: total=$after_total (want $TOTAL_EXPECTED), pending=$remaining (want 0)" >&2
        exit 1
    fi
    sleep 2
done

log "PASS: $TOTAL_EXPECTED events stored, zero pending, zero lost across a real analytics outage"
