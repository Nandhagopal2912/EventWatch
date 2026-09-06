package com.main;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class AlertRepository {
    private final String databaseUrl;

    public AlertRepository(String databaseUrl) throws SQLException {
        this.databaseUrl = databaseUrl;
        initializeTable();
    }

    public synchronized void saveOccurrence(AlertRecord alert) throws SQLException {
        String query = """
                INSERT INTO alerts (alert_key, alert_type, message, status, first_seen,
                                    last_seen, occurrence_count)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(alert_key) DO UPDATE SET
                    status = excluded.status,
                    last_seen = excluded.last_seen,
                    occurrence_count = alerts.occurrence_count + 1
                """;
        try (Connection connection = DriverManager.getConnection(databaseUrl);
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, alert.getAlertKey());
            statement.setString(2, alert.getAlertType());
            statement.setString(3, alert.getMessage());
            statement.setString(4, alert.getStatus().name());
            statement.setString(5, alert.getFirstSeen().toString());
            statement.setString(6, alert.getLastSeen().toString());
            statement.setInt(7, alert.getOccurrenceCount());
            statement.executeUpdate();
        }
    }

    public synchronized boolean resolve(String alertKey, Instant resolvedAt) throws SQLException {
        String query = "UPDATE alerts SET status = 'RESOLVED', last_seen = ? "
                + "WHERE alert_key = ? AND status <> 'RESOLVED'";
        try (Connection connection = DriverManager.getConnection(databaseUrl);
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, resolvedAt.toString());
            statement.setString(2, alertKey);
            return statement.executeUpdate() > 0;
        }
    }

    public synchronized boolean acknowledge(String alertKey) throws SQLException {
        String query = "UPDATE alerts SET status = 'ACKNOWLEDGED' "
                + "WHERE alert_key = ? AND status = 'OPEN'";
        try (Connection connection = DriverManager.getConnection(databaseUrl);
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, alertKey);
            return statement.executeUpdate() > 0;
        }
    }

    public List<AlertRecord> findActive() throws SQLException {
        List<AlertRecord> alerts = new ArrayList<>();
        String query = "SELECT alert_key, alert_type, message, status, first_seen, "
                + "last_seen, occurrence_count FROM alerts WHERE status <> 'RESOLVED' "
                + "ORDER BY last_seen DESC";
        try (Connection connection = DriverManager.getConnection(databaseUrl);
                PreparedStatement statement = connection.prepareStatement(query);
                ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                AlertRecord alert = new AlertRecord(
                        results.getString("alert_key"),
                        results.getString("alert_type"),
                        results.getString("message"),
                        AlertStatus.valueOf(results.getString("status")),
                        Instant.parse(results.getString("first_seen")),
                        Instant.parse(results.getString("last_seen")),
                        results.getInt("occurrence_count"));
                alerts.add(alert);
            }
        }
        return alerts;
    }

    private void initializeTable() throws SQLException {
        try (Connection connection = DriverManager.getConnection(databaseUrl);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS alerts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        alert_key TEXT NOT NULL UNIQUE,
                        alert_type TEXT NOT NULL,
                        message TEXT NOT NULL,
                        status TEXT NOT NULL,
                        first_seen TEXT NOT NULL,
                        last_seen TEXT NOT NULL,
                        occurrence_count INTEGER NOT NULL DEFAULT 1
                    )
                    """);
        }
    }
}
