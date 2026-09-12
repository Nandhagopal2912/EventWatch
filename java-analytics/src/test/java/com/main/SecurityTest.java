package com.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** TLS, CORS, and metrics authentication — the settings that decide whether this is exposable. */
class SecurityTest {
    private static final String API_KEY = "security-secret";

    @TempDir
    Path temporaryDirectory;

    private AnalyticsEngine engine;

    @AfterEach
    void stopEngine() {
        if (engine != null) {
            engine.stop();
            engine = null;
        }
    }

    private EngineConfiguration configuration(boolean tls, String keystorePath, String keystorePassword,
            List<String> origins, boolean metricsRequireKey) {
        return new EngineConfiguration(0,
                TestSupport.databaseUrl(temporaryDirectory, "security.db"), API_KEY, "text",
                85.0, 80.0, 5, false, "", 1, 1, 1, 0, 0, "", "", 2, 0, 60, 100,
                tls, keystorePath, keystorePassword, "PKCS12", origins, metricsRequireKey, 10, 168, 60, "", 60, 5);
    }

    private HttpResponse<String> get(String scheme, int port, String path, String apiKey,
            String origin, HttpClient client) throws Exception {
        HttpRequest.Builder builder = HttpRequest
                .newBuilder(URI.create(scheme + "://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET();
        if (apiKey != null) {
            builder.header("X-EventWatch-Key", apiKey);
        }
        if (origin != null) {
            builder.header("Origin", origin);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void corsAllowsOnlyConfiguredOrigins() throws Exception {
        engine = AnalyticsEngine.start(configuration(false, "", "",
                List.of("https://ops.example.com", "http://localhost:3000"), false));
        int port = engine.port();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> allowed = get("http", port, "/alerts", API_KEY,
                "https://ops.example.com", client);
        assertEquals("https://ops.example.com",
                allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(""),
                "a configured origin must be echoed");

        HttpResponse<String> rejected = get("http", port, "/alerts", API_KEY,
                "https://evil.example.com", client);
        assertTrue(rejected.headers().firstValue("Access-Control-Allow-Origin").isEmpty(),
                "an origin outside the allowlist must not be echoed");
    }

    @Test
    void anEmptyOriginListAllowsNothing() throws Exception {
        engine = AnalyticsEngine.start(configuration(false, "", "", List.of(), false));
        int port = engine.port();

        HttpResponse<String> response = get("http", port, "/alerts", API_KEY,
                "http://localhost:3000", HttpClient.newHttpClient());
        assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    }

    @Test
    void metricsAreOpenByDefaultAndCanBeClosed() throws Exception {
        engine = AnalyticsEngine.start(configuration(false, "", "", List.of(), false));
        HttpClient client = HttpClient.newHttpClient();
        assertEquals(200, get("http", engine.port(), "/metrics", null, null, client).statusCode(),
                "scrapers rarely send custom headers, so metrics stay open by default");

        engine.stop();
        engine = AnalyticsEngine.start(configuration(false, "", "", List.of(), true));
        int port = engine.port();
        assertEquals(401, get("http", port, "/metrics", null, null, client).statusCode(),
                "METRICS_REQUIRE_KEY must close the endpoint");
        assertEquals(200, get("http", port, "/metrics", API_KEY, null, client).statusCode());
    }

    @Test
    void tlsEnabledWithoutAKeystoreIsRefused() {
        IOException failure = assertThrows(IOException.class,
                () -> AnalyticsEngine.start(configuration(true, "", "", List.of(), false)));
        assertTrue(failure.getMessage().contains("TLS_KEYSTORE_PATH"), failure.getMessage());
    }

    @Test
    void aMissingKeystoreIsRefusedWithAReadableMessage() {
        String missing = temporaryDirectory.resolve("absent.p12").toString();
        IOException failure = assertThrows(IOException.class,
                () -> AnalyticsEngine.start(configuration(true, missing, "changeit", List.of(), false)));
        assertTrue(failure.getMessage().contains("keystore not found"), failure.getMessage());
    }

    @Test
    void theApiIsServedOverTlsWhenAKeystoreIsConfigured() throws Exception {
        Path keystore = temporaryDirectory.resolve("eventwatch.p12");
        String password = "changeit";
        TestKeystore.generate(keystore, password);

        engine = AnalyticsEngine.start(configuration(true, keystore.toString(), password, List.of(), false));
        int port = engine.port();

        HttpClient client = HttpClient.newBuilder()
                .sslContext(trustEverything())
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> health = get("https", port, "/health", null, null, client);
        assertEquals(200, health.statusCode(), health.body());
        assertTrue(health.body().contains("\"status\":\"ok\""), health.body());

        // The key now travels inside the TLS session rather than in the clear.
        HttpResponse<String> summary = get("https", port, "/summary", API_KEY, null, client);
        assertEquals(200, summary.statusCode(), summary.body());
        assertEquals(401, get("https", port, "/summary", null, null, client).statusCode());
    }

    /** A client that accepts the test's self-signed certificate. */
    private static SSLContext trustEverything() throws Exception {
        TrustManager[] trustAll = new TrustManager[] {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustAll, new SecureRandom());
        return context;
    }

    /** Confirms the loader rejects a keystore it cannot open. */
    @Test
    void aKeystoreWithTheWrongPasswordIsRefused() throws Exception {
        Path keystore = temporaryDirectory.resolve("wrong-password.p12");
        TestKeystore.generate(keystore, "changeit");

        IOException failure = assertThrows(IOException.class, () -> AnalyticsEngine.start(
                configuration(true, keystore.toString(), "not-the-password", List.of(), false)));
        assertTrue(failure.getMessage().toLowerCase().contains("keystore"), failure.getMessage());
    }

    /** Proves the generated keystore is a real PKCS12 with a usable key entry. */
    @Test
    void theTestKeystoreIsUsable() throws Exception {
        Path keystore = temporaryDirectory.resolve("check.p12");
        TestKeystore.generate(keystore, "changeit");

        KeyStore loaded = KeyStore.getInstance("PKCS12");
        try (var stream = java.nio.file.Files.newInputStream(keystore)) {
            loaded.load(stream, "changeit".toCharArray());
        }
        assertTrue(loaded.aliases().hasMoreElements(), "the keystore must contain a key entry");
    }
}
