# 📊 EventWatch: Distributed Host Telemetry & Stream Processing Pipeline

**EventWatch** is a cloud-agnostic, lightweight systems telemetry and alert-monitoring pipeline. The project mimics a real-world **SIEM (Security Information and Event Management)** tool or an infrastructure health collector (like Datadog or Amazon CloudWatch).

It is intentionally designed as a polyglot system to demonstrate two distinct engineering paradigms:

1. **🐹 Go Ingestion Agent:** Maximizes Go's low-resource concurrency and networking speed to capture real-time host operating system metrics using minimal RAM (~5-10MB).
2. **☕ Java Analytics Core:** Capitalizes on the Java Streams API inside a standard **Maven** ecosystem to orchestrate clean, declarative data filtering, sliding time-window aggregates, and structural alert calculations.

---

## 🏗️ Technical Architecture & Data Lifecycle

The application components run completely decoupled on local network interfaces to simulate a production distributed cloud cluster:

## 📋 The Telemetry JSON Schema

To guarantee data integrity across both language ecosystems, Go and Java communicate using a strict, structured JSON schema:

```json
{
  "event_id": "unique-event-id",
  "level": "ERROR",
  "msg": "High CPU Saturation Alert",
  "timestamp": "2026-09-04T18:46:00Z",
  "cpu_usage": 88.4,
  "ram_usage": 12.1
}
```

---

## ⚙️ Core System Functionalities & Roadmap

### 🟩 Phase 1: In-Memory JSON Pipeline (Current Baseline)

- **Cross-Language JSON Handshake:** Clean JSON parsing across runtime environments without using bloated runtime frameworks.
- **Functional Log Filtering:** Leverages Java Stream `.filter()` and `Collectors.groupingBy()` methods to isolate system events dynamically.
- **Concurrent Load Simulator:** Features a specialized `/stress` Go route utilizing lightweight **Goroutines** to concurrently flood the pipeline with 500 simultaneous data payloads to test local throughput boundaries.

### 🟨 Phase 2: Host Ingestion & Time-Window Analytics (Completed)

- **Real Operating System Telemetry:** Upgrading the Go agent to pull actual live CPU utilisation and RAM footprints directly from the local computer hardware.
- **Moving Average Metrics:** Engineering Java Stream window transformations to isolate sustained hardware trends rather than momentary performance spikes.
- **Flat File Event Archiving:** Completed as an intermediate implementation. The legacy `alerts_history.json` file is retained for reference; the active store is now SQLite.

### 🟥 Phase 3: Embedded Database Integration & Hardening (Completed)

- **SQLite Relational Engine:** The Java service persists telemetry transactionally in `events.db` through the SQLite JDBC driver and reloads it at startup.
- **Request Safeguards:** The Java endpoint rejects malformed JSON, non-object JSON, oversized request bodies, and unsupported HTTP methods with appropriate status codes.
- **Concurrent Writes:** Database writes are serialized and committed before events are added to the in-memory analytics window.
- **Known Limitation:** The current system remains local-host oriented and does not yet provide HTTPS/TLS for multi-machine deployments.

### 🟦 Phase 4–11: Production-Ready Roadmap

The following phases turn the learning project into a usable monitoring and alerting system. Implement them in order, keeping the Go and Java JSON contract backward-compatible.

#### Phase 4: Security and Input Protection (Completed)

- Shared `.env` configuration is loaded by both services.
- Go sends an API key and Java validates it using constant-time comparison.
- Java validates required fields, allowed log levels, numeric ranges, timestamps, content type, and maximum message size.
- Java applies a per-client rate limit of 100 requests per minute.
- Go uses a five-second timeout and up to three bounded retries for temporary backend failures.
- HTTPS/TLS remains a deployment task for communication outside localhost.

#### Phase 5: Reliability and Failure Handling (Completed)

- Add `/health` endpoints to both services. (Completed.)
- Add a durable local queue for events collected during temporary Java outages. (Completed with atomic JSON files.)
- Add Java backpressure and bounded worker queues so traffic cannot exhaust memory. (Completed with eight workers and a 500-request queue.)
- Add Go queue capacity and retry interval configuration through `.env`. (Completed.)
- Permanent 4xx queue failures move to `rejected-events`; transport errors, 5xx responses, and 429 responses remain pending.
- Event IDs and a SQLite unique index make retry delivery idempotent. Duplicate deliveries return success without adding another row.
- All HTTP endpoints return readable JSON responses with consistent status and message fields. (Completed.)
- Graceful shutdown stops accepting requests, drains the Java executor, and performs a final pending-queue delivery pass in Go.

#### Phase 6: Analytics and Alert Rules (Completed)

- Configurable CPU, RAM, and repeated-error thresholds are loaded from `.env`.
- The alert engine evaluates the latest five telemetry events instead of alerting on one spike.
- SQLite stores deduplicated alert records with `OPEN`, `ACKNOWLEDGED`, and `RESOLVED` lifecycle states.
- `GET /alerts` returns active alerts for inspection.
- Operator acknowledgement is available through `POST /alerts/{alert_key}/acknowledge`.
- Remaining work: cooldown policies and richer time-based queries belong to later dashboard and notification work.

#### Phase 7: Query API and Dashboard (Next)

Phase 7 should make the existing telemetry and alert data easy to inspect without changing the Go ingestion contract or the durable queue.

##### Phase 7 Flow

```text
Browser dashboard
  ↓
Java query endpoints
  ↓
SQLite repository queries
  ↓
JSON event, trend, and alert data
  ↓
Dashboard tables, charts, and filters
```

##### Phase 7 API Surface

- `GET /events`: return recent telemetry events.
- `GET /events?level=ERROR&limit=50`: filter events by level and limit result size.
- `GET /events?from=...&to=...`: query events by ISO-8601 time range.
- `GET /alerts`: return active alerts, with optional status and type filters.
- `GET /alerts/{alert_key}`: return one alert and its current lifecycle state.
- `GET /summary`: return total events, active alert count, latest telemetry values, and moving averages.
- Keep `POST /alerts/{alert_key}/acknowledge` and `POST /alerts/{alert_key}/resolve` as the existing operator actions.

All query endpoints should return JSON, validate query parameters, apply a maximum limit, and use parameterized SQLite statements.

##### Phase 7 Java Structure

```text
src/main/java/com/main/
├── AnalyticsEngine.java       # HTTP server and request routing
├── EventRepository.java       # Event history queries
├── AlertRepository.java       # Alert queries and lifecycle updates
├── QueryService.java          # Filters, summaries, and pagination rules
└── ResponseModels.java        # JSON response DTOs when responses grow
```

If the dashboard is served separately, keep it under a distinct directory such as `dashboard/` rather than adding frontend code to the Java analytics classes.

##### Phase 7 Dashboard Views

- **Overview:** total events, active alerts, current CPU/RAM, and five-event averages.
- **Events:** paginated table with level, message, timestamp, CPU, RAM, and event ID.
- **Alerts:** active and historical alerts with acknowledge and resolve actions.
- **Trends:** CPU/RAM charts selected by time range.
- **Filters:** level, alert status, alert type, time range, and result limit.

##### Phase 7 Completion Criteria

- Query results are paginated and bounded.
- Event and alert filters are validated and parameterized.
- The dashboard uses the existing JSON APIs rather than reading SQLite directly.
- Operators can inspect, acknowledge, and resolve alerts from the dashboard.
- Empty results and invalid filters return readable JSON responses.
- Existing Go ingestion, queue recovery, and alert evaluation continue to pass unchanged.

#### Phase 8: Notifications

- Add email, webhook, Slack, or Microsoft Teams notifications.
- Notify only when an alert changes state or passes its cooldown period.
- Record notification attempts, delivery status, and failures.

#### Phase 9: Observability of EventWatch

- Use structured JSON logs in both services.
- Expose Prometheus metrics such as received events, rejected events, processing latency, queue depth, and database failures.
- Add OpenTelemetry tracing across Go ingestion and Java processing.
- Include a request or correlation ID in logs and responses.

#### Phase 10: Testing and Delivery

- Add Go unit tests for metric collection, request validation, retries, and queue behavior.
- Add Java unit tests for parsing, moving averages, alert thresholds, deduplication, and persistence.
- Add integration tests covering Go-to-Java communication and database recovery.
- Add load tests for `/stress` and failure tests for unavailable services and corrupted input.
- Add Dockerfiles, Docker Compose, and CI checks for tests, formatting, dependency vulnerabilities, and builds.

#### Phase 11: Scaling Beyond SQLite

- Keep SQLite for a single-host deployment and development.
- Move to PostgreSQL or a time-series database when multiple collectors or long retention are required.
- Introduce Kafka, RabbitMQ, or NATS only when event volume requires durable distributed streaming.
- Separate ingestion, analytics, storage, and notifications into independently scalable services.

## Recommended Implementation Order

1. Complete Phase 7: query API and dashboard.
2. Complete Phase 8: notifications and notification history.
3. Complete Phase 9: metrics, logs, and tracing.
4. Complete Phase 10: tests, Docker, and CI/CD.
5. Complete Phase 11: scaling beyond SQLite when measured load requires it.

## Intended Real-World Use Case

EventWatch is a small observability and security-event pipeline for monitoring servers or services. A Go agent captures host metrics and application events, Java analyzes them, SQLite stores history, and alert rules identify sustained infrastructure or security problems. The dashboard and notification layer allow an operator to investigate and respond to those alerts.

It is suitable for a personal server, lab environment, portfolio demonstration, or learning distributed systems. It should not be described as a production replacement for Datadog, CloudWatch, or a SIEM until authentication, encryption, durable queues, failure recovery, retention policies, and operational monitoring are implemented.

## Definition of Done for a Strong Release

- Events are validated, authenticated, persisted transactionally, and queryable.
- Temporary service failures do not silently lose events.
- Alerts are configurable, deduplicated, observable, and actionable.
- Operators can view trends and acknowledge or resolve alerts.
- Tests cover normal traffic, concurrent traffic, malformed input, restarts, and dependency failures.
- Services can be configured and deployed without changing source code.
- Metrics, logs, health checks, and traces make failures diagnosable.

---

## 📂 Project Directory Structure

```text
eventwatch/
├── .gitignore
├── README.md                 # Project Blueprint & Roadmap Documentation
│
├── go-collector/             # Ingestion Subsystem (Go Mod environment)
│   ├── go.mod
│   └── main.go               # Handles Port 8082 web ingress & host tracking
│
└── java-analytics/           # Processing Subsystem (Standard Maven layout)
    ├── pom.xml               # Dependency tracking configuration
    └── src/
        └── main/
            └── java/
          └── com/main/
            └── AnalyticsEngine.java   # Hosts Port 8080 listener & Streams API logic
```

---
