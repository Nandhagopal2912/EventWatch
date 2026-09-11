package com.main;

import java.nio.file.Path;
import java.sql.SQLException;

/** Shared helpers for the test suite. */
final class TestSupport {
    private TestSupport() {
    }

    /** SQLite needs forward slashes even on Windows. */
    static String databaseUrl(Path directory, String fileName) {
        return "jdbc:sqlite:" + directory.resolve(fileName).toString().replace('\\', '/');
    }

    /** An initialized SQLite database in a temporary directory. */
    static Database openDatabase(Path directory, String fileName) throws SQLException {
        Database database = Database.open(
                EngineConfiguration.forTesting(databaseUrl(directory, fileName), "test-key"));
        database.initializeSchema();
        return database;
    }
}
