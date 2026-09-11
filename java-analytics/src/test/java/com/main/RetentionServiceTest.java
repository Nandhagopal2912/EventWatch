package com.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetentionServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-11T12:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private Database database;
    private EventRepository events;
    private NotificationRepository notifications;

    @BeforeEach
    void setUp() throws SQLException {
        database = TestSupport.openDatabase(temporaryDirectory, "retention.db");
        events = new EventRepository(database.connections(), database.dialect());
        notifications = new NotificationRepository(database.connections());
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private RetentionService service(int retentionDays) {
        return new RetentionService(database.connections(), new Metrics(), retentionDays);
    }

    private void storeEvent(String eventId, Instant timestamp) throws SQLException {
        events.insertIfAbsent(new AnalyticsEngine.LogEntry(eventId, "INFO", "m", timestamp, 1, 1));
    }

    @Test
    void retentionIsOffByDefault() throws SQLException {
        RetentionService retention = service(0);
        assertFalse(retention.enabled());

        storeEvent("old", NOW.minus(365, ChronoUnit.DAYS));
        assertEquals(0, retention.prune(NOW), "a disabled service must delete nothing");
        assertEquals(1, events.count(null, null, null));
    }

    @Test
    void aNegativeWindowIsAlsoTreatedAsDisabled() {
        assertFalse(service(-1).enabled());
    }

    @Test
    void expiredEventsAreRemovedAndRecentOnesKept() throws SQLException {
        storeEvent("ancient", NOW.minus(30, ChronoUnit.DAYS));
        storeEvent("expired", NOW.minus(8, ChronoUnit.DAYS));
        storeEvent("fresh", NOW.minus(1, ChronoUnit.DAYS));
        storeEvent("now", NOW);
        assertEquals(4, events.count(null, null, null));

        assertEquals(2, service(7).prune(NOW));
        assertEquals(2, events.count(null, null, null));
        assertTrue(events.find(null, null, null, 10, 0).stream()
                .noneMatch(event -> event.eventId.equals("expired")));
    }

    @Test
    void anEventExactlyAtTheBoundaryIsKept() throws SQLException {
        storeEvent("boundary", NOW.minus(7, ChronoUnit.DAYS));
        assertEquals(0, service(7).prune(NOW), "the cutoff is exclusive");
        assertEquals(1, events.count(null, null, null));
    }

    @Test
    void deliveryHistoryIsPrunedAlongsideTheEvents() throws SQLException {
        notifications.record(new NotificationRecord("cpu-high", "alert.opened", "DELIVERED", 200,
                null, 1, NOW.minus(30, ChronoUnit.DAYS)));
        notifications.record(new NotificationRecord("cpu-high", "alert.opened", "DELIVERED", 200,
                null, 1, NOW.minus(1, ChronoUnit.DAYS)));

        assertEquals(1, service(7).prune(NOW));
        assertEquals(1, notifications.findByAlertKey("cpu-high", 10).size());
    }

    @Test
    void pruningAnEmptyDatabaseIsHarmless() throws SQLException {
        assertEquals(0, service(7).prune(NOW));
    }

    @Test
    void sweepSwallowsFailuresSoTheTimerSurvives() {
        database.close();
        // The connection provider is closed, so the sweep must report rather than throw.
        service(7).sweep();
    }
}
