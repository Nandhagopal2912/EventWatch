# EventWatch — first-time user guide

This walks you from a fresh clone to a working fleet monitor with a real alert on the board, and
explains what the system is actually doing after each step. If you only want the short version,
`readme.md` has it; this file is for the first time, when it helps to know why each command matters.

By the end you will have two services running, a dashboard you are signed in to, an alert you
deliberately caused, and a demonstration that nothing is lost when the analytics service dies.

Budget about fifteen minutes.

---

## Before you start

| You need | Version | Check with |
| --- | --- | --- |
| Java | 17 or newer | `java -version` |
| Maven | 3.9 or newer | `mvn -version` |
| Go | 1.27 or newer | `go version` |
| Docker | optional, only for the container route | `docker --version` |

**On Windows, use `curl.exe`, not `curl`.** PowerShell aliases `curl` to `Invoke-WebRequest`, which
takes different arguments and will fail confusingly. Every example below uses `curl.exe`; on Linux
or macOS drop the `.exe`.

You will run two services, so you need **two terminals** open in the project folder.

---

## Step 1 — Create your configuration

```bash
cp .env.example .env
```

Open `.env` and change exactly one line:

```
EVENTWATCH_API_KEY=replace-with-a-local-secret
```

Put any non-empty string there. It is a shared secret, not a password you have to remember — a
random word is fine for a local trial.

**What just happened.** Both services read this one file at startup: the Go agent through
`godotenv.Load("../.env", ".env")`, the Java service through dotenv pointed at the parent directory.
That is why there is a single `.env` at the repository root rather than one per service — they have
to agree on the key, and splitting it into two files is how they would silently stop agreeing.

Everything else in that file has a working default. You do not need to touch it yet.

> **If you skip this step,** the analytics service refuses to start with
> `EVENTWATCH_API_KEY is required`. That is deliberate: it validates the key *before* it opens a
> database connection, so a misconfigured start cannot leave a connection pool running.

---

## Step 2 — Start the analytics service

In your **first terminal**:

```bash
cd java-analytics && mvn compile exec:java
```

Wait for a line containing `analytics engine started`.

**What just happened,** in order:

1. It read `.env` and validated your API key.
2. It opened `java-analytics/events.db` — creating it if absent. This is a plain SQLite file, and
   it is where all your telemetry will live.
3. It created the schema: tables first, then any migrations for an older database, then indexes.
   That ordering matters — an index can cover a column that an older database only gains during
   the migration, and getting it backwards used to crash startup on upgrade.
4. It turned on write-ahead logging. This is the single change that took ingestion from 60 to
   522 events per second, so it is not cosmetic.
5. It registered its HTTP routes and started serving the dashboard from `../dashboard` at the same
   port as the API. Same-origin is what makes the login cookie possible at all.
6. It started background timers: sweeping expired rate-limit windows, checking for machines that
   have gone silent, and pruning old data if you ever set `RETENTION_DAYS`.

The service is now listening on **http://localhost:8080**. Leave this terminal running.

Confirm it is alive:

```bash
curl.exe http://localhost:8080/health
```

You should get `{"status":"ok", ...}`. This endpoint does not just answer — it opens a real database
connection first, so a green health check means storage is genuinely reachable, not merely that the
process is up.

---

## Step 3 — Open the dashboard and sign in

Open **http://localhost:8080** in a browser. Type your `EVENTWATCH_API_KEY` into the box and click
**Sign in**.

**What just happened.** The page sent your key exactly once, to `POST /session`. The server checked
it, generated a completely unrelated random token, and returned it as an `HttpOnly` cookie. Three
consequences worth understanding:

- **The key is now gone from the page.** The input is cleared and the key is never stored in
  JavaScript memory, `localStorage`, or anywhere a script can reach it.
- **The cookie is invisible to scripts.** Open the browser console and type `document.cookie` — it
  is empty. That is `HttpOnly` doing its job: even a script injected into the page cannot steal
  your session.
- **It survives a reload.** Refresh the page; you stay signed in, because the browser still holds
  the cookie and the page asks `GET /session` on load whether it is still valid.

The board is empty because nothing has reported yet. That is next.

---

## Step 4 — Start the agent

In your **second terminal**:

```bash
cd go-collector && go run .
```

Wait for `agent started`.

**What just happened:**

1. It resolved which credential to present. With no `AGENT_TOKEN` set it uses the shared
   `EVENTWATCH_API_KEY`.
2. It settled its identity. On this first run it generated a random `host_id`, wrote it to
   `go-collector/pending-events/host-id` through a temporary file and a rename, and will reuse it
   forever. The rename is why a crash mid-write cannot leave half an identity behind — and why
   restarting the agent does not look like a brand-new machine appearing in your fleet.
3. It created `go-collector/pending-events/`, which is where events go if the analytics service is
   ever unreachable.
4. It bound to `127.0.0.1:8082` — **loopback only**. Because it is not reachable from the network,
   it does not demand a key on `/capture`. If you change `COLLECTOR_BIND` to anything else, the
   agent will refuse to start without `CAPTURE_API_KEY`. Exposure and authentication are coupled on
   purpose, so an exposed agent cannot be an unauthenticated one.
5. It started a background loop that retries anything sitting in the queue every five seconds.

You now have both halves running. One agent per machine is the intended shape — the agent measures
*the machine it runs on*, so pointing several machines at one shared agent would stamp every event
with that agent's CPU rather than their own.

---

## Step 5 — Send your first event

In a **third terminal** (or just stop watching a log for a moment):

```bash
curl.exe "http://localhost:8082/capture?level=INFO&msg=hello%20from%20my%20first%20event"
```

You should get `{"message":"log forwarded to analytics engine successfully","status":"ok"}`.

**What just happened — this is the whole pipeline in one request:**

1. The agent generated a unique event ID and a correlation ID.
2. It sampled **this machine**: CPU over a 100 ms window, RAM, and the fullest filesystem it can
   see, along with the name of that mount.
3. It built the JSON event and POSTed it to the analytics service with your API key.
4. The analytics service authenticated it, rate-limited by client address, checked the content
   type and size, then validated every field.
5. It stored the event in SQLite **inside a transaction, before** adding it to its in-memory
   window — so an event you were told was accepted is genuinely durable.
6. It evaluated the alert rules for this machine against its last five events.
7. The correlation ID came back in the response header, and appears in the logs of *both* services
   — so one event is traceable end to end.

Refresh the dashboard. You now have one machine and one event, with its CPU, RAM, and disk reading.

> **Note the `%20`.** That is a URL-encoded space. Spaces in a URL will otherwise break the
> request or truncate your message.

---

## Step 6 — Make an alert fire on purpose

The most reliable alert to trigger deliberately is the repeated-error rule, because it does not
depend on your machine's actual load. Send **the same error five times**:

```bash
for i in 1 2 3 4 5; do curl.exe -s -o /dev/null "http://localhost:8082/capture?level=ERROR&msg=disk%20controller%20timeout"; done
```

On PowerShell:

```powershell
1..5 | ForEach-Object { curl.exe -s -o NUL "http://localhost:8082/capture?level=ERROR&msg=disk%20controller%20timeout" }
```

Refresh the dashboard. **Active alerts** now shows one.

**What just happened.** The engine looks at the last five events *for this machine* and counts
identical error messages. The default threshold is five, so all five events in the window have to
be the same error — which is why they need to be consecutive. It raised an alert keyed
`repeated-error-…@your-host-id`.

That `@your-host-id` is important: alert keys carry the machine. Two machines with the same problem
get two separate alerts rather than fighting over one row, which is what makes this work for a
fleet rather than a single box.

**You may also already have a disk alert.** If any filesystem on your machine is over 90% full, one
appeared the moment you sent your first event. That one names the mount — `C: is 96.6%
(threshold 90.0%)` — because a percentage is only actionable once you know which disk.

---

## Step 7 — Work the alert

On the dashboard, click **Acknowledge** on the alert, then **Resolve**.

**What just happened.** Alerts move `OPEN` → `ACKNOWLEDGED` → `RESOLVED`. The useful detail is what
acknowledging *survives*: if the same error happens again, the occurrence count goes up but your
acknowledgement is **not** undone. An earlier version reset it on every recurrence, which made the
button meaningless — it is now the one piece of state the engine will not overwrite.

Repeated-error alerts do not resolve themselves, because an error has no "recovered" signal — the
absence of new errors is not proof of anything. CPU, RAM and disk alerts *do* resolve themselves
once the machine recovers, because there the recovery is directly observable.

If you had set `NOTIFICATIONS_ENABLED=true` and a webhook URL, every one of those transitions would
have been POSTed to it, with retries, and each attempt recorded and readable per alert.

---

## Step 8 — Change a threshold for one machine

Thresholds are not just configuration; they are rules you can edit while the system runs. On the
dashboard, use the **Alert rules** panel: pick `High CPU`, leave the machine as *All machines*, set
the threshold to `1`, and save.

Send another event, then look at the alerts.

**What just happened.** Your CPU is almost certainly above 1%, so a CPU alert fired. More
interestingly, you just used the precedence system: for each rule type the most specific stored rule
wins — **this machine's rule, then a fleet-wide rule, then the value from `.env`**. Nothing was
migrated when you saved; the `.env` value simply became the bottom tier.

Ask the system which rule is actually applying and why:

```bash
curl.exe -H "X-EventWatch-Key: YOUR_KEY" "http://localhost:8080/rules/effective?host_id=YOUR_HOST_ID"
```

Each rule comes back with a `source` of `host`, `fleet`, or `default` — because "why did this fire?"
is the first question you ask at 3am.

Set the threshold back to `85` when you are done.

---

## Step 9 — Watch it survive an outage

This is the property worth seeing for yourself.

1. **Stop the analytics service** — `Ctrl+C` in the first terminal.
2. **Send some events anyway:**

   ```bash
   curl.exe "http://localhost:8082/capture?level=ERROR&msg=sent%20during%20the%20outage"
   ```

   You get `503` and the message `backend unavailable; event queued for retry`. That is the
   system working, not failing.
3. **Look in `go-collector/pending-events/`.** There is a `.json` file per event.
4. **Restart the analytics service** (same command as Step 2).
5. **Wait about five seconds**, then refresh the dashboard.

**What just happened.** The agent tried to deliver, failed, and wrote each event to disk through a
temp file and an atomic rename — so a crash cannot leave a half-written event. Its background loop
retried every five seconds, and once the service came back it delivered them and deleted each file
only after a `2xx` confirmed storage.

Your total event count includes everything sent during the outage. **Nothing was lost, and nothing
was duplicated** — every event carries a unique ID with a unique index behind it, so a retry that
arrives twice is stored once.

This exact scenario runs automatically in CI on every push (`scripts/outage-test.sh`), which is why
it is a claim rather than a hope.

---

## Step 10 — Give the agent its own credential (optional)

So far the agent uses the fleet-wide key. For real use, give each machine its own:

```bash
curl.exe -X POST -H "X-EventWatch-Key: YOUR_KEY" -H "Content-Type: application/json" -d "{\"host_id\":\"web-01\"}" http://localhost:8080/agents
```

The response contains a `token` starting with `ewa_`. **Copy it now — it is never shown again.**
Only its hash is stored.

Put it in `.env` as `AGENT_TOKEN=ewa_...` and restart the agent.

**What just happened.** That token is bound to the `host_id` you named. From now on, events
authenticated with it are stamped with *that* host no matter what the payload claims — so one agent
can never report as another machine, which the shared key could never prevent. Revoke it alone with
`DELETE /agents/{id}` and no other machine is affected.

---

## Where things live

| Path | What it is | Safe to delete? |
| --- | --- | --- |
| `.env` | Your configuration and secret | No — it is your key |
| `java-analytics/events.db` | All telemetry and alerts (SQLite) | Yes — deleting resets everything |
| `go-collector/pending-events/` | Events awaiting delivery | Only if empty; files here are undelivered events |
| `go-collector/pending-events/host-id` | This machine's stable identity | No — deleting makes it a "new" machine |
| `go-collector/pending-events/rejected-events/` | Events permanently refused | Yes, after you have looked at why |

Both `.env` and `events.db` are gitignored. Never commit either.

---

## The Docker route instead

If you would rather not install Java, Maven, and Go:

```bash
docker compose up --build
```

That starts both services with the dashboard on **http://localhost:8080**, storing telemetry and the
pending queue on named volumes so a container restart keeps both.

One difference to know: because a published container port is reachable from your network, the agent
image binds to every interface — and the security model therefore *requires* a capture key. Compose
supplies it from `EVENTWATCH_API_KEY` automatically, so `/capture` needs a key in this mode:

```bash
curl.exe -H "X-EventWatch-Key: YOUR_KEY" "http://localhost:8082/capture?level=INFO&msg=from%20a%20container"
```

A second difference: a containerised agent measures **its own container's filesystem**, not your
host's disks. To watch the real disks, mount the host into the container and point `DISK_PATHS` at
that path.

---

## When something does not work

**`EVENTWATCH_API_KEY is required`** — you skipped Step 1, or `.env` is not at the repository root.

**`curl: The term 'curl' is not recognized` or strange parameter errors on Windows** — use
`curl.exe`, not `curl`. PowerShell aliases the latter to something else entirely.

**The dashboard says `Unauthorized`** — the key you typed does not match `.env`. Note that the
services read `.env` at *startup*, so if you edited it, restart them.

**Port 8080 or 8082 already in use** — change `HTTP_PORT` or `COLLECTOR_PORT` in `.env`. If you
change the analytics port, also update `JAVA_BACKEND_URL` so the agent still knows where to send.

**Events return `503` and pile up in `pending-events/`** — the analytics service is not reachable.
Check the first terminal. Nothing is being lost; it will drain when the service returns.

**`no such column` on startup** — you are running against a database from a much older build. This
is handled automatically now; if you still see it, deleting `events.db` resets everything.

**An alert will not go away** — repeated-error alerts never auto-resolve by design. Resolve it from
the dashboard.

---

## What to read next

- **`readme.md`** — what the system does, the design decisions behind it, and the full
  configuration reference.
- **`CLAUDE.md`** — the working guide: current state, conventions, every fixed defect, and what was
  deliberately refused and why.

Two honest notes before you rely on this for anything that matters. It is built for roughly **5–50
machines** and is not a replacement for Datadog, CloudWatch, or a SIEM. And it has no user identity
or audit trail, runs as a single instance with no failover, and has no tested restore procedure —
so treat dashboard access as equivalent to holding the key, and back up `events.db` yourself.
