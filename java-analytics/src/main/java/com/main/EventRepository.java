package com.main;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class EventRepository {
    private final String databaseUrl;

    public EventRepository(String databaseUrl) {
        this.databaseUrl = databaseUrl;
    }

    public List<AnalyticsEngine.LogEntry> find(String level, Instant from, Instant to,
            int limit, int offset) throws SQLException {
        StringBuilder query = new StringBuilder(
                "SELECT event_id, level, message, event_timestamp, cpu_usage, ram_usage "
                        + "FROM telemetry_events WHERE 1 = 1");
        List<String> parameters = new ArrayList<>();
        if (level != null) {
            query.append(" AND level = ?");
            parameters.add(level);
        }
        if (from != null) {
            query.append(" AND event_timestamp >= ?");
            parameters.add(from.toString());
        }
        if (to != null) {
            query.append(" AND event_timestamp <= ?");
            parameters.add(to.toString());
        }
        query.append(" ORDER BY event_timestamp DESC LIMIT ? OFFSET ?");

        List<AnalyticsEngine.LogEntry> events = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(databaseUrl);
                PreparedStatement statement = connection.prepareStatement(query.toString())) {
            int index = 1;
            for (String parameter : parameters) {
                statement.setString(index++, parameter);
            }
            statement.setInt(index++, limit);
            statement.setInt(index, offset);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    events.add(toLogEntry(results));
                }
            }
        }
        return events;
    }

    public long count(String level, Instant from, Instant to) throws SQLException {
        StringBuilder query = new StringBuilder("SELECT COUNT(*) FROM telemetry_events WHERE 1 = 1");
        List<String> parameters = new ArrayList<>();
        if (level != null) {
            query.append(" AND level = ?");
            parameters.add(level);
        }
        if (from != null) {
            query.append(" AND event_timestamp >= ?");
            parameters.add(from.toString());
        }
        if (to != null) {
            query.append(" AND event_timestamp <= ?");
            parameters.add(to.toString());
        }
        try (Connection connection = DriverManager.getConnection(databaseUrl);
                PreparedStatement statement = connection.prepareStatement(query.toString())) {
            for (int index = 0; index < parameters.size(); index++) {
                statement.setString(index + 1, parameters.get(index));
            }
            try (ResultSet results = statement.executeQuery()) {
                results.next();
                return results.getLong(1);
            }
        }
    }

    public AnalyticsEngine.LogEntry latest() throws SQLException {
        String query = "SELECT event_id, level, message, event_timestamp, cpu_usage, ram_usage "
                + "FROM telemetry_events ORDER BY event_timestamp DESC LIMIT 1";
        try (Connection connection = DriverManager.getConnection(databaseUrl);
                PreparedStatement statement = connection.prepareStatement(query);
                ResultSet results = statement.executeQuery()) {
            return results.next() ? toLogEntry(results) : null;
        }
    }

    public List<AnalyticsEngine.LogEntry> recent(int limit) throws SQLException {
        return find(null, null, null, limit, 0);
    }

    private AnalyticsEngine.LogEntry toLogEntry(ResultSet results) throws SQLException {
        return new AnalyticsEngine.LogEntry(
                results.getString("event_id"),
                results.getString("level"),
                results.getString("message"),
                Instant.parse(results.getString("event_timestamp")),
                results.getDouble("cpu_usage"),
                results.getDouble("ram_usage"));
    }
}
