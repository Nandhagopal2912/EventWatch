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
                    host_id TEXT,
                    hostname TEXT,
                    agent_version TEXT,
                    queue_depth INTEGER,
                    cpu_usage REAL NOT NULL,
                    ram_usage REAL NOT NULL,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
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
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
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
                        + "ON notification_deliveries(alert_key, id DESC)",
                """
                CREATE TABLE IF NOT EXISTS alert_rules (
                    rule_type TEXT NOT NULL,
                    scope TEXT NOT NULL,
                    threshold REAL NOT NULL,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (rule_type, scope)
                )
                """);
    }

    @Override
    public String insertEventIgnoringDuplicates() {
        return "INSERT OR IGNORE INTO telemetry_events "
                + "(event_id, level, message, event_timestamp, host_id, hostname, agent_version, queue_depth, "
                + "cpu_usage, ram_usage) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    }

    @Override
    public void applyLegacyMigrations(Statement statement) throws SQLException {
        // Columns added after a database may already have been created.
        addColumn(statement, "ALTER TABLE telemetry_events ADD COLUMN event_id TEXT");
        addColumn(statement, "ALTER TABLE telemetry_events ADD COLUMN host_id TEXT");
        addColumn(statement, "ALTER TABLE telemetry_events ADD COLUMN hostname TEXT");
        addColumn(statement, "ALTER TABLE alerts ADD COLUMN host_id TEXT");
        addColumn(statement, "ALTER TABLE telemetry_events ADD COLUMN agent_version TEXT");
        addColumn(statement, "ALTER TABLE telemetry_events ADD COLUMN queue_depth INTEGER");
    }

    private void addColumn(Statement statement, String ddl) throws SQLException {
        try {
            statement.executeUpdate(ddl);
        } catch (SQLException exception) {
            if (!exception.getMessage().toLowerCase(Locale.ROOT).contains("duplicate column")) {
                throw exception;
            }
        }
    }
}
