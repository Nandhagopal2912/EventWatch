# EventWatch

EventWatch is a self-hosted fleet monitor for a small number of machines. A lightweight Go agent runs on each host, captures application events together with that machine's CPU and RAM usage, and forwards them to a Java analytics service that keeps per-host history, evaluates per-host alert rules, and notifies an operator.

The current implementation completes Phases 1–15. Future improvements are documented in `CLAUDE.md`.

## Project Phase Status

| Phase        | Status           | Focus                            | Scope / outcome                                                                                                                                                                                            |
| ------------ | ---------------- | -------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Phase 1**  | **✅ Completed** | JSON event pipeline              | Go-to-Java HTTP forwarding, JSON parsing, error filtering, grouped error counts, and a 500-event stress route.                                                                                             |
| **Phase 2**  | **✅ Completed** | Host telemetry and analytics     | Live CPU/RAM collection, timestamps, five-event moving averages, and the intermediate JSON archive.                                                                                                        |
| **Phase 3**  | **✅ Completed** | Persistence and hardening        | SQLite storage, transactional inserts, startup recovery, malformed JSON handling, request-size limits, and correct HTTP error responses.                                                                   |
| **Phase 4**  | **✅ Completed** | Security and input protection    | Dotenv configuration, API-key authentication, JSON validation, content-type checks, request limits, per-client rate limiting, and bounded Go retries.                                                      |
| **Phase 5**  | **✅ Completed** | Reliability and failure handling | Go health endpoint, atomic durable JSON queue, recovery worker, queue limits, retry configuration, event-ID deduplication, Java health endpoint, bounded request execution, and consistent JSON responses. |
| **Phase 6**  | **✅ Completed** | Analytics and alert rules        | Configurable CPU/RAM thresholds, moving-window detection, repeated-error alerts, SQLite alert state, deduplication, and the active-alerts endpoint.                                                        |
| **Phase 7**  | **✅ Completed** | Query API and dashboard          | Bounded event queries, summaries, alert lookup/filtering, a separate local dashboard, and operator alert workflows.                                                                                        |
| **Phase 8**  | **✅ Completed** | Notifications                    | Webhook delivery on alert state changes, cooldown reminders, bounded retries, and a durable delivery-attempt audit trail.                                                  |
| **Phase 9**  | **✅ Completed** | Observability                    | Structured JSON logs in both services, correlation IDs carried end to end, and Prometheus metrics for both services.                                          |
| **Phase 10** | **✅ Completed** | Testing and delivery             | Unit, integration, failure and contract tests on both services, plus Dockerfiles, Docker Compose, and a CI pipeline.                                |
| **Phase 11** | **✅ Completed** | Scaling beyond SQLite            | Pluggable storage with a PostgreSQL backend, connection pooling, a retention policy, and a load harness. Messaging and service splits remain deliberately deferred. |
| **Phase 12** | **✅ Completed** | Host identity                    | A stable per-agent identity on every event, per-host moving windows and alert keys, host filters, and a fleet listing.                     |
| **Phase 13** | **✅ Completed** | Agent security                   | Loopback-by-default agent binding, mandatory authentication when exposed, TLS between agent and analytics, configurable CORS origins, and optional metrics auth. |
| **Phase 14** | **✅ Completed** | Per-host alert rules             | Thresholds stored as rules, resolved host → fleet → `.env` default, with a rules API, an effective-rule view, and a dashboard editor. |
| **Phase 15** | **✅ Completed** | Fleet operations                 | Silence detection as an alert rule, a fleet listing with per-machine drill-down, and agents reporting their version and queue depth. |

**Current state:** EventWatch runs a fleet. An agent on each machine reports that machine's events
and resource usage, the analytics service keeps per-host history and raises per-host alerts against thresholds you can set per machine, and notices when a machine stops reporting at all, state
changes reach a webhook, and the dashboard shows every machine with filters and delivery history.

**Deployable on a trusted network.** The agent binds to loopback by default and refuses to bind
anywhere else without a key; traffic to the analytics service can run over TLS with certificate
verification. See [Securing a deployment](#securing-a-deployment).

## Project Structure

```text
go-collector/                   Go agent, one per machine
	main.go                        Handlers, forwarding, durable queue
	identity.go                    Stable host_id, persisted beside the queue
	security.go                    Bind policy, capture auth, backend TLS trust
	logging.go                     Structured JSON log lines
	metrics.go                     Prometheus counters and /metrics
	Dockerfile
	go.mod
java-analytics/                 Maven Java analytics service
	pom.xml
	Dockerfile
	.mvn/jvm.config                Automatic Maven JVM memory settings
	src/main/java/com/main/
		AnalyticsEngine.java           HTTP routing, validation, ingestion
		EngineConfiguration.java       Every runtime setting
		Database.java                  Backend selection, pooling, schema
		ConnectionProvider.java        Where repositories get connections
		SqlDialect.java                Where SQLite and PostgreSQL differ
		SqliteDialect.java
		PostgresDialect.java
		EventRepository.java           Telemetry queries and the fleet listing
		AlertEngine.java               Per-host threshold and error rules
		AlertRecord.java
		AlertRepository.java
		AlertStatus.java
		AlertTransition.java
		AlertRule.java                 One stored rule
		AlertRuleRepository.java       Rule storage, portable SQL
		AlertRules.java                Host, fleet, default precedence and validation
		AgentSilenceMonitor.java       Raises an alert for a machine that stopped reporting
		QueryService.java              JSON shaping for the query API
		NotificationService.java       Webhook dispatch, cooldown, retries
		NotificationRecord.java
		NotificationRepository.java
		RetentionService.java          Prunes history past the window
		Metrics.java                   Prometheus counters and gauges
		StructuredLogger.java          One JSON object per log line
	src/test/java/com/main/            180 tests, including a live PostgreSQL suite
dashboard/                      Local browser dashboard
	index.html
	app.js
	styles.css
loadtest/                       Throughput and latency harness
	main.go
	go.mod
testdata/event-contract.json    Cross-language JSON contract fixture
.github/workflows/ci.yml        Build, test, scan, and image pipeline
.github/dependabot.yml          Weekly dependency updates
docker-compose.yml              Agent, analytics, dashboard, optional PostgreSQL
```

## Architecture

- **Go agent** (`go-collector/`): one per machine. Accepts `GET` requests at
  `http://localhost:8082/capture`, samples that machine's CPU/RAM usage, stamps every event with its
  host identity, and forwards to the Java service.
- **Host identity:** each agent establishes a stable `host_id` at startup and stores it beside its
  queue, so a restart is not mistaken for a new machine. Set `HOST_ID` to pin it explicitly;
  `HOSTNAME_OVERRIDE` renames the reported hostname.
- **Java analytics service** (`java-analytics/`): accepts `POST` requests at
  `http://localhost:8080/receive`, validates and persists telemetry, restores each machine's
  analytics window at startup, and evaluates alert rules per host.
- **Storage:** SQLite by default, PostgreSQL when `DATABASE_URL` names one. Both are pooled and
  behind the same repository code, so the API and alert behaviour are identical on either.
- **Reliability queue** (`go-collector/pending-events/`): stores events when the analytics service
  is unavailable and removes them only after a successful `2xx`. Event IDs prevent duplicate rows
  when retries occur. Permanent client failures move to `rejected-events/`.
- **Health checks:** `GET http://localhost:8082/health` reports agent availability and identity;
  `GET http://localhost:8080/health` reports analytics availability and database reachability.
  Neither requires authentication.
- **Query API:** `GET /events`, `GET /summary`, `GET /hosts`, `GET /alerts`, and
  `GET /alerts/{alert_key}` return bounded JSON. All of them require the API key.
- **Alert lifecycle:** `POST /alerts/{alert_key}/acknowledge` moves an `OPEN` alert to
  `ACKNOWLEDGED`; `POST /alerts/{alert_key}/resolve` moves it to `RESOLVED` and removes it from the
  active list. Further occurrences never undo an acknowledgement.
- **Fleet awareness:** every event carries `host_id` and `hostname`. Moving averages, thresholds,
  and alert keys are scoped per machine — `cpu-high@web-01` is a different alert from
  `cpu-high@db-01` — so one busy host cannot drag an idle one into an alert, and acknowledging one
  machine does not silence another. `GET /hosts` lists every machine with its event count and
  last-seen time; `GET /events?host_id=` filters to one.
- **Notifications:** alert state changes (opened, reopened, acknowledged, resolved) are POSTed as
  versioned JSON to `NOTIFICATION_WEBHOOK_URL`. Repeat occurrences are suppressed until
  `NOTIFICATION_REMINDER_SECONDS` passes. Every attempt is recorded and readable at
  `GET /alerts/{alert_key}/notifications`.
- **Observability:** both services emit one JSON object per log line, expose `GET /metrics` in
  Prometheus text format, and carry an `X-Correlation-ID` from the agent through to the analytics
  response.
- **Fleet view:** `GET /hosts` lists every machine with its status, last-seen time, agent
  version, and pending-queue depth. `GET /hosts/{host_id}` is the per-machine view: averages,
  level counts, its active alerts, and the rules it is judged by.
- **Silence detection:** a background sweep raises `agent-silent@{host}` for a machine that has
  stopped reporting, and the machine's next event resolves it. Nothing else notices silence,
  because every other rule needs an event to evaluate.
- **Alert rules:** thresholds are rules in the database, resolved per machine — that host's
  rule, then a fleet-wide rule, then the `.env` default. `GET /rules`, `PUT /rules`,
  `DELETE /rules`, and `GET /rules/effective?host_id=` manage and explain them.
- **Retention:** `RETENTION_DAYS` prunes telemetry and delivery history older than the window.
  Disabled by default.
- **Dashboard:** `dashboard/index.html` shows the fleet with each machine's status and a
  drill-down, summaries, recent events filtered by machine, active alerts, per-alert delivery
  history, and an alert rules editor. It reads the API key in the browser,
  escapes all event text before rendering it, and never touches the database directly.
- **Configuration:** `HTTP_PORT`, `COLLECTOR_PORT`, `DATABASE_PATH`, and `DATABASE_URL` set ports
  and storage, so neither service needs a source change to be deployed or containerized.

## End-to-End Flow

```text
Client request
	↓
Agent captures the event and its own machine's CPU/RAM
	↓
Agent stamps event_id, correlation_id, host_id and hostname
	↓
Authenticated HTTP request to the analytics service
	↓
Analytics validates API key, content type, size, fields, and value ranges
	↓
Analytics commits telemetry to the configured database
	↓
Alert engine evaluates the latest five events for that machine only
	↓
Database stores or updates the per-host alert state
	↓
Lifecycle changes are handed to the notification dispatcher
	↓
Webhook delivery attempt recorded in SQLite
	↓
JSON response and terminal dashboard output
```

If the analytics service is temporarily unavailable, the agent retries and writes the same event to `pending-events/`. A background worker retries those files later. A queued file is deleted only after Java returns `2xx`; permanent client errors move to `rejected-events/`.

## Feature Contributions

| Feature                     | What it does                                                                                      | Why it matters                                                                |
| --------------------------- | ------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| **Per-machine agent**       | One agent per host, with a stable identity that survives restarts.                                | Makes a fleet distinguishable; host metrics only mean anything per machine.   |
| **Host telemetry**          | Collects the agent machine's CPU and RAM usage with each event.                                   | Connects application errors to the health of the machine producing them.      |
| **Shared JSON contract**    | One canonical event shape, asserted by both test suites against `testdata/`.                      | A contract change fails on whichever side was not updated.                    |
| **API-key authentication**  | Requires the configured `X-EventWatch-Key` header for Java ingestion.                             | Prevents unauthenticated clients from submitting telemetry.                   |
| **Input validation**        | Rejects invalid JSON, fields, content types, ranges, oversized requests, and unsupported methods. | Prevents malformed data from reaching analytics or SQLite.                    |
| **Pluggable storage**       | SQLite by default, PostgreSQL behind the same repository code, both pooled.                       | Runs with no external services, and scales to several agents when needed.     |
| **Retention policy**        | Prunes telemetry and delivery history past a configurable window.                                 | Stops the database being the first thing to fail on a long-running install.   |
| **Durable JSON queue**      | Saves events when Java is unavailable and retries them later.                                     | Prevents temporary backend outages from silently losing events.               |
| **Event-ID deduplication**  | Uses a unique event ID in SQLite.                                                                 | Makes retries safe when Java stores an event but the response is lost.        |
| **Bounded processing**      | Limits Java workers and queued requests.                                                          | Applies backpressure and prevents traffic spikes from exhausting memory.      |
| **Moving-window analytics** | Evaluates the latest five events *per machine* for CPU and RAM trends.                            | Detects sustained pressure without averaging unrelated machines together.     |
| **Alert engine**            | Creates and updates `HIGH_CPU`, `HIGH_RAM`, and `REPEATED_ERROR` alerts, keyed per host.           | Turns raw telemetry into incidents an operator can act on machine by machine. |
| **Per-host alert rules**    | Stores thresholds per machine and fleet-wide, over the `.env` defaults.                            | A build box at 90% CPU is healthy; a database at 90% is not.                  |
| **Silence detection**       | Alerts when a machine stops reporting, and resolves when it returns.                              | A dead agent is invisible to every rule that needs an event.                  |
| **Agent self-reporting**    | Each event carries the agent version and its pending-queue depth.                                  | Confirms a fleet upgrade landed, and shows a backlog building up.             |
| **Alert lifecycle**         | Supports `OPEN`, `ACKNOWLEDGED`, and `RESOLVED` states.                                           | Shows whether an issue is new, being handled, or no longer active.            |
| **Webhook notifications**   | Delivers alert state changes to an external endpoint and retries transient failures.              | Reaches an operator who is not watching the dashboard.                        |
| **Delivery audit trail**    | Records every attempt with status, HTTP code, and attempt number.                                 | Makes a missed notification diagnosable instead of invisible.                 |
| **Health endpoints**        | Reports agent availability and identity, and analytics availability plus database reachability.   | Gives operators and deployment tools a simple readiness check.                |
| **Graceful shutdown**       | Stops new work, drains analytics workers, releases the pool last, and flushes the agent queue.     | Leaves the system in a recoverable state during restarts or deployments.      |
| **Correlation IDs**         | One id follows an event from agent capture to analytics response, surviving a queued retry.       | Makes a single event traceable across both services' logs.                    |

## Requirements

- Go 1.27 or newer
- Java 17 or newer
- Apache Maven
- Docker with Compose, only for the container workflow
- Internet access on the first Maven/Go dependency download

The repository root contains a local `.env` file with the shared API key, service settings, alert thresholds, queue settings, and documented Maven memory settings. It is ignored by Git. Copy `.env.example` to `.env` and change the values when setting up a new checkout.

Maven does not automatically read `.env` files. The committed `.mvn/jvm.config` file applies `-Xms64m` and `-Xmx128m` automatically to every Maven command in this repository, so you do not need to run `$env:MAVEN_OPTS=...` manually. The matching `MAVEN_OPTS` entry in `.env` documents the intended setting for tools or scripts that explicitly load dotenv values.

## Run

Start the Java service first with Maven:

```powershell
cd java-analytics
mvn compile exec:java
```

The command above compiles the Java sources and starts the analytics service on port `8080`. On machines with a small Windows paging file, the project-level `.mvn/jvm.config` keeps Maven within the configured memory budget.

In a second terminal, start the Go service:

```powershell
cd go-collector
go run .
```

Both services load the root `.env` file automatically and must remain running. The Go service listens on port `8082`; the Java service listens on port `8080`. The agent sends `X-EventWatch-Key`, and the analytics service rejects requests with a missing or incorrect key.

Queue settings are controlled by `PENDING_EVENTS_DIR`, `QUEUE_CAPACITY`, and `QUEUE_RETRY_SECONDS` in `.env`. Alert settings are controlled by `CPU_ALERT_THRESHOLD`, `RAM_ALERT_THRESHOLD`, and `REPEATED_ERROR_THRESHOLD`.

Notifications are disabled by default. Set `NOTIFICATIONS_ENABLED=true` and a `NOTIFICATION_WEBHOOK_URL` to turn them on; `NOTIFICATION_TIMEOUT_SECONDS`, `NOTIFICATION_MAX_ATTEMPTS`, `NOTIFICATION_RETRY_DELAY_MILLIS`, and `NOTIFICATION_REMINDER_SECONDS` control delivery behaviour. Delivery never blocks ingestion: it runs on a background dispatcher, and a failed webhook still leaves the alert state correct.

When the analytics service is unavailable, the agent retries the request and writes the event atomically as a JSON file. A single background worker retries pending files. Successfully delivered files disappear; temporary failures remain pending; permanent `4xx` failures move to `pending-events/rejected-events/` for inspection. On shutdown, Go stops accepting requests and performs a final pending-queue delivery pass; Java drains its request executor before stopping.

Stop either service with `Ctrl+C`.

To run the local dashboard, keep both services running and start a third terminal from the repository root:

```powershell
python -m http.server 3000 -d dashboard
```

Open `http://localhost:3000` and enter the value of `EVENTWATCH_API_KEY` from `.env`.

Use the exact host shown in the browser URL. For example, open `http://localhost:3000` rather than a different hostname so the Java CORS policy can allow the dashboard API requests. The dashboard calls Java's `/summary`, `/events`, and `/alerts` endpoints with the entered API key; it never reads `events.db` directly.

## Send a log event

Send a `GET` request to the Go ingress with PowerShell:

```powershell
curl.exe "http://localhost:8082/capture?level=ERROR&msg=Database%20transaction%20deadlock"
```

More examples:

```powershell
curl.exe "http://localhost:8082/capture?level=ERROR&msg=Unauthorized%20API%20access%20attempt"
curl.exe "http://localhost:8082/capture?level=INFO&msg=User%20login%20successful"
```

The Go service forwards the event to Java. The Java terminal displays the total number of processed logs and counts repeated `ERROR` messages.

If `level` or `msg` is omitted, the Go service uses `INFO` and `Default cloud event` respectively.

The Java `/receive` endpoint is intended for the Go service and receives JSON with `event_id`, `level`, `msg`, `timestamp`, `cpu_usage`, and `ram_usage` fields.

The Java service rejects malformed JSON, non-object JSON, request bodies larger than 64 KiB, and unsupported HTTP methods.

All HTTP endpoints return JSON. Successful responses use `status: "ok"`; errors use `status: "error"` with a readable `message`. Example:

```json
{
  "status": "ok",
  "message": "log forwarded to analytics engine successfully"
}
```

When the configured CPU or RAM average exceeds its threshold, or an error repeats enough times within the moving window, Java creates or updates an alert in SQLite. Repeated deliveries update the existing alert instead of creating duplicate alert rows.

Use the API key to acknowledge an alert:

```powershell
curl.exe -i -X POST "http://localhost:8080/alerts/cpu-high/acknowledge" -H "X-EventWatch-Key: local-secret"
```

Acknowledging an alert does not reset it: further occurrences keep incrementing the count while the status stays `ACKNOWLEDGED`. Resolve them after the underlying issue is fixed:

```powershell
curl.exe -i -X POST "http://localhost:8080/alerts/cpu-high/resolve" -H "X-EventWatch-Key: local-secret"
```

Review webhook delivery attempts for one alert:

```powershell
curl.exe "http://localhost:8080/alerts/cpu-high/notifications?limit=10" -H "X-EventWatch-Key: local-secret"
```

## Running a fleet

Install the agent on each machine and point them all at one analytics service:

```powershell
$env:JAVA_BACKEND_URL="http://analytics-host:8080/receive"; go run .
```

Each agent generates and stores its own identity on first start. Nothing else needs configuring —
the analytics service creates per-host windows and alert keys as machines appear.

```powershell
curl.exe "http://localhost:8080/hosts" -H "X-EventWatch-Key: local-secret"
curl.exe "http://localhost:8080/events?host_id=web-01" -H "X-EventWatch-Key: local-secret"
curl.exe -X POST "http://localhost:8080/alerts/cpu-high@web-01/acknowledge" -H "X-EventWatch-Key: local-secret"
```

An event from an agent older than Phase 12 carries no identity and is attributed to the host
`unknown` rather than rejected.

**Exposing an agent:** by default it listens on `127.0.0.1` and accepts events from processes on
its own machine only. To accept events from elsewhere set `COLLECTOR_BIND` and a `CAPTURE_API_KEY`
— the agent refuses to start on a non-loopback address without one, because an open ingress lets
anyone on that network forge telemetry for the host and trigger its alerts.

## Fleet operations

```powershell
# Every machine, newest activity first.
curl.exe "http://localhost:8080/hosts" -H "X-EventWatch-Key: local-secret"

# One machine: averages, level counts, its alerts, and the rules it is judged by.
curl.exe "http://localhost:8080/hosts/web-01" -H "X-EventWatch-Key: local-secret"
```

Each row carries `status` (`reporting` or `silent`), `silent_seconds`, `agent_version`, and
`queue_depth`. A rising queue depth means that agent is holding events the analytics service has
not accepted.

**Silence.** A machine that stops reporting is the failure nothing else can see: every other rule
needs an event to evaluate, and a dead agent sends none. A background sweep raises
`agent-silent@{host}` once a machine has been quiet past its threshold, and the machine's next
event resolves the alert automatically.

`AGENT_SILENCE_MINUTES` (default 10) is the fleet-wide default, and `AGENT_SILENCE_SWEEP_SECONDS`
(default 60) is how often the check runs. Silence is an ordinary rule type, so one machine can have
its own window — a laptop that sleeps overnight — or be exempted entirely:

```powershell
curl.exe -X PUT "http://localhost:8080/rules" -H "X-EventWatch-Key: local-secret" `
  -H "Content-Type: application/json" -d '{"rule_type":"AGENT_SILENT","host_id":"laptop-01","threshold":240}'
```

`AGENT_SILENCE_FORGET_HOURS` (default 168, a week) stops old machines alerting forever: one quiet
for longer than this is treated as decommissioned rather than lost. Without it, every upgrade would
raise an alert for every machine that ever reported.

## Alert rules

Thresholds are rules stored in the database rather than three values in `.env`. For each rule
type — `HIGH_CPU`, `HIGH_RAM`, `REPEATED_ERROR` — the most specific rule wins:

1. a rule for that machine,
2. a fleet-wide rule,
3. the default from `.env` (`CPU_ALERT_THRESHOLD`, `RAM_ALERT_THRESHOLD`, `REPEATED_ERROR_THRESHOLD`).

With no rules stored, every machine behaves exactly as it did before rules existed. Manage rules
from the dashboard's **Alert rules** panel or through the API:

```powershell
# The build box may run hot; every other machine keeps the default.
curl.exe -X PUT "http://localhost:8080/rules" -H "X-EventWatch-Key: local-secret" `
  -H "Content-Type: application/json" -d '{"rule_type":"HIGH_CPU","host_id":"build-01","threshold":95}'

# See what applies to a machine and which tier it came from: host, fleet, or default.
curl.exe "http://localhost:8080/rules/effective?host_id=build-01" -H "X-EventWatch-Key: local-secret"

# Remove the override; the machine falls back to the fleet rule or the default.
curl.exe -X DELETE "http://localhost:8080/rules?rule_type=HIGH_CPU&host_id=build-01" -H "X-EventWatch-Key: local-secret"
```

Omit `host_id` for a fleet-wide rule. CPU and RAM thresholds are percentages from 0 to 100. A
`REPEATED_ERROR` threshold is a whole number from 1 to 5, because the rule only sees each machine's
last five events — a larger value could never fire, so it is refused rather than stored.

Set `"enabled": false` to switch a rule off for its scope. A disabled rule on one machine still
takes precedence over a fleet-wide rule, and an alert it raised resolves on that machine's next
event. Every change applies from each machine's next event.

Repeated-error alerts are not resolved automatically — an error does not "recover" the way CPU
load does — so resolve them from the dashboard or the API once handled.

## Securing a deployment

**Agent exposure.** `COLLECTOR_BIND` defaults to `127.0.0.1`. Any other bind requires
`CAPTURE_API_KEY`, and callers must then send it as `X-EventWatch-Key` on `/capture` and `/stress`.
`CAPTURE_REQUIRE_KEY=true` demands a key even on loopback.

**TLS between agent and analytics.** Generate a keystore and hand the agent the certificate:

```powershell
keytool -genkeypair -alias eventwatch -keyalg RSA -keysize 2048 -validity 365 `
  -dname "CN=analytics.internal,O=EventWatch,C=GB" -ext "SAN=dns:analytics.internal" `
  -storetype PKCS12 -keystore eventwatch.p12 -storepass changeit
keytool -exportcert -rfc -alias eventwatch -keystore eventwatch.p12 -storepass changeit -file eventwatch.pem
```

```powershell
TLS_ENABLED=true
TLS_KEYSTORE_PATH=eventwatch.p12
TLS_KEYSTORE_PASSWORD=changeit

JAVA_BACKEND_URL=https://analytics.internal:8080/receive
BACKEND_CA_FILE=eventwatch.pem
```

The agent verifies the certificate against `BACKEND_CA_FILE`, so a private CA works without
weakening anything. `BACKEND_TLS_SKIP_VERIFY=true` encrypts without authenticating — useful for a
first run, never for a real deployment, and the agent logs a warning when it is set.

**CORS.** `CORS_ALLOWED_ORIGINS` is a comma-separated allowlist, defaulting to the local dashboard.
An origin outside it gets no `Access-Control-Allow-Origin` header.

**Metrics.** `/metrics` is open by default because scrapers rarely send custom headers. Set
`METRICS_REQUIRE_KEY=true` to close it.

**Still open.** The dashboard holds the API key in page memory for the session — cleared from the
input once you connect, never written to storage, but still reachable by script running on that
page. Eliminating that needs same-origin serving and an HttpOnly session cookie, which is a later
phase. Treat dashboard access as equivalent to holding the key.

## Storage backends

SQLite stays the default and needs no external services. Setting `DATABASE_URL` to a PostgreSQL
JDBC URL switches the backend; everything else — the API, the alert rules, the JSON contract —
behaves identically, and the same test suite runs against both.

```powershell
# SQLite (default): DATABASE_URL empty, DATABASE_PATH names the file
DATABASE_URL=
DATABASE_PATH=events.db

# PostgreSQL
DATABASE_URL=jdbc:postgresql://localhost:5432/eventwatch
DATABASE_USER=eventwatch
DATABASE_PASSWORD=eventwatch
```

Both backends use a connection pool sized by `DATABASE_POOL_SIZE`. SQLite additionally runs in
write-ahead-logging mode, which is what makes the default backend fast enough to not be the
constraint — see the measurements below.

Set `RETENTION_DAYS` to a positive number to delete telemetry and delivery history older than that
window; a sweep runs every `RETENTION_SWEEP_MINUTES`. The default of `0` keeps everything, which is
right for a local install and wrong for a long-running one.

`RATE_LIMIT_PER_MINUTE` sets the per-client ingestion limit, default `100`. It is the first ceiling
a high-volume agent meets, well before storage is.

## Observability

Both services log one JSON object per line by default, so the output can be shipped and queried
without parsing prose:

```json
{"correlation_id":"trace-abc-123","event_id":"d7b27bde-...","event_level":"ERROR","level":"INFO","message":"event stored","service":"java-analytics","timestamp":"2026-09-11T06:58:13.549Z"}
```

Set `LOG_FORMAT=text` in `.env` to switch both services back to human-readable lines. In text mode
the Java service also prints the `LIVE CLOUD ALERT DASHBOARD` terminal report; in JSON mode the same
numbers are emitted as a `telemetry snapshot` log line instead, so the ASCII output cannot corrupt a
log stream.

Every captured event is given a correlation ID. A caller may supply its own with the
`X-Correlation-ID` header, and the agent generates one otherwise. It travels to the analytics service in both the
request header and the `correlation_id` payload field, so a queued event keeps its ID through a
retry. Java echoes it in the `X-Correlation-ID` response header and in the JSON response body.

Both services expose unauthenticated Prometheus metrics for local scraping:

```powershell
curl.exe http://localhost:8082/metrics
curl.exe http://localhost:8080/metrics
```

The agent reports captured events by level, forwarding outcomes, queue depth, queue writes,
drops and rejections, and forwarding latency. The analytics service reports events received,
duplicated and rejected by reason, database failures, notification outcomes, HTTP responses by route
and status, active alerts, stored rows, and processing latency. The analytics `/metrics` endpoint can be closed with
`METRICS_REQUIRE_KEY=true`; the agent's is open like `/health` and should be restricted at the
network layer.

## Docker

```powershell
docker compose up --build
```

Compose starts the analytics service on `8080`, the agent on `8082`, and the dashboard on
`3000`, and reads the same root `.env` file. To run against PostgreSQL instead, enable its profile
and point the analytics service at it:

```powershell
docker compose --profile postgres up --build
```

with `DATABASE_URL=jdbc:postgresql://postgres:5432/eventwatch` in `.env`. Telemetry and the pending queue live on named volumes,
so a container restart keeps both history and undelivered events. The agent waits for the
analytics health check before starting.

## Tests

Both suites run offline and need no services started.

```powershell
cd java-analytics; mvn verify
```

```powershell
cd go-collector; go vet ./...; go test ./...
```

```powershell
cd loadtest; go vet ./...; go test ./...
```

The PostgreSQL suite is skipped unless `EVENTWATCH_TEST_POSTGRES_URL` points at a reachable
server, so a checkout without PostgreSQL still builds; CI supplies one as a service container.

The Java suite covers event validation, query-parameter parsing, persistence and deduplication on
both backends, the per-host alert rules and lifecycle, webhook delivery against a local sink, the
engine start/stop lifecycle, retention, the metric and log formats, and an end-to-end pass that
drives the real HTTP server on an ephemeral port with a temporary database — including restart
recovery, two machines staying independent, per-host rules changing which machines
alert, and a silent machine raising and then resolving its own alert. The Go suite covers retry classification, the capture
handler, durable-queue outcomes, correlation IDs, host identity and its persistence across restarts,
and metric rendering, using an `httptest` stand-in for the analytics service.

`testdata/event-contract.json` is read by both suites, so a change to the shared JSON contract fails
on whichever side was not updated.

## Measured throughput

`loadtest/` measures ingestion so scaling decisions rest on numbers rather than guesses.

```powershell
cd loadtest
go run . -mode analytics -events 3000 -concurrency 16
```

Measured on one developer machine, 3000 events at concurrency 16, rate limit raised:

| Backend                              | Throughput    | p50     | p99     |
| ------------------------------------ | ------------- | ------- | ------- |
| SQLite, connection per query          | 60 events/s   | 260 ms  | 461 ms  |
| PostgreSQL in Docker, pooled          | 164 events/s  | 90 ms   | 150 ms  |
| SQLite, pooled with write-ahead log   | 522 events/s  | 28 ms   | 84 ms   |

The headline is that SQLite was never the bottleneck: opening a connection per query and syncing
every commit was. Pooled and in WAL mode it outruns PostgreSQL over a local network socket by three
times. Move to PostgreSQL when you need several agents writing to one store, or retention longer
than a single disk holds — not for throughput.

## Run the stress test

After both services are running, send 500 test requests through the agent:

```powershell
curl.exe http://localhost:8082/stress
```

The stress handler adds a unique `(Log #N)` suffix to each message and bounds its fan-out to 32 concurrent deliveries. Java rate-limits a single client to 100 requests per minute, so most of the burst is answered with `429` and written to the pending queue; the recovery worker then drains it over the following minutes. No event is dropped.

## Notes

- Telemetry is stored in `java-analytics/events.db` by default. Only the newest five events *per
  machine* are mirrored in memory; the full history stays in the database and is reached through the
  query API.
- Maven build output and the runtime database are generated files and should not be committed.
- The services communicate over plain HTTP unless `TLS_ENABLED` is set, and the agent listens on
  loopback unless told otherwise. See [Securing a deployment](#securing-a-deployment).
- If the analytics service is unavailable, the agent returns `503` after bounded retries and saves
  the event in the pending JSON queue for background recovery.
- `agent.md` is the original phase roadmap kept for history. `CLAUDE.md` is the current authority on
  state, conventions, and what comes next.
