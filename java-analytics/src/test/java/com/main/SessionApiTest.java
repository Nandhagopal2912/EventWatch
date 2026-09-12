package com.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The operator session: the API key is exchanged once for an HttpOnly cookie, and the key never
 * has to live in the page again.
 */
class SessionApiTest {
    private static final String API_KEY = "session-secret";

    @TempDir
    Path temporaryDirectory;

    private final HttpClient client = HttpClient.newBuilder()
            // The default client would store and replay cookies, hiding what the server actually
            // sent; every request here carries exactly the cookie the test means to send.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private AnalyticsEngine engine;

    @BeforeEach
    void startEngine() throws IOException {
        engine = AnalyticsEngine.start(configuration(10));
    }

    @AfterEach
    void stopEngine() {
        if (engine != null) {
            engine.stop();
            engine = null;
        }
    }

    private EngineConfiguration configuration(int sessionRateLimit) {
        return new EngineConfiguration(0,
                TestSupport.databaseUrl(temporaryDirectory, "session.db"), API_KEY, "text",
                85.0, 80.0, 5, false, "", 1, 1, 1, 0, 0, "", "", 2, 0, 60, 100,
                false, "", "", "PKCS12", "", false, 10, 168, 60, "", 60, 5, 720, sessionRateLimit);
    }

    @Test
    void theKeyIsExchangedForAnHttpOnlyCookie() throws Exception {
        HttpResponse<String> response = signIn(API_KEY);
        assertEquals(200, response.statusCode(), response.body());

        String cookie = response.headers().firstValue("Set-Cookie").orElse("");
        assertTrue(cookie.startsWith(HttpSupport.SESSION_COOKIE + "="), "got: " + cookie);
        String lowerCase = cookie.toLowerCase(Locale.ROOT);
        assertTrue(lowerCase.contains("httponly"), "a script must not be able to read it: " + cookie);
        assertTrue(lowerCase.contains("samesite=strict"), "this is what stands in for CSRF: " + cookie);
        assertTrue(lowerCase.contains("path=/"), cookie);
        assertFalse(lowerCase.contains("secure"), "plain HTTP must not set Secure, or the cookie is dropped");
        assertFalse(cookie.contains(API_KEY), "the token must not be derived from the key");
    }

    @Test
    void theCookieAuthorizesTheWholeApi() throws Exception {
        String cookie = tokenCookie(signIn(API_KEY));

        assertEquals(200, withCookie("GET", "/summary", cookie, null).statusCode());
        assertEquals(200, withCookie("GET", "/hosts", cookie, null).statusCode());
        assertEquals(200, withCookie("GET", "/alerts", cookie, null).statusCode());
        // The editor's writes matter most: they are what the dashboard could not do without auth.
        assertEquals(200, withCookie("PUT", "/rules", cookie,
                "{\"rule_type\":\"HIGH_CPU\",\"threshold\":42}").statusCode());
        assertEquals(200, withCookie("DELETE", "/rules?rule_type=HIGH_CPU", cookie, null).statusCode());
    }

    @Test
    void theSharedKeyStillWorksForAgentsAndScripts() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/summary"))
                .header("X-EventWatch-Key", API_KEY).GET().build();
        assertEquals(200, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode(),
                "an agent has no browser and no cookie jar");
    }

    @Test
    void aWrongKeyIsRefusedWithoutSayingWhy() throws Exception {
        HttpResponse<String> response = signIn("not-the-key");
        assertEquals(401, response.statusCode());
        assertTrue(response.headers().firstValue("Set-Cookie").isEmpty(), "no cookie on a refusal");
        assertTrue(response.body().contains("Unauthorized"),
                "the message must not distinguish a wrong key from a missing one: " + response.body());
    }

    @Test
    void anUnknownCookieIsNotASession() throws Exception {
        assertEquals(401, withCookie("GET", "/summary",
                HttpSupport.SESSION_COOKIE + "=made-up-token", null).statusCode());
        assertEquals(401, withCookie("GET", "/summary", "", null).statusCode());
    }

    @Test
    void signingOutRevokesTheCookieEverywhere() throws Exception {
        String cookie = tokenCookie(signIn(API_KEY));
        assertEquals(200, withCookie("GET", "/summary", cookie, null).statusCode());

        HttpResponse<String> signOut = withCookie("DELETE", "/session", cookie, null);
        assertEquals(200, signOut.statusCode());
        assertTrue(signOut.headers().firstValue("Set-Cookie").orElse("").contains("Max-Age=0"),
                "the browser has to be told to drop it too");

        // Revoked on the server, so replaying the same cookie is not enough.
        assertEquals(401, withCookie("GET", "/summary", cookie, null).statusCode(),
                "a signed-out token must stop working even if the client keeps it");
    }

    @Test
    void everySignInGetsItsOwnToken() throws Exception {
        assertNotEquals(tokenCookie(signIn(API_KEY)), tokenCookie(signIn(API_KEY)),
                "two operators must not share a token");
    }

    @Test
    void signingInIsRateLimitedBecauseItIsTheOneOpenDoor() throws Exception {
        engine.stop();
        engine = AnalyticsEngine.start(configuration(3));

        assertEquals(401, signIn("wrong-1").statusCode());
        assertEquals(401, signIn("wrong-2").statusCode());
        assertEquals(401, signIn("wrong-3").statusCode());
        HttpResponse<String> blocked = signIn("wrong-4");
        assertEquals(429, blocked.statusCode(), "brute force must hit a wall: " + blocked.body());
        assertEquals(429, signIn(API_KEY).statusCode(), "even the right key waits out the window");
    }

    @Test
    void aReloadCanAskWhetherItIsStillSignedIn() throws Exception {
        // Without this the page would ask for the key again on every reload and mint a second
        // token, which defeats the point of a cookie that outlives the page.
        assertEquals(401, withCookie("GET", "/session", null, null).statusCode());

        String cookie = tokenCookie(signIn(API_KEY));
        assertEquals(200, withCookie("GET", "/session", cookie, null).statusCode());

        withCookie("DELETE", "/session", cookie, null);
        assertEquals(401, withCookie("GET", "/session", cookie, null).statusCode());
    }

    @Test
    void theSessionRouteRefusesWhatItDoesNotSupport() throws Exception {
        HttpResponse<String> wrongMethod = withCookie("PUT", "/session", null, null);
        assertEquals(405, wrongMethod.statusCode());
        assertEquals(Optional.of("GET, POST, DELETE"), wrongMethod.headers().firstValue("Allow"));

        HttpRequest plainText = HttpRequest.newBuilder(uri("/session"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(API_KEY)).build();
        assertEquals(415, client.send(plainText, HttpResponse.BodyHandlers.ofString()).statusCode());

        HttpRequest malformed = HttpRequest.newBuilder(uri("/session"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{oops")).build();
        assertEquals(400, client.send(malformed, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void anExpiredSessionStopsBeingValid() {
        // Driven against the store rather than HTTP: the configured minimum is twelve hours, and
        // a test that waits for it is not a test.
        Metrics metrics = new Metrics();
        SessionStore store = new SessionStore(Duration.ofMinutes(30), metrics);
        Instant now = Instant.now();
        String token = store.create(now);

        assertTrue(store.isValid(token, now.plus(Duration.ofMinutes(29))));
        assertFalse(store.isValid(token, now.plus(Duration.ofMinutes(31))), "the ttl must be enforced");
        assertEquals(0, store.size(), "an expired token is dropped rather than kept forever");
        assertTrue(metrics.render(0, 0).contains("eventwatch_sessions_active 0"));
    }

    @Test
    void signInsAreCountedByOutcome() throws Exception {
        signIn(API_KEY);
        signIn("wrong");
        String metrics = body(withCookie("GET", "/metrics", null, null));
        assertTrue(metrics.contains("eventwatch_sessions_total{outcome=\"created\"} 1"), metrics);
        assertTrue(metrics.contains("eventwatch_sessions_total{outcome=\"rejected\"} 1"), metrics);
        assertTrue(metrics.contains("eventwatch_sessions_active 1"), metrics);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + engine.port() + path);
    }

    private HttpResponse<String> signIn(String key) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/session"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"api_key\":\"" + key + "\"}"))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> withCookie(String method, String path, String cookie, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path));
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String body(HttpResponse<String> response) {
        return response.body();
    }

    /** The name=value pair alone, as a browser would send it back. */
    private static String tokenCookie(HttpResponse<String> response) {
        String setCookie = response.headers().firstValue("Set-Cookie").orElse("");
        assertNotNull(setCookie);
        int semicolon = setCookie.indexOf(';');
        return semicolon < 0 ? setCookie : setCookie.substring(0, semicolon);
    }
}
