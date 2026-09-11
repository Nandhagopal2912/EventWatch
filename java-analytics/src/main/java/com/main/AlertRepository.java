package com.main;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class AlertRepository {
    private final ConnectionProvider connections;

    public AlertRepository(ConnectionProvider connections) {
        this.connections = connections;
    }

    /**
     * Records one occurrence of an alert and reports how the lifecycle changed so the
     * notification policy can decide whether the change is worth delivering.
     */
    public synchronized AlertTransition saveOccurrence(AlertRecord alert) throws SQLException {
        AlertRecord existing = findByKey(alert.getAlertKey());
        String query = """
                INSERT INTO alerts (alert_key, alert_type, host_id, message, status, first_seen,
                                    last_seen, occurrence_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(alert_key) DO UPDATE SET
                    message = excluded.message,
                    status = CASE WHEN alerts.status = 'RESOLVED' THEN 'OPEN' ELSE alerts.status END,
                    last_seen = excluded.last_seen,
                    occurrence_count = alerts.occurrence_count + 1
                """;
        try (Connection connection = connections.getConnection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, alert.getAlertKey());
            statement.setString(2, alert.getAlertType());
            statement.setString(3, alert.getHostId());
            statement.setString(4, alert.getMessage());
            statement.setString(5, alert.getStatus().name());
            statement.setString(6, alert.getFirstSeen().toString());
            statement.setString(7, alert.getLastSeen().toString());
            statement.setInt(8, alert.getOccurrenceCount());
            statement.executeUpdate();
        }
        AlertTransition.Type type;
        if (existing == null) {
            type = AlertTransition.Type.OPENED;
        } else if (existing.getStatus() == AlertStatus.RESOLVED) {
            type = AlertTransition.Type.REOPENED;
        } else {
            type = AlertTransition.Type.OCCURRENCE;
        }
        AlertRecord stored = findByKey(alert.getAlertKey());
        return new AlertTransition(stored == null ? alert : stored, type);
    }

    /** Returns the resolving transition, or null when the alert was already resolved or unknown. */
    public synchronized AlertTransition resolve(String alertKey, Instant resolvedAt) throws SQLException {
        String query = "UPDATE alerts SET status = 'RESOLVED', last_seen = ? "
                + "WHERE alert_key = ? AND status <> 'RESOLVED'";
        try (Connection connection = connections.getConnection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, resolvedAt.toString());
            statement.setString(2, alertKey);
            if (statement.executeUpdate() == 0) {
                return null;
            }
        }
        AlertRecord stored = findByKey(alertKey);
        return stored == null ? null : new AlertTransition(stored, AlertTransition.Type.RESOLVED);
    }

    /** Returns the acknowledging transition, or null when the alert was not open. */
    public synchronized AlertTransition acknowledge(String alertKey) throws SQLException {
        String query = "UPDATE alerts SET status = 'ACKNOWLEDGED' "
                + "WHERE alert_key = ? AND status = 'OPEN'";
        try (Connection connection = connections.getConnection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, alertKey);
            if (statement.executeUpdate() == 0) {
                return null;
            }
        }
        AlertRecord stored = findByKey(alertKey);
        return stored == null ? null : new AlertTransition(stored, AlertTransition.Type.ACKNOWLEDGED);
    }

    public List<AlertRecord> findActive() throws SQLException {
        return find(null, null);
    }

    public AlertRecord findByKey(String alertKey) throws SQLException {
        List<AlertRecord> alerts = find(alertKey, null);
        return alerts.isEmpty() ? null : alerts.get(0);
    }

    public List<AlertRecord> find(String alertKey, String status) throws SQLException {
        List<AlertRecord> alerts = new ArrayList<>();
        StringBuilder query = new StringBuilder("SELECT alert_key, alert_type, host_id, message, status, "
                + "first_seen, last_seen, occurrence_count FROM alerts WHERE 1 = 1");
        List<String> parameters = new ArrayList<>();
        if (alertKey != null) {
            query.append(" AND alert_key = ?");
            parameters.add(alertKey);
        }
        if (status != null) {
            query.append(" AND status = ?");
            parameters.add(status);
        } else if (alertKey == null) {
            query.append(" AND status <> 'RESOLVED'");
        }
        query.append(" ORDER BY last_seen DESC");
        try (Connection connection = connections.getConnection();
                PreparedStatement statement = connection.prepareStatement(query.toString())) {
            for (int index = 0; index < parameters.size(); index++) {
                statement.setString(index + 1, parameters.get(index));
            }
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    AlertRecord alert = new AlertRecord(
                            results.getString("alert_key"),
                            results.getString("alert_type"),
                            results.getString("host_id"),
                            results.getString("message"),
                            AlertStatus.valueOf(results.getString("status")),
                            Instant.parse(results.getString("first_seen")),
                            Instant.parse(results.getString("last_seen")),
                            results.getInt("occurrence_count"));
                    alerts.add(alert);
                }
            }
        }
        return alerts;
    }
}
