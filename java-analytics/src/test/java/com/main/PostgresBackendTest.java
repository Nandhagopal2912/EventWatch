package com.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Runs the whole storage layer against a real PostgreSQL server. Skipped unless
 * EVENTWATCH_TEST_POSTGRES_URL is set, so a checkout without PostgreSQL still builds.
 * CI provides one as a service container.
 */
@EnabledIfEnvironmentVariable(named = "EVENTWATCH_TEST_POSTGRES_URL", matches = ".+")
class PostgresBackendTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API_KEY = "postgres-secret";

    private final HttpClient client = HttpClient.newHttpClient();
    private Database database;
    private EngineConfiguration configuration;

    private EngineConfiguration configuration(int retentionDays) {
        return new EngineConfiguration(
                0,
                System.getenv("EVENTWATCH_TEST_POSTGRES_URL"),
                API_KEY,
                "text",
                85.0, 80.0, 5,
                false, "", 1, 1, 1, 0,
                0,
                System.getenv().getOrDefault("EVENTWATCH_TEST_POSTGRES_USER", "eventwatch"),
                System.getenv().getOrDefault("EVENTWATCH_TEST_POSTGRES_PASSWORD", "eventwatch"),
                4,
                retentionDays,
                60,
                100,
                false, "", "", "PKCS12", List.of("http://localhost:3000"), false);
    }

    @BeforeEach
    void setUp() throws SQLException {
        configuration = configuration(0);
        database = Database.open(configuration);
        dropSchema();
        database.initializeSchema();
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    private void dropSchema() throws SQLException {
        try (Connection connection = database.connections().getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS telemetry_events");
            statement.executeUpdate("DROP TABLE IF EXISTS alerts");
            statement.executeUpdate("DROP TABLE IF EXISTS notification_deliveries");
        }
    }

    private AnalyticsEngine.LogEntry entry(String eventId, String level, String message, Instant timestamp) {
        return new AnalyticsEngine.LogEntry(eventId, level, message, timestamp, 42.5, 24.5);
    }

    @Test
    void theBackendIsSelectedFromTheUrl() {
        assertEquals("postgresql", database.dialect().name());
    }

    @Test
    void schemaCreationIsRepeatable() throws SQLException {
        database.initializeSchema();
        database.initializeSchema();
        assertEquals(0, new EventRepository(database.connections(), database.dialect())
                .count(null, null, null));
    }

    @Test
    void eventsRoundTripWithTheSameSemanticsAsSqlite() throws SQLException {
        EventRepository events = new EventRepository(database.connections(), database.dialect());
        Instant timestamp = Instant.parse("2026-09-11T10:00:00Z");
        assertTrue(events.insertIfAbsent(entry("e1", "ERROR", "disk full", timestamp)));

        List<AnalyticsEngine.LogEntry> stored = events.recent(10);
        assertEquals(1, stored.size());
        assertEquals("e1", stored.get(0).eventId);
        assertEquals("disk full", stored.get(0).message);
        assertEquals(timestamp, stored.get(0).timestamp);
        assertEquals(42.5, stored.get(0).cpuUsage, 0.0001);
    }

    @Test
    void duplicateEventIdsAreIgnored() throws SQLException {
        EventRepository events = new EventRepository(database.connections(), database.dialect());
        Instant timestamp = Instant.parse("2026-09-11T10:00:00Z");
        assertTrue(events.insertIfAbsent(entry("dup", "INFO", "first", timestamp)));
        assertFalse(events.insertIfAbsent(entry("dup", "INFO", "second", timestamp.plusSeconds(60))));
        assertEquals(1, events.count(null, null, null));
        assertEquals("first", events.recent(1).get(0).message);
    }

    @Test
    void filtersAndPagingBehaveIdentically() throws SQLException {
        EventRepository events = new EventRepository(database.connections(), database.dialect());
        for (int index = 0; index < 5; index++) {
            events.insertIfAbsent(entry("e" + index, index % 2 == 0 ? "INFO" : "ERROR", "m" + index,
                    Instant.parse("2026-09-11T10:00:00Z").plusSeconds(index * 60L)));
        }
        assertEquals(5, events.count(null, null, null));
        assertEquals(2, events.count("ERROR", null, null));
        assertEquals(2, events.find(null, null, null, 2, 0).size());
        assertEquals("m2", events.find(null, null, null, 2, 2).get(0).message);
        assertEquals(2, events.topErrorMessages(5).size());
    }

    @Test
    void theAlertLifecycleBehavesIdentically() throws SQLException {
        AlertRepository alerts = new AlertRepository(database.connections());
        Instant timestamp = Instant.parse("2026-09-11T10:00:00Z");

        assertEquals(AlertTransition.Type.OPENED,
                alerts.saveOccurrence(new AlertRecord("cpu-high", "HIGH_CPU", "web-01", "m", timestamp)).type());
        assertEquals(AlertTransition.Type.OCCURRENCE,
                alerts.saveOccurrence(new AlertRecord("cpu-high", "HIGH_CPU", "web-01", "m", timestamp)).type());

        assertNotNull(alerts.acknowledge("cpu-high"));
        AlertTransition afterAcknowledgement =
                alerts.saveOccurrence(new AlertRecord("cpu-high", "HIGH_CPU", "web-01", "m", timestamp));
        assertEquals(AlertStatus.ACKNOWLEDGED, afterAcknowledgement.alert().getStatus(),
                "the upsert must preserve an acknowledgement on PostgreSQL too");

        assertNotNull(alerts.resolve("cpu-high", timestamp));
        assertNull(alerts.resolve("cpu-high", timestamp));
        assertEquals(AlertTransition.Type.REOPENED,
                alerts.saveOccurrence(new AlertRecord("cpu-high", "HIGH_CPU", "web-01", "m", timestamp)).type());
    }

    @Test
    void notificationHistoryBehavesIdentically() throws SQLException {
        NotificationRepository notifications = new NotificationRepository(database.connections());
        notifications.record(new NotificationRecord("cpu-high", "alert.opened", "FAILED", 500,
                "boom", 1, Instant.parse("2026-09-11T10:00:00Z")));
        notifications.record(new NotificationRecord("cpu-high", "alert.opened", "DELIVERED", 200,
                null, 2, Instant.parse("2026-09-11T10:00:05Z")));

        List<NotificationRecord> history = notifications.findByAlertKey("cpu-high", 10);
        assertEquals(2, history.size());
        assertEquals("DELIVERED", history.get(0).deliveryStatus());
        assertNull(history.get(0).errorMessage());
        assertEquals(Instant.parse("2026-09-11T10:00:05Z"), notifications.lastDeliveredAt("cpu-high"));
    }

    @Test
    void retentionPrunesOnPostgres() throws SQLException {
        EventRepository events = new EventRepository(database.connections(), database.dialect());
        Instant now = Instant.parse("2026-09-11T12:00:00Z");
        events.insertIfAbsent(entry("old", "INFO", "m", now.minus(30, ChronoUnit.DAYS)));
        events.insertIfAbsent(entry("fresh", "INFO", "m", now.minus(1, ChronoUnit.DAYS)));

        RetentionService retention = new RetentionService(database.connections(), new Metrics(), 7);
        assertEquals(1, retention.prune(now));
        assertEquals(1, events.count(null, null, null));
    }

    @Test
    void theEngineServesTrafficBackedByPostgres() throws Exception {
        database.close();
        database = null;

        HttpServer server = AnalyticsEngine.start(configuration(0));
        try {
            int port = server.getAddress().getPort();
            HttpResponse<String> ingest = client.send(HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:" + port + "/receive"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-EventWatch-Key", API_KEY)
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"event_id":"pg-1","level":"ERROR","msg":"postgres backed",
                             "timestamp":"2026-09-11T10:00:00Z","cpu_usage":50,"ram_usage":50}"""))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ingest.statusCode(), ingest.body());

            HttpResponse<String> summary = client.send(HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:" + port + "/summary"))
                    .timeout(Duration.ofSeconds(10))
                    .header("X-EventWatch-Key", API_KEY)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body = MAPPER.readTree(summary.body());
            assertEquals(1, body.path("total_events").asLong());
            assertEquals("pg-1", body.path("latest_event").path("event_id").asText());

            HttpResponse<String> health = client.send(HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
        } finally {
            AnalyticsEngine.stop(server);
        }
    }
}
