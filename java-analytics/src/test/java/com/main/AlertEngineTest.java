package com.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AlertEngineTest {
    private static final int WINDOW = 5;

    @TempDir
    Path temporaryDirectory;

    private AlertRepository alerts;
    private AlertEngine engine;

    @BeforeEach
    void setUp() throws SQLException {
        String databaseUrl = TestSupport.databaseUrl(temporaryDirectory, "alerts.db");
        alerts = new AlertRepository(databaseUrl);
        // A null notification service keeps these tests focused on the rules themselves.
        engine = new AlertEngine(alerts, null, WINDOW, 85.0, 80.0, 3);
    }

    private List<AnalyticsEngine.LogEntry> window(double cpu, double ram, int count) {
        List<AnalyticsEngine.LogEntry> events = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            events.add(new AnalyticsEngine.LogEntry("e" + index, "INFO", "m",
                    Instant.parse("2026-09-11T10:00:00Z").plusSeconds(index), cpu, ram));
        }
        return events;
    }

    @Test
    void anEmptyWindowRaisesNothing() throws SQLException {
        engine.evaluate(List.of());
        assertTrue(alerts.findActive().isEmpty());
    }

    @Test
    void sustainedHighCpuOpensAnAlert() throws SQLException {
        engine.evaluate(window(92.0, 10.0, WINDOW));
        AlertRecord alert = alerts.findByKey("cpu-high");
        assertNotNull(alert);
        assertEquals("HIGH_CPU", alert.getAlertType());
        assertEquals(AlertStatus.OPEN, alert.getStatus());
        assertTrue(alert.getMessage().contains("92.0"), alert.getMessage());
        assertNull(alerts.findByKey("ram-high"), "RAM stayed under its threshold");
    }

    @Test
    void oneSpikeInsideTheWindowDoesNotAlert() throws SQLException {
        // Four calm samples and one spike average below the threshold.
        List<AnalyticsEngine.LogEntry> events = window(10.0, 10.0, 4);
        events.add(new AnalyticsEngine.LogEntry("spike", "INFO", "m",
                Instant.parse("2026-09-11T10:00:10Z"), 100.0, 10.0));
        engine.evaluate(events);
        assertNull(alerts.findByKey("cpu-high"));
    }

    @Test
    void alertOpensExactlyAtTheThreshold() throws SQLException {
        engine.evaluate(window(85.0, 10.0, WINDOW));
        assertNotNull(alerts.findByKey("cpu-high"), "the threshold is inclusive");
    }

    @Test
    void recoveryResolvesAnOpenAlert() throws SQLException {
        engine.evaluate(window(95.0, 10.0, WINDOW));
        assertEquals(AlertStatus.OPEN, alerts.findByKey("cpu-high").getStatus());

        engine.evaluate(window(5.0, 10.0, WINDOW));
        assertEquals(AlertStatus.RESOLVED, alerts.findByKey("cpu-high").getStatus());
        assertTrue(alerts.findActive().isEmpty());
    }

    @Test
    void onlyTheNewestEventsCount() throws SQLException {
        // Ten calm samples followed by five hot ones: only the last five are evaluated.
        List<AnalyticsEngine.LogEntry> events = new ArrayList<>(window(5.0, 5.0, 10));
        events.addAll(window(95.0, 5.0, WINDOW));
        engine.evaluate(events);
        assertNotNull(alerts.findByKey("cpu-high"));
    }

    @Test
    void highRamRaisesItsOwnAlert() throws SQLException {
        engine.evaluate(window(10.0, 88.0, WINDOW));
        assertNotNull(alerts.findByKey("ram-high"));
        assertEquals("HIGH_RAM", alerts.findByKey("ram-high").getAlertType());
    }

    @Test
    void repeatedErrorsRaiseAnAlertOnceTheThresholdIsMet() throws SQLException {
        List<AnalyticsEngine.LogEntry> events = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            events.add(new AnalyticsEngine.LogEntry("e" + index, "ERROR", "database deadlock",
                    Instant.parse("2026-09-11T10:00:00Z").plusSeconds(index), 5.0, 5.0));
        }
        engine.evaluate(events);

        List<AlertRecord> active = alerts.findActive();
        assertEquals(1, active.size());
        assertEquals("REPEATED_ERROR", active.get(0).getAlertType());
        assertTrue(active.get(0).getMessage().contains("occurred 3 times"), active.get(0).getMessage());
    }

    @Test
    void repeatedErrorsBelowTheThresholdStaySilent() throws SQLException {
        List<AnalyticsEngine.LogEntry> events = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            events.add(new AnalyticsEngine.LogEntry("e" + index, "ERROR", "database deadlock",
                    Instant.parse("2026-09-11T10:00:00Z").plusSeconds(index), 5.0, 5.0));
        }
        engine.evaluate(events);
        assertTrue(alerts.findActive().isEmpty());
    }

    @Test
    void criticalCountsTowardsRepeatedErrors() throws SQLException {
        List<AnalyticsEngine.LogEntry> events = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            events.add(new AnalyticsEngine.LogEntry("e" + index, "CRITICAL", "out of memory",
                    Instant.parse("2026-09-11T10:00:00Z").plusSeconds(index), 5.0, 5.0));
        }
        engine.evaluate(events);
        assertEquals(1, alerts.findActive().size());
    }

    @Test
    void differentMessagesAreTrackedSeparately() throws SQLException {
        List<AnalyticsEngine.LogEntry> events = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            events.add(new AnalyticsEngine.LogEntry("a" + index, "ERROR", "deadlock",
                    Instant.parse("2026-09-11T10:00:00Z").plusSeconds(index), 5.0, 5.0));
        }
        engine.evaluate(events);
        String firstKey = alerts.findActive().get(0).getAlertKey();

        List<AnalyticsEngine.LogEntry> others = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            others.add(new AnalyticsEngine.LogEntry("b" + index, "ERROR", "timeout",
                    Instant.parse("2026-09-11T11:00:00Z").plusSeconds(index), 5.0, 5.0));
        }
        engine.evaluate(others);

        assertEquals(2, alerts.findActive().size());
        assertTrue(alerts.findActive().stream().noneMatch(alert ->
                !alert.getAlertKey().equals(firstKey) && alert.getAlertKey().equals(firstKey)));
    }

    @Test
    void repeatedEvaluationDoesNotDuplicateAlerts() throws SQLException {
        engine.evaluate(window(95.0, 95.0, WINDOW));
        engine.evaluate(window(95.0, 95.0, WINDOW));
        engine.evaluate(window(95.0, 95.0, WINDOW));

        assertEquals(2, alerts.findActive().size(), "one CPU alert and one RAM alert");
        assertEquals(3, alerts.findByKey("cpu-high").getOccurrenceCount());
    }
}
