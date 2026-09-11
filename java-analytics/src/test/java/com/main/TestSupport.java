package com.main;

import java.nio.file.Path;

/** Shared helpers for the test suite. */
final class TestSupport {
    private TestSupport() {
    }

    /** SQLite needs forward slashes even on Windows. */
    static String databaseUrl(Path directory, String fileName) {
        return "jdbc:sqlite:" + directory.resolve(fileName).toString().replace('\\', '/');
    }
}
