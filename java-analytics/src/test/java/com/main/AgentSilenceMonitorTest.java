package com.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Detecting a machine that stopped reporting — the one condition no event can reveal. */
class AgentSilenceMonitorTest {
    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");
    private static final int SILENCE_MINUTES = 10;

    @TempDir
    Path temporaryDirectory;

    private Database database;
    private EventRepository events;
    private AlertRepository alerts;
    private AlertRules rules;
    private Metrics metrics;
    private AgentSilenceMonitor monitor;

    @BeforeEach
    void setUp() throws SQLException {
        database = Database.open(EngineConfiguration.forTesting(
                TestSupport.databaseUrl(temporaryDirectory, "silence.db"), "silence-secret"));
        database.initializeSchema();
        events = new EventRepository(database.connections(), database.dialect());
        alerts = new AlertRepository(database.connections());
        rules = new AlertRules(new AlertRuleRepository(database.connections()),
                85.0, 80.0, 90.0, 5, SILENCE_MINUTES, 5);
        metrics = new Metrics();
        monitor = new AgentSilenceMonitor(events, alerts, rules, null, metrics, 200, Duration.ofDays(7));
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    /** Records one event from a machine, as of so many minutes before NOW. */
    private void reported(String hostId, long minutesAgo) throws SQLException {
        events.insertIfAbsent(new LogEntry(
                hostId + "-" + minutesAgo, "INFO", "heartbeat",
                NOW.minus(Duration.ofMinutes(minutesAgo)), hostId, hostId, "0.15.0", 0, 5.0, 5.0));
    }

    private String silenceKey(String hostId) {
        return AlertEngine.alertKey(AgentSilenceMonitor.ALERT_RULE, hostId);
    }

    @Test
    void aMachineReportingRecentlyRaisesNothing() throws SQLException {
        reported("web-01", 5);
        assertEquals(0, monitor.check(NOW));
        assertNull(alerts.findByKey(silenceKey("web-01")));
    }

    @Test
    void aMachinePastItsThresholdRaisesAnAlert() throws SQLException {
        reported("db-01", 20);
        assertEquals(1, monitor.check(NOW));

        AlertRecord alert = alerts.findByKey(silenceKey("db-01"));
        assertNotNull(alert);
        assertEquals(AlertRules.AGENT_SILENT, alert.getAlertType());
        assertEquals(AlertStatus.OPEN, alert.getStatus());
        assertEquals("db-01", alert.getHostId());
        assertTrue(alert.getMessage().contains("20 minutes"), alert.getMessage());
    }

    @Test
    void theThresholdIsInclusive() throws SQLException {
        reported("db-01", SILENCE_MINUTES);
        assertEquals(1, monitor.check(NOW));
    }

    @Test
    void aMachineGoneLongerThanTheForgetWindowIsNotAlerted() throws SQLException {
        // Decommissioned, not lost: alerting forever would make every upgrade noisy.
        reported("retired-01", Duration.ofDays(30).toMinutes());
        assertEquals(0, monitor.check(NOW));
        assertNull(alerts.findByKey(silenceKey("retired-01")));
    }

    @Test
    void aMachineCanHaveItsOwnSilenceThreshold() throws SQLException {
        // A laptop that sleeps overnight should not page anyone.
        rules.save(AlertRules.AGENT_SILENT, "laptop-01", 240.0, true);
        reported("laptop-01", 60);
        reported("db-01", 60);

        assertEquals(1, monitor.check(NOW));
        assertNull(alerts.findByKey(silenceKey("laptop-01")));
        assertNotNull(alerts.findByKey(silenceKey("db-01")));
    }

    @Test
    void disablingTheRuleResolvesAnAlertItRaised() throws SQLException {
        reported("build-01", 30);
        monitor.check(NOW);
        assertEquals(AlertStatus.OPEN, alerts.findByKey(silenceKey("build-01")).getStatus());

        rules.save(AlertRules.AGENT_SILENT, "build-01", 10.0, false);
        assertEquals(0, monitor.check(NOW));
        assertEquals(AlertStatus.RESOLVED, alerts.findByKey(silenceKey("build-01")).getStatus());
    }

    @Test
    void aFleetWideRuleAppliesToEveryMachine() throws SQLException {
        rules.save(AlertRules.AGENT_SILENT, null, 45.0, true);
        reported("web-01", 30);
        reported("web-02", 50);

        assertEquals(1, monitor.check(NOW));
        assertNull(alerts.findByKey(silenceKey("web-01")));
        assertNotNull(alerts.findByKey(silenceKey("web-02")));
    }

    @Test
    void repeatedSweepsDoNotDuplicateTheAlert() throws SQLException {
        reported("db-01", 20);
        monitor.check(NOW);
        monitor.check(NOW);
        monitor.check(NOW);

        assertEquals(1, alerts.findActive().size());
        assertEquals(3, alerts.findByKey(silenceKey("db-01")).getOccurrenceCount());
    }

    @Test
    void theSilentCountIsExposedAsAGauge() throws SQLException {
        reported("db-01", 20);
        reported("web-01", 20);
        reported("fresh-01", 1);
        monitor.check(NOW);

        assertTrue(metrics.render(0, 0).contains("eventwatch_agents_silent 2"), metrics.render(0, 0));
    }

    @Test
    void sweepSwallowsFailuresSoTheTimerSurvives() {
        database.close();
        // The provider is closed, so the sweep must report rather than throw.
        monitor.sweep();
    }
}
