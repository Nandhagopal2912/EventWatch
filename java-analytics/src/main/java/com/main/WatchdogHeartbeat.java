package com.main;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * A dead-man's switch. The analytics service cannot report its own death, so it tells an
 * outside endpoint that it is alive on a timer; when the pings stop, that endpoint alerts.
 *
 * The ping is skipped while the database is unreachable, on purpose: a heartbeat that keeps
 * arriving from a service that cannot store anything is worse than no heartbeat at all.
 */
public class WatchdogHeartbeat {
    static final String SCHEMA_VERSION = "eventwatch.heartbeat.v1";

    private final Database database;
    private final EventRepository events;
    private final AlertRepository alerts;
    private final Metrics metrics;
    private final ObjectMapper objectMapper;
    private final String url;
    private final Duration timeout;
    private final HttpClient client;

    public WatchdogHeartbeat(Database database, EventRepository events, AlertRepository alerts,
            Metrics metrics, ObjectMapper objectMapper, String url, int timeoutSeconds) {
        this.database = database;
        this.events = events;
        this.alerts = alerts;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.url = url;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.client = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    public boolean enabled() {
        return url != null && !url.isBlank();
    }

    /** Timer entry point: nothing may escape, or the scheduler cancels the task for good. */
    public void ping() {
        if (!enabled()) {
            return;
        }
        try {
            database.checkReachable();
        } catch (Exception exception) {
            // Staying silent is the signal: the watchdog should fire when storage is broken.
            metrics.recordWatchdogPing("skipped");
            StructuredLogger.warn("watchdog heartbeat skipped, the database is unreachable",
                    StructuredLogger.fields("error", String.valueOf(exception)));
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload()))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            boolean delivered = response.statusCode() >= 200 && response.statusCode() < 300;
            metrics.recordWatchdogPing(delivered ? "delivered" : "failed");
            if (!delivered) {
                StructuredLogger.warn("watchdog heartbeat rejected",
                        StructuredLogger.fields("http_status", response.statusCode()));
            }
        } catch (Exception exception) {
            metrics.recordWatchdogPing("failed");
            StructuredLogger.warn("watchdog heartbeat failed",
                    StructuredLogger.fields("error", String.valueOf(exception)));
        }
    }

    /** A body the receiving endpoint can log: enough to tell a healthy fleet from a stalled one. */
    private String payload() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("schema_version", SCHEMA_VERSION);
        payload.put("service", "java-analytics");
        payload.put("timestamp", Instant.now().toString());
        try {
            payload.put("hosts", events.hosts(QueryService.MAX_HOSTS_LISTED).size());
            payload.put("active_alerts", alerts.findActive().size());
        } catch (Exception exception) {
            // The ping matters more than its contents; a counting failure must not stop it.
            payload.put("counts_unavailable", true);
        }
        return payload.toString();
    }
}
