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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AlertEngineTest {
    private static final int WINDOW = 5;

    @TempDir
    Path temporaryDirectory;

    private Database database;
    private AlertRepository alerts;
    private AlertEngine engine;

    @BeforeEach
    void setUp() throws SQLException {
        database = TestSupport.openDatabase(temporaryDirectory, "alerts.db");
        alerts = new AlertRepository(database.connections());
        // A null notification service keeps these tests focused on the rules themselves.
        engine = new AlertEngine(alerts, null, WINDOW, AlertRules.defaultsOnly(85.0, 80.0, 90.0, 3, 10, WINDOW));
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private static final String HOST = "web-01";

    private List<LogEntry> window(double cpu, double ram, int count) {
        return window(HOST, cpu, ram, count);
    }

    private List<LogEntry> window(String hostId, double cpu, double ram, int count) {
        List<LogEntry> events = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            events.add(new LogEntry(hostId + "-e" + index, "INFO", "m",
                    Instant.parse("2026-09-11T10:00:00Z").plusSeconds(index), hostId, hostId, cpu, ram));
        }
        return events;
    }

    private String key(String rule) {
        return AlertEngine.alertKey(rule, HOST);
    }

    /** A window whose readings each carry a disk level, oldest first. */
    private List<LogEntry> diskWindow(String mount, double... readings) {
        List<LogEntry> events = new ArrayList<>();
        for (int index = 0; index < readings.length; index++) {
            LogEntry event = new LogEntry(HOST + "-d" + index, "INFO", "m",
                    Instant.parse("2026-09-11T10:00:00Z").plusSeconds(index), HOST, HOST, 1.0, 1.0);
            event.diskUsage = readings[index];
            event.diskPath = mount;
            events.add(event);
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
        AlertRecord alert = alerts.findByKey(key("cpu-high"));
        assertNotNull(alert);
        assertEquals("HIGH_CPU", alert.getAlertType());
        assertEquals(AlertStatus.OPEN, alert.getStatus());
        assertTrue(alert.getMessage().contains("92.0"), alert.getMessage());
        assertNull(alerts.findByKey(key("ram-high")), "RAM stayed under its threshold");
    }

    @Test
    void oneSpikeInsideTheWindowDoesNotAlert() throws SQLException {
        // Four calm samples and one spike average below the threshold.
        List<LogEntry> events = window(10.0, 10.0, 4);
        events.add(new LogEntry("spike", "INFO", "m",
                Instant.parse("2026-09-11T10:00:10Z"), HOST, HOST, 100.0, 10.0));
        engine.evaluate(events);
        assertNull(alerts.findByKey(key("cpu-high")));
    }

    @Test
    void alertOpensExactlyAtTheThreshold() throws SQLException {
        engine.evaluate(window(85.0, 10.0, WINDOW));
        assertNotNull(alerts.findByKey(key("cpu-high")), "the threshold is inclusive");
    }

    @Test
    void recoveryResolvesAnOpenAlert() throws SQLException {
        engine.evaluate(window(95.0, 10.0, WINDOW));
        assertEquals(AlertStatus.OPEN, alerts.findByKey(key("cpu-high")).getStatus());

        engine.evaluate(window(5.0, 10.0, WINDOW));
        assertEquals(AlertStatus.RESOLVED, alerts.findByKey(key("cpu-high")).getStatus());
        assertTrue(alerts.findActive().isEmpty());
    }

    @Test
    void onlyTheNewestEventsCount() throws SQLException {
        // Ten calm samples followed by five hot ones: only the last five are evaluated.
        List<LogEntry> events = new ArrayList<>(window(5.0, 5.0, 10));
        events.addAll(window(95.0, 5.0, WINDOW));
        engine.evaluate(events);
        assertNotNull(alerts.findByKey(key("cpu-high")));
    }

    @Test
    void highRamRaisesItsOwnAlert() throws SQLException {
        engine.evaluate(window(10.0, 88.0, WINDOW));
        assertNotNull(alerts.findByKey(key("ram-high")));
        assertEquals("HIGH_RAM", alerts.findByKey(key("ram-high")).getAlertType());
    }

    @Test
    void repeatedErrorsRaiseAnAlertOnceTheThresholdIsMet() throws SQLException {
        List<LogEntry> events = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            events.add(new LogEntry("e" + index, "ERROR", "database deadlock",
                    Instant.parse("2026-09-11T10:00:00Z").plusSeconds(index), HOST, HOST, 5.0, 5.0));
        }
        engine.evaluate(events);

        List<AlertRecord> active = alerts.findActive();
        assertEquals(1, active.size());
        assertEquals("REPEATED_ERROR", active.get(0).getAlertType());
        assertTrue(active.get(0).getMessage().contains("occurred 3 times"), active.get(0).getMessage());
    }

    @Test
    void repeatedErrorsBelowTheThresholdStaySilent() throws SQLException {
        List<LogEntry> events = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            events.add(new LogEntry("e" + index, "ERROR", "database deadlock",
                    Instant.parse("2026-09-11T10:00:00Z").plusSeconds(index), HOST, HOST, 5.0, 5.0));
        }
        engine.evaluate(events);
        assertTrue(alerts.findActive().isEmpty());
    }

    @Test
    void criticalCountsTowardsRepeatedErrors() throws SQLException {
        List<LogEntry> events = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            events.add(new LogEntry("e" + index, "CRITICAL", "out of memory",
                    Instant.parse("2026-09-11T10:00:00Z").plusSeconds(index), HOST, HOST, 5.0, 5.0));
        }
        engine.evaluate(events);
        assertEquals(1, alerts.findActive().size());
    }

    @Test
    void differentMessagesAreTrackedSeparately() throws SQLException {
        List<LogEntry> events = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            events.add(new LogEntry("a" + index, "ERROR", "deadlock",
                    Instant.parse("2026-09-11T10:00:00Z").plusSeconds(index), HOST, HOST, 5.0, 5.0));
        }
        engine.evaluate(events);
        String firstKey = alerts.findActive().get(0).getAlertKey();

        List<LogEntry> others = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            others.add(new LogEntry("b" + index, "ERROR", "timeout",
                    Instant.parse("2026-09-11T11:00:00Z").plusSeconds(index), HOST, HOST, 5.0, 5.0));
        }
        engine.evaluate(others);

        assertEquals(2, alerts.findActive().size());
        assertTrue(alerts.findActive().stream().noneMatch(alert ->
                !alert.getAlertKey().equals(firstKey) && alert.getAlertKey().equals(firstKey)));
    }

    @Test
    void eachMachineRaisesItsOwnAlert() throws SQLException {
        engine.evaluate(window("web-01", 95.0, 10.0, WINDOW));
        engine.evaluate(window("db-01", 95.0, 10.0, WINDOW));

        assertEquals(2, alerts.findActive().size(), "two machines must not share one alert row");
        AlertRecord web = alerts.findByKey(AlertEngine.alertKey("cpu-high", "web-01"));
        AlertRecord database = alerts.findByKey(AlertEngine.alertKey("cpu-high", "db-01"));
        assertNotNull(web);
        assertNotNull(database);
        assertEquals("web-01", web.getHostId());
        assertEquals("db-01", database.getHostId());
        assertEquals(1, web.getOccurrenceCount(), "one machine must not inflate another's count");
    }

    @Test
    void oneMachineRecoveringDoesNotResolveAnother() throws SQLException {
        engine.evaluate(window("web-01", 95.0, 10.0, WINDOW));
        engine.evaluate(window("db-01", 95.0, 10.0, WINDOW));

        engine.evaluate(window("web-01", 5.0, 10.0, WINDOW));

        assertEquals(AlertStatus.RESOLVED,
                alerts.findByKey(AlertEngine.alertKey("cpu-high", "web-01")).getStatus());
        assertEquals(AlertStatus.OPEN,
                alerts.findByKey(AlertEngine.alertKey("cpu-high", "db-01")).getStatus(),
                "the other machine is still hot");
    }

    @Test
    void anEventWithoutIdentityFallsBackToAnUnknownHost() throws SQLException {
        List<LogEntry> legacy = new ArrayList<>();
        for (int index = 0; index < WINDOW; index++) {
            legacy.add(new LogEntry("legacy-" + index, "INFO", "m",
                    Instant.parse("2026-09-11T10:00:00Z").plusSeconds(index), 95.0, 10.0));
        }
        engine.evaluate(legacy);

        AlertRecord alert = alerts.findByKey(
                AlertEngine.alertKey("cpu-high", LogEntry.UNKNOWN_HOST));
        assertNotNull(alert, "an agent older than phase 12 still raises alerts");
        assertEquals(LogEntry.UNKNOWN_HOST, alert.getHostId());
    }

    @Test
    void repeatedEvaluationDoesNotDuplicateAlerts() throws SQLException {
        engine.evaluate(window(95.0, 95.0, WINDOW));
        engine.evaluate(window(95.0, 95.0, WINDOW));
        engine.evaluate(window(95.0, 95.0, WINDOW));

        assertEquals(2, alerts.findActive().size(), "one CPU alert and one RAM alert");
        assertEquals(3, alerts.findByKey(key("cpu-high")).getOccurrenceCount());
    }

    @Test
    void diskAlertsOnTheNewestReadingRatherThanTheWindowAverage() throws SQLException {
        // Four healthy readings then one full disk: the average is 39%, well under the threshold,
        // but the machine is full right now. CPU is averaged because it is spiky; a filesystem is
        // a level, and averaging it would only delay the alert.
        engine.evaluate(diskWindow("/var", 10.0, 10.0, 10.0, 80.0, 95.0));

        AlertRecord raised = alerts.findByKey(key("disk-high"));
        assertNotNull(raised, "the newest reading is over the threshold, so the disk is full now");
        assertTrue(raised.getMessage().contains("/var"),
                "a percentage is only actionable with the mount: " + raised.getMessage());
        assertTrue(raised.getMessage().contains("95.0"), raised.getMessage());
    }

    @Test
    void aRecoveredDiskResolvesOnTheNextReading() throws SQLException {
        engine.evaluate(diskWindow("/var", 95.0));
        assertEquals(AlertStatus.OPEN, alerts.findByKey(key("disk-high")).getStatus());

        engine.evaluate(diskWindow("/var", 20.0));
        assertEquals(AlertStatus.RESOLVED, alerts.findByKey(key("disk-high")).getStatus(),
                "space was freed, so the alert closes itself like CPU and RAM do");
    }

    @Test
    void aFullDiskInTheWindowDoesNotAlertOnceItIsNoLongerTheNewest() throws SQLException {
        // The mirror of the first test: a spike that has already been cleared must not alert.
        engine.evaluate(diskWindow("/var", 99.0, 99.0, 99.0, 99.0, 10.0));
        assertNull(alerts.findByKey(key("disk-high")),
                "the machine has space now; an old reading is history, not an alert");
    }

    @Test
    void anEventWithoutADiskReadingLeavesAnExistingAlertAlone() throws SQLException {
        engine.evaluate(diskWindow("/var", 95.0));
        assertEquals(AlertStatus.OPEN, alerts.findByKey(key("disk-high")).getStatus());

        // An agent that cannot read its filesystem, or one older than this feature, reports no
        // disk at all. Silence is not a recovery, so the alert must not be resolved by it.
        engine.evaluate(window(5.0, 5.0, 1));

        assertEquals(AlertStatus.OPEN, alerts.findByKey(key("disk-high")).getStatus(),
                "no reading is not the same claim as a healthy reading");
    }

    @Test
    void aMachineThatNeverReportsDiskNeverRaisesADiskAlert() throws SQLException {
        engine.evaluate(window(5.0, 5.0, 5));
        assertNull(alerts.findByKey(key("disk-high")));
    }

    @Test
    void aDiskReadingWithoutAMountStillAlerts() throws SQLException {
        List<LogEntry> events = diskWindow(null, 97.0);
        engine.evaluate(events);

        AlertRecord raised = alerts.findByKey(key("disk-high"));
        assertNotNull(raised, "the reading is what matters; the mount is context");
        assertTrue(raised.getMessage().startsWith("disk is"), raised.getMessage());
    }
}
