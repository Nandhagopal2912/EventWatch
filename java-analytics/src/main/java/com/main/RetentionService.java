package com.main;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Deletes telemetry and delivery history older than the configured window. Without this
 * the database only ever grows, which is the first thing to bite a long-running deployment
 * regardless of which backend is in use.
 */
public class RetentionService {
    private final ConnectionProvider connections;
    private final Metrics metrics;
    private final int retentionDays;

    public RetentionService(ConnectionProvider connections, Metrics metrics, int retentionDays) {
        this.connections = connections;
        this.metrics = metrics;
        this.retentionDays = retentionDays;
    }

    /** Zero or negative keeps everything, which stays the default. */
    public boolean enabled() {
        return retentionDays > 0;
    }

    /** Returns the number of rows removed across both history tables. */
    public int prune(Instant now) throws SQLException {
        if (!enabled()) {
            return 0;
        }
        String cutoff = now.minus(retentionDays, ChronoUnit.DAYS).toString();
        int removed = 0;
        try (Connection connection = connections.getConnection()) {
            removed += delete(connection,
                    "DELETE FROM telemetry_events WHERE event_timestamp < ?", cutoff);
            // Delivery history is only useful alongside the events that produced it.
            removed += delete(connection,
                    "DELETE FROM notification_deliveries WHERE attempted_at < ?", cutoff);
        }
        return removed;
    }

    /** Runs one sweep and reports it, swallowing failures so the timer thread survives. */
    public void sweep() {
        if (!enabled()) {
            return;
        }
        try {
            int removed = prune(Instant.now());
            if (removed > 0) {
                StructuredLogger.info("retention sweep removed expired rows", StructuredLogger.fields(
                        "removed_rows", removed, "retention_days", retentionDays));
            }
        } catch (SQLException exception) {
            metrics.recordDatabaseFailure();
            StructuredLogger.error("retention sweep failed",
                    StructuredLogger.fields("error", exception.getMessage()));
        }
    }

    private int delete(Connection connection, String query, String cutoff) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, cutoff);
            return statement.executeUpdate();
        }
    }
}
