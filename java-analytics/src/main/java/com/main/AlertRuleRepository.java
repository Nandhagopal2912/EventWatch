package com.main;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Alert rules as stored rows. Portable SQL, so it runs unchanged on both backends. */
public class AlertRuleRepository {
    private final ConnectionProvider connections;

    public AlertRuleRepository(ConnectionProvider connections) {
        this.connections = connections;
    }

    public List<AlertRule> findAll() throws SQLException {
        String query = "SELECT rule_type, scope, threshold, enabled, updated_at "
                + "FROM alert_rules ORDER BY rule_type, scope";
        List<AlertRule> rules = new ArrayList<>();
        try (Connection connection = connections.getConnection();
                PreparedStatement statement = connection.prepareStatement(query);
                ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                rules.add(new AlertRule(
                        results.getString("rule_type"),
                        results.getString("scope"),
                        results.getDouble("threshold"),
                        results.getBoolean("enabled"),
                        Instant.parse(results.getString("updated_at"))));
            }
        }
        return rules;
    }

    /** Creates the rule, or replaces the one already stored for that type and scope. */
    public void save(AlertRule rule) throws SQLException {
        String query = """
                INSERT INTO alert_rules (rule_type, scope, threshold, enabled, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (rule_type, scope) DO UPDATE SET
                    threshold = excluded.threshold,
                    enabled = excluded.enabled,
                    updated_at = excluded.updated_at
                """;
        try (Connection connection = connections.getConnection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, rule.ruleType());
            statement.setString(2, rule.scope());
            statement.setDouble(3, rule.threshold());
            statement.setBoolean(4, rule.enabled());
            statement.setString(5, rule.updatedAt().toString());
            statement.executeUpdate();
        }
    }

    /** Returns false when there was no such rule. */
    public boolean delete(String ruleType, String scope) throws SQLException {
        String query = "DELETE FROM alert_rules WHERE rule_type = ? AND scope = ?";
        try (Connection connection = connections.getConnection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, ruleType);
            statement.setString(2, scope);
            return statement.executeUpdate() > 0;
        }
    }
}
