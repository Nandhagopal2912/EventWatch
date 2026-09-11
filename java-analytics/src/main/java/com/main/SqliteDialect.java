package com.main;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

/** The single-host and development backend. */
public class SqliteDialect implements SqlDialect {
    @Override
    public String name() {
        return "sqlite";
    }

    @Override
    public List<String> schemaStatements() {
        return List.of("""
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
                """,
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_telemetry_events_event_id "
                        + "ON telemetry_events(event_id) WHERE event_id IS NOT NULL",
                "CREATE INDEX IF NOT EXISTS idx_telemetry_events_level "
                        + "ON telemetry_events(level, event_timestamp)",
                """
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
                """,
                """
                CREATE TABLE IF NOT EXISTS notification_deliveries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
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
    public String insertEventIgnoringDuplicates() {
        return "INSERT OR IGNORE INTO telemetry_events "
                + "(event_id, level, message, event_timestamp, cpu_usage, ram_usage) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
    }

    @Override
    public void applyLegacyMigrations(Statement statement) throws SQLException {
        // Databases written before phase 5 have no event_id column.
        try {
            statement.executeUpdate("ALTER TABLE telemetry_events ADD COLUMN event_id TEXT");
        } catch (SQLException exception) {
            if (!exception.getMessage().toLowerCase(Locale.ROOT).contains("duplicate column")) {
                throw exception;
            }
        }
    }
}
