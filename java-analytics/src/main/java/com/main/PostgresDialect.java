package com.main;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * The backend for multiple collectors or long retention. Timestamps stay ISO-8601 text
 * on both backends so ordering, range filters, and stored data mean exactly the same thing.
 */
public class PostgresDialect implements SqlDialect {
    @Override
    public String name() {
        return "postgresql";
    }

    @Override
    public List<String> schemaStatements() {
        return List.of("""
                CREATE TABLE IF NOT EXISTS telemetry_events (
                    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    event_id TEXT,
                    level TEXT NOT NULL,
                    message TEXT NOT NULL,
                    event_timestamp TEXT NOT NULL,
                    host_id TEXT,
                    hostname TEXT,
                    cpu_usage DOUBLE PRECISION NOT NULL,
                    ram_usage DOUBLE PRECISION NOT NULL,
                    created_at TEXT NOT NULL DEFAULT (now() AT TIME ZONE 'utc')::text
                )
                """,
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_telemetry_events_event_id "
                        + "ON telemetry_events(event_id) WHERE event_id IS NOT NULL",
                "CREATE INDEX IF NOT EXISTS idx_telemetry_events_level "
                        + "ON telemetry_events(level, event_timestamp)",
                "CREATE INDEX IF NOT EXISTS idx_telemetry_events_host "
                        + "ON telemetry_events(host_id, event_timestamp)",
                """
                CREATE TABLE IF NOT EXISTS alerts (
                    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    alert_key TEXT NOT NULL UNIQUE,
                    alert_type TEXT NOT NULL,
                    host_id TEXT,
                    message TEXT NOT NULL,
                    status TEXT NOT NULL,
                    first_seen TEXT NOT NULL,
                    last_seen TEXT NOT NULL,
                    occurrence_count INTEGER NOT NULL DEFAULT 1
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS notification_deliveries (
                    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    alert_key TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    delivery_status TEXT NOT NULL,
                    http_status INTEGER,
                    error_message TEXT,
                    attempt_number INTEGER NOT NULL,
                    attempted_at TEXT NOT NULL
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_notification_deliveries_alert "
                        + "ON notification_deliveries(alert_key, id DESC)");
    }

    @Override
    public void applyLegacyMigrations(Statement statement) throws SQLException {
        // Columns added after a database may already have been created.
        statement.executeUpdate("ALTER TABLE telemetry_events ADD COLUMN IF NOT EXISTS host_id TEXT");
        statement.executeUpdate("ALTER TABLE telemetry_events ADD COLUMN IF NOT EXISTS hostname TEXT");
        statement.executeUpdate("ALTER TABLE alerts ADD COLUMN IF NOT EXISTS host_id TEXT");
    }

    @Override
    public String insertEventIgnoringDuplicates() {
        return "INSERT INTO telemetry_events "
                + "(event_id, level, message, event_timestamp, host_id, hostname, cpu_usage, ram_usage) "
                // The arbiter is a partial index, so PostgreSQL needs its predicate repeated here.
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (event_id) WHERE event_id IS NOT NULL DO NOTHING";
    }
}
