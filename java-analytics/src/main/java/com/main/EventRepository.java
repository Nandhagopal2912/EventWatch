package com.main;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EventRepository {
    private final ConnectionProvider connections;
    private final SqlDialect dialect;

    public EventRepository(ConnectionProvider connections, SqlDialect dialect) {
        this.connections = connections;
        this.dialect = dialect;
    }

    /**
     * Commits one event and reports whether it was new. The unique event_id index makes a
     * repeated delivery a no-op instead of a duplicate row.
     */
    public synchronized boolean insertIfAbsent(AnalyticsEngine.LogEntry event) throws SQLException {
        String query = dialect.insertEventIgnoringDuplicates();
        try (Connection connection = connections.getConnection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            connection.setAutoCommit(false);
            statement.setString(1, event.eventId);
            statement.setString(2, event.level);
            statement.setString(3, event.message);
            statement.setString(4, event.timestamp.toString());
            statement.setString(5, event.hostId);
            statement.setString(6, event.hostname);
            statement.setDouble(7, event.cpuUsage);
            statement.setDouble(8, event.ramUsage);
            int inserted = statement.executeUpdate();
            connection.commit();
            return inserted > 0;
        }
    }

    public List<AnalyticsEngine.LogEntry> find(String level, Instant from, Instant to,
            int limit, int offset) throws SQLException {
        return find(level, null, from, to, limit, offset);
    }

    public List<AnalyticsEngine.LogEntry> find(String level, String hostId, Instant from, Instant to,
            int limit, int offset) throws SQLException {
        StringBuilder query = new StringBuilder(
                "SELECT event_id, level, message, event_timestamp, host_id, hostname, cpu_usage, ram_usage "
                        + "FROM telemetry_events WHERE 1 = 1");
        List<String> parameters = new ArrayList<>();
        if (level != null) {
            query.append(" AND level = ?");
            parameters.add(level);
        }
        if (hostId != null) {
            query.append(" AND host_id = ?");
            parameters.add(hostId);
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
        try (Connection connection = connections.getConnection();
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
        return count(level, null, from, to);
    }

    public long count(String level, String hostId, Instant from, Instant to) throws SQLException {
        StringBuilder query = new StringBuilder("SELECT COUNT(*) FROM telemetry_events WHERE 1 = 1");
        List<String> parameters = new ArrayList<>();
        if (level != null) {
            query.append(" AND level = ?");
            parameters.add(level);
        }
        if (hostId != null) {
            query.append(" AND host_id = ?");
            parameters.add(hostId);
        }
        if (from != null) {
            query.append(" AND event_timestamp >= ?");
            parameters.add(from.toString());
        }
        if (to != null) {
            query.append(" AND event_timestamp <= ?");
            parameters.add(to.toString());
        }
        try (Connection connection = connections.getConnection();
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
        try (Connection connection = connections.getConnection();
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

    /** One row per machine that has ever reported, with its last-seen time. */
    public List<HostSummary> hosts(int limit) throws SQLException {
        String query = "SELECT host_id, MAX(hostname) AS hostname, COUNT(*) AS event_count, "
                + "MAX(event_timestamp) AS last_seen FROM telemetry_events "
                + "WHERE host_id IS NOT NULL GROUP BY host_id ORDER BY last_seen DESC LIMIT ?";
        List<HostSummary> hosts = new ArrayList<>();
        try (Connection connection = connections.getConnection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, limit);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    hosts.add(new HostSummary(
                            results.getString("host_id"),
                            results.getString("hostname"),
                            results.getLong("event_count"),
                            Instant.parse(results.getString("last_seen"))));
                }
            }
        }
        return hosts;
    }

    /** A machine as the analytics service knows it. */
    public record HostSummary(String hostId, String hostname, long eventCount, Instant lastSeen) {
    }

    public AnalyticsEngine.LogEntry latest() throws SQLException {
        String query = "SELECT event_id, level, message, event_timestamp, host_id, hostname, cpu_usage, ram_usage "
                + "FROM telemetry_events ORDER BY event_timestamp DESC LIMIT 1";
        try (Connection connection = connections.getConnection();
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
                results.getString("host_id"),
                results.getString("hostname"),
                results.getDouble("cpu_usage"),
                results.getDouble("ram_usage"));
    }
}
