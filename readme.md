# EventWatch

EventWatch is a self-hosted fleet monitor for a small number of machines — a homelab, a handful of
VPSes, a lab network. A lightweight Go agent runs on each host and reports that machine's events
together with its CPU and RAM usage. A Java analytics service keeps per-host history, evaluates
alert rules scoped to each machine, notices when a machine stops reporting, and notifies an
operator. A static dashboard shows the fleet.

It is built without web frameworks, an ORM, or a DI container on either side, so every mechanism —
retries, queueing, deduplication, alert state, delivery — is visible in the code rather than hidden
behind configuration.

**Status:** Phases 1–15 complete. 228 tests pass: 180 Java, 44 Go agent, 4 load harness.
`CLAUDE.md` is the working guide for contributors and records what is deliberately deferred.

**Scope:** built for roughly 5–50 machines. It is not an APM, a log aggregator, or a metrics
warehouse, and it is not intended to replace Datadog, CloudWatch, or a SIEM.

---

## What it does

**Collects.** Each agent accepts events on `GET /capture`, samples its own machine's CPU and RAM,
and stamps every event with a stable host identity, a correlation ID, its own version, and its
pending-queue depth. One agent runs per machine.

**Never loses an event.** If the analytics service is unreachable, the agent retries with bounded
attempts, then writes the event atomically to a durable file queue. A background worker drains the
queue when the service returns. A queued file is deleted only after a `2xx`; permanent client errors
move aside to `rejected-events/` for inspection. Repeated deliveries are safe: a unique event ID
makes a retry a no-op rather than a duplicate row.

**Stores and queries.** Telemetry lands in SQLite by default, or PostgreSQL when `DATABASE_URL`
names one — the same repository code, the same API behaviour, the same test suite on both. Events,
summaries, hosts, and alerts are queryable over a bounded JSON API. `RETENTION_DAYS` prunes old
history when set.

**Alerts per machine.** The engine evaluates each machine's last five events independently, so one
busy host never drags an idle one into an alert. It raises `HIGH_CPU`, `HIGH_RAM`, `REPEATED_ERROR`,
and `AGENT_SILENT` alerts, each keyed to the machine it concerns. Thresholds are rules you can set
fleet-wide or for one machine.

**Notices silence.** A machine that stops reporting is invisible to every rule that needs an event.
A background sweep raises `agent-silent@{host}` once a machine has been quiet past its threshold,
and the machine's next event resolves the alert automatically.

**Tracks an incident's life.** Alerts move through `OPEN` → `ACKNOWLEDGED` → `RESOLVED`. Further
occurrences increment the count but never undo an acknowledgement. CPU and RAM alerts resolve
themselves when the average recovers.

**Notifies.** Every lifecycle change is POSTed as versioned JSON to a webhook, with bounded retries
and a cooldown so a sustained alert reminds rather than floods. Every delivery attempt — successful
or not — is recorded and readable per alert.

**Shows the fleet.** `GET /hosts` lists every machine with its status, last-seen time, agent version,
and queue depth. `GET /hosts/{host_id}` is the per-machine view: averages, level counts, its active
alerts, and the rules it is judged by. The dashboard renders all of it, plus a rules editor.

**Explains itself.** Both services emit one JSON object per log line, expose Prometheus metrics, and
carry a correlation ID from agent capture through to the analytics response — so one event is
traceable across both services' logs, even when it arrived hours late through the queue.

**Defends itself.** The agent binds to loopback by default and refuses to bind anywhere else without
a key. The analytics API authenticates every data route, rate-limits ingestion per client, validates
every field, and can serve TLS with certificate verification.

---

## Design decisions

The reasoning behind the parts that would otherwise look arbitrary.

**1. No frameworks, on purpose.** Spring, Gin, and Hibernate are out of scope. The point is to see
the machinery — the retry classification, the connection pooling, the alert state transitions — not
to configure someone else's. This constraint is why the Prometheus exposition is hand-rolled and why
there is no migration tool.

**2. One agent per machine, not a central ingress.** The agent samples the CPU and RAM of the
machine it runs on. Pointed at as a shared ingress, it would stamp every event with the *collector's*
resource usage, which is worse than useless. Everything downstream follows from committing to
per-host agents: per-host windows, per-host alert keys, the fleet listing.

**3. SQLite first — and a measurement that settled it.** A load harness (`loadtest/`) was built
before choosing. SQLite with a connection per query managed 60 events/s; PostgreSQL over a local
socket managed 164; SQLite pooled and in write-ahead-logging mode managed **522 at a 28 ms p50**.
Storage was never the bottleneck — opening a connection per query and syncing every commit was.
PostgreSQL is there for several agents sharing one store or retention beyond one disk, not for speed.

**4. A durable file queue instead of a message broker.** The queue loses nothing across an outage,
verified by stopping the analytics container mid-flight. Kafka or NATS would be an operational
dependency heavier than both services combined. The trigger to revisit is a second analytics
instance needing the same stream, because a file queue is point-to-point.

**5. The contract only ever gains fields.** The durable queue forces this: files written by an older
agent arrive *after* an upgrade. So `host_id`, `agent_version`, and `queue_depth` are all optional,
and an event without identity is attributed to the host `unknown` rather than rejected. A shared
fixture, `testdata/event-contract.json`, is asserted by both test suites — change the contract and
whichever side was not updated fails.

**6. The correlation ID rides in the payload, not only the header.** A header does not survive being
written to a file and replayed later. Putting it in the body means a queued event keeps its ID
through a retry hours afterwards.

**7. Thresholds are rules, and the most specific wins.** A build box at 90% CPU is healthy; a
database at 90% is not. For each rule type the machine's own rule wins, then a fleet-wide rule, then
the `.env` default. The `.env` values did not disappear — they became the bottom tier, so an install
with no rules behaves exactly as it did before rules existed. `GET /rules/effective?host_id=` reports
which tier won, because "why did this fire?" is the first question an operator asks.

**8. Alert keys carry the machine.** `cpu-high@web-01` is a different alert from `cpu-high@db-01`.
Without this, two machines fight over one row and acknowledging one silences the other. The `@`
survives a URL path segment without encoding and stays readable in a notification.

**9. Silence is a rule type, not a special case.** `AGENT_SILENT` uses the same precedence as every
other rule, so a laptop that sleeps overnight is one stored rule rather than a code path. A forget
window (`AGENT_SILENCE_FORGET_HOURS`, a week) stops machines decommissioned long ago from alerting
forever — without it the first sweep after an upgrade would alert for every machine that ever
reported, and the feature would be switched off within a day.

**10. Exposure and authentication are coupled.** The agent binds to `127.0.0.1` by default. Any
other bind *requires* `CAPTURE_API_KEY` and the agent refuses to start without it. A key that
defaults to optional stays optional; an open ingress lets anyone on that network forge telemetry for
the host and trigger its alerts.

**11. Notifications never block ingestion.** Delivery runs on a background dispatcher with bounded
retries. A webhook that is slow, down, or wrong still leaves the alert state correct — and every
attempt is recorded, so a missed notification is diagnosable instead of invisible.

**12. Everything that can grow is bounded.** Only the newest five events per machine are held in
memory; the tracked-host map is an LRU; rate-limit windows are swept on a timer; queries have a
maximum limit; the terminal report shows the top five errors from SQL rather than every distinct
message. Each of these replaced something that grew without limit.

**13. Configuration over constants.** Ports, database location, thresholds, rate limit, pool size,
shutdown grace, and silence windows all come from `.env` with in-code fallbacks. A deployment or a
test should never need a source change — and a hardcoded rate limit once made a whole benchmark
meaningless.

---

## Architecture

```text
Client or application
        ↓  GET /capture
┌─────────────────────────┐
│  Go agent (per machine) │  identity · host CPU/RAM · correlation ID
│  :8082                  │  durable queue when the backend is down
└─────────────────────────┘
        ↓  POST /receive   (API key, optionally TLS)
┌─────────────────────────┐
│  Java analytics service │  validate · persist · evaluate per-host rules
│  :8080                  │  silence sweep · retention · notifications
└─────────────────────────┘
        ↓                        ↓
   SQLite / PostgreSQL      webhook + Prometheus + JSON logs
        ↑
   Dashboard :3000  (query API only, never the database)
```

One event, end to end:

```text
Agent captures the event and samples its own machine
    ↓
Stamps event_id, correlation_id, host_id, hostname, agent_version, queue_depth
    ↓
Authenticated HTTP request to the analytics service
    ↓
Analytics validates the key, content type, size, fields, and value ranges
    ↓
Commits to the configured database (a repeat event_id is ignored)
    ↓
Alert engine evaluates the last five events for that machine only
    ↓
Alert state is created, updated, or resolved for that machine
    ↓
Lifecycle changes go to the notification dispatcher; every attempt is recorded
    ↓
JSON response carrying the correlation ID back to the agent
```

If the analytics service is unavailable, the agent returns `503` after bounded retries and writes the
event to `pending-events/`. A background worker retries it later.

---

## HTTP API

**Agent, port 8082**

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/capture?level=&msg=` | none on loopback, key when exposed | Submit an event |
| GET | `/health` | none | Availability, identity, version, queue depth |
| GET | `/metrics` | none | Prometheus text format |
| GET | `/stress` | same as `/capture` | 500 synthetic events, 32 concurrent |

**Analytics, port 8080.** Every data route requires `X-EventWatch-Key`.

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/receive` | Ingest one event; rate limited per client |
| GET | `/health` | Availability and database reachability (no key) |
| GET | `/metrics` | Prometheus text format (key only if `METRICS_REQUIRE_KEY`) |
| GET | `/events?level=&host_id=&from=&to=&limit=&offset=` | Event history, limit ≤ 200 |
| GET | `/summary` | Totals, active alerts, host count, five-event averages |
| GET | `/hosts?limit=` | Fleet listing with status, version, queue depth |
| GET | `/hosts/{host_id}` | One machine: averages, level counts, alerts, rules |
| GET | `/alerts?status=&type=` | Active alerts |
| GET | `/alerts/{alert_key}` | One alert |
| POST | `/alerts/{alert_key}/acknowledge` | `OPEN` → `ACKNOWLEDGED` |
| POST | `/alerts/{alert_key}/resolve` | → `RESOLVED` |
| GET | `/alerts/{alert_key}/notifications?limit=` | Delivery attempts for that alert |
| GET | `/rules` | Stored rules plus the `.env` defaults beneath them |
| PUT | `/rules` | Create or replace one rule |
| DELETE | `/rules?rule_type=&host_id=` | Remove an override |
| GET | `/rules/effective?host_id=` | What applies to one machine, and from which tier |

Every endpoint answers JSON. Success uses `status: "ok"`; failures use `status: "error"` with a
readable `message`.

### The event contract

Agent → analytics, `POST /receive`, `Content-Type: application/json`:

```json
{
  "event_id": "8f14e45f-ceea-167a-5a36-dedd4bea2543",
  "correlation_id": "2b1f7c90-4d5e-4a11-9d3c-71b0f0a9c8e2",
  "host_id": "9c1f4b2e-7a35-4c88-b0d1-3e6a9f2c5d47",
  "hostname": "web-01",
  "agent_version": "0.15.0",
  "queue_depth": 0,
  "level": "ERROR",
  "msg": "High CPU Saturation Alert",
  "timestamp": "2026-09-04T18:46:00Z",
  "cpu_usage": 88.4,
  "ram_usage": 12.1
}
```

`level` is one of `INFO`, `WARN`, `ERROR`, `CRITICAL`. `msg` is 1–1000 characters. `event_id` is
1–128 characters and unique — a partial unique index makes retries idempotent. Usage values are
finite numbers from 0 to 100. Identity and version fields are optional but bounded to 128 characters
when present; `queue_depth` must be zero or more. Bodies are limited to 64 KiB.

---

## Project structure

```text
go-collector/                   Go agent, one per machine
	main.go                        Handlers, forwarding, durable queue
	identity.go                    Stable host_id, persisted beside the queue
	security.go                    Bind policy, capture auth, backend TLS trust
	logging.go                     Structured JSON log lines
	metrics.go                     Prometheus counters and /metrics
	Dockerfile
java-analytics/                 Maven Java analytics service
	.mvn/jvm.config                Maven JVM memory settings
	src/main/java/com/main/
		AnalyticsEngine.java           HTTP routing, validation, ingestion
		EngineConfiguration.java       Every runtime setting
		Database.java                  Backend selection, pooling, schema
		ConnectionProvider.java        Where repositories get connections
		SqlDialect.java                Where SQLite and PostgreSQL differ
		SqliteDialect.java / PostgresDialect.java
		EventRepository.java           Telemetry queries and the fleet listing
		AlertEngine.java               Per-host threshold and error rules
		AlertRecord.java / AlertStatus.java / AlertTransition.java
		AlertRepository.java           Alert state and lifecycle transitions
		AlertRule.java / AlertRuleRepository.java
		AlertRules.java                Host, fleet, default precedence and validation
		AgentSilenceMonitor.java       Alerts on a machine that stopped reporting
		QueryService.java              JSON shaping for the query API
		NotificationService.java       Webhook dispatch, cooldown, retries
		NotificationRecord.java / NotificationRepository.java
		RetentionService.java          Prunes history past the window
		TlsSupport.java                HTTPS listener from a keystore
		Metrics.java                   Prometheus counters and gauges
		StructuredLogger.java          One JSON object per log line
	src/test/java/com/main/            180 tests, including a live PostgreSQL suite
	Dockerfile
dashboard/                      Static browser dashboard (index.html, app.js, styles.css)
loadtest/                       Throughput and latency harness
testdata/event-contract.json    Cross-language JSON contract fixture
docker-compose.yml              Agent, analytics, dashboard, optional PostgreSQL
.github/workflows/ci.yml        Build, test, scan, and image pipeline
```

---

## Getting started

**Requirements:** Go 1.27+, Java 17+, Apache Maven, and Docker with Compose only for the container
workflow. Internet access is needed for the first dependency download.

Copy `.env.example` to `.env` and set `EVENTWATCH_API_KEY`. Both services read the repository-root
`.env` automatically. It is gitignored.

Maven does not read `.env`. The committed `.mvn/jvm.config` applies `-Xms64m -Xmx128m` to every
Maven command here, so no manual `MAVEN_OPTS` is needed; the matching `.env` entry documents the
intent for tools that load dotenv values.

Start the analytics service:

```powershell
cd java-analytics
mvn compile exec:java
```

In a second terminal, start the agent:

```powershell
cd go-collector
go run .
```

In a third, serve the dashboard and open `http://localhost:3000`, entering `EVENTWATCH_API_KEY` when
it asks. Use that exact origin, or add yours to `CORS_ALLOWED_ORIGINS`.

```powershell
python -m http.server 3000 -d dashboard
```

### Send an event

```powershell
curl.exe "http://localhost:8082/capture?level=ERROR&msg=Database%20transaction%20deadlock"
curl.exe "http://localhost:8082/capture?level=INFO&msg=User%20login%20successful"
```

Omitting `level` or `msg` yields `INFO` and `Default cloud event`. Under `LOG_FORMAT=text` the
analytics terminal also prints a live report of totals, averages, and the top repeated errors.

### Work with alerts

```powershell
curl.exe "http://localhost:8080/alerts" -H "X-EventWatch-Key: local-secret"
curl.exe -X POST "http://localhost:8080/alerts/cpu-high@web-01/acknowledge" -H "X-EventWatch-Key: local-secret"
curl.exe -X POST "http://localhost:8080/alerts/cpu-high@web-01/resolve" -H "X-EventWatch-Key: local-secret"
curl.exe "http://localhost:8080/alerts/cpu-high@web-01/notifications?limit=10" -H "X-EventWatch-Key: local-secret"
```

Acknowledging does not reset an alert: further occurrences keep incrementing the count while the
status stays `ACKNOWLEDGED`. Resolve it once the underlying issue is fixed.

---

## Running a fleet

Install the agent on each machine and point them all at one analytics service:

```powershell
$env:JAVA_BACKEND_URL="http://analytics-host:8080/receive"; go run .
```

Each agent generates a stable identity on first start and stores it beside its queue, so a restart
is not mistaken for a new machine. `HOST_ID` pins it explicitly for containers or config management;
`HOSTNAME_OVERRIDE` renames the reported hostname without changing the identity. Nothing else needs
configuring — the analytics service creates per-host windows and alert keys as machines appear.

```powershell
# Every machine, newest activity first
curl.exe "http://localhost:8080/hosts" -H "X-EventWatch-Key: local-secret"

# One machine: averages, level counts, its alerts, and the rules it is judged by
curl.exe "http://localhost:8080/hosts/web-01" -H "X-EventWatch-Key: local-secret"

# That machine's events only
curl.exe "http://localhost:8080/events?host_id=web-01" -H "X-EventWatch-Key: local-secret"
```

Each fleet row carries `status` (`reporting` or `silent`), `silent_seconds`, `agent_version`, and
`queue_depth`. A rising queue depth means that agent is holding events the analytics service has not
accepted — the early warning that the pipeline is degrading before anything is lost.

### Silence detection

A background sweep raises `agent-silent@{host}` once a machine has been quiet past its threshold,
and the machine's next event resolves the alert. `AGENT_SILENCE_MINUTES` (default 10) is the
fleet-wide default and `AGENT_SILENCE_SWEEP_SECONDS` (default 60) is how often the check runs.

Silence is an ordinary rule type, so one machine can have its own window — a laptop that sleeps
overnight — or be exempted entirely with `"enabled": false`:

```powershell
curl.exe -X PUT "http://localhost:8080/rules" -H "X-EventWatch-Key: local-secret" `
  -H "Content-Type: application/json" -d '{"rule_type":"AGENT_SILENT","host_id":"laptop-01","threshold":240}'
```

---

## Alert rules

Thresholds are rules stored in the database rather than fixed values in `.env`. For each rule type —
`HIGH_CPU`, `HIGH_RAM`, `REPEATED_ERROR`, `AGENT_SILENT` — the most specific rule wins:

1. a rule for that machine,
2. a fleet-wide rule,
3. the `.env` default (`CPU_ALERT_THRESHOLD`, `RAM_ALERT_THRESHOLD`, `REPEATED_ERROR_THRESHOLD`,
   `AGENT_SILENCE_MINUTES`).

With no rules stored, every machine uses the defaults. Manage them from the dashboard's **Alert
rules** panel or the API:

```powershell
# The build box may run hot; every other machine keeps the default
curl.exe -X PUT "http://localhost:8080/rules" -H "X-EventWatch-Key: local-secret" `
  -H "Content-Type: application/json" -d '{"rule_type":"HIGH_CPU","host_id":"build-01","threshold":95}'

# What applies to a machine, and which tier it came from
curl.exe "http://localhost:8080/rules/effective?host_id=build-01" -H "X-EventWatch-Key: local-secret"

# Remove the override; the machine falls back to the fleet rule or the default
curl.exe -X DELETE "http://localhost:8080/rules?rule_type=HIGH_CPU&host_id=build-01" -H "X-EventWatch-Key: local-secret"
```

Omit `host_id` for a fleet-wide rule. CPU and RAM thresholds are percentages from 0 to 100. A
`REPEATED_ERROR` threshold is a whole number from 1 to 5, because the rule only sees each machine's
last five events — a larger value could never fire, so it is refused rather than stored silently.
An `AGENT_SILENT` threshold is whole minutes.

A disabled rule on one machine still takes precedence over a fleet-wide rule, and an alert it raised
resolves on that machine's next event. Every change applies from the next event.

Repeated-error alerts are not resolved automatically — an error has no equivalent of CPU load
recovering — so resolve them once handled.

---

## Securing a deployment

**Agent exposure.** `COLLECTOR_BIND` defaults to `127.0.0.1`, accepting events only from processes
on its own machine. Any other bind requires `CAPTURE_API_KEY`, sent as `X-EventWatch-Key` on
`/capture` and `/stress`; the agent refuses to start otherwise. `CAPTURE_REQUIRE_KEY=true` demands a
key even on loopback.

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
first run, never for a real deployment, and the agent logs a warning whenever it is set.

**CORS.** `CORS_ALLOWED_ORIGINS` is a comma-separated allowlist. An origin outside it receives no
`Access-Control-Allow-Origin` header; an empty list allows nothing.

**Metrics.** `/metrics` is open by default because scrapers rarely send custom headers. Set
`METRICS_REQUIRE_KEY=true` to close the analytics endpoint; restrict the agent's at the network
layer.

**Known gap.** The dashboard holds the API key in page memory for the session — cleared from the
input once you connect and never written to storage, but still reachable by script running on that
page. Closing it properly needs same-origin serving and an HttpOnly session cookie. Treat dashboard
access as equivalent to holding the key.

---

## Storage backends

SQLite is the default and needs no external service. Setting `DATABASE_URL` to a PostgreSQL JDBC URL
switches the backend; the API, the alert rules, and the JSON contract behave identically, and the
same test suite runs against both.

```powershell
# SQLite (default): DATABASE_URL empty, DATABASE_PATH names the file
DATABASE_URL=
DATABASE_PATH=events.db

# PostgreSQL
DATABASE_URL=jdbc:postgresql://localhost:5432/eventwatch
DATABASE_USER=eventwatch
DATABASE_PASSWORD=eventwatch
```

Both backends pool connections, sized by `DATABASE_POOL_SIZE`. SQLite additionally runs in
write-ahead-logging mode, which is what makes the default backend fast enough not to be the
constraint. Timestamps are stored as ISO-8601 text on both, so ordering, range filters, and stored
values mean exactly the same thing either way.

`RETENTION_DAYS` deletes telemetry and delivery history older than that window, swept every
`RETENTION_SWEEP_MINUTES`. The default of `0` keeps everything — right for a local install, wrong
for a long-running one.

---

## Observability

Both services log one JSON object per line, so output can be shipped and queried without parsing
prose:

```json
{"correlation_id":"trace-abc-123","event_id":"d7b27bde-...","event_level":"ERROR","level":"INFO","message":"event stored","service":"java-analytics","timestamp":"2026-09-11T06:58:13.549Z"}
```

Reserved keys — `timestamp`, `level`, `service`, `message` — are always the log's own; an event's
level appears as `event_level` so it can never clobber them. `LOG_FORMAT=text` switches both services
to human-readable lines and enables the analytics terminal report; under `json` the same numbers are
emitted as a `telemetry snapshot` line instead, because ASCII art would corrupt a log stream.

Every captured event gets a correlation ID. A caller may supply one with `X-Correlation-ID`, and the
agent generates one otherwise. It travels in both the request header and the payload, so a queued
event keeps it through a retry, and the analytics service echoes it in the response header and body.

```powershell
curl.exe http://localhost:8082/metrics
curl.exe http://localhost:8080/metrics
```

The agent reports captured events by level, forwarding outcomes, queue depth, writes, drops and
rejections, and forwarding latency. The analytics service reports events received, duplicated, and
rejected by reason, database failures, notification outcomes, HTTP responses by route and status,
active alerts, silent agents, stored rows, and processing latency.

---

## Docker

```powershell
docker compose up --build
```

Compose starts analytics on `8080`, the agent on `8082`, and the dashboard on `3000`, reading the
same root `.env`. Telemetry and the pending queue live on named volumes, so a container restart
keeps both history and undelivered events. The agent waits for the analytics health check first.

Because a published container port is reachable, the agent image binds to every interface and the
security model therefore requires `CAPTURE_API_KEY`; Compose supplies it from `EVENTWATCH_API_KEY`
by default.

For PostgreSQL, enable its profile and point the analytics service at it with
`DATABASE_URL=jdbc:postgresql://postgres:5432/eventwatch` in `.env`:

```powershell
docker compose --profile postgres up --build
```

---

## Tests

All three suites run offline and need no services started.

```powershell
cd java-analytics; mvn verify
cd go-collector; go vet ./...; go test ./...
cd loadtest; go vet ./...; go test ./...
```

The Java suite covers event validation, query-parameter parsing, persistence and deduplication on
both backends, per-host alert rules and lifecycle, silence detection, webhook delivery against a
local sink, the engine start/stop lifecycle, retention, TLS with a generated certificate, and the
metric and log formats. An end-to-end pass drives the real HTTP server on an ephemeral port with a
temporary database, covering restart recovery, two machines staying independent, per-host rules
changing which machines alert, and a silent machine raising and then resolving its own alert.

The Go suite covers retry classification, the capture handler, durable-queue outcomes, correlation
IDs, host identity and its persistence across restarts, the bind and authentication policy, and
metric rendering, using an `httptest` stand-in for the analytics service.

The PostgreSQL suites are skipped unless `EVENTWATCH_TEST_POSTGRES_URL` points at a reachable
server, so a checkout without PostgreSQL still builds; CI supplies one as a service container.

---

## Measured throughput

`loadtest/` measures ingestion so scaling decisions rest on numbers rather than guesses.

```powershell
cd loadtest
go run . -mode analytics -events 3000 -concurrency 16
```

Measured on one developer machine, 3000 events at concurrency 16, rate limit raised:

| Backend | Throughput | p50 | p99 |
| --- | --- | --- | --- |
| SQLite, connection per query | 60 events/s | 260 ms | 461 ms |
| PostgreSQL in Docker, pooled | 164 events/s | 90 ms | 150 ms |
| SQLite, pooled with write-ahead log | 522 events/s | 28 ms | 84 ms |

Move to PostgreSQL when several agents must share one store, or for retention longer than a single
disk holds — not for throughput.

The agent's `/stress` route sends 500 synthetic events with a bounded fan-out of 32. Ingestion is
rate limited to `RATE_LIMIT_PER_MINUTE` per client, so most of that burst is answered with `429`,
written to the pending queue, and drained over the following minutes. Nothing is dropped — that is
the system working, not a failure.

---

## Configuration reference

Everything lives in the repository-root `.env`; copy `.env.example` to start. Each setting has an
in-code fallback, so an absent key is never fatal.

| Setting | Default | Purpose |
| --- | --- | --- |
| `EVENTWATCH_API_KEY` | — | Shared key for analytics data routes. Required. |
| `JAVA_BACKEND_URL` | `http://localhost:8080/receive` | Where the agent sends events |
| `HTTP_PORT` / `COLLECTOR_PORT` | `8080` / `8082` | Listening ports |
| `DATABASE_URL` | empty | PostgreSQL JDBC URL; empty means SQLite |
| `DATABASE_PATH` | `events.db` | SQLite file when `DATABASE_URL` is empty |
| `DATABASE_USER` / `DATABASE_PASSWORD` | empty | PostgreSQL credentials |
| `DATABASE_POOL_SIZE` | `10` | Connection pool size, both backends |
| `SHUTDOWN_GRACE_SECONDS` | `5` | Drain window before the pool is released |
| `COLLECTOR_BIND` | `127.0.0.1` | Agent bind address; anything else needs a key |
| `CAPTURE_API_KEY` | empty | Key for the agent's own ingress |
| `CAPTURE_REQUIRE_KEY` | `false` | Demand that key even on loopback |
| `BACKEND_CA_FILE` | empty | CA the agent trusts for the analytics certificate |
| `BACKEND_TLS_SKIP_VERIFY` | `false` | Encrypt without verifying. Never in production |
| `TLS_ENABLED` | `false` | Serve the analytics API over HTTPS |
| `TLS_KEYSTORE_PATH` / `_PASSWORD` / `_TYPE` | empty / empty / `PKCS12` | Keystore for TLS |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://127.0.0.1:3000` | Dashboard origin allowlist |
| `METRICS_REQUIRE_KEY` | `false` | Close the analytics `/metrics` endpoint |
| `HOST_ID` / `HOSTNAME_OVERRIDE` / `HOST_ID_FILE` | empty | Pin agent identity, rename it, or relocate its file |
| `PENDING_EVENTS_DIR` | `pending-events` | Durable queue location |
| `QUEUE_CAPACITY` | `1000` | Maximum queued events before drops are reported |
| `QUEUE_RETRY_SECONDS` | `5` | How often the recovery worker retries |
| `RATE_LIMIT_PER_MINUTE` | `100` | Ingestion limit per client address |
| `RETENTION_DAYS` | `0` | Prune history past this window; `0` keeps everything |
| `RETENTION_SWEEP_MINUTES` | `60` | How often retention runs |
| `CPU_ALERT_THRESHOLD` / `RAM_ALERT_THRESHOLD` | `85` / `80` | Default percentage thresholds |
| `REPEATED_ERROR_THRESHOLD` | `5` | Repeats of one message within the five-event window |
| `AGENT_SILENCE_MINUTES` | `10` | Default silence threshold |
| `AGENT_SILENCE_FORGET_HOURS` | `168` | Beyond this a machine counts as decommissioned |
| `AGENT_SILENCE_SWEEP_SECONDS` | `60` | How often silence is checked |
| `LOG_FORMAT` | `json` | `json` or `text` for both services |
| `NOTIFICATIONS_ENABLED` | `false` | Turn webhook delivery on |
| `NOTIFICATION_WEBHOOK_URL` | empty | Where lifecycle changes are POSTed |
| `NOTIFICATION_TIMEOUT_SECONDS` | `5` | Per-attempt timeout |
| `NOTIFICATION_MAX_ATTEMPTS` | `3` | Bounded retries per delivery |
| `NOTIFICATION_RETRY_DELAY_MILLIS` | `1000` | Pause between attempts |
| `NOTIFICATION_REMINDER_SECONDS` | `900` | Cooldown before a sustained alert reminds again |
| `MAVEN_OPTS` | `-Xms64m -Xmx128m` | Documents the intent of `.mvn/jvm.config` |

---

## Limitations and what is next

- **The dashboard session.** The API key lives in page memory; closing that needs same-origin
  serving and an HttpOnly cookie.
- **One analytics instance.** Alert rules are cached per process and notification cooldowns are
  held in memory, so a second instance would need cache invalidation and shared reservations.
- **Nothing watches the watcher.** If the host running the analytics service dies, nothing reports
  it. The honest answer is an external uptime check rather than more code here.
- **Deferred on evidence, not forgotten:** a message broker in place of the file queue, and
  splitting into separate services. Neither has a measured problem to solve yet; `CLAUDE.md` records
  the trigger conditions for revisiting both, along with OpenTelemetry tracing.

---

## Phase history

The project was built in phases; each is a single commit.

| Phase | Focus | Outcome |
| --- | --- | --- |
| 1 | JSON event pipeline | Go-to-Java forwarding, JSON parsing, error filtering, grouped counts |
| 2 | Host telemetry | Live CPU/RAM collection, timestamps, five-event moving averages |
| 3 | Persistence | SQLite storage, transactional inserts, startup recovery, request limits |
| 4 | Security and input | Dotenv config, API-key auth, validation, rate limiting, bounded retries |
| 5 | Reliability | Durable queue, recovery worker, event-ID deduplication, health endpoints |
| 6 | Alert rules | Configurable thresholds, moving-window detection, repeated-error alerts |
| 7 | Query API and dashboard | Bounded queries, summaries, alert workflows, browser dashboard |
| 8 | Notifications | Webhook delivery, cooldown reminders, bounded retries, delivery audit trail |
| 9 | Observability | Structured JSON logs, end-to-end correlation IDs, Prometheus metrics |
| 10 | Testing and delivery | Unit, integration, failure and contract tests; Docker, Compose, CI |
| 11 | Beyond SQLite | Pluggable storage, PostgreSQL, pooling, retention, load harness |
| 12 | Host identity | Stable per-agent identity, per-host windows and alert keys, fleet listing |
| 13 | Agent security | Loopback binding, mandatory auth when exposed, TLS, configurable CORS |
| 14 | Per-host alert rules | Rules table, host → fleet → default precedence, rules API and editor |
| 15 | Fleet operations | Silence detection, per-machine drill-down, agent version and queue depth |

`agent.md` is the original roadmap, kept for history. `CLAUDE.md` is the current authority on state,
conventions, and what comes next.
