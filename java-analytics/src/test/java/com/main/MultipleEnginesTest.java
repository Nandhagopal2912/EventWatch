package com.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two engines in one JVM, which is what phase 17 exists to make possible.
 *
 * <p>Every assertion here fails against the previous shape, where the key, the metrics, the
 * event window and the database all lived in static fields: starting the second engine would
 * have taken the first one over rather than running beside it.
 */
class MultipleEnginesTest {
    private static final String FIRST_KEY = "first-engine-secret";
    private static final String SECOND_KEY = "second-engine-secret";

    @TempDir
    Path temporaryDirectory;

    private final HttpClient client = HttpClient.newHttpClient();
    private AnalyticsEngine first;
    private AnalyticsEngine second;

    @BeforeEach
    void startBoth() throws IOException {
        first = AnalyticsEngine.start(EngineConfiguration.forTesting(
                TestSupport.databaseUrl(temporaryDirectory, "first.db"), FIRST_KEY));
        second = AnalyticsEngine.start(EngineConfiguration.forTesting(
                TestSupport.databaseUrl(temporaryDirectory, "second.db"), SECOND_KEY));
    }

    @AfterEach
    void stopBoth() {
        if (first != null) {
            first.stop();
        }
        if (second != null) {
            second.stop();
        }
    }

    @Test
    void eachEngineKeepsItsOwnApiKey() throws Exception {
        // The sharpest consequence of the old static field: starting the second engine
        // overwrote the first engine's key, so the first one stopped accepting its own agents.
        assertEquals(200, post(first, FIRST_KEY, event("a-1", 10.0)).statusCode());
        assertEquals(401, post(first, SECOND_KEY, event("a-2", 10.0)).statusCode(),
                "the first engine must not accept the second engine's key");
        assertEquals(200, post(second, SECOND_KEY, event("b-1", 90.0)).statusCode());
        assertEquals(401, post(second, FIRST_KEY, event("b-2", 90.0)).statusCode());
    }

    @Test
    void eachEngineCountsOnlyItsOwnTraffic() throws Exception {
        assertEquals(200, post(first, FIRST_KEY, event("a-1", 10.0)).statusCode());
        assertEquals(200, post(first, FIRST_KEY, event("a-2", 10.0)).statusCode());
        assertEquals(200, post(second, SECOND_KEY, event("b-1", 90.0)).statusCode());

        assertTrue(metrics(first).contains("eventwatch_events_received_total 2"),
                "the first engine counts its own two events");
        assertTrue(metrics(second).contains("eventwatch_events_received_total 1"),
                "the second engine counts only its own");
    }

    @Test
    void eachEngineKeepsItsOwnEventWindow() throws Exception {
        assertEquals(200, post(first, FIRST_KEY, event("a-1", 10.0)).statusCode());
        assertEquals(200, post(second, SECOND_KEY, event("b-1", 90.0)).statusCode());
        assertEquals(200, post(second, SECOND_KEY, event("b-2", 90.0)).statusCode());

        // The in-memory window feeds alert evaluation; sharing it would average unrelated
        // fleets together and make every threshold meaningless.
        assertTrue(metrics(first).contains("eventwatch_events_stored 1"));
        assertTrue(metrics(second).contains("eventwatch_events_stored 2"));
    }

    @Test
    void theEnginesListenOnDifferentPorts() {
        assertTrue(first.port() != second.port(), "two engines must not fight over one port");
    }

    private String event(String eventId, double cpuUsage) {
        return "{\"event_id\":\"" + eventId + "\",\"level\":\"INFO\",\"msg\":\"parallel engines\","
                + "\"timestamp\":\"" + Instant.now() + "\",\"cpu_usage\":" + cpuUsage
                + ",\"ram_usage\":20.0}";
    }

    private HttpResponse<String> post(AnalyticsEngine engine, String apiKey, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + engine.port() + "/receive"))
                .header("Content-Type", "application/json")
                .header("X-EventWatch-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String metrics(AnalyticsEngine engine) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + engine.port() + "/metrics"))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }
}
