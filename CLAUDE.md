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
cd java-analytics && mvn verify
```

```bash
docker compose up --build
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
go-collector/main.go            Handlers, forwardToJava retries, durable file queue
go-collector/logging.go         Structured JSON log lines, LOG_FORMAT switch
go-collector/metrics.go         Prometheus counters, queue-depth gauge, /metrics handler
go-collector/main_test.go       Event-ID uniqueness + queue capacity
go-collector/collector_test.go  Retries, capture handler, queue outcomes, metrics
go-collector/contract_test.go   Shared JSON contract, against testdata/
go-collector/Dockerfile         Static binary on alpine, queue on a volume

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
  StructuredLogger.java         One JSON object per log line; LOG_FORMAT=text for humans
  Metrics.java                  Prometheus counters, gauges, and text rendering
  EngineConfiguration.java      Every runtime setting; fromDotenv() and forTesting()
  Database.java                 Picks the backend from the JDBC url, pools, creates the schema
  ConnectionProvider.java       Where repositories get connections from
  SqlDialect.java               The few places SQLite and PostgreSQL disagree
  SqliteDialect.java / PostgresDialect.java
  RetentionService.java         Prunes telemetry and delivery history past the window

java-analytics/src/test/java/com/main/
  AnalyticsEngineIntegrationTest.java   Real server on an ephemeral port, temp database
  RepositoryTest.java           Persistence, dedup, alert lifecycle
  AlertEngineTest.java          Threshold, window, and repeated-error rules
  NotificationServiceTest.java  Delivery, cooldown, and retry against a local sink
  EventValidationTest.java      Validation and query-parameter parsing
  ObservabilityTest.java        Log shape and metric rendering
  ContractTest.java             Shared JSON contract, against testdata/
  PostgresBackendTest.java      The storage layer against a real PostgreSQL; skipped without one
  RetentionServiceTest.java     Prune boundaries and failure handling
java-analytics/Dockerfile       Shaded jar on a JRE, database on a volume

loadtest/main.go                Throughput and latency harness (its own module, stdlib only)
testdata/event-contract.json    One canonical event, read by both test suites
docker-compose.yml              Collector, analytics, dashboard
.github/workflows/ci.yml        Go job, Java job, image build job

dashboard/{index.html,app.js,styles.css}
```

### The JSON contract (do not break)

Go → Java `POST /receive`, header `X-EventWatch-Key`, `Content-Type: application/json`:

```json
{ "event_id": "...", "correlation_id": "...", "level": "ERROR", "msg": "...",
  "timestamp": "2026-09-04T18:46:00Z", "cpu_usage": 88.4, "ram_usage": 12.1 }
```

`correlation_id` is optional and carried in the payload so a queued event keeps it across a retry;
the live request also sends it as the `X-Correlation-ID` header.
Rules: `level` ∈ INFO|WARN|ERROR|CRITICAL; `msg` 1–1000 chars; `event_id` 1–128 chars and unique
(partial unique index makes retries idempotent); usages are finite numbers 0–100; body ≤ 64 KiB.
Changes to this schema must stay backward compatible — additive fields only, never renames.

### HTTP surface

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| POST | `/receive` (8080) | key | Ingest; rate limited 100/min/IP |
| GET | `/health` (8080, 8082) | none | 8080 also probes SQLite |
| GET | `/metrics` (8080, 8082) | none | Prometheus text format |
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
- **Logging:** never `System.out.println` or `fmt.Printf` for a log line — use `StructuredLogger`
  (Java) or `logInfo/logWarn/logError` (Go). Reserved field names are `timestamp`, `level`,
  `service`, and `message`; an event's own level goes in `event_level`. Pass a `correlation_id`
  field wherever one is in scope.
- **Tests:** every behaviour change needs a test. Java tests live in `com.main` so they can reach
  package-private helpers; use `@TempDir` with `TestSupport.databaseUrl` rather than a shared file.
  Go tests use `withCollector` to swap the package globals and restore them on cleanup. A change to
  the Go/Java JSON contract must update `testdata/event-contract.json`, which both suites assert on.
- **Configuration over constants:** ports, database path, and the shutdown grace are all in
  `EngineConfiguration`. Anything a container or a test needs to vary belongs there, not in a
  `static final`.
- **Metrics:** add counters to `Metrics.java` / `metrics.go` rather than inventing ad-hoc counters.
  Label values must come from a fixed, bounded set — never a message, an event id, or a raw path.
- **Secrets:** `.env` is gitignored and must stay that way. Never commit a real key; never print the
  key in logs or responses.
- **Commits:** phase-scoped, imperative, one phase per commit — matching existing history
  ("Implemented Alerts and its management through phase 6").

---

## 5. Current state — read before starting work

**Phases 1–11 are complete**, with two Phase 11 items deliberately deferred (section 11).

- `cd java-analytics && mvn verify` → 106 tests, BUILD SUCCESS
- `cd go-collector && go vet ./... && go test ./...` → 27 tests, pass
- `cd loadtest && go vet ./... && go build ./...` → clean
- `docker compose up --build` → all services healthy

Storage is now pluggable: `Database.open` picks SQLite or PostgreSQL from the JDBC URL, both behind
`ConnectionProvider` and `SqlDialect`, both pooled, and the same repository code runs on either.
SQLite stays the default. `RetentionService` prunes history past `RETENTION_DAYS`. The rate limit
and pool size became configuration.

**The measurement that matters** (`loadtest/`, 3000 events, concurrency 16, limit raised): SQLite
with a connection per query managed 60 events/s at a 260 ms p50. PostgreSQL pooled managed 164.
SQLite pooled and in WAL mode managed **522 at a 28 ms p50** — three times PostgreSQL over a local
socket. SQLite was never the constraint; connection-per-query and an fsync per commit were. Do not
migrate for throughput; migrate for several collectors sharing one store, or for retention beyond
one disk.

B1–B10 in section 10 are all fixed.

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

## 7. Phase 9 as built — observability

- **Logs.** `StructuredLogger` (Java) and `logging.go` (Go) emit one JSON object per line.
  Reserved keys — `timestamp`, `level`, `service`, `message` — are written *after* caller fields so
  a caller can never overwrite them. That ordering exists because the first cut let an event's
  `level` field clobber the log level; the event's level is now `event_level`.
- **Correlation IDs.** The collector honours an inbound `X-Correlation-ID` and generates one
  otherwise. It travels in the request header *and* the `correlation_id` payload field, so a queued
  event keeps its ID through a retry — the header would be lost. Java prefers the header, falls back
  to the payload, holds it in a `ThreadLocal`, and echoes it in the response header and body.
- **Metrics.** Hand-rolled Prometheus text format on both sides — no client library, consistent with
  the no-framework constraint and the 128 MB heap. Java counts HTTP responses centrally in
  `recordResponse`, keyed by the registered context path, so the label set stays bounded no matter
  what a client requests.
- **The terminal report** prints only under `LOG_FORMAT=text`. Under `json` the same numbers are
  emitted as a `telemetry snapshot` log line, because ASCII art in stdout breaks a log shipper.
- **Both `/metrics` endpoints are unauthenticated**, matching `/health`, because scrapers rarely
  send custom headers. They expose counts, not content — but restrict them at the network layer
  before either service leaves localhost.

## 8. Phase 10 as built — tests and delivery

**Layout.** Java tests sit in `com.main` so they can exercise package-private helpers
(`validateEvent`, `boundedInteger`, `queryParameters`) without a public API just for testing.
Every test that touches SQLite takes a `@TempDir` database through `TestSupport.databaseUrl`, which
also converts Windows separators — SQLite needs forward slashes.

**The integration test** starts the real `HttpServer` on port 0 and drives it with `HttpClient`
over loopback. It covers auth, content type, size, malformed JSON, validation, rate limiting,
the query API and its filter validation, the full alert lifecycle, notification history, metrics,
and restart recovery against the same database file.

**Speed matters.** The suite first took 158 seconds because `stop()` blocked for a flat five
seconds per test. The shutdown grace is now `SHUTDOWN_GRACE_SECONDS` (5 in production,
0 in `forTesting`), and the class runs in under five seconds. If a suite suddenly slows down, look
for a fixed wait before assuming the work itself got slower.

**The shared contract.** `testdata/event-contract.json` holds one canonical event. The Go suite
asserts its marshalled payload has exactly those keys and values; the Java suite asserts the same
file passes `validateEvent`, that removing any required field fails, and that the optional
`correlation_id` may be absent. Change the contract and whichever side was not updated fails.

**Containers.** Both Dockerfiles are multi-stage, run as a non-root user, pin their base image, and
declare a health check. The analytics image is a shaded jar on a JRE; the collector is a static
`CGO_ENABLED=0` binary on alpine. Each keeps its durable state on a volume — the database and the
pending queue — because losing either on a restart defeats the reliability work of Phase 5.

**A build gotcha worth remembering:** buildx caches tag resolution independently of `docker pull`,
so `golang:1.27-alpine` kept resolving to a stale 1.25 image and failed the `go >= 1.27` check in
`go.mod`. Base images are now pinned to a patch version.

**CI** runs three jobs: Go (gofmt check, vet, `-race` tests, govulncheck), Java (`mvn verify`, with
surefire reports uploaded on failure), and images (build both, validate the compose file). Images
are built but never pushed — publishing is a deployment decision. Dependabot covers Go modules,
Maven, both Dockerfiles, and the actions themselves.

## 9. Phase 11 as built — beyond SQLite

**Shape of the abstraction.** Not one interface per store with two implementations — that would
duplicate almost-identical JDBC three times. Instead `ConnectionProvider` supplies connections and
`SqlDialect` supplies the handful of fragments the two engines disagree on (DDL, and the
insert-ignoring-duplicates). The repositories are unchanged single implementations running portable
SQL, so there is one query path to maintain and the PostgreSQL suite asserts identical behaviour.

**Timestamps stay ISO-8601 text on both backends.** Native timestamp types would drag in timezone
semantics that differ between the engines; text keeps stored data, lexical ordering, and range
filters meaning exactly the same thing everywhere.

**Two things the PostgreSQL work taught, both caught by tests:**

- `ON CONFLICT (event_id) DO NOTHING` fails against a *partial* unique index — PostgreSQL needs the
  index predicate repeated: `ON CONFLICT (event_id) WHERE event_id IS NOT NULL DO NOTHING`.
  SQLite's `INSERT OR IGNORE` has no such requirement.
- A hardcoded `MAX_REQUESTS_PER_MINUTE = 100` made the first benchmark meaningless: only 100 of 2000
  events were admitted. It is now `RATE_LIMIT_PER_MINUTE`. A scaling phase cannot proceed past a
  fixed admission ceiling.

**Retention** is off by default (`RETENTION_DAYS=0`) and runs on the maintenance timer that already
sweeps rate windows. The cutoff is exclusive, delivery history is pruned alongside the events that
produced it, and a failed sweep is logged rather than thrown so the timer thread survives.

**What is deliberately NOT done, and when to revisit** — see section 11. Short version: the file
queue and the two-service shape are both still comfortably inside what the measurements justify.

## 10. Fixed defects and remaining quality work

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

**B10 — PostgreSQL rejected the deduplicating insert.** `ON CONFLICT (event_id) DO NOTHING` cannot
use a partial unique index as its arbiter without repeating the predicate. Caught by
`PostgresBackendTest`, which is why that suite exists rather than trusting the SQL to be portable.

**B9 — Go metric labels did not escape backslashes.** Found by the new
`TestMetricsEscapeLabelValues`: a heredoc had collapsed the replacement pair so
`strings.NewReplacer` mapped a backslash to itself. A label value containing one would have emitted
malformed Prometheus output. The Java side was already correct.

### Remaining quality work

- **Routing and static state.** `start()` is still a long run of inline lambdas, and the engine's
  collaborators live in static fields, so only one instance can run per JVM. Extracting handlers
  into their own classes with an injected context would fix both and let the integration tests run
  in parallel.
- **Timestamps.** Go now sends UTC `Z` values so lexical order matches chronological order, but rows
  written by older builds may carry a local offset. A one-off normalization pass would make range
  filters exact for that history.
- **Stale artifacts.** `java-analytics/alerts_history.json` is a Phase 2 leftover that is no longer
  written — delete it and the `readme.md` note referencing it.
- **Notification reminders are per-process.** `NotificationService` keeps a `reminderScheduledAt` map
  in memory alongside the SQL `lastDeliveredAt` lookup; a restart falls back to the SQL value, which
  is correct but means an in-flight reservation is lost. Fine for one instance, wrong for two.

## 11. Roadmap and deferred work

**Phase 9 — Observability.** Done except tracing (see section 7).

**OpenTelemetry tracing — an open decision, not an oversight.** It is the only remaining Phase 9
item, and it is the one that conflicts with the project's stated constraints: the Java SDK plus
exporters is roughly a dozen jars against a 128 MB heap, and it needs a collector process to receive
spans. The correlation ID already answers the question tracing was listed for here — "which log
lines belong to this event?" — across the Go→Java hop. Recommendation: add it only alongside Phase
11, when there are genuinely multiple services and hops worth measuring; adopt the W3C
`traceparent` header at that point rather than inventing a second ID. If it is wanted sooner, the
cheapest honest version is span timings emitted as structured log fields, with no SDK at all.

**Phase 10 — Testing and delivery.** Done (see section 8). One gap remains from the original
plan: there is no test that runs *both* services as processes and kills one mid-flight. Each side is
covered against a stand-in for the other, and the compose stack was exercised by hand — a scripted
version of that outage run would close it.

**Phase 11 — Scale beyond SQLite.** The storage half is done (section 9). Two items remain, both
deferred on purpose rather than forgotten:

- **A message broker in place of the file queue.** The durable file queue has no measured problem:
  it loses nothing across an outage (verified in containers in Phase 10), and ingestion sustains
  522 events/s before it is even involved. Kafka or NATS would add an operational dependency heavier
  than both services combined. Revisit when a single collector host cannot hold the backlog of a
  realistic outage, or when more than one analytics instance must consume the same stream — that is
  the real trigger, because the file queue is point-to-point.
- **Splitting ingestion, analytics, storage, and notification into separate services.** There is no
  component whose scaling needs differ enough to justify it yet; notification already runs on its
  own dispatcher thread and never blocks ingestion. Revisit when one part genuinely needs to scale
  independently, and expect to need the broker first.

The honest next step for scale is neither: it is the rate limit and the per-event work in the
ingestion path. Every event currently triggers alert evaluation plus a grouped error query. Batching
that, or evaluating alerts on a timer instead of per event, is a larger win than changing databases.

**Cross-cutting, currently unowned:** TLS between the services (the API key travels in plaintext over
localhost today), authentication for the public `/capture` endpoint, per-alert-rule configuration
instead of three global thresholds, and CORS origins moved to `.env`.

---

## 12. Definition of done for a release

- Events are authenticated, validated, persisted transactionally, deduplicated, and queryable.
- A temporary Java outage loses nothing and duplicates nothing.
- Alerts are configurable, deduplicated, actionable, and notify exactly once per state change.
- An operator can see trends, acknowledge, resolve, and read delivery history.
- Tests cover normal traffic, concurrent traffic, malformed input, restarts, and dependency failures.
- Configuration and deployment require no source changes.
- Logs, metrics, health checks, and traces make a failure diagnosable without a debugger.

EventWatch is a strong learning and portfolio system. It should not be described as a production
replacement for Datadog, CloudWatch, or a SIEM until TLS, retention, and the Phase 9–10 work land.
