# Cloud Log Collector

A small two-service demo for collecting cloud log events and forwarding them to a Java analytics engine.

The Go service is the entry point for incoming logs. It forwards each log to the Java service, which keeps an in-memory collection and prints an error dashboard.

## Project Structure

```text
gocode/                         Go HTTP ingress
	main.go
	go.mod
javacode/                       Maven Java analytics service
	pom.xml
	src/main/java/com/main/Main.java
```

## Architecture

- **Go ingress** (`gocode/`): accepts `GET` requests at `http://localhost:8082/capture` and forwards each event to the Java service.
- **Java analytics engine** (`javacode/`): accepts `POST` requests at `http://localhost:8080/receive`, stores logs in memory, and prints a live error summary in the terminal.

## Requirements

- Go 1.27 or newer
- Java 17 or newer
- Apache Maven

## Run

Start the Java service first with Maven:

```powershell
cd javacode
mvn compile exec:java
```

In a second terminal, start the Go service:

```powershell
cd gocode
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

The Java `/receive` endpoint is intended for the Go service and receives JSON with `level`, `msg`, and `timestamp` fields.

## Run the stress test

After both services are running, send 500 test requests through the Go service:

```powershell
curl.exe http://localhost:8082/stress
```

The stress handler adds a unique `(Log #N)` suffix to each message. Because the Java dashboard groups by the complete message, each generated message currently appears with a count of `1`.

## Notes

- Logs are stored only in memory and are lost when the Java service stops.
- Maven build output is written under `javacode/target/` and should not be committed.
- The services currently communicate over `localhost` without authentication.
