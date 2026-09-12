package com.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The dead-man's switch: alive means alive *and* able to store, or no ping at all. */
class WatchdogHeartbeatTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    private HeartbeatSink sink;
    private Database database;
    private EventRepository events;
    private AlertRepository alerts;
    private Metrics metrics;

    @BeforeEach
    void setUp() throws IOException, SQLException {
        sink = new HeartbeatSink();
        database = Database.open(EngineConfiguration.forTesting(
                TestSupport.databaseUrl(temporaryDirectory, "watchdog.db"), "watchdog-secret"));
        database.initializeSchema();
        events = new EventRepository(database.connections(), database.dialect());
        alerts = new AlertRepository(database.connections());
        metrics = new Metrics();
    }

    @AfterEach
    void tearDown() {
        sink.close();
        database.close();
    }

    private WatchdogHeartbeat heartbeat(String url) {
        return new WatchdogHeartbeat(database, events, alerts, metrics, MAPPER, url, 2);
    }

    @Test
    void aHealthyServicePingsWithSomethingWorthLogging() throws Exception {
        events.insertIfAbsent(new AnalyticsEngine.LogEntry("e1", "INFO", "heartbeat",
                Instant.now(), "web-01", "web-01", "0.15.0", 0, 5.0, 5.0));
        alerts.saveOccurrence(new AlertRecord("cpu-high@web-01", "HIGH_CPU", "web-01", "hot", Instant.now()));

        heartbeat(sink.url()).ping();

        assertEquals(1, sink.count());
        JsonNode payload = MAPPER.readTree(sink.lastBody());
        assertEquals(WatchdogHeartbeat.SCHEMA_VERSION, payload.path("schema_version").asText());
        assertEquals("java-analytics", payload.path("service").asText());
        assertEquals(1, payload.path("hosts").asInt());
        assertEquals(1, payload.path("active_alerts").asInt());
        assertFalse(payload.path("timestamp").asText().isBlank());
        assertTrue(metrics.render(0, 0).contains("eventwatch_watchdog_pings_total{outcome=\"delivered\"} 1"));
    }

    @Test
    void noUrlMeansNoHeartbeat() {
        WatchdogHeartbeat disabled = heartbeat("");
        assertFalse(disabled.enabled());
        disabled.ping();
        assertEquals(0, sink.count());
    }

    @Test
    void anUnreachableEndpointIsCountedNotThrown() {
        // Port 1 is reserved and refuses connections.
        heartbeat("http://127.0.0.1:1/ping").ping();
        assertTrue(metrics.render(0, 0).contains("eventwatch_watchdog_pings_total{outcome=\"failed\"} 1"));
    }

    @Test
    void aRejectedHeartbeatIsCountedAsFailed() {
        sink.respondWith(500);
        heartbeat(sink.url()).ping();

        assertEquals(1, sink.count(), "the request was made");
        assertTrue(metrics.render(0, 0).contains("eventwatch_watchdog_pings_total{outcome=\"failed\"} 1"));
    }

    @Test
    void aBrokenDatabaseStopsTheHeartbeatOnPurpose() {
        // Staying silent is the signal: a service that cannot store anything is not alive in
        // any useful sense, and a heartbeat saying otherwise would be worse than none.
        database.close();
        heartbeat(sink.url()).ping();

        assertEquals(0, sink.count(), "no ping may be sent while storage is broken");
        assertTrue(metrics.render(0, 0).contains("eventwatch_watchdog_pings_total{outcome=\"skipped\"} 1"));
    }

    @Test
    void aMalformedUrlIsCountedNotThrown() {
        heartbeat("not a url").ping();
        assertTrue(metrics.render(0, 0).contains("eventwatch_watchdog_pings_total{outcome=\"failed\"} 1"));
    }

    /** A minimal endpoint standing in for the external dead-man switch. */
    private static final class HeartbeatSink implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicReference<String> lastBody = new AtomicReference<>("");
        private volatile int responseStatus = 200;

        HeartbeatSink() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/ping", exchange -> {
                lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                requests.incrementAndGet();
                exchange.sendResponseHeaders(responseStatus, -1);
                exchange.close();
            });
            server.start();
        }

        void respondWith(int status) {
            responseStatus = status;
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/ping";
        }

        int count() {
            return requests.get();
        }

        String lastBody() {
            return lastBody.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
