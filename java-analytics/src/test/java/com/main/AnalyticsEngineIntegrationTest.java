package com.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the real HTTP server over the loopback interface against a temporary database. */
class AnalyticsEngineIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API_KEY = "integration-secret";
    private static final String CPU_ALERT = "cpu-high@web-01";

    @TempDir
    Path temporaryDirectory;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private AnalyticsEngine engine;
    private String databaseUrl;
    private int port;

    @BeforeEach
    void startEngine() throws IOException {
        databaseUrl = TestSupport.databaseUrl(temporaryDirectory, "integration.db");
        restart(EngineConfiguration.forTesting(databaseUrl, API_KEY));
    }

    @AfterEach
    void stopEngine() {
        if (engine != null) {
            engine.stop();
            engine = null;
        }
    }

    private void restart(EngineConfiguration configuration) throws IOException {
        stopEngine();
        engine = AnalyticsEngine.start(configuration);
        port = engine.port();
    }

    private EngineConfiguration configurationWithThresholds(double cpu, double ram, int repeatedErrors) {
        return new EngineConfiguration(0, databaseUrl, API_KEY, "text",
                cpu, ram, repeatedErrors, false, "", 1, 1, 1, 0, 0, "", "", 2, 0, 60, 100,
                false, "", "", "PKCS12", "", false, 10, 168, 60, "", 60, 5, 720, 10, true);
    }

    private String eventBody(String eventId, String level, double cpu, double ram) {
        return eventBody(eventId, level, "web-01", cpu, ram);
    }

    private String eventBody(String eventId, String level, String hostId, double cpu, double ram) {
        return """
                {"event_id":"%s","level":"%s","host_id":"%s","hostname":"%s","msg":"integration event",
                 "timestamp":"%s","cpu_usage":%s,"ram_usage":%s}"""
                .formatted(eventId, level, hostId, hostId, Instant.now().toString(), cpu, ram);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
    }

    private HttpResponse<String> post(String path, String body, boolean authenticated) throws Exception {
        HttpRequest.Builder builder = request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authenticated) {
            builder.header("X-EventWatch-Key", API_KEY);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, boolean authenticated) throws Exception {
        HttpRequest.Builder builder = request(path).GET();
        if (authenticated) {
            builder.header("X-EventWatch-Key", API_KEY);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> ingest(String eventId, String level, double cpu, double ram) throws Exception {
        return post("/receive", eventBody(eventId, level, cpu, ram), true);
    }

    @Test
    void healthReportsDatabaseReachability() throws Exception {
        HttpResponse<String> response = get("/health", false);
        assertEquals(200, response.statusCode());
        JsonNode body = MAPPER.readTree(response.body());
        assertEquals("ok", body.path("status").asText());
        assertEquals("java-analytics", body.path("service").asText());
    }

    @Test
    void ingestionStoresAnEventAndAnswersWithJson() throws Exception {
        HttpResponse<String> response = ingest("e1", "INFO", 10, 10);
        assertEquals(200, response.statusCode());
        assertEquals("ok", MAPPER.readTree(response.body()).path("status").asText());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));

        JsonNode summary = MAPPER.readTree(get("/summary", true).body());
        assertEquals(1, summary.path("total_events").asLong());
    }

    @Test
    void ingestionRequiresTheApiKey() throws Exception {
        HttpResponse<String> response = post("/receive", eventBody("e1", "INFO", 10, 10), false);
        assertEquals(401, response.statusCode());
        assertEquals("error", MAPPER.readTree(response.body()).path("status").asText());
    }

    @Test
    void ingestionRejectsTheWrongContentType() throws Exception {
        HttpResponse<String> response = client.send(request("/receive")
                .header("Content-Type", "text/plain")
                .header("X-EventWatch-Key", API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(eventBody("e1", "INFO", 10, 10)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(415, response.statusCode());
    }

    @Test
    void ingestionRejectsMalformedAndNonObjectJson() throws Exception {
        assertEquals(400, post("/receive", "{not json", true).statusCode());
        assertEquals(400, post("/receive", "[1,2,3]", true).statusCode());
        assertEquals(400, post("/receive", "\"a string\"", true).statusCode());
    }

    @Test
    void ingestionRejectsAnOversizedBody() throws Exception {
        String oversized = "{\"event_id\":\"e1\",\"msg\":\"" + "x".repeat(70_000) + "\"}";
        assertEquals(413, post("/receive", oversized, true).statusCode());
    }

    @Test
    void ingestionRejectsAnInvalidEventWithAReadableMessage() throws Exception {
        HttpResponse<String> response = post("/receive", eventBody("e1", "TRACE", 10, 10), true);
        assertEquals(400, response.statusCode());
        assertEquals("level must be INFO, WARN, ERROR, or CRITICAL",
                MAPPER.readTree(response.body()).path("message").asText());
    }

    @Test
    void unsupportedMethodsAreRejected() throws Exception {
        HttpResponse<String> response = client.send(request("/receive").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, response.statusCode());
        assertEquals("POST", response.headers().firstValue("Allow").orElse(""));
    }

    @Test
    void theCorrelationIdIsEchoedInTheHeaderAndBody() throws Exception {
        HttpResponse<String> response = client.send(request("/receive")
                .header("Content-Type", "application/json")
                .header("X-EventWatch-Key", API_KEY)
                .header("X-Correlation-ID", "trace-integration")
                .POST(HttpRequest.BodyPublishers.ofString(eventBody("e1", "INFO", 10, 10)))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals("trace-integration", response.headers().firstValue("X-Correlation-ID").orElse(""));
        assertEquals("trace-integration", MAPPER.readTree(response.body()).path("correlation_id").asText());
    }

    @Test
    void aCorrelationIdInThePayloadSurvivesAQueuedRetry() throws Exception {
        // A queued event carries the id in its body because headers do not survive the file queue.
        String body = """
                {"event_id":"queued-1","correlation_id":"trace-from-queue","level":"INFO",
                 "msg":"replayed","timestamp":"%s","cpu_usage":5,"ram_usage":5}"""
                .formatted(Instant.now().toString());
        HttpResponse<String> response = post("/receive", body, true);
        assertEquals(200, response.statusCode());
        assertEquals("trace-from-queue", MAPPER.readTree(response.body()).path("correlation_id").asText());
    }

    @Test
    void repeatDeliveryOfOneEventIsAcceptedWithoutDuplicating() throws Exception {
        String body = eventBody("repeat-1", "INFO", 10, 10);
        assertEquals(200, post("/receive", body, true).statusCode());
        assertEquals(200, post("/receive", body, true).statusCode(), "a retry must still succeed");

        assertEquals(1, MAPPER.readTree(get("/summary", true).body()).path("total_events").asLong());
        assertTrue(get("/metrics", false).body().contains("eventwatch_events_duplicate_total 1"));
    }

    @Test
    void aClientIsRateLimitedAfterOneHundredRequestsPerMinute() throws Exception {
        for (int index = 0; index < 100; index++) {
            assertEquals(200, ingest("rate-" + index, "INFO", 10, 10).statusCode(), "request " + index);
        }
        assertEquals(429, ingest("rate-overflow", "INFO", 10, 10).statusCode());
    }

    @Test
    void eventsAreQueryableWithValidatedFilters() throws Exception {
        ingest("q1", "INFO", 10, 10);
        ingest("q2", "ERROR", 20, 20);
        ingest("q3", "ERROR", 30, 30);

        JsonNode all = MAPPER.readTree(get("/events", true).body());
        assertEquals(3, all.path("total").asLong());
        assertEquals(3, all.path("items").size());
        assertEquals(QueryService.DEFAULT_LIMIT, all.path("limit").asInt());

        JsonNode errors = MAPPER.readTree(get("/events?level=ERROR", true).body());
        assertEquals(2, errors.path("total").asLong());

        JsonNode limited = MAPPER.readTree(get("/events?limit=1&offset=1", true).body());
        assertEquals(1, limited.path("items").size());
        assertEquals(3, limited.path("total").asLong(), "total ignores paging");

        assertEquals(400, get("/events?level=TRACE", true).statusCode());
        assertEquals(400, get("/events?limit=9999", true).statusCode());
        assertEquals(400, get("/events?limit=abc", true).statusCode());
        assertEquals(400, get("/events?from=yesterday", true).statusCode());
        assertEquals(400, get("/events?from=2026-09-11T12:00:00Z&to=2026-09-11T10:00:00Z", true).statusCode());
        assertEquals(401, get("/events", false).statusCode());
    }

    @Test
    void anEmptyResultIsAReadableResponse() throws Exception {
        JsonNode body = MAPPER.readTree(get("/events?level=CRITICAL", true).body());
        assertEquals(0, body.path("total").asLong());
        assertEquals(0, body.path("items").size());

        JsonNode summary = MAPPER.readTree(get("/summary", true).body());
        assertTrue(summary.path("latest_event").isNull());
        assertEquals(0.0, summary.path("average_cpu").asDouble(), 0.0001);
    }

    @Test
    void summaryReportsLatestValuesAndAverages() throws Exception {
        ingest("s1", "INFO", 10, 20);
        ingest("s2", "INFO", 30, 40);

        JsonNode summary = MAPPER.readTree(get("/summary", true).body());
        assertEquals(2, summary.path("total_events").asLong());
        assertEquals(20.0, summary.path("average_cpu").asDouble(), 0.0001);
        assertEquals(30.0, summary.path("average_ram").asDouble(), 0.0001);
        assertEquals("s2", summary.path("latest_event").path("event_id").asText());
        assertEquals(401, get("/summary", false).statusCode());
    }

    @Test
    void alertQueriesRequireTheApiKey() throws Exception {
        assertEquals(401, get("/alerts", false).statusCode());
        assertEquals(200, get("/alerts", true).statusCode());
    }

    @Test
    void theAlertLifecycleIsDrivenThroughTheApi() throws Exception {
        restart(configurationWithThresholds(0.0, 0.0, 5));
        ingest("alert-1", "INFO", 50, 50);

        JsonNode active = MAPPER.readTree(get("/alerts", true).body());
        assertEquals(2, active.size(), "a zero threshold opens both the CPU and RAM alerts");

        JsonNode alert = MAPPER.readTree(get("/alerts/" + CPU_ALERT, true).body());
        assertEquals("HIGH_CPU", alert.path("alert_type").asText());
        assertEquals("OPEN", alert.path("status").asText());

        assertEquals(200, post("/alerts/" + CPU_ALERT + "/acknowledge", "", true).statusCode());
        assertEquals("ACKNOWLEDGED",
                MAPPER.readTree(get("/alerts/" + CPU_ALERT, true).body()).path("status").asText());

        // Regression: a further occurrence must not undo the acknowledgement.
        ingest("alert-2", "INFO", 50, 50);
        assertEquals("ACKNOWLEDGED",
                MAPPER.readTree(get("/alerts/" + CPU_ALERT, true).body()).path("status").asText());

        assertEquals(200, post("/alerts/" + CPU_ALERT + "/resolve", "", true).statusCode());
        assertEquals("RESOLVED",
                MAPPER.readTree(get("/alerts/" + CPU_ALERT, true).body()).path("status").asText());
        assertEquals(404, post("/alerts/" + CPU_ALERT + "/resolve", "", true).statusCode(), "already resolved");
        assertEquals(404, post("/alerts/does-not-exist/acknowledge", "", true).statusCode());
        assertEquals(404, get("/alerts/does-not-exist", true).statusCode());
        assertEquals(404, get("/alerts/" + CPU_ALERT + "/unknown-action", true).statusCode());
    }

    @Test
    void repeatedErrorsOpenAnAlertThroughIngestion() throws Exception {
        restart(configurationWithThresholds(200.0, 200.0, 3));
        for (int index = 0; index < 3; index++) {
            HttpResponse<String> response = post("/receive", """
                    {"event_id":"err-%d","level":"ERROR","msg":"database deadlock",
                     "timestamp":"%s","cpu_usage":5,"ram_usage":5}"""
                    .formatted(index, Instant.now().toString()), true);
            assertEquals(200, response.statusCode());
        }

        JsonNode active = MAPPER.readTree(get("/alerts", true).body());
        assertEquals(1, active.size());
        assertEquals("REPEATED_ERROR", active.get(0).path("alert_type").asText());
    }

    @Test
    void notificationHistoryIsServedPerAlert() throws Exception {
        restart(configurationWithThresholds(0.0, 0.0, 5));
        ingest("notify-1", "INFO", 50, 50);

        HttpResponse<String> response = get("/alerts/" + CPU_ALERT + "/notifications?limit=10", true);
        assertEquals(200, response.statusCode());
        assertEquals(0, MAPPER.readTree(response.body()).size(), "notifications are disabled in this config");
        assertEquals(400, get("/alerts/" + CPU_ALERT + "/notifications?limit=9999", true).statusCode());
        assertEquals(401, get("/alerts/" + CPU_ALERT + "/notifications", false).statusCode());
    }

    @Test
    void eventsCarryAndFilterByTheReportingMachine() throws Exception {
        assertEquals(200, post("/receive", eventBody("h1", "INFO", "web-01", 10, 10), true).statusCode());
        assertEquals(200, post("/receive", eventBody("h2", "ERROR", "db-01", 20, 20), true).statusCode());
        assertEquals(200, post("/receive", eventBody("h3", "INFO", "db-01", 30, 30), true).statusCode());

        JsonNode all = MAPPER.readTree(get("/events", true).body());
        assertEquals(3, all.path("total").asLong());
        assertEquals("db-01", all.path("items").get(0).path("host_id").asText());
        assertEquals("db-01", all.path("items").get(0).path("hostname").asText());

        JsonNode database = MAPPER.readTree(get("/events?host_id=db-01", true).body());
        assertEquals(2, database.path("total").asLong());
        assertEquals(2, database.path("items").size());

        JsonNode combined = MAPPER.readTree(get("/events?host_id=db-01&level=ERROR", true).body());
        assertEquals(1, combined.path("total").asLong(), "host and level filters combine");

        assertEquals(0, MAPPER.readTree(get("/events?host_id=nope", true).body()).path("total").asLong());
    }

    @Test
    void theFleetIsListedWithLastSeenTimes() throws Exception {
        post("/receive", eventBody("f1", "INFO", "web-01", 10, 10), true);
        post("/receive", eventBody("f2", "INFO", "db-01", 10, 10), true);
        post("/receive", eventBody("f3", "INFO", "db-01", 10, 10), true);

        JsonNode hosts = MAPPER.readTree(get("/hosts", true).body());
        assertEquals(2, hosts.size());
        for (JsonNode host : hosts) {
            assertFalse(host.path("host_id").asText().isBlank());
            assertFalse(host.path("last_seen").asText().isBlank());
            if ("db-01".equals(host.path("host_id").asText())) {
                assertEquals(2, host.path("event_count").asLong());
            }
        }
        assertEquals(2, MAPPER.readTree(get("/summary", true).body()).path("hosts").asInt());
        assertEquals(401, get("/hosts", false).statusCode());
    }

    @Test
    void twoMachinesRaiseSeparateAlerts() throws Exception {
        restart(configurationWithThresholds(0.0, 200.0, 5));
        assertEquals(200, post("/receive", eventBody("a1", "INFO", "web-01", 50, 5), true).statusCode());
        assertEquals(200, post("/receive", eventBody("a2", "INFO", "db-01", 50, 5), true).statusCode());

        JsonNode active = MAPPER.readTree(get("/alerts", true).body());
        assertEquals(2, active.size(), "each machine gets its own alert");

        JsonNode web = MAPPER.readTree(get("/alerts/cpu-high@web-01", true).body());
        assertEquals("web-01", web.path("host_id").asText());
        assertEquals(200, post("/alerts/cpu-high@web-01/acknowledge", "", true).statusCode());

        assertEquals("ACKNOWLEDGED",
                MAPPER.readTree(get("/alerts/cpu-high@web-01", true).body()).path("status").asText());
        assertEquals("OPEN",
                MAPPER.readTree(get("/alerts/cpu-high@db-01", true).body()).path("status").asText(),
                "acknowledging one machine must not silence another");
    }

    @Test
    void oneMachineDoesNotPolluteAnothersMovingAverage() throws Exception {
        // A shared window would average these to 50 and fire on a host that is idle.
        restart(configurationWithThresholds(80.0, 200.0, 5));
        for (int index = 0; index < 5; index++) {
            post("/receive", eventBody("hot-" + index, "INFO", "busy-01", 100, 5), true);
            post("/receive", eventBody("cold-" + index, "INFO", "idle-01", 1, 5), true);
        }

        assertEquals(200, get("/alerts/cpu-high@busy-01", true).statusCode(), "the busy machine alerts");
        assertEquals(404, get("/alerts/cpu-high@idle-01", true).statusCode(), "the idle machine does not");
    }

    @Test
    void metricsAreExposedInPrometheusFormat() throws Exception {
        ingest("m1", "INFO", 10, 10);

        HttpResponse<String> response = get("/metrics", false);
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
        String body = response.body();
        assertTrue(body.contains("# TYPE eventwatch_events_received_total counter"), body);
        assertTrue(body.contains("eventwatch_events_stored 1"), body);
        assertTrue(body.contains("eventwatch_http_requests_total{path=\"/receive\",status=\"200\"}"), body);
    }

    @Test
    void storedEventsSurviveARestart() throws Exception {
        ingest("persist-1", "INFO", 10, 10);
        ingest("persist-2", "ERROR", 20, 20);
        assertEquals(2, MAPPER.readTree(get("/summary", true).body()).path("total_events").asLong());

        restart(EngineConfiguration.forTesting(databaseUrl, API_KEY));

        JsonNode summary = MAPPER.readTree(get("/summary", true).body());
        assertEquals(2, summary.path("total_events").asLong(), "history is reloaded from SQLite");
        assertEquals("persist-2", summary.path("latest_event").path("event_id").asText());
        assertEquals(2, MAPPER.readTree(get("/events", true).body()).path("items").size());
    }

    @Test
    void anAcknowledgedAlertSurvivesARestart() throws Exception {
        restart(configurationWithThresholds(0.0, 0.0, 5));
        ingest("alert-restart", "INFO", 50, 50);
        assertEquals(200, post("/alerts/" + CPU_ALERT + "/acknowledge", "", true).statusCode());

        restart(configurationWithThresholds(0.0, 0.0, 5));
        JsonNode alert = MAPPER.readTree(get("/alerts/" + CPU_ALERT, true).body());
        assertEquals("ACKNOWLEDGED", alert.path("status").asText());
        assertNotNull(alert.path("first_seen").asText());
    }

    @Test
    void aFreshDatabaseStartsEmptyRatherThanFailing() throws Exception {
        restart(EngineConfiguration.forTesting(
                TestSupport.databaseUrl(temporaryDirectory, "brand-new.db"), API_KEY));

        assertEquals(200, get("/health", false).statusCode());
        JsonNode summary = MAPPER.readTree(get("/summary", true).body());
        assertEquals(0, summary.path("total_events").asLong());
        assertFalse(summary.path("latest_event").isMissingNode());
    }
}
