package com.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryTest {
    @TempDir
    Path temporaryDirectory;

    private String databaseUrl;
    private EventRepository events;
    private AlertRepository alerts;

    @BeforeEach
    void setUp() throws SQLException {
        databaseUrl = TestSupport.databaseUrl(temporaryDirectory, "repository.db");
        events = new EventRepository(databaseUrl);
        events.initializeSchema();
        alerts = new AlertRepository(databaseUrl);
    }

    private AnalyticsEngine.LogEntry entry(String eventId, String level, String message,
            String timestamp, double cpu, double ram) {
        return new AnalyticsEngine.LogEntry(eventId, level, message, Instant.parse(timestamp), cpu, ram);
    }

    @Test
    void schemaCreationIsRepeatable() throws SQLException {
        events.initializeSchema();
        events.initializeSchema();
        assertEquals(0, events.count(null, null, null));
    }

    @Test
    void storesAnEventAndReadsItBack() throws SQLException {
        assertTrue(events.insertIfAbsent(entry("e1", "ERROR", "disk full", "2026-09-11T10:00:00Z", 55.5, 44.4)));
        List<AnalyticsEngine.LogEntry> stored = events.recent(10);
        assertEquals(1, stored.size());
        assertEquals("e1", stored.get(0).eventId);
        assertEquals("ERROR", stored.get(0).level);
        assertEquals("disk full", stored.get(0).message);
        assertEquals(55.5, stored.get(0).cpuUsage, 0.0001);
        assertEquals(44.4, stored.get(0).ramUsage, 0.0001);
        assertEquals(Instant.parse("2026-09-11T10:00:00Z"), stored.get(0).timestamp);
    }

    @Test
    void repeatedDeliveryOfOneEventIdIsIgnored() throws SQLException {
        assertTrue(events.insertIfAbsent(entry("dup", "INFO", "first", "2026-09-11T10:00:00Z", 1, 1)));
        assertFalse(events.insertIfAbsent(entry("dup", "INFO", "second", "2026-09-11T10:05:00Z", 2, 2)));
        assertEquals(1, events.count(null, null, null));
        assertEquals("first", events.recent(1).get(0).message);
    }

    @Test
    void filtersByLevelAndTimeRange() throws SQLException {
        events.insertIfAbsent(entry("a", "INFO", "one", "2026-09-11T09:00:00Z", 1, 1));
        events.insertIfAbsent(entry("b", "ERROR", "two", "2026-09-11T10:00:00Z", 2, 2));
        events.insertIfAbsent(entry("c", "ERROR", "three", "2026-09-11T11:00:00Z", 3, 3));

        assertEquals(2, events.count("ERROR", null, null));
        assertEquals(1, events.count("INFO", null, null));
        assertEquals(2, events.count(null, Instant.parse("2026-09-11T10:00:00Z"), null));
        assertEquals(1, events.count("ERROR", Instant.parse("2026-09-11T10:30:00Z"), null));
        assertEquals(1, events.count(null, null, Instant.parse("2026-09-11T09:30:00Z")));

        List<AnalyticsEngine.LogEntry> errors = events.find("ERROR", null, null, 10, 0);
        assertEquals(2, errors.size());
        assertEquals("three", errors.get(0).message, "newest first");
    }

    @Test
    void appliesLimitAndOffset() throws SQLException {
        for (int index = 0; index < 5; index++) {
            events.insertIfAbsent(entry("e" + index, "INFO", "m" + index,
                    "2026-09-11T1%d:00:00Z".formatted(index), index, index));
        }
        assertEquals(2, events.find(null, null, null, 2, 0).size());
        assertEquals("m2", events.find(null, null, null, 2, 2).get(0).message);
        assertEquals(5, events.count(null, null, null));
    }

    @Test
    void topErrorMessagesIsRankedAndBounded() throws SQLException {
        events.insertIfAbsent(entry("a", "ERROR", "repeated", "2026-09-11T10:00:00Z", 1, 1));
        events.insertIfAbsent(entry("b", "ERROR", "repeated", "2026-09-11T10:01:00Z", 1, 1));
        events.insertIfAbsent(entry("c", "CRITICAL", "severe", "2026-09-11T10:02:00Z", 1, 1));
        events.insertIfAbsent(entry("d", "INFO", "ignored", "2026-09-11T10:03:00Z", 1, 1));

        Map<String, Long> counts = events.topErrorMessages(5);
        assertEquals(2, counts.size(), "INFO messages must not be counted");
        assertEquals(2L, counts.get("repeated"));
        assertEquals(1L, counts.get("severe"));
        assertEquals(1, events.topErrorMessages(1).size());
    }

    @Test
    void latestReturnsNullOnAnEmptyDatabase() throws SQLException {
        assertNull(events.latest());
        events.insertIfAbsent(entry("a", "INFO", "only", "2026-09-11T10:00:00Z", 1, 1));
        assertEquals("only", events.latest().message);
    }

    @Test
    void firstOccurrenceOpensAnAlert() throws SQLException {
        AlertTransition transition = alerts.saveOccurrence(
                new AlertRecord("cpu-high", "HIGH_CPU", "cpu is high", Instant.parse("2026-09-11T10:00:00Z")));
        assertEquals(AlertTransition.Type.OPENED, transition.type());
        assertEquals(AlertStatus.OPEN, transition.alert().getStatus());
        assertEquals(1, transition.alert().getOccurrenceCount());
    }

    @Test
    void repeatOccurrenceIncrementsWithoutReopening() throws SQLException {
        alerts.saveOccurrence(new AlertRecord("cpu-high", "HIGH_CPU", "first", Instant.parse("2026-09-11T10:00:00Z")));
        AlertTransition transition = alerts.saveOccurrence(
                new AlertRecord("cpu-high", "HIGH_CPU", "second", Instant.parse("2026-09-11T10:01:00Z")));
        assertEquals(AlertTransition.Type.OCCURRENCE, transition.type());
        assertEquals(2, transition.alert().getOccurrenceCount());
        assertEquals("second", transition.alert().getMessage(), "the message follows the latest value");
    }

    @Test
    void acknowledgedAlertSurvivesFurtherOccurrences() throws SQLException {
        // Regression: the upsert used to reset status to OPEN, undoing an acknowledgement.
        alerts.saveOccurrence(new AlertRecord("cpu-high", "HIGH_CPU", "m", Instant.parse("2026-09-11T10:00:00Z")));
        assertNotNull(alerts.acknowledge("cpu-high"));

        AlertTransition transition = alerts.saveOccurrence(
                new AlertRecord("cpu-high", "HIGH_CPU", "m", Instant.parse("2026-09-11T10:01:00Z")));
        assertEquals(AlertTransition.Type.OCCURRENCE, transition.type());
        assertEquals(AlertStatus.ACKNOWLEDGED, transition.alert().getStatus());
        assertEquals(2, transition.alert().getOccurrenceCount());
    }

    @Test
    void resolvedAlertReopensOnTheNextOccurrence() throws SQLException {
        alerts.saveOccurrence(new AlertRecord("ram-high", "HIGH_RAM", "m", Instant.parse("2026-09-11T10:00:00Z")));
        assertNotNull(alerts.resolve("ram-high", Instant.parse("2026-09-11T10:05:00Z")));

        AlertTransition transition = alerts.saveOccurrence(
                new AlertRecord("ram-high", "HIGH_RAM", "m", Instant.parse("2026-09-11T10:10:00Z")));
        assertEquals(AlertTransition.Type.REOPENED, transition.type());
        assertEquals(AlertStatus.OPEN, transition.alert().getStatus());
    }

    @Test
    void lifecycleActionsReportNoChangeWhenNothingHappens() throws SQLException {
        assertNull(alerts.acknowledge("missing"), "acknowledging an unknown alert changes nothing");
        assertNull(alerts.resolve("missing", Instant.now()), "resolving an unknown alert changes nothing");

        alerts.saveOccurrence(new AlertRecord("cpu-high", "HIGH_CPU", "m", Instant.parse("2026-09-11T10:00:00Z")));
        assertNotNull(alerts.resolve("cpu-high", Instant.parse("2026-09-11T10:01:00Z")));
        assertNull(alerts.resolve("cpu-high", Instant.parse("2026-09-11T10:02:00Z")), "already resolved");
        assertNull(alerts.acknowledge("cpu-high"), "a resolved alert cannot be acknowledged");
    }

    @Test
    void activeAlertsExcludeResolvedOnes() throws SQLException {
        alerts.saveOccurrence(new AlertRecord("cpu-high", "HIGH_CPU", "m", Instant.parse("2026-09-11T10:00:00Z")));
        alerts.saveOccurrence(new AlertRecord("ram-high", "HIGH_RAM", "m", Instant.parse("2026-09-11T10:00:00Z")));
        assertEquals(2, alerts.findActive().size());

        alerts.resolve("ram-high", Instant.parse("2026-09-11T10:05:00Z"));
        assertEquals(1, alerts.findActive().size());
        assertEquals("cpu-high", alerts.findActive().get(0).getAlertKey());
        assertNotNull(alerts.findByKey("ram-high"), "a resolved alert is still addressable by key");
    }

    @Test
    void notificationHistoryIsRecordedNewestFirst() throws SQLException {
        NotificationRepository notifications = new NotificationRepository(databaseUrl);
        notifications.record(new NotificationRecord("cpu-high", "alert.opened", "FAILED", 500,
                "boom", 1, Instant.parse("2026-09-11T10:00:00Z")));
        notifications.record(new NotificationRecord("cpu-high", "alert.opened", "DELIVERED", 200,
                null, 2, Instant.parse("2026-09-11T10:00:05Z")));

        List<NotificationRecord> history = notifications.findByAlertKey("cpu-high", 10);
        assertEquals(2, history.size());
        assertEquals("DELIVERED", history.get(0).deliveryStatus());
        assertNull(history.get(0).errorMessage());
        assertEquals(500, history.get(1).httpStatus());
        assertEquals(Instant.parse("2026-09-11T10:00:05Z"), notifications.lastDeliveredAt("cpu-high"));
        assertNull(notifications.lastDeliveredAt("other"));
        assertEquals(1, notifications.findByAlertKey("cpu-high", 1).size());
    }
}
