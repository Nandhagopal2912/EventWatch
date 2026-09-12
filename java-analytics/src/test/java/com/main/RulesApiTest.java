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

/** Rules driven through the HTTP API against the real engine, proving they change alerting. */
class RulesApiTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API_KEY = "rules-secret";

    @TempDir
    Path temporaryDirectory;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private AnalyticsEngine engine;
    private String databaseUrl;
    private int port;
    private int sequence;

    @BeforeEach
    void startEngine() throws IOException {
        databaseUrl = TestSupport.databaseUrl(temporaryDirectory, "rules-api.db");
        restart();
    }

    @AfterEach
    void stopEngine() {
        if (engine != null) {
            engine.stop();
            engine = null;
        }
    }

    private void restart() throws IOException {
        stopEngine();
        // Defaults: CPU 85, RAM 80, repeated errors 5.
        engine = AnalyticsEngine.start(EngineConfiguration.forTesting(databaseUrl, API_KEY));
        port = engine.port();
    }

    private HttpResponse<String> send(String method, String path, String body, String contentType,
            boolean authenticated) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (authenticated) {
            builder.header("X-EventWatch-Key", API_KEY);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", contentType);
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> putRule(String json) throws Exception {
        return send("PUT", "/rules", json, "application/json", true);
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = send("GET", path, null, null, true);
        assertEquals(200, response.statusCode(), response.body());
        return MAPPER.readTree(response.body());
    }

    private void report(String hostId, String level, String message, double cpu) throws Exception {
        String body = """
                {"event_id":"rules-%d","host_id":"%s","hostname":"%s","level":"%s","msg":"%s",
                 "timestamp":"%s","cpu_usage":%s,"ram_usage":5}"""
                .formatted(sequence++, hostId, hostId, level, message, Instant.now().toString(), cpu);
        HttpResponse<String> response = send("POST", "/receive", body, "application/json", true);
        assertEquals(200, response.statusCode(), response.body());
    }

    private int alertLookup(String alertKey) throws Exception {
        return send("GET", "/alerts/" + alertKey, null, null, true).statusCode();
    }

    private String alertStatus(String alertKey) throws Exception {
        return get("/alerts/" + alertKey).path("status").asText();
    }

    private JsonNode effectiveFor(String hostId, String ruleType) throws Exception {
        for (JsonNode rule : get("/rules/effective?host_id=" + hostId).path("rules")) {
            if (ruleType.equals(rule.path("rule_type").asText())) {
                return rule;
            }
        }
        throw new AssertionError("no effective " + ruleType + " rule for " + hostId);
    }

    @Test
    void aMachinesOwnRuleOverridesTheFleetDefault() throws Exception {
        // The case this phase exists for: a build box at 90% CPU is healthy, a database is not.
        assertEquals(200, putRule("{\"rule_type\":\"HIGH_CPU\",\"host_id\":\"build-01\",\"threshold\":95}")
                .statusCode());

        report("build-01", "INFO", "compiling", 90);
        report("db-01", "INFO", "serving", 90);

        assertEquals(404, alertLookup("cpu-high@build-01"), "95% is this machine's threshold");
        assertEquals(200, alertLookup("cpu-high@db-01"), "the default 85% still applies here");
    }

    @Test
    void aFleetWideRuleAppliesToEveryMachineWithoutItsOwn() throws Exception {
        assertEquals(200, putRule("{\"rule_type\":\"HIGH_CPU\",\"threshold\":50}").statusCode());

        report("web-01", "INFO", "load", 60);
        report("web-02", "INFO", "load", 60);

        assertEquals(200, alertLookup("cpu-high@web-01"));
        assertEquals(200, alertLookup("cpu-high@web-02"), "60% is below the default but above the fleet rule");
    }

    @Test
    void disablingARuleForAMachineResolvesItsOpenAlert() throws Exception {
        report("db-01", "INFO", "hot", 90);
        assertEquals("OPEN", alertStatus("cpu-high@db-01"));

        assertEquals(200, putRule(
                "{\"rule_type\":\"HIGH_CPU\",\"host_id\":\"db-01\",\"threshold\":85,\"enabled\":false}")
                .statusCode());
        report("db-01", "INFO", "still hot", 90);

        assertEquals("RESOLVED", alertStatus("cpu-high@db-01"),
                "a rule that no longer applies must not leave its alert firing");
    }

    @Test
    void removingAnOverrideFallsBackToTheNextBroaderRule() throws Exception {
        putRule("{\"rule_type\":\"HIGH_CPU\",\"threshold\":50}");
        putRule("{\"rule_type\":\"HIGH_CPU\",\"host_id\":\"web-01\",\"threshold\":95}");

        JsonNode host = effectiveFor("web-01", "HIGH_CPU");
        assertEquals(95.0, host.path("threshold").asDouble());
        assertEquals("host", host.path("source").asText());

        assertEquals(200, send("DELETE", "/rules?rule_type=HIGH_CPU&host_id=web-01", null, null, true).statusCode());
        JsonNode fleet = effectiveFor("web-01", "HIGH_CPU");
        assertEquals(50.0, fleet.path("threshold").asDouble());
        assertEquals("fleet", fleet.path("source").asText());

        assertEquals(200, send("DELETE", "/rules?rule_type=HIGH_CPU", null, null, true).statusCode());
        JsonNode fallback = effectiveFor("web-01", "HIGH_CPU");
        assertEquals(85.0, fallback.path("threshold").asDouble());
        assertEquals("default", fallback.path("source").asText());

        assertEquals(404, send("DELETE", "/rules?rule_type=HIGH_CPU", null, null, true).statusCode(),
                "there is nothing left to remove");
    }

    @Test
    void theRepeatedErrorRuleCanBeTightenedForOneMachine() throws Exception {
        putRule("{\"rule_type\":\"REPEATED_ERROR\",\"host_id\":\"db-01\",\"threshold\":2}");

        report("db-01", "ERROR", "deadlock", 5);
        report("db-01", "ERROR", "deadlock", 5);
        report("web-01", "ERROR", "deadlock", 5);
        report("web-01", "ERROR", "deadlock", 5);

        JsonNode active = get("/alerts");
        long databaseErrors = 0;
        long webErrors = 0;
        for (JsonNode alert : active) {
            if (!"REPEATED_ERROR".equals(alert.path("alert_type").asText())) {
                continue;
            }
            if ("db-01".equals(alert.path("host_id").asText())) {
                databaseErrors++;
            } else if ("web-01".equals(alert.path("host_id").asText())) {
                webErrors++;
            }
        }
        assertEquals(1, databaseErrors, "two repeats reach db-01's threshold of 2");
        assertEquals(0, webErrors, "web-01 still needs the default of 5");
    }

    @Test
    void rulesAndTheirEffectSurviveARestart() throws Exception {
        putRule("{\"rule_type\":\"HIGH_CPU\",\"host_id\":\"build-01\",\"threshold\":98}");
        restart();

        JsonNode listing = get("/rules");
        assertEquals(1, listing.path("rules").size());
        assertEquals("build-01", listing.path("rules").get(0).path("host_id").asText());

        report("build-01", "INFO", "compiling", 95);
        assertEquals(404, alertLookup("cpu-high@build-01"), "the restored rule is enforced");
    }

    @Test
    void theListingShowsStoredRulesAndTheDefaultsBeneathThem() throws Exception {
        putRule("{\"rule_type\":\"HIGH_RAM\",\"threshold\":70}");

        JsonNode listing = get("/rules");
        assertEquals(85.0, listing.path("defaults").path("HIGH_CPU").asDouble());
        assertEquals(80.0, listing.path("defaults").path("HIGH_RAM").asDouble());
        assertEquals(5.0, listing.path("defaults").path("REPEATED_ERROR").asDouble());

        JsonNode rule = listing.path("rules").get(0);
        assertEquals("HIGH_RAM", rule.path("rule_type").asText());
        assertTrue(rule.path("host_id").isNull(), "a fleet-wide rule has no host, never the '*' sentinel");
        assertTrue(rule.path("enabled").asBoolean(), "enabled defaults to true");
    }

    @Test
    void invalidRulesAreRejectedWithReadableReasons() throws Exception {
        assertRejected("{\"rule_type\":\"DISK_FULL\",\"threshold\":50}", "rule_type");
        assertRejected("{\"threshold\":50}", "rule_type");
        assertRejected("{\"rule_type\":\"HIGH_CPU\",\"threshold\":150}", "percentage");
        assertRejected("{\"rule_type\":\"HIGH_CPU\",\"threshold\":\"high\"}", "number");
        assertRejected("{\"rule_type\":\"HIGH_CPU\"}", "number");
        assertRejected("{\"rule_type\":\"REPEATED_ERROR\",\"threshold\":9}", "could never fire");
        assertRejected("{\"rule_type\":\"HIGH_CPU\",\"host_id\":\"*\",\"threshold\":50}", "reserved");
        assertRejected("{\"rule_type\":\"HIGH_CPU\",\"host_id\":42,\"threshold\":50}", "text");
        assertRejected("{\"rule_type\":\"HIGH_CPU\",\"threshold\":50,\"enabled\":\"yes\"}", "true or false");
        assertRejected("{not json", "Invalid JSON");
        assertRejected("[1,2]", "JSON object");

        assertEquals(0, get("/rules").path("rules").size(), "nothing invalid may be stored");
    }

    @Test
    void theRulesApiRequiresTheKeyAndTheRightMethodsAndContentType() throws Exception {
        assertEquals(401, send("GET", "/rules", null, null, false).statusCode());
        assertEquals(401, send("PUT", "/rules", "{}", "application/json", false).statusCode());
        assertEquals(405, send("POST", "/rules", "{}", "application/json", true).statusCode());
        assertEquals(415, send("PUT", "/rules", "{\"rule_type\":\"HIGH_CPU\",\"threshold\":50}",
                "text/plain", true).statusCode());
        assertEquals(404, send("GET", "/rules/unknown", null, null, true).statusCode());
        assertEquals(400, send("GET", "/rules/effective", null, null, true).statusCode(), "host_id is required");
        assertEquals(400, send("DELETE", "/rules?rule_type=DISK_FULL", null, null, true).statusCode());
    }

    @Test
    void thePreflightAllowsTheMethodsTheEditorUses() throws Exception {
        HttpResponse<String> preflight = send("OPTIONS", "/rules", null, null, false);
        assertEquals(204, preflight.statusCode());
        String methods = preflight.headers().firstValue("Access-Control-Allow-Methods").orElse("");
        assertTrue(methods.contains("PUT") && methods.contains("DELETE"),
                "the dashboard's cross-origin PUT and DELETE would be blocked, got: " + methods);
    }

    private void assertRejected(String body, String reasonFragment) throws Exception {
        HttpResponse<String> response = putRule(body);
        assertEquals(400, response.statusCode(), body + " -> " + response.body());
        String message = MAPPER.readTree(response.body()).path("message").asText();
        assertTrue(message.contains(reasonFragment),
                body + " should explain \"" + reasonFragment + "\", got: " + message);
    }
}
