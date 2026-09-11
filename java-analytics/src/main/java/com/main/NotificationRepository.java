package com.main;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Durable audit trail for outbound notification attempts. */
public class NotificationRepository {
    private final ConnectionProvider connections;

    public NotificationRepository(ConnectionProvider connections) {
        this.connections = connections;
    }

    public synchronized void record(NotificationRecord notification) throws SQLException {
        String query = "INSERT INTO notification_deliveries (alert_key, event_type, delivery_status, "
                + "http_status, error_message, attempt_number, attempted_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = connections.getConnection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, notification.alertKey());
            statement.setString(2, notification.eventType());
            statement.setString(3, notification.deliveryStatus());
            if (notification.httpStatus() == null) {
                statement.setNull(4, java.sql.Types.INTEGER);
            } else {
                statement.setInt(4, notification.httpStatus());
            }
            statement.setString(5, notification.errorMessage());
            statement.setInt(6, notification.attemptNumber());
            statement.setString(7, notification.attemptedAt().toString());
            statement.executeUpdate();
        }
    }

    public Instant lastDeliveredAt(String alertKey) throws SQLException {
        String query = "SELECT attempted_at FROM notification_deliveries WHERE alert_key = ? "
                + "AND delivery_status = 'DELIVERED' ORDER BY id DESC LIMIT 1";
        try (Connection connection = connections.getConnection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, alertKey);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Instant.parse(results.getString(1)) : null;
            }
        }
    }

    public List<NotificationRecord> findByAlertKey(String alertKey, int limit) throws SQLException {
        List<NotificationRecord> records = new ArrayList<>();
        String query = "SELECT alert_key, event_type, delivery_status, http_status, error_message, "
                + "attempt_number, attempted_at FROM notification_deliveries WHERE alert_key = ? "
                + "ORDER BY id DESC LIMIT ?";
        try (Connection connection = connections.getConnection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, alertKey);
            statement.setInt(2, limit);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    int status = results.getInt("http_status");
                    Integer httpStatus = results.wasNull() ? null : status;
                    records.add(new NotificationRecord(
                            results.getString("alert_key"), results.getString("event_type"),
                            results.getString("delivery_status"), httpStatus,
                            results.getString("error_message"), results.getInt("attempt_number"),
                            Instant.parse(results.getString("attempted_at"))));
                }
            }
        }
        return records;
    }
}
