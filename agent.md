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

#### Phase 6: Analytics and Alert Rules

- Define configurable thresholds for CPU, RAM, error frequency, and repeated failures.
- Detect sustained conditions using time windows instead of alerting on a single spike.
- Add alert deduplication, cooldown periods, and alert states: `OPEN`, `ACKNOWLEDGED`, and `RESOLVED`.
- Record hostname, service name, environment, and correlation ID in every event.
- Provide queries for recent events, historical trends, and active alerts.

#### Phase 7: Query API and Dashboard

- Add a Java REST API for querying events and alerts by time, severity, host, and service.
- Build a web dashboard showing current CPU/RAM values, moving averages, recent errors, and active alerts.
- Add charts for time-series trends and controls for filtering and time ranges.
- Add an alert acknowledgement workflow so operators can track incidents.

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

1. Complete Phase 6: configurable alert rules and alert state transitions.
2. Complete Phase 7: query API and dashboard.
3. Complete Phase 8: notifications and notification history.
4. Complete Phase 9: metrics, logs, and tracing.
5. Complete Phase 10: tests, Docker, and CI/CD.
6. Complete Phase 11: scaling beyond SQLite when measured load requires it.

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
