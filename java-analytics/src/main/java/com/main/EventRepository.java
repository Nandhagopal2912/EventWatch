package com.main;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EventRepository {
    private final String databaseUrl;

    private static final String UNIQUE_INDEX = "idx_telemetry_events_event_id";

    public EventRepository(String databaseUrl) {
        this.databaseUrl = databaseUrl;
    }

    /** Creates the schema on first startup so no manual database setup is required. */
    public void initializeSchema() throws SQLException {
        try (Connection connection = DriverManager.getConnection(databaseUrl);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS telemetry_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        event_id TEXT,
                        level TEXT NOT NULL,
                        message TEXT NOT NULL,
                        event_timestamp TEXT NOT NULL,
                        cpu_usage REAL NOT NULL,
                        ram_usage REAL NOT NULL,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            try {
                statement.executeUpdate("ALTER TABLE telemetry_events ADD COLUMN event_id TEXT");
            } catch (SQLException exception) {
                if (!exception.getMessage().toLowerCase(Locale.ROOT).contains("duplicate column")) {
                    throw exception;
                }
            }
            statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS " + UNIQUE_INDEX
                    + " ON telemetry_events(event_id) WHERE event_id IS NOT NULL");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_telemetry_events_level "
                    + "ON telemetry_events(level, event_timestamp)");
        }
    }

    /**
     * Commits one event and reports whether it was new. The unique event_id index makes a
     * repeated delivery a no-op instead of a duplicate row.
     */
    public synchronized boolean insertIfAbsent(AnalyticsEngine.LogEntry event) throws SQLException {
        String query = "INSERT OR IGNORE INTO telemetry_events "
                + "(event_id, level, message, event_timestamp, cpu_usage, ram_usage) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection connection = DriverManager.getConnection(databaseUrl);
                PreparedStatement statement = connection.prepareStatement(query)) {
            connection.setAutoCommit(false);
            statement.setString(1, event.eventId);
            statement.setString(2, event.level);
            statement.setString(3, event.message);
            statement.setString(4, event.timestamp.toString());
            statement.setDouble(5, event.cpuUsage);
            statement.setDouble(6, event.ramUsage);
            int inserted = statement.executeUpdate();
            connection.commit();
            return inserted > 0;
        }
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

    /**
     * Most frequent error messages, newest counts first. Bounded so the terminal report
     * cannot grow with the number of distinct messages in the database.
     */
    public Map<String, Long> topErrorMessages(int limit) throws SQLException {
        String query = "SELECT message, COUNT(*) AS occurrences FROM telemetry_events "
                + "WHERE level IN ('ERROR', 'CRITICAL') GROUP BY message "
                + "ORDER BY occurrences DESC, message LIMIT ?";
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(databaseUrl);
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, limit);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    counts.put(results.getString("message"), results.getLong("occurrences"));
                }
            }
        }
        return counts;
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
