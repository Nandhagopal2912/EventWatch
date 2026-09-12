# CLAUDE.md — EventWatch

Working guide for agents and contributors. `readme.md` is the user-facing doc; `agent.md` is the
original phase roadmap. **This file is the authority on current state, conventions, and what to do
next.** Update it whenever a phase closes or an item in the backlog is fixed.

---

## 1. What this project is

EventWatch is a self-hosted fleet monitor for 5–50 machines: one Go agent per host, one Java
analytics service, one dashboard. Built without frameworks so every mechanism stays visible.

**Purpose, settled in Phase 12:** a per-host agent, not a central ingress. The agent samples the
machine it runs on, so host metrics only mean anything when one agent runs per machine. Everything
downstream — per-host windows, per-host alert keys, the fleet listing — follows from that.

| Service | Language | Port | Role |
| --- | --- | --- | --- |
| `go-collector/` | Go 1.27, stdlib + gopsutil + godotenv | 8082 | One per machine: stable identity, version, host CPU/RAM, durable retry queue |
| `java-analytics/` | Java 17, Maven, `com.sun.net.httpserver` + Jackson + sqlite-jdbc/PostgreSQL + HikariCP | 8080 | Validates, persists, evaluates per-host rules, serves query and rules API |
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
- Open the dashboard at `http://localhost:3000`, which is what `CORS_ALLOWED_ORIGINS` allows by
  default. Serve it from anywhere else and that origin has to be added to the list.

Smoke test end to end:

```bash
curl.exe "http://localhost:8082/capture?level=ERROR&msg=Database%20transaction%20deadlock"
```

---

## 3. Code map

```text
go-collector/main.go            Handlers, forwardToJava retries, durable file queue
go-collector/identity.go        Stable per-agent host_id, persisted beside the queue
go-collector/security.go        Bind policy, capture auth, backend TLS trust
go-collector/watchdog.go        Delivery-health tracking, agent alerts
go-collector/logging.go         Structured JSON log lines, LOG_FORMAT switch
go-collector/metrics.go         Prometheus counters, queue-depth gauge, /metrics handler
go-collector/main_test.go       Event-ID uniqueness + queue capacity
go-collector/collector_test.go  Retries, capture handler, queue outcomes, metrics
go-collector/contract_test.go   Shared JSON contract, against testdata/
go-collector/identity_test.go   Identity generation, persistence, overrides
go-collector/security_test.go   Bind policy, capture auth, backend TLS trust
go-collector/watchdog_test.go   Stall detection, recovery, payload, metrics
go-collector/Dockerfile         Static binary on alpine, queue on a volume

java-analytics/src/main/java/com/main/
  AnalyticsEngine.java          One running engine: start/stop, route registration, timers
  EngineContext.java            Everything one engine owns, built once and passed to handlers
  ApiHandler.java               Preflight, method, key, and the 400/503 every route shares
  ReceiveHandler.java           Ingestion; the only route that writes telemetry
  HealthHandler.java            /health      MetricsHandler.java  /metrics
  EventsHandler.java            /events      SummaryHandler.java  /summary
  HostsHandler.java             /hosts and the per-machine drill-down
  AlertsHandler.java            /alerts      AlertDetailHandler.java  one alert and its actions
  RulesHandler.java             /rules and /rules/effective
  HttpSupport.java              Auth, CORS policy, response envelope, correlation id
  RequestParameters.java        Query-string parsing, shared by every read route
  EventValidation.java          The ingestion contract in one place
  LogEntry.java                 One telemetry event, as the repositories and rules see it
  RecentEvents.java             Per-host in-memory window and the stored-event total
  RateLimiter.java              One-minute window per client address, swept on the timer
  TelemetryReport.java          The running summary, as ASCII or as one log line
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
                                (tables, then legacy migrations, then indexes - see B16)
  ConnectionProvider.java       Where repositories get connections from
  SqlDialect.java               The few places SQLite and PostgreSQL disagree
  SqliteDialect.java / PostgresDialect.java
  RetentionService.java         Prunes telemetry and delivery history past the window
  TlsSupport.java               HTTPS listener from a keystore, TLS 1.2+
  WatchdogHeartbeat.java        Dead-man switch; silent when storage is broken
  AlertRule.java                One stored rule; scope '*' means fleet-wide
  AlertRuleRepository.java      alert_rules table, portable upsert on (rule_type, scope)
  AlertRules.java               Host → fleet → default precedence, validation, cache
  AgentSilenceMonitor.java      Raises agent-silent@{host}; AlertEngine resolves it

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
  EngineLifecycleTest.java      Pool released on stop and on every failed start
  SecurityTest.java             CORS, metrics auth, and a real TLS handshake (TestKeystore)
  AlertRulesTest.java           Rule precedence, validation, persistence
  RulesApiTest.java             The rules API changing which machines alert, end to end
  AlertRulesPostgresTest.java   Rule storage on a real PostgreSQL; skipped without one
  AgentSilenceMonitorTest.java  Silence thresholds, the forget window, the gauge
  FleetApiTest.java             Fleet listing, drill-down, silence raised and resolved
  MultipleEnginesTest.java      Two engines in one JVM: separate keys, metrics, windows
  WatchdogHeartbeatTest.java    Heartbeat payload, failures, and the deliberate silence
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
{ "event_id": "...", "correlation_id": "...", "host_id": "...", "hostname": "web-01",
  "agent_version": "0.15.0", "queue_depth": 0,
  "level": "ERROR", "msg": "...", "timestamp": "2026-09-04T18:46:00Z",
  "cpu_usage": 88.4, "ram_usage": 12.1 }
```

`correlation_id` is optional and carried in the payload so a queued event keeps it across a retry;
the live request also sends it as the `X-Correlation-ID` header. `host_id` and `hostname` are
optional too — an agent older than Phase 12 sends neither and its events are attributed to the host
`unknown` — but bounded to 128 characters when present, because `host_id` becomes part of an alert
key and a metric label. `agent_version` is bounded the same way and `queue_depth` must be a
whole number of zero or more; both are optional, so an older agent still reports.
Rules: `level` ∈ INFO|WARN|ERROR|CRITICAL; `msg` 1–1000 chars; `event_id` 1–128 chars and unique
(partial unique index makes retries idempotent); usages are finite numbers 0–100; body ≤ 64 KiB.
Changes to this schema must stay backward compatible — additive fields only, never renames.

### HTTP surface

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| POST | `/receive` (8080) | key | Ingest; rate limited per IP by `RATE_LIMIT_PER_MINUTE` (default 100) |
| GET | `/health` (8080, 8082) | none | 8080 also probes SQLite |
| GET | `/metrics` (8080, 8082) | none, or key when `METRICS_REQUIRE_KEY` | Prometheus text format |
| GET | `/capture?level=&msg=` (8082) | none on loopback, key when exposed | Agent ingress |
| GET | `/stress` (8082) | same as `/capture` | 500 events, 32 concurrent |
| GET | `/events?level=&host_id=&from=&to=&limit=&offset=` | key | limit ≤ 200, default 50 |
| GET | `/hosts?limit=` | key | Fleet listing: status, silent_seconds, version, queue depth |
| GET | `/hosts/{host_id}` | key | One machine: averages, level counts, its alerts and rules |
| GET | `/summary` | key | Totals, active alerts, 5-event averages |
| GET | `/alerts?status=&type=` | key | Active alerts |
| GET | `/alerts/{key}` | key | Single alert |
| POST | `/alerts/{key}/acknowledge`, `/resolve` | key | Operator actions; both notify |
| GET | `/alerts/{key}/notifications?limit=` | key | Delivery audit trail, limit ≤ 200 |
| GET | `/rules` | key | Stored rules plus the `.env` defaults beneath them |
| PUT | `/rules` | key | Upsert one rule: `rule_type`, optional `host_id`, `threshold`, `enabled` |
| DELETE | `/rules?rule_type=&host_id=` | key | Remove an override; omit `host_id` for the fleet rule |
| GET | `/rules/effective?host_id=` | key | What applies to one machine, with its source tier |

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
  `ALTER TABLE` in `SqlDialect.applyLegacyMigrations`, for both dialects.
- **Config:** everything tunable comes from `.env` with an in-code fallback, through
  `EngineConfiguration.fromDotenv` (Java) or `getEnv/getIntEnv` (Go). Never hardcode a new
  tunable. Add every new key to `.env.example`.
- **Logging:** never `System.out.println` or `fmt.Printf` for a log line — use `StructuredLogger`
  (Java) or `logInfo/logWarn/logError` (Go). Reserved field names are `timestamp`, `level`,
  `service`, and `message`; an event's own level goes in `event_level`. Pass a `correlation_id`
  field wherever one is in scope.
- **Tests:** every behaviour change needs a test. Java tests live in `com.main` so they can reach
  package-private helpers; use `@TempDir` with `TestSupport.databaseUrl` rather than a shared file.
  Go tests use `withCollector` to swap the package globals and restore them on cleanup. A change to
  the Go/Java JSON contract must update `testdata/event-contract.json`, which both suites assert on.
- **Test classes run in parallel** (`src/test/resources/junit-platform.properties`); methods inside
  one class stay sequential. A class that touches process-wide state must say so with
  `@ResourceLock` - `Resources.GLOBAL` for `System.out` or the logger, a named lock for a shared
  external server. Anything else must isolate itself with `@TempDir` and port 0.
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

**Phases 1–17 are complete.** Everything is green:

- `cd java-analytics && mvn verify` → 191 tests, BUILD SUCCESS (12 of them need
  `EVENTWATCH_TEST_POSTGRES_URL`; CI supplies a server, locally they skip). Verified against a real
  PostgreSQL 16 container with all 191 running.
- `cd go-collector && go vet ./... && go test ./...` → 53 tests, pass
- `cd loadtest && go vet ./... && go test ./...` → 4 tests, pass
- `docker compose up --build` → all services healthy

The phase roadmap (1–15) delivered the system; 16 onward is hardening for real use, planned in
section 17. Phase 17 rewrote the internals with no change to the HTTP surface: the routes are nine
handler classes over an `EngineContext`, and `AnalyticsEngine` is an instance rather than a page of
static fields, so several engines can run in one JVM. Test classes now run in parallel.

**Next is Phase 18, the operator session.** Serving the dashboard from the analytics service makes
it same-origin, which lets the API key move into an HttpOnly cookie and deletes the CORS
configuration and the separate static container along with it.

B1–B17 in section 16 are all fixed.

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

**The compose gotcha:** `analytics` needs `depends_on: postgres` for the profile, but a plain
`depends_on` would fail the default SQLite run where that container does not exist. `required: false`
is the resolution — the dependency applies when the profile is active and is ignored otherwise.

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

**What is deliberately NOT done, and when to revisit** — see section 17. Short version: the file
queue and the two-service shape are both still comfortably inside what the measurements justify.

## 10. Phase 12 as built — host identity

**Why it had to be more than a column.** Phase 11 made several agents writing to one store
possible; Phase 12 made them distinguishable. Adding `host_id` to the table alone would not have
been enough — the moving window and the alert keys were both global, so two machines would have
averaged together and fought over one `cpu-high` row. The fix is three-part: identity on the event,
`recentEventsByHost` instead of one deque, and `rule@host` alert keys.

**Agent identity** is generated once and persisted beside the durable queue, so a restart is not a
new machine. `HOST_ID` pins it explicitly (containers, config management); `HOSTNAME_OVERRIDE`
renames the reported hostname without changing the id. The id is written through a temp file and
renamed, like the queue, so a crash cannot leave half an identity.

**Backward compatibility.** Identity is optional in the contract. An agent older than Phase 12 sends
neither field and its events land under the host `unknown` rather than being rejected — the same
choice the durable queue forces, since queued files written by an old agent will arrive after an
upgrade. Both dialects migrate existing databases with guarded `ALTER TABLE`.

**Bounded on purpose.** `host_id` is capped at 128 characters because it becomes part of an alert
key (which appears in URLs) and could become a metric label. `recentEventsByHost` is an LRU capped
at `MAX_TRACKED_HOSTS`, so a misconfigured fleet sending random ids cannot grow memory without
bound — the same lesson as the rate-limit map in B4.

**Alert keys use `rule@host`.** They appear in URLs (`/alerts/cpu-high@web-01/acknowledge`), and `@`
survives a path segment without encoding while staying readable in a notification.

## 11. Phase 13 as built — agent security

**The bind policy is the load-bearing decision.** `resolveIngressSecurity` couples exposure to
authentication: loopback needs no key, anything else *requires* one, and the agent refuses to start
otherwise. That ordering matters — a key that defaults to optional gets left optional. The container
image sets `COLLECTOR_BIND=0.0.0.0` precisely because a published port is reachable, so Compose has
to supply `CAPTURE_API_KEY`.

**Capture auth uses constant-time comparison** and a length check, matching `isValidApiKey` on the
Java side. `/stress` is behind the same check — it was the one route that could flood the backend.

**TLS is opt-in, verification is not.** `TlsSupport` builds an `HttpsServer` from a PKCS12 keystore
and pins TLS 1.2+. The agent trusts a private CA through `BACKEND_CA_FILE` rather than skipping
verification; `BACKEND_TLS_SKIP_VERIFY` exists for a first run and logs a warning every time it is
used, so it cannot quietly become the deployment setting.

**Testing TLS needed a real certificate.** Java 17 has no public API for issuing one and
BouncyCastle is not worth a test fixture, so `TestKeystore` shells out to the `keytool` beside the
running JDK. The test performs a real handshake rather than asserting on configuration.

**CORS was a hardcoded origin** (`localhost:3000`) that made the dashboard undeployable anywhere
else. It is now a comma-separated allowlist; an empty list allows nothing.

## 12. Phase 14 as built — per-host alert rules

**Precedence is the whole design.** For each rule type the most specific stored rule wins: the
machine's own, then fleet-wide, then the `.env` value. The `.env` thresholds did not go away — they
became the bottom tier, so an install with no rules behaves exactly as before and an upgrade needs
no migration of intent. `GET /rules/effective?host_id=` reports the winning tier (`host`, `fleet`,
`default`) because "why did this fire?" is the first question an operator asks.

**Fleet-wide is the sentinel `'*'`, not NULL.** The table's key is `(rule_type, scope)`, and both
SQLite and PostgreSQL treat NULLs as distinct in a unique key — NULL would have allowed two
fleet-wide rules of one type. The sentinel never reaches the API: a fleet rule serializes with
`"host_id": null`, and `"*"` is refused as a host id.

**A disabled rule is a decision, not an absence.** A disabled host rule still beats an enabled
fleet rule, and the engine resolves an alert whose rule is now disabled on that machine's next
event, so nothing is left firing under a rule that no longer applies.

**Validation refuses rules that can never fire.** A `REPEATED_ERROR` threshold above the 5-event
window is rejected at the API rather than stored and silently dead. The same condition in `.env`
logs a startup warning.

**Rules are read on every event, so they are cached** in `AlertRules` and replaced wholesale on
each write. That is correct for one analytics instance; a second instance would need a refresh
interval or change notification — the same limitation as notification reminders in section 16.

**A wiring bug the tests caught before commit:** the CORS preflight still advertised
`GET, POST, OPTIONS`, because the edit replaced the first of two identical strings — in the
`/alerts/` handler — and missed `handleCorsPreflight`. The dashboard's cross-origin PUT and DELETE
would have been blocked. `RulesApiTest.thePreflightAllowsTheMethodsTheEditorUses` caught it; both
places now carry one API-wide policy.

**Pre-existing behaviour, now documented:** repeated-error alerts never auto-resolve. CPU and RAM
alerts resolve when the average recovers; an error has no equivalent "recovered" signal, so those
alerts wait for an operator.

## 13. Phase 15 as built — fleet operations

**Silence is a rule type, not a special case.** `AGENT_SILENT` joins the three Phase 14 types, so a
machine gets its own window through the same host → fleet → default precedence, and a laptop that
sleeps overnight is one stored rule rather than a code path. Disabling it resolves the alert it
raised, exactly like the other types.

**The forget window is what makes it usable.** `AGENT_SILENCE_FORGET_HOURS` (a week) stops machines
that were decommissioned long ago from alerting forever. Without it, the first sweep after an
upgrade would raise an alert for every machine that ever reported — the feature would be turned off
within a day, which is the same as not having it.

**Raise on a timer, resolve on an event.** The sweep only raises; `AlertEngine.evaluate` resolves
`agent-silent@{host}` because a machine that just reported is by definition not silent. That keeps
recovery immediate rather than waiting for the next sweep, and it reuses the transition and
notification path every other alert already goes through.

**Latest, not aggregate.** The fleet listing reads each machine's newest `agent_version` and
`queue_depth` through a correlated subquery, because `MAX(agent_version)` would report the highest
string rather than the current one — "0.9.0" sorts above "0.15.0". The listing is bounded by
`MAX_HOSTS_LISTED`, so the repeated lookup stays cheap.

**Queue depth is the agent's own backlog**, sampled at capture time. A rising value means that agent
is holding events the analytics service has not accepted — the early warning that the pipeline is
degrading before events are actually lost.

## 14. Phase 16 as built — watchdog

**Two halves, because there are two ways to go dark.** The analytics service cannot report its own
death, so it tells an outside endpoint it is alive and the absence of that is the alarm. The agent
cannot be told by analytics that analytics is down, so it judges for itself. Neither half depends on
the other, which is the point: a single mechanism covering both would share the failure it is meant
to detect.

**The heartbeat is skipped when the database is unreachable.** A ping that keeps arriving from a
service that cannot store anything is worse than no ping, because it actively asserts health. The
check runs first and a failure records `skipped` and sends nothing, so the dead-man switch fires.

**A stall needs a failure, not merely silence.** An agent with no traffic sends nothing and fails
nothing; treating that as an outage would alert on every idle machine. The condition is "the most
recent attempt failed *and* nothing has been delivered for `DELIVERY_STALL_MINUTES`".

**Tracking the last attempt is a boolean, not a timestamp comparison.** The first implementation
compared `lastDeliveryFail.After(lastDeliveryOK)`, which the tests caught immediately on Windows:
the two calls land on the same clock tick, `After` is false, and no stall is ever detected. Asking
"did the last attempt fail?" is both clock-independent and a plainer statement of the intent.

**A permanent 4xx is excluded deliberately.** The service answered and refused that one event, which
says nothing about reachability — counting it would turn a storm of malformed events into a fake
outage. The three outcomes the watchdog reads are `delivered`, `queued`, and `failed`.

**The signal is read where it is already classified.** `recordForward` knows the outcome, so the
watchdog hooks in there rather than at six call sites that could drift apart.

## 15. Phase 17 as built — internal structure

No user-visible change. `AnalyticsEngine` went from 1097 lines to 213, and `start()` from 615 to 56.

**The static fields were the real problem, not the length.** Twenty mutable statics meant one engine
per JVM, and the `synchronized` window methods locked the *class* object — a process-wide lock
guarding one engine's five-event window. `EngineContext` holds that state now, and `AnalyticsEngine`
is an instance: `start()` returns the engine and `engine.stop()` replaces `stop(server)`. Passing
the server back in to stop it was the old API admitting its state lived elsewhere.

**`ApiHandler` holds what every route repeated.** The preflight, the method check with its `Allow`
header, the API key, and the two failures every route turns into the same status — a bad parameter
into 400, unreachable storage into 503. Each handler now contains only what its route does. The
per-route differences that had to survive are constructor arguments: the allowed methods, the 503
message, and whether the failure counts as a database failure (only `/rules` did).

**`/alerts/` no longer carries its own copy of the CORS policy.** That duplicate string is exactly
how the phase 14 preflight bug happened; there is now one `ALLOWED_METHODS` in `HttpSupport`.

**The one deliberate behaviour change is an added `Allow` header.** Only `/receive` and `/rules`
sent one on a 405 before; the shared base sends it everywhere, which is what RFC 7231 asks for.
Nothing else about the HTTP surface moved, and that was the acceptance criterion for the phase.

**`MultipleEnginesTest` is the proof.** Two engines, two databases, one JVM. Every assertion in it
fails against the old shape — most sharply the one about keys, because `start()` assigned a static
`apiKey`, so starting a second engine would have stopped the first from accepting its own agents.

**Test classes run in parallel now: 30s → 20s.** Three classes opt out through `@ResourceLock`,
because they touch state the refactor does not make per-instance: `ObservabilityTest` replaces
`System.out` and reconfigures the process-wide logger, and the two PostgreSQL suites drop tables on
one real server. Worth knowing: under parallel classes Surefire files some results in the wrong
per-class XML, though every `<testcase>` still carries its own correct `classname`. Fork-level
parallelism keeps the grouping exact but only reached 25s, so it was not worth the memory.

**What stays static, and why.** `StructuredLogger` and the log format are properties of the process
rather than of one engine, so they stay global — and that is precisely why `ObservabilityTest` needs
its lock.

## 16. Fixed defects and remaining quality work

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

**B11 — stop() closed the connection pool before draining the request executor.** Work still
running lost its database mid-flight. The pool is now released after `awaitTermination`. Note the
window is narrower than it first looks: `server.stop(grace)` blocks for the full grace and drains
its own exchanges, so an HTTP request only observes this if its handler outlives the grace. The
regression test asserts the ordering on the executor directly for that reason — and was checked to
fail against the old ordering before being kept.

**B12 — a failed start() leaked the connection pool.** `Database.open` allocated a live pool (and
its housekeeper thread) before the API-key check, and nothing closed it when start() threw. The key
is now validated first, every failure path calls `releaseDatabase()`, and an unreachable backend is
wrapped into a readable `IOException` instead of an unchecked pool error.

**B13 — a non-positive RETENTION_SWEEP_MINUTES crashed startup.** `scheduleWithFixedDelay` rejects a
delay below one. `EngineConfiguration` now clamps `retentionSweepMinutes`, `databasePoolSize`, and
`rateLimitPerMinute` in its compact constructor, so the rule holds however the record is built —
not just through `fromDotenv`.

**B14 — RetentionService.sweep caught only SQLException.** Anything else escaped into the scheduled
task, which cancels it silently for the life of the process. It now catches `Exception`.

**B15 — the load harness did not drain response bodies.** A single 512-byte `Read` leaves the
connection unreusable, so the harness timed TCP handshakes; and `percentile` truncated instead of
using nearest rank, biasing p95/p99 downward. Both fixed. (The published Phase 11 numbers are
unaffected: at 3000 samples both percentile formulas select the same index.)

**B16 — upgrading from an older database crashed at startup.** `initializeSchema` ran every
statement in `schemaStatements()` and *then* the guarded `ALTER TABLE` migrations — but that list
included `CREATE INDEX ... ON telemetry_events(host_id, event_timestamp)`, a column an old database
only gains during the migration. Starting against a real pre-phase-12 file died with
`no such column: host_id`; against a pre-phase-5 one, `no such column: event_id`. Every test used a
fresh database, where those columns are part of `CREATE TABLE`, so the ordering never showed. The
dialects now expose `tableStatements()` and `indexStatements()` separately and the migrations run
between them. Found by starting the service against the actual local `events.db`, which is the only
reason it surfaced at all; `EngineLifecycleTest.aDatabaseFromAnOlderBuildIsUpgradedOnStartup` builds
that old schema and was checked to fail against the previous ordering.

**B17 — the collector container could never become healthy.** Both Dockerfiles probed with
`wget --quiet --spider`, which sends a HEAD request, and `/health` answers HEAD with 405. The
analytics image hid it: GNU wget retries with GET after that, so its check passed. The collector is
on alpine, whose busybox wget does not retry, so the container sat `unhealthy` forever — enough to
break `depends_on: service_healthy` or make an orchestrator restart it in a loop. Both checks now
issue a plain GET (`--output-document=/dev/null`), which behaves the same under either wget. Found
by reading `docker compose ps` rather than trusting that the stack was up.

### Remaining quality work

- **Timestamps.** Go now sends UTC `Z` values so lexical order matches chronological order, but rows
  written by older builds may carry a local offset. A one-off normalization pass would make range
  filters exact for that history.
- **Notification reminders are per-process.** `NotificationService` keeps a `reminderScheduledAt` map
  in memory alongside the SQL `lastDeliveredAt` lookup; a restart falls back to the SQL value, which
  is correct but means an in-flight reservation is lost. Fine for one instance, wrong for two.

## 17. Roadmap and deferred work

**The phase roadmap (1–15) is complete.** What follows is hardening for real use rather than new
capability. Phases 16–20 are planned, one commit each, in this order.

**Phase 16 — Watchdog.** Done (see section 14). The monitor cannot be the only thing that knows it is alive. Two
independent halves, neither needing another service:

- **A dead-man's switch on the analytics side:** a heartbeat POSTed to `WATCHDOG_URL` on a timer.
  When the host dies the heartbeat stops and the external endpoint alerts. The ping is skipped while
  the database is unreachable — a heartbeat that keeps arriving from a service that cannot store
  anything would be worse than none.
- **A delivery-stall alert on the agent side:** the agent is the part still running when analytics
  is unreachable, so it is the only part that can report it. It already knows — its queue is
  growing. An optional webhook fires when nothing has been delivered for `DELIVERY_STALL_MINUTES`
  *and* the most recent attempt failed; the second condition matters, or an idle agent with no
  traffic would look broken.

Goes first because it is the only item where the current state can fail silently, and because it
barely touches the routing the next phase rewrites.

**Phase 17 — Internal structure.** Done (see section 15). No user-visible change; enabling work. Extract the route handlers
out of `start()` into classes with an injected context and drop the static collaborator fields.
Unlocks parallel test execution and stops phases 18 and 19 from adding several hundred lines to a
class that is already a thousand. Doing it before those two, rather than after, is the whole point.

**Phase 18 — Operator session.** Serve the dashboard from the analytics service so it is
same-origin, then exchange the API key for an HttpOnly `SameSite=Strict` cookie at `POST /session`,
accepting cookie or header. The key leaves page memory, the CORS configuration becomes unnecessary,
and the separate static server and its Compose container disappear. A feature that deletes moving
parts is usually the right shape.

**Phase 19 — Per-agent credentials.** One shared key for a fleet cannot be rotated or revoked per
machine; one leaked agent compromises every host. An `agents` table with hashed tokens, `POST
/agents` to mint and `DELETE /agents/{id}` to revoke, and `last_used` for spotting dead credentials.
The shared key keeps working behind a deprecation switch during migration — the same additive
discipline the event contract follows.

**Phase 20 — Cleanup and closing gaps.** The scripted two-process outage test that Phase 10 left
open, the one-off timestamp normalisation for rows written by pre-Phase-9 builds, and removing
`/stress` from the shipped binary now that `loadtest/` covers it properly.

**Agreed for discussion after Phase 20 — more host samples.** The agent samples only CPU and RAM.
Disk usage, network, process liveness, or an application pushing its own custom metric would each
widen what the system can alert on, and Phase 14's rule machinery already accepts new rule types
without structural change. This is feature growth rather than hardening, so it is a separate
conversation once the five phases above are done.

### Deferred on evidence, not forgotten

**A message broker in place of the file queue.** The durable file queue has no measured problem: it
loses nothing across an outage (verified in containers in Phase 10), and ingestion sustains
522 events/s before it is even involved. Kafka or NATS would add an operational dependency heavier
than both services combined. Revisit when a single agent host cannot hold the backlog of a realistic
outage, or when more than one analytics instance must consume the same stream — that is the real
trigger, because the file queue is point-to-point.

**Splitting ingestion, analytics, storage, and notification into separate services.** No component
has scaling needs different enough to justify it; notification already runs on its own dispatcher
thread and never blocks ingestion. Revisit when one part genuinely needs to scale independently, and
expect to need the broker first.

**Running more than one analytics instance.** Alert rules are cached per process and notification
cooldown reservations are held in memory, so a second instance would need cache invalidation and
shared reservations. This is coupled to the two items above rather than separate from them: there is
no reason to run two instances until there is a broker in front of them.

**OpenTelemetry tracing.** The Java SDK plus exporters is roughly a dozen jars against a 128 MB
heap, and it needs a collector process to receive spans. The correlation ID already answers the
question tracing was listed for — "which log lines belong to this event?" — across the one hop that
exists. Adopt the W3C `traceparent` header if and when there are genuinely multiple hops worth
measuring. The cheapest honest version in the meantime is span timings emitted as structured log
fields, with no SDK at all.

**The honest next step for throughput,** if it is ever needed, is neither storage nor messaging: it
is the per-event work in the ingestion path. Every event triggers alert evaluation plus a grouped
error query. Batching that, or evaluating alerts on a timer rather than per event, is a larger win
than changing databases.

---

## 18. Definition of done for a release

- Events are authenticated, validated, persisted transactionally, deduplicated, and queryable.
- A temporary Java outage loses nothing and duplicates nothing.
- Alerts are configurable, deduplicated, actionable, and notify exactly once per state change.
- An operator can see trends, acknowledge, resolve, and read delivery history.
- Tests cover normal traffic, concurrent traffic, malformed input, restarts, and dependency failures.
- Configuration and deployment require no source changes.
- Logs, metrics, health checks, and traces make a failure diagnosable without a debugger.

EventWatch is a strong learning and portfolio system. It should not be described as a production
replacement for Datadog, CloudWatch, or a SIEM until TLS, retention, and the Phase 9–10 work land.
