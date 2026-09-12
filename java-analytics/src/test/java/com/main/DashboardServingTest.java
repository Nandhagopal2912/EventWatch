package com.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Serving the dashboard from the analytics service is what makes the session cookie possible, so
 * the static handler is part of the security surface rather than a convenience.
 */
class DashboardServingTest {
    private static final String API_KEY = "dashboard-secret";

    @TempDir
    Path temporaryDirectory;

    private final HttpClient client = HttpClient.newHttpClient();
    private Path secretOutside;
    private AnalyticsEngine engine;

    @BeforeEach
    void startEngine() throws IOException {
        Path dashboard = Files.createDirectory(temporaryDirectory.resolve("dashboard"));
        Files.writeString(dashboard.resolve("index.html"), "<h1>EventWatch</h1>");
        Files.writeString(dashboard.resolve("app.js"), "console.log('hi');");
        Files.writeString(dashboard.resolve("styles.css"), "body { margin: 0; }");
        // A file next to the served directory, which is what a traversal would be reaching for.
        secretOutside = Files.writeString(temporaryDirectory.resolve("secrets.env"),
                "EVENTWATCH_API_KEY=" + API_KEY);

        engine = AnalyticsEngine.start(configuration(dashboard.toString()));
    }

    @AfterEach
    void stopEngine() {
        if (engine != null) {
            engine.stop();
            engine = null;
        }
    }

    private EngineConfiguration configuration(String dashboardDirectory) {
        return new EngineConfiguration(0,
                TestSupport.databaseUrl(temporaryDirectory, "dashboard.db"), API_KEY, "text",
                85.0, 80.0, 90.0, 5, false, "", 1, 1, 1, 0, 0, "", "", 2, 0, 60, 100,
                false, "", "", "PKCS12", dashboardDirectory, false, 10, 168, 60, "", 60, 5, 720, 10, true);
    }

    @Test
    void theRootServesTheDashboard() throws Exception {
        HttpResponse<String> response = get("/");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("EventWatch"), response.body());
        assertEquals("text/html; charset=UTF-8",
                response.headers().firstValue("Content-Type").orElse(""));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""),
                "the dashboard ships with the service; a cached copy can disagree with the API");
    }

    @Test
    void assetsAreServedWithTheirOwnContentType() throws Exception {
        assertEquals("text/javascript; charset=UTF-8",
                get("/app.js").headers().firstValue("Content-Type").orElse(""));
        assertEquals("text/css; charset=UTF-8",
                get("/styles.css").headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void theDashboardNeedsNoKeyBecauseItIsTheSignInPage() throws Exception {
        assertEquals(200, get("/").statusCode(), "there is no session before the page loads");
        assertEquals(401, get("/summary").statusCode(), "the API behind it is still closed");
    }

    @Test
    void aTraversalCannotEscapeTheServedDirectory() throws Exception {
        assertTrue(Files.exists(secretOutside), "the file a traversal would be aiming at");

        for (String attempt : new String[] {
                "/../secrets.env",
                "/..%2fsecrets.env",
                "/%2e%2e%2fsecrets.env",
                "/%2e%2e/secrets.env",
                "/subdir/../../secrets.env",
                "/....//secrets.env",
        }) {
            HttpResponse<String> response = get(attempt);
            assertFalse(response.body().contains(API_KEY),
                    attempt + " leaked a file outside the dashboard: " + response.body());
            assertTrue(response.statusCode() == 404 || response.statusCode() == 400,
                    attempt + " should be refused, got " + response.statusCode());
        }
    }

    @Test
    void aMissingFileIs404AndAWriteIs405() throws Exception {
        assertEquals(404, get("/nope.js").statusCode());

        HttpRequest post = HttpRequest.newBuilder(uri("/app.js"))
                .POST(HttpRequest.BodyPublishers.ofString("x")).build();
        HttpResponse<String> response = client.send(post, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, response.statusCode());
        assertEquals("GET, HEAD", response.headers().firstValue("Allow").orElse(""));
    }

    @Test
    void theApiRoutesAreNotShadowedByTheRootHandler() throws Exception {
        // The dashboard is registered at "/", which matches everything without a longer prefix.
        // If that ordering were wrong, the API would start returning HTML.
        assertEquals(200, get("/health").statusCode());
        assertTrue(get("/health").body().contains("java-analytics"), "the API must still answer");
        assertEquals(405, get("/receive").statusCode(), "/receive is POST-only, not a static file");
        assertEquals(401, get("/rules").statusCode());
    }

    @Test
    void anAbsentDirectoryLeavesTheApiRunning() throws Exception {
        engine.stop();
        engine = AnalyticsEngine.start(configuration(
                temporaryDirectory.resolve("not-there").toString()));

        assertEquals(404, get("/").statusCode(), "nothing is served at the root");
        assertEquals(200, get("/health").statusCode(), "the service is still an API");
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + engine.port() + path);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
