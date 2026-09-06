# EventWatch

EventWatch is a two-service host telemetry and event-monitoring pipeline. A Go collector captures application events together with CPU and RAM usage, then forwards them to a Java analytics engine for processing, persistence, and reporting.

The current implementation completes Phases 1–6. Future improvements are documented in `agent.md`.

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
| **Phase 8**  | 🗓️ Planned       | Notifications                    | Email and webhook integrations with delivery tracking and duplicate-alert prevention.                                                                                                                      |
| **Phase 9**  | 🗓️ Planned       | Observability                    | Structured logs, Prometheus metrics, OpenTelemetry tracing, and correlation IDs.                                                                                                                           |
| **Phase 10** | 🗓️ Planned       | Testing and delivery             | Unit, integration, load, and failure tests plus Docker and CI/CD automation.                                                                                                                               |
| **Phase 11** | 🗓️ Planned       | Scaling beyond SQLite            | PostgreSQL or time-series storage, durable messaging, and independently scalable services when required.                                                                                                   |

**Current state:** EventWatch is a working local telemetry and event-monitoring system. Go collects and forwards events, Java analyzes and persists them, and the terminal dashboard reports errors and recent resource averages.

## Project Structure

```text
go-collector/                   Go HTTP ingress
	main.go
	go.mod
java-analytics/                 Maven Java analytics service
	pom.xml
	.mvn/jvm.config                Automatic Maven JVM memory settings
	src/main/java/com/main/
		AnalyticsEngine.java
		AlertEngine.java
		AlertRecord.java
		AlertRepository.java
		AlertStatus.java
dashboard/                      Local browser dashboard
	index.html
	app.js
	styles.css
```

## Architecture

- **Go ingress** (`go-collector/`): accepts `GET` requests at `http://localhost:8082/capture`, samples host CPU/RAM usage, and forwards each event to the Java service.
- **Java analytics engine** (`java-analytics/`): accepts `POST` requests at `http://localhost:8080/receive`, stores telemetry in SQLite, reloads events after restart, and prints error counts plus a five-event CPU/RAM moving average.
- **Reliability queue** (`go-collector/pending-events/`): stores events when Java is temporarily unavailable and removes them only after a successful `2xx` response. Event IDs prevent duplicate database rows when retries occur. Permanent client failures move to `rejected-events/`.
- **Health checks:** `GET http://localhost:8082/health` and `GET http://localhost:8080/health` report service availability without authentication.
- **Alert API:** `GET http://localhost:8080/alerts` returns active `OPEN` and `ACKNOWLEDGED` alerts as JSON.
- **Alert acknowledgement:** `POST http://localhost:8080/alerts/{alert_key}/acknowledge` changes an `OPEN` alert to `ACKNOWLEDGED`.
- **Alert resolution:** `POST http://localhost:8080/alerts/{alert_key}/resolve` changes an alert to `RESOLVED`, removing it from the active alerts response.
- **Phase 7 query API:** `GET /events`, `GET /summary`, `GET /alerts`, and `GET /alerts/{alert_key}` provide bounded JSON data for the dashboard.
- **Dashboard:** `dashboard/index.html` displays summaries, recent events, and active alerts. It reads the API key in the browser and never accesses SQLite directly.
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
| **Health endpoints**        | Reports Go availability and Java availability plus SQLite reachability.                           | Gives operators and future deployment tools a simple readiness check.         |
| **Graceful shutdown**       | Stops new work, drains Java workers, and flushes the Go queue once.                               | Leaves the system in a recoverable state during restarts or deployments.      |

## Requirements

- Go 1.27 or newer
- Java 17 or newer
- Apache Maven
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

Acknowledged alerts remain visible because they are still active. Resolve them after the underlying issue is fixed:

```powershell
curl.exe -i -X POST "http://localhost:8080/alerts/cpu-high/resolve" -H "X-EventWatch-Key: local-secret"
```

## Run the stress test

After both services are running, send 500 test requests through the Go service:

```powershell
curl.exe http://localhost:8082/stress
```

The stress handler adds a unique `(Log #N)` suffix to each message. Because the Java dashboard groups by the complete message, each generated message currently appears with a count of `1`.

## Notes

- Telemetry is stored in `java-analytics/events.db` and loaded when the Java service restarts.
- `alerts_history.json` is a legacy Phase 2 archive and is no longer written by the service.
- Maven build output and the runtime SQLite database are generated files and should not be committed.
- The services currently communicate over `localhost`; HTTPS/TLS remains a future deployment task.
- If Java is unavailable, Go returns `503` after bounded retries and saves the event in the pending JSON queue for background recovery.
- Future improvements and the long-term roadmap are documented in `agent.md`.
