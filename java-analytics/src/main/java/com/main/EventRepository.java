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
    public synchronized boolean insertIfAbsent(LogEntry event) throws SQLException {
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
            statement.setString(7, event.agentVersion);
            if (event.queueDepth == null) {
                statement.setNull(8, java.sql.Types.INTEGER);
            } else {
                statement.setInt(8, event.queueDepth);
            }
            statement.setDouble(9, event.cpuUsage);
            statement.setDouble(10, event.ramUsage);
            if (event.diskUsage == null) {
                statement.setNull(11, java.sql.Types.DOUBLE);
            } else {
                statement.setDouble(11, event.diskUsage);
            }
            statement.setString(12, event.diskPath);
            int inserted = statement.executeUpdate();
            connection.commit();
            return inserted > 0;
        }
    }

    public List<LogEntry> find(String level, Instant from, Instant to,
            int limit, int offset) throws SQLException {
        return find(level, null, from, to, limit, offset);
    }

    public List<LogEntry> find(String level, String hostId, Instant from, Instant to,
            int limit, int offset) throws SQLException {
        StringBuilder query = new StringBuilder(
                "SELECT event_id, level, message, event_timestamp, host_id, hostname, agent_version, queue_depth, cpu_usage, ram_usage, disk_usage, disk_path "
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

        List<LogEntry> events = new ArrayList<>();
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
        return hostQuery(null, limit);
    }

    /** The same aggregate for one machine, or null when it has never reported. */
    public HostSummary host(String hostId) throws SQLException {
        List<HostSummary> found = hostQuery(hostId, 1);
        return found.isEmpty() ? null : found.get(0);
    }

    private List<HostSummary> hostQuery(String hostId, int limit) throws SQLException {
        // The correlated subqueries read each machine's newest values rather than an
        // aggregate of them: MAX(agent_version) would report the highest string, not the
        // current one. The listing is bounded, so the repeated lookup stays cheap.
        String query = "SELECT t.host_id, MAX(t.hostname) AS hostname, COUNT(*) AS event_count, "
                + "MIN(t.event_timestamp) AS first_seen, MAX(t.event_timestamp) AS last_seen, "
                + "(SELECT l.agent_version FROM telemetry_events l WHERE l.host_id = t.host_id "
                + "ORDER BY l.event_timestamp DESC LIMIT 1) AS agent_version, "
                + "(SELECT l.queue_depth FROM telemetry_events l WHERE l.host_id = t.host_id "
                + "ORDER BY l.event_timestamp DESC LIMIT 1) AS queue_depth, "
                // Newest rather than highest, for the same reason as the version above: a disk
                // that has since been cleared should stop being reported as full.
                + "(SELECT l.disk_usage FROM telemetry_events l WHERE l.host_id = t.host_id "
                + "ORDER BY l.event_timestamp DESC LIMIT 1) AS disk_usage, "
                + "(SELECT l.disk_path FROM telemetry_events l WHERE l.host_id = t.host_id "
                + "ORDER BY l.event_timestamp DESC LIMIT 1) AS disk_path "
                + "FROM telemetry_events t WHERE t.host_id IS NOT NULL"
                + (hostId == null ? "" : " AND t.host_id = ?")
                + " GROUP BY t.host_id ORDER BY last_seen DESC LIMIT ?";
        List<HostSummary> hosts = new ArrayList<>();
        try (Connection connection = connections.getConnection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            int index = 1;
            if (hostId != null) {
                statement.setString(index++, hostId);
            }
            statement.setInt(index, limit);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    hosts.add(new HostSummary(
                            results.getString("host_id"),
                            results.getString("hostname"),
                            results.getLong("event_count"),
                            Instant.parse(results.getString("first_seen")),
                            Instant.parse(results.getString("last_seen")),
                            results.getString("agent_version"),
                            results.getObject("queue_depth") == null ? null : results.getInt("queue_depth"),
                            results.getObject("disk_usage") == null ? null : results.getDouble("disk_usage"),
                            results.getString("disk_path")));
                }
            }
        }
        return hosts;
    }

    /** How many events of each level one machine has reported. */
    public Map<String, Long> levelCounts(String hostId) throws SQLException {
        String query = "SELECT level, COUNT(*) AS occurrences FROM telemetry_events "
                + "WHERE host_id = ? GROUP BY level ORDER BY level";
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection connection = connections.getConnection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, hostId);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    counts.put(results.getString("level"), results.getLong("occurrences"));
                }
            }
        }
        return counts;
    }

    /** A machine as the analytics service knows it. */
    public record HostSummary(String hostId, String hostname, long eventCount, Instant firstSeen,
            Instant lastSeen, String agentVersion, Integer queueDepth, Double diskUsage, String diskPath) {
    }

    public LogEntry latest() throws SQLException {
        String query = "SELECT event_id, level, message, event_timestamp, host_id, hostname, agent_version, queue_depth, cpu_usage, ram_usage, disk_usage, disk_path "
                + "FROM telemetry_events ORDER BY event_timestamp DESC LIMIT 1";
        try (Connection connection = connections.getConnection();
                PreparedStatement statement = connection.prepareStatement(query);
                ResultSet results = statement.executeQuery()) {
            return results.next() ? toLogEntry(results) : null;
        }
    }

    public List<LogEntry> recent(int limit) throws SQLException {
        return find(null, null, null, limit, 0);
    }

    private LogEntry toLogEntry(ResultSet results) throws SQLException {
        LogEntry event = new LogEntry(
                results.getString("event_id"),
                results.getString("level"),
                results.getString("message"),
                Instant.parse(results.getString("event_timestamp")),
                results.getString("host_id"),
                results.getString("hostname"),
                results.getString("agent_version"),
                results.getObject("queue_depth") == null ? null : results.getInt("queue_depth"),
                results.getDouble("cpu_usage"),
                results.getDouble("ram_usage"));
        // Rows written before this column existed read back as null, not as an empty disk.
        event.diskUsage = results.getObject("disk_usage") == null ? null : results.getDouble("disk_usage");
        event.diskPath = results.getString("disk_path");
        return event;
    }
}
