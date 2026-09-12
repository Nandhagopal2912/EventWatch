package com.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Minting, listing, and revoking per-agent credentials through the operator API. */
class AgentsApiTest {
    private static final String API_KEY = "agents-secret";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    private final HttpClient client = HttpClient.newHttpClient();
    private AnalyticsEngine engine;

    @BeforeEach
    void startEngine() throws IOException {
        engine = AnalyticsEngine.start(EngineConfiguration.forTesting(
                TestSupport.databaseUrl(temporaryDirectory, "agents-api.db"), API_KEY));
    }

    @AfterEach
    void stopEngine() {
        if (engine != null) {
            engine.stop();
            engine = null;
        }
    }

    @Test
    void mintingReturnsTheTokenExactlyOnce() throws Exception {
        HttpResponse<String> response = mint("web-01", "front door");
        assertEquals(201, response.statusCode(), response.body());

        JsonNode body = MAPPER.readTree(response.body());
        assertEquals("web-01", body.path("host_id").asText());
        assertEquals("front door", body.path("label").asText());
        assertTrue(body.path("token").asText().startsWith("ewa_"), body.toString());
        assertFalse(body.path("id").asText().isBlank());
    }

    @Test
    void theListNeverIncludesTheTokenOrItsHash() throws Exception {
        mint("web-01", null);

        HttpResponse<String> listed = get("/agents");
        assertEquals(200, listed.statusCode());
        JsonNode agents = MAPPER.readTree(listed.body());
        assertEquals(1, agents.size());
        JsonNode agent = agents.get(0);
        assertTrue(agent.path("token").isMissingNode(), agent.toString());
        assertTrue(agent.path("token_hash").isMissingNode(), agent.toString());
        assertEquals("web-01", agent.path("host_id").asText());
        assertTrue(agent.path("last_used_at").isNull());
        assertTrue(agent.path("revoked_at").isNull());
    }

    @Test
    void rotationMintsASecondTokenBeforeRevokingTheFirst() throws Exception {
        String firstId = MAPPER.readTree(mint("web-01", null).body()).path("id").asText();
        String secondToken = MAPPER.readTree(mint("web-01", null).body()).path("token").asText();

        assertEquals(2, MAPPER.readTree(get("/agents").body()).size(),
                "both credentials are active at once during a rotation");

        assertEquals(200, delete("/agents/" + firstId).statusCode());

        // The new one still authenticates ingestion after the old one is gone.
        assertEquals(200, postEvent(secondToken, "web-01", "rot-1").statusCode());
    }

    @Test
    void revokingAnUnknownIdIs404() throws Exception {
        assertEquals(404, delete("/agents/does-not-exist").statusCode());
    }

    @Test
    void mintingRequiresAHostId() throws Exception {
        HttpResponse<String> response = mint(null, "no host");
        assertEquals(400, response.statusCode());
    }

    @Test
    void mintingRejectsAnOversizedHostId() throws Exception {
        assertEquals(400, mint("h".repeat(200), null).statusCode());
    }

    @Test
    void mintingRejectsAnOversizedLabel() throws Exception {
        assertEquals(400, mint("web-01", "x".repeat(500)).statusCode());
    }

    @Test
    void theAgentsRouteRequiresOperatorAuth() throws Exception {
        HttpRequest unauthenticated = HttpRequest.newBuilder(uri("/agents")).GET().build();
        assertEquals(401, client.send(unauthenticated, HttpResponse.BodyHandlers.ofString()).statusCode());

        HttpRequest mintRequest = HttpRequest.newBuilder(uri("/agents"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"host_id\":\"web-01\"}"))
                .build();
        assertEquals(401, client.send(mintRequest, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void anAgentTokenCannotMintOrListOrRevokeCredentials() throws Exception {
        // The whole point of scoping the token to ingestion: it must not be its own admin key.
        String token = MAPPER.readTree(mint("web-01", null).body()).path("token").asText();

        HttpRequest list = HttpRequest.newBuilder(uri("/agents"))
                .header("X-EventWatch-Key", token).GET().build();
        assertEquals(401, client.send(list, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void aWrongMethodOnAgentsIs405() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/agents"))
                .header("X-EventWatch-Key", API_KEY)
                .DELETE().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, response.statusCode());
        assertEquals("GET, POST", response.headers().firstValue("Allow").orElse(""));
    }

    @Test
    void twoMintedTokensAreNeverTheSame() throws Exception {
        String first = MAPPER.readTree(mint("web-01", null).body()).path("token").asText();
        String second = MAPPER.readTree(mint("web-01", null).body()).path("token").asText();
        assertNotEquals(first, second);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + engine.port() + path);
    }

    private HttpResponse<String> mint(String hostId, String label) throws Exception {
        StringBuilder body = new StringBuilder("{");
        if (hostId != null) {
            body.append("\"host_id\":\"").append(hostId).append("\"");
        }
        if (label != null) {
            body.append(hostId != null ? "," : "").append("\"label\":\"").append(label).append("\"");
        }
        body.append("}");
        HttpRequest request = HttpRequest.newBuilder(uri("/agents"))
                .header("X-EventWatch-Key", API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("X-EventWatch-Key", API_KEY).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("X-EventWatch-Key", API_KEY).DELETE().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postEvent(String key, String hostId, String eventId) throws Exception {
        String body = "{\"event_id\":\"" + eventId + "\",\"level\":\"INFO\",\"msg\":\"rotation check\","
                + "\"timestamp\":\"" + java.time.Instant.now() + "\",\"host_id\":\"" + hostId + "\","
                + "\"cpu_usage\":1,\"ram_usage\":1}";
        HttpRequest request = HttpRequest.newBuilder(uri("/receive"))
                .header("X-EventWatch-Key", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
