package com.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The fleet view, the per-machine drill-down, and silence detection through the real server. */
class FleetApiTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API_KEY = "fleet-secret";

    @TempDir
    Path temporaryDirectory;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private HttpServer server;
    private int port;
    private int sequence;

    @BeforeEach
    void startEngine() throws IOException {
        server = AnalyticsEngine.start(EngineConfiguration.forTesting(
                TestSupport.databaseUrl(temporaryDirectory, "fleet.db"), API_KEY));
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopEngine() {
        if (server != null) {
            AnalyticsEngine.stop(server);
            server = null;
        }
    }

    private HttpResponse<String> send(String method, String path, String body, boolean authenticated)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (authenticated) {
            builder.header("X-EventWatch-Key", API_KEY);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = send("GET", path, null, true);
        assertEquals(200, response.statusCode(), response.body());
        return MAPPER.readTree(response.body());
    }

    /** Reports one event, optionally backdated so silence can be tested without waiting. */
    private void report(String hostId, String level, double cpu, long minutesAgo) throws Exception {
        String body = """
                {"event_id":"fleet-%d","host_id":"%s","hostname":"%s","agent_version":"0.15.0",
                 "queue_depth":3,"level":"%s","msg":"heartbeat","timestamp":"%s",
                 "cpu_usage":%s,"ram_usage":10}"""
                .formatted(sequence++, hostId, hostId, level,
                        Instant.now().minus(Duration.ofMinutes(minutesAgo)).toString(), cpu);
        assertEquals(200, send("POST", "/receive", body, true).statusCode());
    }

    private JsonNode hostRow(JsonNode listing, String hostId) {
        for (JsonNode host : listing) {
            if (hostId.equals(host.path("host_id").asText())) {
                return host;
            }
        }
        throw new AssertionError("no listing row for " + hostId);
    }

    @Test
    void theFleetListingReportsWhatEachAgentSaidAboutItself() throws Exception {
        report("web-01", "INFO", 10, 0);
        report("db-01", "INFO", 20, 0);

        JsonNode listing = get("/hosts");
        assertEquals(2, listing.size());
        JsonNode web = hostRow(listing, "web-01");
        assertEquals("web-01", web.path("hostname").asText());
        assertEquals("0.15.0", web.path("agent_version").asText());
        assertEquals(3, web.path("queue_depth").asInt());
        assertEquals("reporting", web.path("status").asText());
        assertTrue(web.path("silent_seconds").asLong() < 60, "a fresh event is not silent");
    }

    @Test
    void theDrillDownDescribesOneMachine() throws Exception {
        report("db-01", "INFO", 10, 0);
        report("db-01", "ERROR", 30, 0);
        report("web-01", "INFO", 90, 0);

        JsonNode detail = get("/hosts/db-01");
        assertEquals("db-01", detail.path("host_id").asText());
        assertEquals(2, detail.path("event_count").asLong());
        assertEquals("0.15.0", detail.path("agent_version").asText());
        assertTrue(detail.hasNonNull("first_seen"));

        assertEquals(20.0, detail.path("averages").path("cpu").asDouble(), 0.001);
        assertEquals(2, detail.path("averages").path("window").asInt());
        assertEquals(1, detail.path("levels").path("INFO").asLong());
        assertEquals(1, detail.path("levels").path("ERROR").asLong());

        // Every rule type, so an operator can see what this machine is judged by.
        assertEquals(AlertRules.RULE_TYPES.size(), detail.path("rules").size());
        assertTrue(detail.path("alerts").isArray());
    }

    @Test
    void theDrillDownOnlyShowsThatMachinesAlerts() throws Exception {
        // A zero CPU threshold makes both machines alert, but each detail view sees only its own.
        assertEquals(200, send("PUT", "/rules",
                "{\"rule_type\":\"HIGH_CPU\",\"threshold\":0}", true).statusCode());
        report("web-01", "INFO", 10, 0);
        report("db-01", "INFO", 10, 0);

        JsonNode detail = get("/hosts/web-01");
        assertEquals(1, detail.path("alerts").size());
        assertEquals("web-01", detail.path("alerts").get(0).path("host_id").asText());
    }

    @Test
    void anUnknownMachineIsNotFound() throws Exception {
        report("web-01", "INFO", 10, 0);
        assertEquals(404, send("GET", "/hosts/nope", null, true).statusCode());
        assertEquals(404, send("GET", "/hosts/", null, true).statusCode());
    }

    @Test
    void theFleetRoutesRequireTheApiKey() throws Exception {
        report("web-01", "INFO", 10, 0);
        assertEquals(401, send("GET", "/hosts", null, false).statusCode());
        assertEquals(401, send("GET", "/hosts/web-01", null, false).statusCode());
        assertEquals(405, send("POST", "/hosts", "{}", true).statusCode());
    }

    @Test
    void aSilentMachineRaisesAnAlertAndReportsItsStatus() throws Exception {
        // Backdated, so the machine looks silent without the test waiting.
        report("db-01", "INFO", 10, 30);
        report("web-01", "INFO", 10, 0);

        assertEquals(1, AnalyticsEngine.agentSilenceMonitor().check(Instant.now()));

        JsonNode alert = get("/alerts/agent-silent@db-01");
        assertEquals(AlertRules.AGENT_SILENT, alert.path("alert_type").asText());
        assertEquals("OPEN", alert.path("status").asText());

        JsonNode listing = get("/hosts");
        assertEquals("silent", hostRow(listing, "db-01").path("status").asText());
        assertEquals("reporting", hostRow(listing, "web-01").path("status").asText());
    }

    @Test
    void aMachineThatReportsAgainResolvesItsSilenceAlert() throws Exception {
        report("db-01", "INFO", 10, 30);
        AnalyticsEngine.agentSilenceMonitor().check(Instant.now());
        assertEquals("OPEN", get("/alerts/agent-silent@db-01").path("status").asText());

        report("db-01", "INFO", 10, 0);

        assertEquals("RESOLVED", get("/alerts/agent-silent@db-01").path("status").asText(),
                "the machine is back, so its silence alert must close on its own");
        assertEquals("reporting", hostRow(get("/hosts"), "db-01").path("status").asText());
    }

    @Test
    void aMachineCanBeGivenItsOwnSilenceThreshold() throws Exception {
        assertEquals(200, send("PUT", "/rules",
                "{\"rule_type\":\"AGENT_SILENT\",\"host_id\":\"laptop-01\",\"threshold\":240}", true)
                .statusCode());
        report("laptop-01", "INFO", 10, 60);
        report("db-01", "INFO", 10, 60);

        assertEquals(1, AnalyticsEngine.agentSilenceMonitor().check(Instant.now()));
        assertEquals(404, send("GET", "/alerts/agent-silent@laptop-01", null, true).statusCode());
        assertEquals(200, send("GET", "/alerts/agent-silent@db-01", null, true).statusCode());
    }

    @Test
    void anImpossibleSilenceThresholdIsRejected() throws Exception {
        HttpResponse<String> response = send("PUT", "/rules",
                "{\"rule_type\":\"AGENT_SILENT\",\"threshold\":0}", true);
        assertEquals(400, response.statusCode());
        assertTrue(MAPPER.readTree(response.body()).path("message").asText().contains("minutes"),
                response.body());
    }

    @Test
    void anEventWithAnInvalidQueueDepthIsRejected() throws Exception {
        String body = """
                {"event_id":"bad-depth","host_id":"web-01","level":"INFO","msg":"m",
                 "timestamp":"%s","queue_depth":-1,"cpu_usage":5,"ram_usage":5}"""
                .formatted(Instant.now().toString());
        HttpResponse<String> response = send("POST", "/receive", body, true);
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("queue_depth"), response.body());
    }
}
