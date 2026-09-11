package com.main;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * The handful of places where SQLite and PostgreSQL disagree. Everything else in the
 * repositories is portable SQL, so there is one query path to maintain and test.
 */
public interface SqlDialect {
    /** A readable backend name for logs and metrics. */
    String name();

    /** Table and index creation, run at startup and safe to repeat. */
    List<String> schemaStatements();

    /** Insert that silently does nothing when the event id is already stored. */
    String insertEventIgnoringDuplicates();

    /**
     * Adjustments for databases created by an older build. Nothing to do on a backend
     * that never carried those schemas.
     */
    default void applyLegacyMigrations(Statement statement) throws SQLException {
    }
}
