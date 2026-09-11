# EventWatch

EventWatch is a self-hosted fleet monitor for a small number of machines. A lightweight Go agent runs on each host, captures application events together with that machine's CPU and RAM usage, and forwards them to a Java analytics service that keeps per-host history, evaluates per-host alert rules, and notifies an operator.

The current implementation completes Phases 1–12. Future improvements are documented in `CLAUDE.md`.

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

**Current state:** EventWatch is a working local telemetry and event-monitoring system. Go collects and forwards events, Java analyzes and persists them, alert state changes are delivered to a configured webhook, and the browser dashboard reports events, alerts, and delivery history.

## Project Structure

```text
go-collector/                   Go HTTP ingress
	main.go
	Dockerfile
	logging.go                     Structured JSON log lines
	metrics.go                     Prometheus counters and /metrics
	go.mod
java-analytics/                 Maven Java analytics service
	pom.xml
	Dockerfile
	.mvn/jvm.config                Automatic Maven JVM memory settings
	src/main/java/com/main/
		AnalyticsEngine.java
		Metrics.java
		StructuredLogger.java
		AlertEngine.java
		AlertRecord.java
		AlertRepository.java
		AlertStatus.java
		AlertTransition.java
		EventRepository.java
		QueryService.java
		NotificationRecord.java
		NotificationRepository.java
		NotificationService.java
dashboard/                      Local browser dashboard
	index.html
	app.js
	styles.css
loadtest/                       Throughput and latency harness
testdata/                       Cross-language JSON contract fixture
.github/workflows/ci.yml        Build, test, scan, and image pipeline
docker-compose.yml              Collector, analytics, and dashboard together
```

## Architecture

- **Go agent** (`go-collector/`): one per machine. Accepts `GET` requests at `http://localhost:8082/capture`, samples that machine's CPU/RAM usage, stamps every event with its host identity, and forwards to the Java service.
- **Host identity:** each agent establishes a stable `host_id` at startup and stores it beside its queue, so a restart is not mistaken for a new machine. Set `HOST_ID` to pin it explicitly; `HOSTNAME_OVERRIDE` renames the reported hostname.
- **Java analytics engine** (`java-analytics/`): accepts `POST` requests at `http://localhost:8080/receive`, stores telemetry in SQLite, reloads events after restart, and prints error counts plus a five-event CPU/RAM moving average.
- **Reliability queue** (`go-collector/pending-events/`): stores events when Java is temporarily unavailable and removes them only after a successful `2xx` response. Event IDs prevent duplicate database rows when retries occur. Permanent client failures move to `rejected-events/`.
- **Health checks:** `GET http://localhost:8082/health` and `GET http://localhost:8080/health` report service availability without authentication.
- **Alert API:** `GET http://localhost:8080/alerts` returns active `OPEN` and `ACKNOWLEDGED` alerts as JSON.
- **Alert acknowledgement:** `POST http://localhost:8080/alerts/{alert_key}/acknowledge` changes an `OPEN` alert to `ACKNOWLEDGED`.
- **Alert resolution:** `POST http://localhost:8080/alerts/{alert_key}/resolve` changes an alert to `RESOLVED`, removing it from the active alerts response.
- **Phase 7 query API:** `GET /events`, `GET /summary`, `GET /alerts`, and `GET /alerts/{alert_key}` provide bounded JSON data for the dashboard. All of them require the API key.
- **Phase 12 fleet awareness:** every event carries `host_id` and `hostname`. Moving averages, alert thresholds, and alert keys are scoped per machine — `cpu-high@web-01` is a different alert from `cpu-high@db-01` — so one busy host cannot drag an idle one into an alert, and acknowledging one machine does not silence another. `GET /hosts` lists every machine with its event count and last-seen time; `GET /events?host_id=` filters to one.
- **Phase 9 observability:** both services emit one JSON object per log line, expose `GET /metrics` in Prometheus text format, and carry an `X-Correlation-ID` from the collector through to the analytics response.
- **Phase 8 notifications:** alert state changes (opened, reopened, acknowledged, resolved) are POSTed as versioned JSON to `NOTIFICATION_WEBHOOK_URL`. Repeat occurrences are suppressed until `NOTIFICATION_REMINDER_SECONDS` passes. Every attempt is recorded and readable at `GET /alerts/{alert_key}/notifications`.
- **Dashboard:** `dashboard/index.html` displays summaries, recent events, active alerts, and per-alert delivery history. It reads the API key in the browser, escapes all event text before rendering it, and never accesses SQLite directly.
- **Configuration:** `HTTP_PORT`, `COLLECTOR_PORT`, and `DATABASE_PATH` set the listening ports and database location, so neither service needs a source change to be deployed or containerized.
- **SQLite database** (`java-analytics/events.db`): stores telemetry in the `telemetry_events` table. The database is created automatically when the Java service starts.

## End-to-End Flow

```text
Client request
	↓
Go collector: captures event and host CPU/RAM metrics
	↓
Go creates event_id and serializes the shared JSON payload
	↓
Authenticated HTTP request to Java
	↓
Java validates API key, content type, size, fields, and value ranges
	↓
Java commits telemetry to SQLite
	↓
Alert engine evaluates the latest five-event window
	↓
SQLite stores or updates alert state
	↓
Lifecycle changes are handed to the notification dispatcher
	↓
Webhook delivery attempt recorded in SQLite
	↓
JSON response and terminal dashboard output
```

If Java is temporarily unavailable, Go retries the request and writes the same event to `pending-events/`. A background worker retries those files later. A queued file is deleted only after Java returns `2xx`; permanent client errors move to `rejected-events/`.

## Feature Contributions

| Feature                     | What it does                                                                                      | Why it matters                                                                |
| --------------------------- | ------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| **Host telemetry**          | Collects CPU and RAM usage with each event.                                                       | Connects application errors to the health of the machine producing them.      |
| **Shared JSON contract**    | Uses the same `event_id`, level, message, timestamp, CPU, and RAM fields across Go and Java.      | Keeps both services interoperable and makes events easy to inspect or replay. |
| **API-key authentication**  | Requires the configured `X-EventWatch-Key` header for Java ingestion.                             | Prevents unauthenticated clients from submitting telemetry.                   |
| **Input validation**        | Rejects invalid JSON, fields, content types, ranges, oversized requests, and unsupported methods. | Prevents malformed data from reaching analytics or SQLite.                    |
| **SQLite persistence**      | Stores telemetry and alert records in local tables and reloads them after restart.                | Preserves history without requiring a separate database server.               |
| **Durable JSON queue**      | Saves events when Java is unavailable and retries them later.                                     | Prevents temporary backend outages from silently losing events.               |
| **Event-ID deduplication**  | Uses a unique event ID in SQLite.                                                                 | Makes retries safe when Java stores an event but the response is lost.        |
| **Bounded processing**      | Limits Java workers and queued requests.                                                          | Applies backpressure and prevents traffic spikes from exhausting memory.      |
| **Moving-window analytics** | Evaluates the latest five events for CPU and RAM trends.                                          | Detects sustained pressure instead of reacting to one momentary spike.        |
| **Alert engine**            | Creates and updates `HIGH_CPU`, `HIGH_RAM`, and `REPEATED_ERROR` alerts.                          | Turns raw telemetry into actionable incidents.                                |
| **Alert lifecycle**         | Supports `OPEN`, `ACKNOWLEDGED`, and `RESOLVED` states.                                           | Shows whether an issue is new, being handled, or no longer active.            |
| **Webhook notifications**   | Delivers alert state changes to an external endpoint and retries transient failures.              | Reaches an operator who is not watching the dashboard.                        |
| **Delivery audit trail**    | Records every attempt with status, HTTP code, and attempt number.                                 | Makes a missed notification diagnosable instead of invisible.                 |
| **Health endpoints**        | Reports Go availability and Java availability plus SQLite reachability.                           | Gives operators and future deployment tools a simple readiness check.         |
| **Graceful shutdown**       | Stops new work, drains Java workers, and flushes the Go queue once.                               | Leaves the system in a recoverable state during restarts or deployments.      |

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
`X-Correlation-ID` header, and the collector generates one otherwise. It travels to Java in both the
request header and the `correlation_id` payload field, so a queued event keeps its ID through a
retry. Java echoes it in the `X-Correlation-ID` response header and in the JSON response body.

Both services expose unauthenticated Prometheus metrics for local scraping:

```powershell
curl.exe http://localhost:8082/metrics
curl.exe http://localhost:8080/metrics
```

The collector reports captured events by level, forwarding outcomes, queue depth, queue writes,
drops and rejections, and forwarding latency. The analytics service reports events received,
duplicated and rejected by reason, database failures, notification outcomes, HTTP responses by route
and status, active alerts, stored rows, and processing latency. Both `/metrics` endpoints are open
like `/health`; restrict them at the network layer before exposing either service beyond localhost.

## Tests

Both suites run offline and need no services started.

```powershell
cd java-analytics; mvn verify
```

```powershell
cd go-collector; go vet ./...; go test ./...
```

The PostgreSQL suite is skipped unless `EVENTWATCH_TEST_POSTGRES_URL` points at a reachable
server, so a checkout without PostgreSQL still builds; CI supplies one as a service container.

The Java suite covers event validation, query-parameter parsing, SQLite persistence and
deduplication, the alert rules and lifecycle, webhook delivery against a local sink, the metric and
log formats, and an end-to-end pass that drives the real HTTP server on an ephemeral port with a
temporary database — including restart recovery. The Go suite covers retry classification, the
capture handler, durable-queue outcomes, correlation IDs, metric rendering, and host sampling,
using an `httptest` stand-in for the analytics service.

`testdata/event-contract.json` is read by both suites, so a change to the shared JSON contract fails
on whichever side was not updated.

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
a high-volume collector meets, well before storage is.

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
times. Move to PostgreSQL when you need several collectors writing to one store, or retention longer
than a single disk holds — not for throughput.

## Docker

```powershell
docker compose up --build
```

Compose starts the analytics service on `8080`, the collector on `8082`, and the dashboard on
`3000`, and reads the same root `.env` file. To run against PostgreSQL instead, enable its profile
and point the analytics service at it:

```powershell
docker compose --profile postgres up --build
```

with `DATABASE_URL=jdbc:postgresql://postgres:5432/eventwatch` in `.env`. Telemetry and the pending queue live on named volumes,
so a container restart keeps both history and undelivered events. The collector waits for the
analytics health check before starting.

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

**Before deploying beyond localhost:** `/capture` is unauthenticated and there is no TLS, so an
agent must not be exposed to an untrusted network yet. That is Phase 13.

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

Both services load the root `.env` file automatically and must remain running. The Go service listens on port `8082`; the Java service listens on port `8080`. The Go collector sends `X-EventWatch-Key`, and Java rejects requests with a missing or incorrect key.

Queue settings are controlled by `PENDING_EVENTS_DIR`, `QUEUE_CAPACITY`, and `QUEUE_RETRY_SECONDS` in `.env`. Alert settings are controlled by `CPU_ALERT_THRESHOLD`, `RAM_ALERT_THRESHOLD`, and `REPEATED_ERROR_THRESHOLD`.

Notifications are disabled by default. Set `NOTIFICATIONS_ENABLED=true` and a `NOTIFICATION_WEBHOOK_URL` to turn them on; `NOTIFICATION_TIMEOUT_SECONDS`, `NOTIFICATION_MAX_ATTEMPTS`, `NOTIFICATION_RETRY_DELAY_MILLIS`, and `NOTIFICATION_REMINDER_SECONDS` control delivery behaviour. Delivery never blocks ingestion: it runs on a background dispatcher, and a failed webhook still leaves the alert state correct.

When Java is unavailable, the Go collector retries the request and writes the event atomically as a JSON file. A single background worker retries pending files. Successfully delivered files disappear; temporary failures remain pending; permanent `4xx` failures move to `pending-events/rejected-events/` for inspection. On shutdown, Go stops accepting requests and performs a final pending-queue delivery pass; Java drains its request executor before stopping.

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

## Run the stress test

After both services are running, send 500 test requests through the Go service:

```powershell
curl.exe http://localhost:8082/stress
```

The stress handler adds a unique `(Log #N)` suffix to each message and bounds its fan-out to 32 concurrent deliveries. Java rate-limits a single client to 100 requests per minute, so most of the burst is answered with `429` and written to the pending queue; the recovery worker then drains it over the following minutes. No event is dropped.

## Notes

- Telemetry is stored in `java-analytics/events.db`. Only the newest five events are mirrored in memory at startup; the full history stays in SQLite and is reached through the query API.
- `alerts_history.json` is a legacy Phase 2 archive and is no longer written by the service.
- Maven build output and the runtime SQLite database are generated files and should not be committed.
- The services currently communicate over `localhost`; HTTPS/TLS remains a future deployment task.
- If Java is unavailable, Go returns `503` after bounded retries and saves the event in the pending JSON queue for background recovery.
- Future improvements and the long-term roadmap are documented in `agent.md`.
