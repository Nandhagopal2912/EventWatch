# EventWatch

EventWatch is a two-service host telemetry and event-monitoring pipeline. A Go collector captures application events together with CPU and RAM usage, then forwards them to a Java analytics engine for processing, persistence, and reporting.

The current implementation completes Phases 1–3. Future improvements are documented in `agent.md`.

## Current Phase Status

| Phase       | Focus                        | Completed capabilities                                                                                                                  | Result                                                    |
| ----------- | ---------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------- |
| **Phase 1** | JSON event pipeline          | Go-to-Java HTTP forwarding, JSON parsing, error filtering, grouped error counts, and a 500-event stress route                           | Events move successfully between both services            |
| **Phase 2** | Host telemetry and analytics | Live CPU/RAM collection, timestamps, five-event moving averages, and the intermediate JSON archive                                      | The dashboard shows recent system health trends           |
| **Phase 3** | Persistence and hardening    | SQLite storage, transactional inserts, startup recovery, malformed JSON handling, request-size limits, and correct HTTP error responses | Events survive restarts and invalid requests are rejected |

**Current state:** EventWatch is a working local telemetry and event-monitoring system. Go collects and forwards events, Java analyzes and persists them, and the terminal dashboard reports errors and recent resource averages.

## Project Structure

```text
go-collector/                   Go HTTP ingress
	main.go
	go.mod
java-analytics/                 Maven Java analytics service
	pom.xml
	src/main/java/com/main/AnalyticsEngine.java
```

## Architecture

- **Go ingress** (`go-collector/`): accepts `GET` requests at `http://localhost:8082/capture`, samples host CPU/RAM usage, and forwards each event to the Java service.
- **Java analytics engine** (`java-analytics/`): accepts `POST` requests at `http://localhost:8080/receive`, stores telemetry in SQLite, reloads events after restart, and prints error counts plus a five-event CPU/RAM moving average.
- **SQLite database** (`java-analytics/events.db`): stores telemetry in the `telemetry_events` table. The database is created automatically when the Java service starts.

## Requirements

- Go 1.27 or newer
- Java 17 or newer
- Apache Maven
- Internet access on the first Maven/Go dependency download

## Run

Start the Java service first with Maven:

```powershell
cd java-analytics
mvn compile exec:java
```

In a second terminal, start the Go service:

```powershell
cd go-collector
go run .
```

Both services must remain running. The Go service listens on port `8082`; the Java service listens on port `8080`.

Stop either service with `Ctrl+C`.

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

The Java `/receive` endpoint is intended for the Go service and receives JSON with `level`, `msg`, `timestamp`, `cpu_usage`, and `ram_usage` fields.

The Java service rejects malformed JSON, non-object JSON, request bodies larger than 64 KiB, and unsupported HTTP methods.

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
- The services currently communicate over `localhost` without authentication.
- Future improvements and the long-term roadmap are documented in `agent.md`.
