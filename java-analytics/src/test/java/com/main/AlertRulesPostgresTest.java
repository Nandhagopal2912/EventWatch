package com.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The rule storage on a real PostgreSQL. Skipped unless EVENTWATCH_TEST_POSTGRES_URL is set,
 * so a checkout without a server still builds; CI provides one.
 */
class AlertRulesPostgresTest {
    private Database database;
    private AlertRuleRepository repository;
    private AlertRules rules;

    @BeforeEach
    void connect() throws SQLException {
        String url = System.getenv("EVENTWATCH_TEST_POSTGRES_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "no PostgreSQL server configured");
        database = Database.open(new EngineConfiguration(0, url, "rules-secret", "text",
                85.0, 80.0, 5, false, "", 1, 1, 1, 0, 0,
                System.getenv("EVENTWATCH_TEST_POSTGRES_USER"),
                System.getenv("EVENTWATCH_TEST_POSTGRES_PASSWORD"),
                2, 0, 60, 100, false, "", "", "PKCS12", List.of(), false, 10, 168, 60));
        database.initializeSchema();
        try (Connection connection = database.connections().getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM alert_rules");
        }
        repository = new AlertRuleRepository(database.connections());
        rules = new AlertRules(repository, 85.0, 80.0, 5, 10, 5);
    }

    @AfterEach
    void disconnect() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void theUpsertReplacesARuleOnTheCompositeKey() throws SQLException {
        rules.save(AlertRules.HIGH_CPU, "db-01", 90.0, true);
        rules.save(AlertRules.HIGH_CPU, "db-01", 70.0, false);

        List<AlertRule> stored = repository.findAll();
        assertEquals(1, stored.size());
        assertEquals(70.0, stored.get(0).threshold());
        assertFalse(stored.get(0).enabled(), "BOOLEAN must round-trip on PostgreSQL");
    }

    @Test
    void fleetAndHostRulesCoexistForOneType() throws SQLException {
        rules.save(AlertRules.HIGH_CPU, null, 60.0, true);
        rules.save(AlertRules.HIGH_CPU, "db-01", 95.0, true);

        assertEquals(2, repository.findAll().size());
        assertEquals(95.0, rules.effective(AlertRules.HIGH_CPU, "db-01").threshold());
        assertEquals(60.0, rules.effective(AlertRules.HIGH_CPU, "web-01").threshold());
    }

    @Test
    void deletingReportsWhetherARuleExisted() throws SQLException {
        rules.save(AlertRules.HIGH_RAM, null, 70.0, true);
        assertTrue(rules.delete(AlertRules.HIGH_RAM, null));
        assertFalse(rules.delete(AlertRules.HIGH_RAM, null));
    }
}
