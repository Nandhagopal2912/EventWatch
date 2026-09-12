package com.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The credential side of /receive: a per-agent token authenticates like the shared key, a
 * revoked one does not, and a token binds its holder to one host regardless of what the payload
 * claims.
 */
class AgentIngestionTest {
    private static final String API_KEY = "ingestion-secret";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    private final HttpClient client = HttpClient.newHttpClient();
    private AnalyticsEngine engine;

    @AfterEach
    void stopEngine() {
        if (engine != null) {
            engine.stop();
            engine = null;
        }
    }

    private EngineConfiguration configuration(boolean sharedKeyEnabled) {
        return new EngineConfiguration(0,
                TestSupport.databaseUrl(temporaryDirectory, "ingestion.db"), API_KEY, "text",
                85.0, 80.0, 5, false, "", 1, 1, 1, 0, 0, "", "", 2, 0, 60, 100,
                false, "", "", "PKCS12", "", false, 10, 168, 60, "", 60, 5, 720, 10, sharedKeyEnabled);
    }

    private String mintToken(String hostId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/agents"))
                .header("X-EventWatch-Key", API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"host_id\":\"" + hostId + "\"}"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return MAPPER.readTree(response.body()).path("token").asText();
    }

    private HttpResponse<String> send(String key, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri("/receive"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            builder.header("X-EventWatch-Key", key);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String event(String eventId, String hostId) {
        String hostField = hostId == null ? "" : "\"host_id\":\"" + hostId + "\",";
        return "{\"event_id\":\"" + eventId + "\",\"level\":\"INFO\",\"msg\":\"agent auth test\","
                + "\"timestamp\":\"" + Instant.now() + "\"," + hostField
                + "\"cpu_usage\":5,\"ram_usage\":5}";
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + engine.port() + path);
    }

    @Test
    void aValidAgentTokenAuthenticatesIngestion() throws Exception {
        engine = AnalyticsEngine.start(configuration(true));
        String token = mintToken("web-01");

        assertEquals(200, send(token, event("t1", "web-01")).statusCode());
    }

    @Test
    void aRevokedTokenIsRejectedLikeAnyWrongKey() throws Exception {
        engine = AnalyticsEngine.start(configuration(true));
        HttpRequest mint = HttpRequest.newBuilder(uri("/agents"))
                .header("X-EventWatch-Key", API_KEY).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"host_id\":\"web-01\"}")).build();
        JsonNode minted = MAPPER.readTree(
                client.send(mint, HttpResponse.BodyHandlers.ofString()).body());
        String token = minted.path("token").asText();
        String id = minted.path("id").asText();

        HttpRequest revoke = HttpRequest.newBuilder(uri("/agents/" + id))
                .header("X-EventWatch-Key", API_KEY).DELETE().build();
        client.send(revoke, HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> response = send(token, event("t2", "web-01"));
        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("Unauthorized"));
    }

    @Test
    void anUnknownTokenIsRejected() throws Exception {
        engine = AnalyticsEngine.start(configuration(true));
        assertEquals(401, send("ewa_" + "not-a-real-token", event("t3", "web-01")).statusCode());
    }

    @Test
    void theTokenOverridesWhateverHostIdThePayloadClaims() throws Exception {
        // The token is the source of truth for identity - this is what a shared key could never
        // enforce, because any caller with it could claim to be any host.
        engine = AnalyticsEngine.start(configuration(true));
        String token = mintToken("web-01");

        assertEquals(200, send(token, event("t4", "someone-elses-host")).statusCode());

        HttpRequest hostLookup = HttpRequest.newBuilder(uri("/hosts/web-01"))
                .header("X-EventWatch-Key", API_KEY).GET().build();
        HttpResponse<String> host = client.send(hostLookup, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, host.statusCode(), "the event must land under the token's bound host");

        HttpRequest spoofedLookup = HttpRequest.newBuilder(uri("/hosts/someone-elses-host"))
                .header("X-EventWatch-Key", API_KEY).GET().build();
        assertEquals(404, client.send(spoofedLookup, HttpResponse.BodyHandlers.ofString()).statusCode(),
                "the claimed host must never have received the event");
    }

    @Test
    void aTokenWorksWithNoHostIdInThePayloadAtAll() throws Exception {
        // An agent authenticated by a bound token does not need to send its own identity.
        engine = AnalyticsEngine.start(configuration(true));
        String token = mintToken("web-01");

        assertEquals(200, send(token, event("t5", null)).statusCode());

        HttpRequest hostLookup = HttpRequest.newBuilder(uri("/hosts/web-01"))
                .header("X-EventWatch-Key", API_KEY).GET().build();
        assertEquals(200, client.send(hostLookup, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void theSharedKeyStillWorksWhileEnabled() throws Exception {
        engine = AnalyticsEngine.start(configuration(true));
        assertEquals(200, send(API_KEY, event("t6", "web-01")).statusCode());
    }

    @Test
    void theSharedKeyIsRefusedOnceDisabled() throws Exception {
        engine = AnalyticsEngine.start(configuration(false));
        assertEquals(401, send(API_KEY, event("t7", "web-01")).statusCode());
    }

    @Test
    void anAgentTokenStillWorksWhenTheSharedKeyIsDisabled() throws Exception {
        engine = AnalyticsEngine.start(configuration(false));
        String token = mintToken("web-01");
        assertEquals(200, send(token, event("t8", "web-01")).statusCode());
    }

    @Test
    void aSessionCookieDoesNotAuthorizeIngestion() throws Exception {
        // Deliberate narrowing: the dashboard never posts telemetry, and a stolen session should
        // not be able to either.
        engine = AnalyticsEngine.start(configuration(true));
        HttpRequest signIn = HttpRequest.newBuilder(uri("/session"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"api_key\":\"" + API_KEY + "\"}"))
                .build();
        String cookie = client.send(signIn, HttpResponse.BodyHandlers.ofString())
                .headers().firstValue("Set-Cookie").orElseThrow();
        String pair = cookie.substring(0, cookie.indexOf(';'));

        HttpRequest request = HttpRequest.newBuilder(uri("/receive"))
                .header("Cookie", pair)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(event("t9", "web-01")))
                .build();
        assertEquals(401, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void acceptedIngestionIsCountedByWhichCredentialAuthorizedIt() throws Exception {
        engine = AnalyticsEngine.start(configuration(true));
        String token = mintToken("web-01");
        send(API_KEY, event("t10", "web-01"));
        send(token, event("t11", "web-01"));

        HttpRequest metricsRequest = HttpRequest.newBuilder(uri("/metrics")).GET().build();
        String metrics = client.send(metricsRequest, HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(metrics.contains("eventwatch_receive_auth_total{method=\"shared_key\"} 1"), metrics);
        assertTrue(metrics.contains("eventwatch_receive_auth_total{method=\"agent_token\"} 1"), metrics);
    }

    @Test
    void aSuccessfulTokenAuthRecordsLastUsed() throws Exception {
        engine = AnalyticsEngine.start(configuration(true));
        String token = mintToken("web-01");
        send(token, event("t12", "web-01"));

        HttpRequest list = HttpRequest.newBuilder(uri("/agents"))
                .header("X-EventWatch-Key", API_KEY).GET().build();
        List<JsonNode> agents = MAPPER.readerForListOf(JsonNode.class)
                .<List<JsonNode>>readValue(client.send(list, HttpResponse.BodyHandlers.ofString()).body());
        assertTrue(agents.get(0).path("last_used_at").isTextual(),
                "a credential that just authenticated must show a last-used time");
    }
}
