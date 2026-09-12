# EventWatch

EventWatch is a self-hosted fleet monitor for a small number of machines — a homelab, a handful of
VPSes, a lab network. A lightweight Go agent runs on each host and reports that machine's events
together with its CPU, RAM, and disk usage. A Java analytics service keeps per-host history,
evaluates alert rules scoped to each machine, notices when a machine stops reporting, and notifies
an operator. A static dashboard shows the fleet.

It is built without web frameworks, an ORM, or a DI container on either side, so every mechanism —
retries, queueing, deduplication, alert state, delivery — is visible in the code rather than hidden
behind configuration.

**Status:** Phases 1–21 complete. 323 tests pass: 255 Java, 64 Go agent, 4 load harness, plus a
scripted two-process outage test that runs in CI on every push.
`CLAUDE.md` is the working guide for contributors and records what is deliberately deferred.

**Scope:** built for roughly 5–50 machines. It is not an APM, a log aggregator, or a metrics
warehouse, and it is not intended to replace Datadog, CloudWatch, or a SIEM.

**Is it production-ready?** For its actual scope — a homelab, a personal VPS fleet, or a small
team's internal machines, run by someone who reads this file — yes. Every reliability claim here
is measured or tested rather than assumed: throughput numbers come from a load harness, an outage
losing zero events was verified in running containers, and the security settings are proven with a
live TLS handshake and a live cookie inspection rather than trusted on paper.

Past that scope, the gap is specific rather than vague. There is no user identity: every signed-in
operator is equivalent, so there is no record of *who* acknowledged or resolved an alert. There is
exactly one analytics instance with no failover — an accepted tradeoff, not an oversight, but a
real one.
Sessions live in memory, so restarting the service signs every operator out. And there is no
secrets manager, no encryption at rest, and no tested restore procedure — just a database file you
are responsible for. None of that is a defect to be fixed quietly; it is the honest boundary of
what a system built and reviewed by one person, at this scope, can claim.

---

## What it does

**Collects.** Each agent accepts events on `GET /capture`, samples its own machine's CPU, RAM, and
fullest filesystem, and stamps every event with a stable host identity, a correlation ID, its own
version, and its pending-queue depth. One agent runs per machine.

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
busy host never drags an idle one into an alert. It raises `HIGH_CPU`, `HIGH_RAM`, `HIGH_DISK`,
`REPEATED_ERROR`, and `AGENT_SILENT` alerts, each keyed to the machine it concerns. Thresholds are
rules you can set fleet-wide or for one machine.

**Watches the disk that will fill first.** The agent reports its fullest filesystem and names the
mount, so an alert reads `C: is 96.6% (threshold 90.0%)` rather than a percentage with no home. A
full disk is the most common way a service dies quietly on a small fleet.

**Notices silence.** A machine that stops reporting is invisible to every rule that needs an event.
A background sweep raises `agent-silent@{host}` once a machine has been quiet past its threshold,
and the machine's next event resolves the alert automatically.

**Tracks an incident's life.** Alerts move through `OPEN` → `ACKNOWLEDGED` → `RESOLVED`. Further
occurrences increment the count but never undo an acknowledgement. CPU, RAM, and disk alerts
resolve themselves once the machine recovers.

**Notifies.** Every lifecycle change is POSTed as versioned JSON to a webhook, with bounded retries
and a cooldown so a sustained alert reminds rather than floods. Every delivery attempt — successful
or not — is recorded and readable per alert.

**Shows the fleet.** `GET /hosts` lists every machine with its status, last-seen time, agent version,
and queue depth. `GET /hosts/{host_id}` is the per-machine view: averages, level counts, its active
alerts, and the rules it is judged by. The dashboard renders all of it, plus a rules editor.

**Explains itself.** Both services emit one JSON object per log line, expose Prometheus metrics, and
carry a correlation ID from agent capture through to the analytics response — so one event is
traceable across both services' logs, even when it arrived hours late through the queue.

**Reports its own failure.** The analytics service sends a heartbeat to an outside endpoint while
it is healthy, so silence there means it died. The agent covers the other direction: when nothing
has reached analytics for a while, the agent — which is still running — says so through a webhook
of its own.

**Signs operators in.** The dashboard is served by the analytics service, so the two share an
origin. The API key is presented once, to `POST /session`, and exchanged for an `HttpOnly`
cookie the page itself cannot read.

**Gives every agent its own key.** `POST /agents` mints a token bound to one machine; a leaked
agent is revoked alone, not rotated fleet-wide, and it can only ever report as the host it was
minted for.

**Proves it survives an outage, on every push.** A CI job builds both real images, kills analytics
mid-traffic, and asserts the durable queue drains to zero loss once it returns — the thing that
used to be reverified by hand at the end of each phase.

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

**13. A monitor needs two ways to report its own death.** The analytics heartbeat and the agent's
stall alert are deliberately independent: a single mechanism covering both would share the failure
it exists to detect. In both cases silence is the signal — the heartbeat stops when the service
dies, and is withheld on purpose when the database is unreachable, because a ping asserting health
from a service that cannot store anything is worse than none.

**14. Configuration over constants.** Ports, database location, thresholds, rate limit, pool size,
shutdown grace, and silence windows all come from `.env` with in-code fallbacks. A deployment or a
test should never need a source change — and a hardcoded rate limit once made a whole benchmark
meaningless.

**15. The credential the dashboard holds should not be the one that opens everything.** Serving
the page from the analytics service makes it same-origin, and that one change is what lets the key
be exchanged for an `HttpOnly` cookie the page cannot read, removes the CORS allowlist, and deletes
a container. A feature that takes moving parts away is usually the right shape.

**16. One class per route, one engine per instance.** Routing used to be six hundred lines of inline
lambdas over static fields, which meant a single engine could run in a JVM. Each route is now its
own class over a shared context, and the cross-cutting work every route repeated — the preflight,
the method check, the API key, and turning a bad parameter into 400 and unreachable storage into 503
— lives in one base class. The test that proves it starts two engines side by side.

**17. A level is judged on its newest reading; a rate is judged on a window.** CPU and RAM are
averaged over the last five events because they are spiky and the average is a noise filter. Disk is
not: a filesystem moves over hours, so averaging only delays the alert — and because the window is
counted in events rather than minutes, a machine that reports rarely would alert on an average
spanning hours, blending two different mounts if the fullest one changed. Disk therefore alerts on
the latest reading, and the dashboard says "(latest)" where it says "last 5 events" for the others.

**18. A credential should bind an identity, not just gate a request.** A per-agent token is minted
for one host, and ingestion stamps every event with that host regardless of what the payload
claims — the token is the source of truth, not the message. This closes a gap the shared key could
never close: with one key for the whole fleet, any caller holding it could claim to be any machine.

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
   Dashboard (served by the service at :8080, query API only, never the database)
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

**Analytics, port 8080.** Every route below marked "key" accepts the `X-EventWatch-Key`
header or a session cookie, except `/receive`, which never accepts a session — a browser has no
business posting telemetry, and an agent has no cookie jar.

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| POST | `/receive` | shared key or agent token | Ingest one event; rate limited per client |
| GET | `/health` | none | Availability and database reachability |
| GET | `/metrics` | none, or key if `METRICS_REQUIRE_KEY` | Prometheus text format |
| POST | `/session` | key, in the body | Exchange the key for a session cookie |
| GET | `/session` | key or cookie | Whether a session is currently valid |
| DELETE | `/session` | none | Revoke the presented cookie |
| GET | `/agents` | key | Every issued credential; never the token or its hash |
| POST | `/agents` | key | Mint one: `host_id`, optional `label`; token shown once |
| DELETE | `/agents/{id}` | key | Revoke; the row is kept for history |
| GET | `/events?level=&host_id=&from=&to=&limit=&offset=` | key | Event history, limit ≤ 200 |
| GET | `/summary` | key | Totals, active alerts, host count, five-event averages |
| GET | `/hosts?limit=` | key | Fleet listing with status, version, queue depth |
| GET | `/hosts/{host_id}` | key | One machine: averages, level counts, alerts, rules |
| GET | `/alerts?status=&type=` | key | Active alerts |
| GET | `/alerts/{alert_key}` | key | One alert |
| POST | `/alerts/{alert_key}/acknowledge` | key | `OPEN` → `ACKNOWLEDGED` |
| POST | `/alerts/{alert_key}/resolve` | key | → `RESOLVED` |
| GET | `/alerts/{alert_key}/notifications?limit=` | key | Delivery attempts for that alert |
| GET | `/rules` | key | Stored rules plus the `.env` defaults beneath them |
| PUT | `/rules` | key | Create or replace one rule |
| DELETE | `/rules?rule_type=&host_id=` | key | Remove an override |
| GET | `/rules/effective?host_id=` | key | What applies to one machine, and from which tier |

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
  "ram_usage": 12.1,
  "disk_usage": 91.7,
  "disk_path": "/var"
}
```

`level` is one of `INFO`, `WARN`, `ERROR`, `CRITICAL`. `msg` is 1–1000 characters. `event_id` is
1–128 characters and unique — a partial unique index makes retries idempotent. Usage values are
finite numbers from 0 to 100. Identity and version fields are optional but bounded to 128 characters
when present; `queue_depth` must be zero or more. Bodies are limited to 64 KiB.

Authenticating with a per-agent token overrides `host_id` regardless of what the body claims: the
token is the source of truth for identity, not the payload. `host_id` can be left out entirely when
a token is in use — the identity comes from which credential was presented.

`disk_usage` is the fullest filesystem the agent can see, and `disk_path` names that mount. Both are
omitted entirely when no filesystem can be read — never sent as `0`, which would read as the
healthiest possible machine.

---

## Project structure

```text
go-collector/                   Go agent, one per machine
	main.go                        Handlers, forwarding, durable queue
	identity.go                    Stable host_id, persisted beside the queue
	security.go                    Bind policy, capture auth, backend TLS trust
	watchdog.go                    Delivery-health tracking and agent alerts
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
		WatchdogHeartbeat.java         Dead-man switch: a heartbeat while healthy
		EngineContext.java             Everything one running engine owns
		ApiHandler.java                What every authenticated route repeats
		*Handler.java                  One class per route
		HttpSupport.java               Key or cookie auth, the session cookie, responses
		SessionHandler.java            Sign in, probe, and sign out
		SessionStore.java              In-memory operator sessions
		DashboardHandler.java          Static files at /, with the traversal check
		Metrics.java                   Prometheus counters and gauges
		StructuredLogger.java          One JSON object per log line
	src/test/java/com/main/            180 tests, including a live PostgreSQL suite
	Dockerfile
dashboard/                      Browser dashboard, served by the analytics service
loadtest/                       Throughput and latency harness
testdata/event-contract.json    Cross-language JSON contract fixture
docker-compose.yml              Agent and analytics, optional PostgreSQL
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

Then open `http://localhost:8080` and sign in with `EVENTWATCH_API_KEY`. The analytics service
serves the dashboard itself, from `DASHBOARD_DIR`, so there is no second server to start and no
origin to configure.

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

`HIGH_DISK` is judged on the machine's latest reading rather than the five-event average the other
percentage rules use — a filesystem is a level, not a spike, and averaging it would only delay the
alert.

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
`/capture`; the agent refuses to start otherwise. `CAPTURE_REQUIRE_KEY=true` demands a
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

**Operator sessions.** The key is sent once, to `POST /session`, and comes back as an `HttpOnly`,
`SameSite=Strict` cookie. `HttpOnly` keeps it out of reach of any script on the page, including an
injected one; `SameSite=Strict` means a request from another site never carries it, which is what
stands in for a CSRF token. Sign-in has its own rate limit, tighter than ingestion, because it is
the one route that accepts an unauthenticated request and checks a secret. `DELETE /session` revokes
the token on the server, not just in the browser.

Sessions are held in memory, so restarting the service signs everyone out. Agents and scripts are
unaffected: they have no cookie jar and keep sending `X-EventWatch-Key`.

**Metrics.** `/metrics` is open by default because scrapers rarely send custom headers. Set
`METRICS_REQUIRE_KEY=true` to close the analytics endpoint; restrict the agent's at the network
layer.

**Serving the dashboard elsewhere.** There is no CORS configuration any more. If the page has to
live on another origin, put both behind one reverse proxy so they still share one — the cookie
depends on it.

**Per-agent credentials.** `POST /agents` mints a token bound to one `host_id`; the agent sends it
as `AGENT_TOKEN` instead of the fleet-wide key. A leaked or decommissioned agent is revoked alone
with `DELETE /agents/{id}` — nothing else needs to change. The bound host is enforced, not merely
recorded: an event authenticated by that token always lands under its bound host, whatever `host_id`
the payload itself claims, which is what actually stops one agent from spoofing another's identity —
something the shared key never prevented. Multiple tokens per host are allowed on purpose, so a
credential can be rotated by minting the replacement before revoking the original.

The shared key still works everywhere it always has, gated by `SHARED_KEY_INGESTION_ENABLED`
(default `true`). Turn it off once every agent holds its own token; `eventwatch_receive_auth_total`
in `/metrics` shows the split between the two, which is how you know it is safe to.

---

## Watchdog

A monitor that cannot report its own death is only half a monitor. Two independent halves cover the
two ways that happens, and neither needs another service running.

**The analytics service sends a heartbeat.** Point `WATCHDOG_URL` at any endpoint that accepts a
POST — healthchecks.io, Uptime Kuma, a cron ping — and it receives a small JSON body every
`WATCHDOG_INTERVAL_SECONDS`:

```json
{"schema_version":"eventwatch.heartbeat.v1","service":"java-analytics",
 "timestamp":"2026-09-11T23:53:44Z","hosts":4,"active_alerts":1}
```

When the host dies the heartbeat stops and that endpoint alerts. The ping is **deliberately skipped
while the database is unreachable**: a heartbeat still arriving from a service that cannot store
anything would be worse than none, so silence is the signal there too.

**The agent reports a stalled delivery.** The agent is the part still running when analytics is
unreachable, so it is the only part that can say so — and it already knows, because its queue is
growing. Set `AGENT_ALERT_WEBHOOK_URL` and it posts when nothing has been delivered for
`DELIVERY_STALL_MINUTES` and the most recent attempt failed:

```json
{"schema_version":"eventwatch.agent_alert.v1","event":"delivery_stalled","host_id":"...",
 "hostname":"web-01","agent_version":"0.15.0","queue_depth":1,"stalled_seconds":62,
 "backend_url":"http://analytics:8080/receive","timestamp":"2026-09-11T23:55:12Z"}
```

A matching `delivery_recovered` follows when events flow again. Both conditions are required on
purpose: an agent with no traffic sends nothing and fails nothing, so silence alone must never look
like an outage. A permanent `4xx` is excluded too — the service answered and refused that one event,
which says nothing about whether it is reachable.

Each side is also visible in the agent's metrics as `eventwatch_delivery_healthy`,
`eventwatch_delivery_stall_alerts_total`, and `eventwatch_delivery_recovery_alerts_total`, and in
the analytics metrics as `eventwatch_watchdog_pings_total` by outcome.

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

Compose starts analytics on `8080` — serving the dashboard from the same port — and the agent on
`8082`, reading the same root `.env`. Telemetry and the pending queue live on named volumes, so a container restart
keeps both history and undelivered events. The agent waits for the analytics health check first.

Because a published container port is reachable, the agent image binds to every interface and the
security model therefore requires `CAPTURE_API_KEY`; Compose supplies it from `EVENTWATCH_API_KEY`
by default.

For PostgreSQL, enable its profile and point the analytics service at it with
`DATABASE_URL=jdbc:postgresql://postgres:5432/eventwatch` in `.env`:

```powershell
docker compose --profile postgres up --build
```

**The outage test.** `scripts/outage-test.sh` builds both images, sends real events through the
real agent, stops the analytics container while the agent keeps running, sends more events into
the durable queue, restarts analytics, and asserts every event landed with nothing left pending.
It runs in CI on every push; run it yourself with:

```bash
scripts/outage-test.sh
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

The session and the static handler have their own suites: the cookie's attributes, that signing out
revokes the token on the server rather than only in the browser, that sign-in is rate limited, and
that an encoded path traversal cannot read a file from outside the served directory.

Test classes run in parallel, which the phase 17 refactor made possible: two engines can now run in
one JVM without sharing a key, a database, a metrics registry or an event window, and one test
starts a pair side by side to prove it. A class that touches process-wide state — `System.out`, the
logger, or a shared PostgreSQL server — declares a `@ResourceLock` and runs alone.

The Go suite covers retry classification, the capture handler, durable-queue outcomes, correlation
IDs, host identity and its persistence across restarts, the bind and authentication policy,
delivery-health tracking and its alerts, and metric rendering, using an `httptest` stand-in for the analytics service.

Neither suite starts a second real process, which is deliberate — it is what keeps them fast. The
one behaviour that genuinely needs two real processes, a real outage, and a real durable queue on
disk is `scripts/outage-test.sh`, described under Docker above.

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

`loadtest -mode collector -events 500 -concurrency 32` reproduces the same burst against the real
`/capture` path, with a count and concurrency you choose rather than a fixed 500/32 baked into the
binary. Most of a burst that size is answered `429` by the rate limit, written to the pending
queue, and drained over the following minutes. Nothing is dropped — that is the system working, not
a failure. (An earlier phase carried this as a `/stress` route on the agent itself; it added an
unauthenticated-unless-configured load generator to the production binary for something the load
harness already did better, so it was removed.)

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
| `DASHBOARD_DIR` | `../dashboard` | Directory served at `/`; empty runs the service as an API only |
| `SESSION_TTL_MINUTES` | `720` | How long an operator session lasts |
| `SESSION_RATE_LIMIT_PER_MINUTE` | `10` | Sign-in attempts allowed per address |
| `AGENT_TOKEN` | empty | This agent's own credential; falls back to the shared key |
| `SHARED_KEY_INGESTION_ENABLED` | `true` | Whether the fleet-wide key still authenticates ingestion |
| `DISK_ALERT_THRESHOLD` | `90` | Percentage at which the fullest filesystem alerts |
| `DISK_PATHS` | empty | Mounts the agent measures; empty means every real filesystem |
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
| `WATCHDOG_URL` | empty | Endpoint receiving the analytics heartbeat |
| `WATCHDOG_INTERVAL_SECONDS` | `60` | How often the heartbeat is sent |
| `WATCHDOG_TIMEOUT_SECONDS` | `5` | Heartbeat request timeout |
| `AGENT_ALERT_WEBHOOK_URL` | empty | Endpoint receiving the agent's own alerts |
| `DELIVERY_STALL_MINUTES` | `5` | Silence before the agent reports a stalled delivery |
| `AGENT_ALERT_TIMEOUT_SECONDS` | `5` | Agent alert request timeout |
| `NOTIFICATIONS_ENABLED` | `false` | Turn webhook delivery on |
| `NOTIFICATION_WEBHOOK_URL` | empty | Where lifecycle changes are POSTed |
| `NOTIFICATION_TIMEOUT_SECONDS` | `5` | Per-attempt timeout |
| `NOTIFICATION_MAX_ATTEMPTS` | `3` | Bounded retries per delivery |
| `NOTIFICATION_RETRY_DELAY_MILLIS` | `1000` | Pause between attempts |
| `NOTIFICATION_REMINDER_SECONDS` | `900` | Cooldown before a sustained alert reminds again |
| `MAVEN_OPTS` | `-Xms64m -Xmx128m` | Documents the intent of `.mvn/jvm.config` |

---

## Limitations and what is next

- **Sessions do not survive a restart.** They are held in memory on purpose, rather than storing a
  second long-lived secret beside the telemetry; restarting the service signs operators out.
- **One analytics instance, no failover.** Alert rules are cached per process and notification
  cooldowns are held in memory, so a second instance would need cache invalidation and shared
  reservations — but the more basic fact is that stopping this one process is total downtime.
- **No user identity or audit trail.** Every signed-in operator is equivalent; there is no record
  of which person acknowledged or resolved a given alert. Fine for one or two operators, not for
  a team that needs to know who did what.
- **No secrets manager, no encryption at rest, no tested restore procedure.** Credentials live
  in `.env` and on disk on each agent, and the telemetry lives in a plain database file. Protecting
  all of it is left to the host.
- **Revocation is immediate on the server but not announced to the agent.** A 401 is a permanent
  rejection, not queued or retried, so a revoked agent's very next event fails outright — but
  nothing pushes that fact to the agent process itself. It keeps trying and logging the rejection
  until an operator notices and fixes its configuration.
- **Disk is one reading per machine, not one per mount.** The agent reports its fullest
  filesystem, so a machine raises a single disk alert naming the mount that is worst right now.
  Acknowledging mounts independently would need the mount in the alert key, which has not been
  needed at this scale.
- **Network and custom application metrics were refused, not deferred.** Network has no natural
  alerting threshold, and arbitrary user-defined metrics are the line between this and a metrics
  warehouse; `CLAUDE.md` records the reasoning and what would justify reopening either.
- **The watchdog still needs somewhere to point.** Phase 16 closed the silent-death gap from
  both directions, but the heartbeat has to reach an endpoint you run or subscribe to. That is
  the correct boundary — a monitor cannot be its own last line of defence — but it does mean
  the watchdog is only as good as the endpoint behind it.
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
| 13 | Agent security | Loopback binding, mandatory auth when exposed, TLS, CORS |
| 14 | Per-host alert rules | Rules table, host → fleet → default precedence, rules API and editor |
| 15 | Fleet operations | Silence detection, per-machine drill-down, agent version and queue depth |
| 16 | Watchdog | Analytics heartbeat and agent-side delivery-stall alerts |
| 17 | Internal structure | One class per route, one engine per instance, parallel tests |
| 18 | Operator session | Same-origin dashboard, HttpOnly session cookie, no CORS |
| 19 | Per-agent credentials | Mint, bind, and revoke per-machine ingestion tokens |
| 20 | Cleanup and closing gaps | Scripted outage test in CI, `/stress` removed |
| 21 | Disk usage | Fullest-mount sampling, `HIGH_DISK` judged on the latest reading |

`agent.md` is the original roadmap, kept for history. `CLAUDE.md` is the current authority on state,
conventions, and what comes next.
