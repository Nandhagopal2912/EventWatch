package com.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which threshold applies to a machine, and which rules can be stored at all. */
class AlertRulesTest {
    private static final int WINDOW = 5;

    @TempDir
    Path temporaryDirectory;

    private Database database;
    private AlertRuleRepository repository;
    private AlertRules rules;

    @BeforeEach
    void setUp() throws SQLException {
        database = Database.open(EngineConfiguration.forTesting(
                TestSupport.databaseUrl(temporaryDirectory, "rules.db"), "rules-secret"));
        database.initializeSchema();
        repository = new AlertRuleRepository(database.connections());
        rules = new AlertRules(repository, 85.0, 80.0, 5, WINDOW);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void withNoRulesEveryMachineUsesTheConfiguredDefaults() {
        AlertRules.EffectiveRule cpu = rules.effective(AlertRules.HIGH_CPU, "web-01");
        assertEquals(85.0, cpu.threshold());
        assertTrue(cpu.enabled());
        assertEquals(AlertRules.Source.DEFAULT, cpu.source());
        assertEquals(80.0, rules.effective(AlertRules.HIGH_RAM, "web-01").threshold());
        assertEquals(5.0, rules.effective(AlertRules.REPEATED_ERROR, "web-01").threshold());
    }

    @Test
    void aHostRuleBeatsAFleetRuleWhichBeatsTheDefault() throws SQLException {
        rules.save(AlertRules.HIGH_CPU, null, 70.0, true);
        assertEquals(70.0, rules.effective(AlertRules.HIGH_CPU, "web-01").threshold());
        assertEquals(AlertRules.Source.FLEET, rules.effective(AlertRules.HIGH_CPU, "web-01").source());

        rules.save(AlertRules.HIGH_CPU, "web-01", 95.0, true);
        AlertRules.EffectiveRule web = rules.effective(AlertRules.HIGH_CPU, "web-01");
        assertEquals(95.0, web.threshold());
        assertEquals(AlertRules.Source.HOST, web.source());
    }

    @Test
    void aHostRuleDoesNotLeakToOtherMachines() throws SQLException {
        rules.save(AlertRules.HIGH_CPU, "build-01", 99.0, true);
        AlertRules.EffectiveRule database = rules.effective(AlertRules.HIGH_CPU, "db-01");
        assertEquals(85.0, database.threshold());
        assertEquals(AlertRules.Source.DEFAULT, database.source());
    }

    @Test
    void aRuleOnlyAffectsItsOwnType() throws SQLException {
        rules.save(AlertRules.HIGH_CPU, "web-01", 99.0, true);
        assertEquals(AlertRules.Source.DEFAULT, rules.effective(AlertRules.HIGH_RAM, "web-01").source());
    }

    @Test
    void aDisabledHostRuleStillTakesPrecedence() throws SQLException {
        // Disabling is itself a decision about that machine, so it must not fall through.
        rules.save(AlertRules.HIGH_CPU, null, 50.0, true);
        rules.save(AlertRules.HIGH_CPU, "build-01", 90.0, false);
        AlertRules.EffectiveRule build = rules.effective(AlertRules.HIGH_CPU, "build-01");
        assertFalse(build.enabled());
        assertEquals(AlertRules.Source.HOST, build.source());
    }

    @Test
    void savingAgainReplacesTheRuleForThatScope() throws SQLException {
        rules.save(AlertRules.HIGH_RAM, "db-01", 90.0, true);
        rules.save(AlertRules.HIGH_RAM, "db-01", 70.0, false);

        List<AlertRule> stored = rules.all();
        assertEquals(1, stored.size(), "one rule per type and scope");
        assertEquals(70.0, stored.get(0).threshold());
        assertFalse(stored.get(0).enabled());
    }

    @Test
    void rulesArePersistedAndSeenByANewInstance() throws SQLException {
        rules.save(AlertRules.HIGH_CPU, "web-01", 91.0, true);
        rules.save(AlertRules.HIGH_CPU, null, 60.0, true);

        AlertRules reloaded = new AlertRules(repository, 85.0, 80.0, 5, WINDOW);
        assertEquals(91.0, reloaded.effective(AlertRules.HIGH_CPU, "web-01").threshold());
        assertEquals(60.0, reloaded.effective(AlertRules.HIGH_CPU, "db-01").threshold());
        assertTrue(reloaded.all().stream().anyMatch(AlertRule::fleetWide));
    }

    @Test
    void deletingRemovesOnlyThatScopeAndReportsWhetherItExisted() throws SQLException {
        rules.save(AlertRules.HIGH_CPU, null, 60.0, true);
        rules.save(AlertRules.HIGH_CPU, "web-01", 95.0, true);

        assertTrue(rules.delete(AlertRules.HIGH_CPU, "web-01"));
        assertEquals(AlertRules.Source.FLEET, rules.effective(AlertRules.HIGH_CPU, "web-01").source());
        assertFalse(rules.delete(AlertRules.HIGH_CPU, "web-01"), "already gone");

        assertTrue(rules.delete(AlertRules.HIGH_CPU, null));
        assertEquals(AlertRules.Source.DEFAULT, rules.effective(AlertRules.HIGH_CPU, "web-01").source());
    }

    @Test
    void invalidRulesAreRejectedWithReadableReasons() {
        assertRejected(null, null, 50.0, "rule_type");
        assertRejected("DISK_FULL", null, 50.0, "rule_type");
        assertRejected(AlertRules.HIGH_CPU, null, -1.0, "percentage");
        assertRejected(AlertRules.HIGH_CPU, null, 100.5, "percentage");
        assertRejected(AlertRules.HIGH_RAM, null, Double.NaN, "finite");
        assertRejected(AlertRules.HIGH_RAM, null, Double.POSITIVE_INFINITY, "finite");
        assertRejected(AlertRules.REPEATED_ERROR, null, 0.0, "whole number");
        assertRejected(AlertRules.REPEATED_ERROR, null, 2.5, "whole number");
        // A threshold above the window could never be reached, so it is refused, not stored.
        assertRejected(AlertRules.REPEATED_ERROR, null, WINDOW + 1, "could never fire");
        assertRejected(AlertRules.HIGH_CPU, "*", 50.0, "reserved");
        assertRejected(AlertRules.HIGH_CPU, "   ", 50.0, "blank");
        assertRejected(AlertRules.HIGH_CPU, "x".repeat(129), 50.0, "128");
        assertTrue(rules.all().isEmpty(), "nothing invalid may be stored");
    }

    @Test
    void boundaryThresholdsAreAccepted() throws SQLException {
        assertNull(rules.validate(AlertRules.HIGH_CPU, null, 0.0));
        assertNull(rules.validate(AlertRules.HIGH_CPU, null, 100.0));
        assertNull(rules.validate(AlertRules.REPEATED_ERROR, null, 1.0));
        assertNull(rules.validate(AlertRules.REPEATED_ERROR, null, WINDOW));
        assertNotNull(rules.save(AlertRules.REPEATED_ERROR, "web-01", WINDOW, true));
    }

    @Test
    void deletingAnUnknownRuleTypeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> rules.delete("DISK_FULL", null));
    }

    @Test
    void effectiveForListsEveryRuleTypeInOrder() throws SQLException {
        rules.save(AlertRules.HIGH_RAM, "web-01", 70.0, true);
        List<AlertRules.EffectiveRule> effective = rules.effectiveFor("web-01");
        assertEquals(AlertRules.RULE_TYPES.size(), effective.size());
        assertEquals(AlertRules.HIGH_CPU, effective.get(0).ruleType());
        assertEquals(AlertRules.Source.HOST, effective.get(1).source());
    }

    @Test
    void aConfigurationOnlyRuleSetResolvesButCannotBeChanged() {
        AlertRules configurationOnly = AlertRules.defaultsOnly(85.0, 80.0, 5, WINDOW);
        assertEquals(85.0, configurationOnly.effective(AlertRules.HIGH_CPU, "web-01").threshold());
        assertThrows(IllegalStateException.class,
                () -> configurationOnly.save(AlertRules.HIGH_CPU, null, 50.0, true));
    }

    private void assertRejected(String ruleType, String hostId, double threshold, String reasonFragment) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> rules.save(ruleType, hostId, threshold, true));
        assertTrue(failure.getMessage().contains(reasonFragment),
                "expected the reason to mention \"" + reasonFragment + "\", got: " + failure.getMessage());
    }
}
