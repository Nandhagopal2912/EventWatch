# CLAUDE.md — EventWatch

Working guide for agents and contributors. `readme.md` is the user-facing doc; `agent.md` is the
original phase roadmap. **This file is the authority on current state, conventions, and what to do
next.** Update it whenever a phase closes or an item in the backlog is fixed.

---

## 1. What this project is

EventWatch is a two-service, local-first host telemetry and alert pipeline — a small SIEM /
infrastructure-health collector built to learn distributed-systems mechanics without frameworks.

| Service | Language | Port | Role |
| --- | --- | --- | --- |
| `go-collector/` | Go 1.27, stdlib + gopsutil + godotenv | 8082 | Captures events + host CPU/RAM, forwards to Java, durable retry queue |
| `java-analytics/` | Java 17, Maven, `com.sun.net.httpserver` + Jackson + sqlite-jdbc | 8080 | Validates, persists to SQLite, evaluates alerts, serves query API |
| `dashboard/` | Static HTML/CSS/JS, no build step | 3000 (any static server) | Reads the Java query API only; never touches SQLite |

Deliberate constraint: **no web frameworks, no ORM, no DI container** on either side. Spring, Gin,
Hibernate and friends are out of scope — the point is to see the machinery. Do not introduce one
without the user asking.

---

## 2. Build, run, test

Run every command from the directory shown. Windows/PowerShell is the primary environment.

```bash
cd java-analytics && mvn compile exec:java
```

```bash
cd go-collector && go run .
```

```bash
python -m http.server 3000 -d dashboard
```

Checks:

```bash
cd go-collector && go vet ./... && go test ./...
```

```bash
cd java-analytics && mvn -q compile
```

Notes:
- Both services load the repo-root `.env` (Go via `godotenv.Load("../.env", ".env")`, Java via
  `Dotenv.configure().directory("..")`). Copy `.env.example` → `.env` for a new checkout.
- `java-analytics/.mvn/jvm.config` pins `-Xms64m -Xmx128m`; do not remove it — it exists because the
  dev machine has a small paging file.
- `events.db` is created on first Java start. It is gitignored and disposable; delete it to reset.
- Open the dashboard at exactly `http://localhost:3000` — the Java CORS allowlist is hardcoded to
  `localhost:3000` / `127.0.0.1:3000` ([AnalyticsEngine.java:597](java-analytics/src/main/java/com/main/AnalyticsEngine.java:597)).

Smoke test end to end:

```bash
curl.exe "http://localhost:8082/capture?level=ERROR&msg=Database%20transaction%20deadlock"
```

---

## 3. Code map

```text
go-collector/main.go            Single file: handlers, forwardToJava retries, durable file queue
go-collector/main_test.go       Event-ID uniqueness + queue capacity

java-analytics/src/main/java/com/main/
  AnalyticsEngine.java          main(), HTTP routing, auth, validation, rate limit, SQLite bootstrap
  EventRepository.java          Telemetry queries (find/count/latest/recent)
  AlertRepository.java          Alert upsert + lifecycle transitions
  AlertEngine.java              Moving-window CPU/RAM/repeated-error rules
  AlertRecord.java              Alert model     AlertStatus.java  OPEN|ACKNOWLEDGED|RESOLVED
  QueryService.java             JSON shaping for /events, /summary, alert DTOs
  NotificationRecord.java       Delivery-attempt row
  NotificationRepository.java   notification_deliveries table + audit trail
  NotificationService.java      Webhook dispatch, retry, cooldown policy
  AlertTransition.java          OPENED/REOPENED/ACKNOWLEDGED/RESOLVED/OCCURRENCE

dashboard/{index.html,app.js,styles.css}
```

### The JSON contract (do not break)

Go → Java `POST /receive`, header `X-EventWatch-Key`, `Content-Type: application/json`:

```json
{ "event_id": "...", "level": "ERROR", "msg": "...",
  "timestamp": "2026-09-04T18:46:00Z", "cpu_usage": 88.4, "ram_usage": 12.1 }
```

Rules: `level` ∈ INFO|WARN|ERROR|CRITICAL; `msg` 1–1000 chars; `event_id` 1–128 chars and unique
(partial unique index makes retries idempotent); usages are finite numbers 0–100; body ≤ 64 KiB.
Changes to this schema must stay backward compatible — additive fields only, never renames.

### HTTP surface

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| POST | `/receive` (8080) | key | Ingest; rate limited 100/min/IP |
| GET | `/health` (8080, 8082) | none | 8080 also probes SQLite |
| GET | `/capture?level=&msg=` (8082) | none | Public ingress |
| GET | `/stress` (8082) | none | 500 events, 32 concurrent |
| GET | `/events?level=&from=&to=&limit=&offset=` | key | limit ≤ 200, default 50 |
| GET | `/summary` | key | Totals, active alerts, 5-event averages |
| GET | `/alerts?status=&type=` | key | Active alerts |
| GET | `/alerts/{key}` | key | Single alert |
| POST | `/alerts/{key}/acknowledge`, `/resolve` | key | Operator actions; both notify |
| GET | `/alerts/{key}/notifications?limit=` | key | Delivery audit trail, limit ≤ 200 |

---

## 4. Conventions

- **Naming:** full words, no abbreviations — `exception` not `e`, `statement` not `stmt`,
  `requestExecutor` not `pool`. Match this; it is consistent across both languages.
- **Comments:** sparse, and only where they explain *why* (e.g. "Commit to SQLite before adding the
  event to memory, preventing acknowledged data loss"). Do not add narration comments.
- **Responses:** every endpoint returns JSON. Errors → `{"status":"error","message":"..."}` via
  `sendResponse`; data payloads → `sendJsonResponse`. Never return a bare string or empty body.
- **Status codes:** 400 validation, 401 bad/missing key, 405 method, 413 oversize, 415 content type,
  429 rate limit, 503 storage or backend unavailable. Go treats 5xx and 429 as retryable; other 4xx
  are permanent and move the queued file to `pending-events/rejected-events/`.
- **SQL:** always `PreparedStatement` with parameters. Schema is created idempotently at startup
  (`CREATE TABLE IF NOT EXISTS`), so there is no migration tool — new columns go in as guarded
  `ALTER TABLE` following the `event_id` pattern in `initializeDatabase()`.
- **Config:** everything tunable comes from `.env` with an in-code fallback, through
  `getConfig/getDoubleConfig/getIntConfig` (Java) or `getEnv/getIntEnv` (Go). Never hardcode a new
  tunable. Add every new key to `.env.example`.
- **Secrets:** `.env` is gitignored and must stay that way. Never commit a real key; never print the
  key in logs or responses.
- **Commits:** phase-scoped, imperative, one phase per commit — matching existing history
  ("Implemented Alerts and its management through phase 6").

---

## 5. Current state — read before starting work

**Phases 1–8 are complete.** Both services build and the test suite passes:
`mvn -o compile` → BUILD SUCCESS; `go vet ./...` and `go test ./...` → clean.

Phase 8 delivers webhook notifications end to end: `AlertRepository` now reports an `AlertTransition`
for every lifecycle change, `AlertEngine` and the operator endpoints hand those to
`NotificationService`, which dispatches on a daemon thread with bounded retries and records every
attempt in `notification_deliveries`. Verified against a local sink — open/reopen/acknowledge/resolve
each delivered once, repeat occurrences were suppressed by the cooldown, a downed sink produced three
`FAILED` rows with ascending attempt numbers, and the alert state stayed correct throughout.

Notifications are **off by default**: `NOTIFICATIONS_ENABLED=false` and an empty
`NOTIFICATION_WEBHOOK_URL` make `handle()` a no-op.

B1–B8 in the backlog below are all fixed. What remains unowned is Phase 9 onward plus the
cross-cutting items in section 8.

## 6. Phase 8 as built — notifications

Delivery policy, in one place:

- `AlertRepository.saveOccurrence` reads the stored row before upserting and classifies the change:
  no row → `OPENED`; stored row `RESOLVED` → `REOPENED`; otherwise `OCCURRENCE`. `resolve` and
  `acknowledge` return their transition, or `null` when nothing changed.
- `OPENED`, `REOPENED`, `ACKNOWLEDGED` and `RESOLVED` always deliver. `OCCURRENCE` delivers only
  once `NOTIFICATION_REMINDER_SECONDS` has passed since the last delivery, so a sustained alert
  reminds rather than floods. Set the reminder to `0` to disable reminders entirely.
- Dispatch is asynchronous on a single daemon thread; ingestion never blocks on a slow webhook, and
  a failed delivery never changes alert state.
- Retries are bounded by `NOTIFICATION_MAX_ATTEMPTS` with a fixed `NOTIFICATION_RETRY_DELAY_MILLIS`
  pause. A 4xx other than 429 is permanent and stops immediately — the same classification the Go
  queue uses.
- Every attempt, successful or not, is one row in `notification_deliveries`, readable through
  `GET /alerts/{key}/notifications`.

The payload is versioned — `schema_version: "eventwatch.notification.v1"`. Treat it as a published
contract: add fields, never rename or remove them, and bump the version if the shape has to change.

To exercise it locally, point `NOTIFICATION_WEBHOOK_URL` at any endpoint that accepts POST and set
`NOTIFICATIONS_ENABLED=true`. Temporarily setting `CPU_ALERT_THRESHOLD=0` forces an alert on the
next event.

Still open for a later pass: email/SMTP, Slack and Teams channel formatters, HMAC request signing,
and per-alert routing rules.

## 7. Fixed defects and remaining quality work

### Fixed (keep these fixed — each has a way to regress)

**B1 — Acknowledged alerts reverted to OPEN.** The upsert set `status = excluded.status`, which is
always `OPEN`, so the next occurrence undid the operator's acknowledgement. Now a
`CASE WHEN alerts.status = 'RESOLVED' THEN 'OPEN' ELSE alerts.status END` preserves
`ACKNOWLEDGED`. Verified: occurrence count advanced 4 → 5 with the status unchanged.

**B2 — Stored XSS in the dashboard.** Event and alert text reached `innerHTML` unescaped, and any
client can post a `msg` through the public `/capture` endpoint into a page that holds the API key.
All interpolated values now go through `escapeHtml`. Any new `innerHTML` template must do the same.

**B3 — `GET /alerts` was unauthenticated.** Now checks the API key like every sibling route.

**B4 — Rate-limit map grew without bound.** A daemon `eventwatch-maintenance` thread sweeps expired
windows every 60 seconds.

**B5 — Unbounded in-memory event mirror.** `logStorage` held every event ever, reloaded in full at
startup, in a `CopyOnWriteArrayList` copied again on every ingest — O(n²). Replaced by a bounded
`ArrayDeque` of the newest `MOVING_AVERAGE_WINDOW` events
([AnalyticsEngine.java:50](java-analytics/src/main/java/com/main/AnalyticsEngine.java:50)), an
`AtomicLong` total seeded from SQL, and `EventRepository.topErrorMessages(5)` for the terminal
report, backed by a new `(level, event_timestamp)` index. Memory is now independent of history size.

**B6 — `/stress` spawned 500 unbounded goroutines.** Bounded to `stressConcurrency` (32) with a
channel semaphore.

**B7 — Dead code.** `AlertRecord.recordOccurrence` / `resolve` removed; the model is now immutable.

**B8 — Rate-limited events were silently dropped.** Found while load-testing the B5/B6 fixes: Java
rate-limits one client to 100 requests/minute, so a 500-event `/stress` burst produced 400 `429`
responses — and the send path only queued on 5xx, so those events vanished. `processPendingEvents`
had always treated 429 as retryable; the send path did not. Both now share `isRetryableStatus`.
Verified: the same burst leaves exactly 400 files in `pending-events/` and the worker drains them.

Note what this means for `/stress`: a 500-event burst legitimately exceeds the rate limit and most of
it arrives over the following minutes through the queue. That is the system working, not a failure.

### Remaining quality work

- **Connection handling.** Every repository call opens a fresh `DriverManager.getConnection`. A
  shared connection or small pool with `PRAGMA journal_mode=WAL` and `busy_timeout` would cut
  per-query overhead and lock contention under load.
- **Routing.** `main()` is 300+ lines of inline lambdas. Extract handlers into their own classes and
  keep `main()` to wiring.
- **Retention.** Nothing deletes from `telemetry_events` or `notification_deliveries`. Add a
  configurable retention window and a periodic prune.
- **Timestamps.** Go now sends UTC `Z` values so lexical order matches chronological order, but rows
  written by older builds may carry a local offset. A one-off normalization pass would make range
  filters exact for that history.
- **Stale artifacts.** `java-analytics/alerts_history.json` is a Phase 2 leftover that is no longer
  written — delete it and the `readme.md` note referencing it.
- **Notification reminders are per-process.** `NotificationService` keeps a `reminderScheduledAt` map
  in memory alongside the SQL `lastDeliveredAt` lookup; a restart falls back to the SQL value, which
  is correct but means an in-flight reservation is lost. Fine for one instance, wrong for two.

## 8. Roadmap — Phase 9 onward

**Phase 9 — Observability.** Structured JSON logs on both sides (replacing the emoji `Printf` /
`System.out` calls, which are fine for a demo terminal but not parseable). A correlation ID
generated by Go, carried on the request header, echoed in Java logs and responses. A
`/metrics` endpoint in Prometheus text format: events received/rejected/deduplicated, processing
latency, queue depth, pending-file count, notification success/failure, database error count. Add
OpenTelemetry tracing across the Go→Java hop last, since it is the heaviest dependency.

**Phase 10 — Testing and delivery.** This is now the largest real gap: Java has **no tests and no test
dependency in `pom.xml`**. Add JUnit 5 + `maven-surefire`, then cover — event validation (every
rejection branch), moving averages, alert threshold and dedup logic, `AlertRepository` lifecycle
against a temp SQLite file, and idempotent re-delivery of a duplicate `event_id`. Extend the Go
tests to cover `forwardToJava` retry/backoff classification and `processPendingEvents` file
outcomes with an `httptest` server. Add a `NotificationService` test with a
local HTTP sink covering the cooldown, the 4xx stop, and the retry ladder — the behaviour verified by
hand during Phase 8 should not stay hand-verified. Then an integration test that runs both services
end to end, kills Java mid-flight, and asserts the queue drains without loss or duplication. Finally
Dockerfiles, Compose, and a CI workflow running `go vet`/`go test`/`mvn verify` plus dependency
scanning. Note that the Java service currently hardcodes port 8080 and a relative
`jdbc:sqlite:events.db` — both need to become config before containerizing.

**Phase 11 — Scale beyond SQLite.** Only when measurements justify it. Order: extract a storage
interface behind the repositories → PostgreSQL or a time-series store for multi-collector or
long-retention deployments → a broker (Kafka/NATS) in place of the file queue when volume demands
durable distributed streaming → split ingestion, analytics, and notification into separately
scalable services. Keep SQLite as the single-host and development path.

**Cross-cutting, currently unowned:** TLS between the services (the API key travels in plaintext over
localhost today), authentication for the public `/capture` endpoint, per-alert-rule configuration
instead of three global thresholds, and CORS origins moved to `.env`.

---

## 9. Definition of done for a release

- Events are authenticated, validated, persisted transactionally, deduplicated, and queryable.
- A temporary Java outage loses nothing and duplicates nothing.
- Alerts are configurable, deduplicated, actionable, and notify exactly once per state change.
- An operator can see trends, acknowledge, resolve, and read delivery history.
- Tests cover normal traffic, concurrent traffic, malformed input, restarts, and dependency failures.
- Configuration and deployment require no source changes.
- Logs, metrics, health checks, and traces make a failure diagnosable without a debugger.

EventWatch is a strong learning and portfolio system. It should not be described as a production
replacement for Datadog, CloudWatch, or a SIEM until TLS, retention, and the Phase 9–10 work land.
