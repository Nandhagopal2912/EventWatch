package com.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Start and stop must not leak the connection pool, nor close it out from under live requests. */
class EngineLifecycleTest {
    private static final String API_KEY = "lifecycle-secret";

    @TempDir
    Path temporaryDirectory;

    private AnalyticsEngine engine;

    @AfterEach
    void stopEngine() {
        if (engine != null) {
            engine.stop();
            engine = null;
        }
    }

    private EngineConfiguration configuration(String fileName, int shutdownGraceSeconds, int rateLimit) {
        return configuration(fileName, shutdownGraceSeconds, rateLimit, 4);
    }

    private EngineConfiguration configuration(String fileName, int shutdownGraceSeconds,
            int rateLimit, int poolSize) {
        return new EngineConfiguration(0,
                TestSupport.databaseUrl(temporaryDirectory, fileName), API_KEY, "text",
                85.0, 80.0, 5, false, "", 1, 1, 1, 0,
                shutdownGraceSeconds, "", "", poolSize, 0, 60, rateLimit,
                false, "", "", "PKCS12", List.of("http://localhost:3000"), false, 10, 168, 60, "", 60, 5);
    }

    @Test
    void stopReleasesTheConnectionPool() throws IOException {
        AnalyticsEngine started = AnalyticsEngine.start(configuration("stop.db", 0, 100));
        engine = started;
        Database pool = started.database();
        assertNotNull(pool, "a started engine holds a database");

        started.stop();
        engine = null;
        assertNull(started.database(), "stop must release the pool, not leak it");
        assertTrue(pool.isClosed(), "stop must close the pool it opened");
    }

    @Test
    void aMissingApiKeyIsRejectedWithoutOpeningAPool() {
        EngineConfiguration withoutKey = new EngineConfiguration(0,
                TestSupport.databaseUrl(temporaryDirectory, "nokey.db"), "  ", "text",
                85.0, 80.0, 5, false, "", 1, 1, 1, 0, 0, "", "", 4, 0, 60, 100,
                false, "", "", "PKCS12", List.of("http://localhost:3000"), false, 10, 168, 60, "", 60, 5);

        Database.forgetLastOpened();
        IOException failure = assertThrows(IOException.class, () -> AnalyticsEngine.start(withoutKey));
        assertTrue(failure.getMessage().contains("EVENTWATCH_API_KEY"), failure.getMessage());
        assertNull(Database.lastOpened(), "the key is checked before a pool is ever opened");
    }

    @Test
    void anUnusableDatabaseUrlFailsWithoutLeakingAPool() {
        EngineConfiguration unusable = new EngineConfiguration(0,
                "jdbc:mysql://localhost/eventwatch", API_KEY, "text",
                85.0, 80.0, 5, false, "", 1, 1, 1, 0, 0, "", "", 4, 0, 60, 100,
                false, "", "", "PKCS12", List.of("http://localhost:3000"), false, 10, 168, 60, "", 60, 5);

        Database.forgetLastOpened();
        assertThrows(IOException.class, () -> AnalyticsEngine.start(unusable));
        assertNull(Database.lastOpened(), "an unsupported url is refused before a pool is opened");
    }

    @Test
    void aDatabaseFromAnOlderBuildIsUpgradedOnStartup() throws Exception {
        // Regression for B16: the host index was created before the migration that adds the
        // host_id column, so starting against a database written by a pre-phase-12 build died
        // with "no such column: host_id". Every test before this one used a fresh database,
        // where the column is part of CREATE TABLE and the ordering never showed.
        String databaseUrl = TestSupport.databaseUrl(temporaryDirectory, "legacy.db");
        try (Connection connection = DriverManager.getConnection(databaseUrl);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE telemetry_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        level TEXT NOT NULL,
                        message TEXT NOT NULL,
                        event_timestamp TEXT NOT NULL,
                        cpu_usage REAL NOT NULL,
                        ram_usage REAL NOT NULL,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE alerts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        alert_key TEXT NOT NULL UNIQUE,
                        alert_type TEXT NOT NULL,
                        message TEXT NOT NULL,
                        status TEXT NOT NULL,
                        first_seen TEXT NOT NULL,
                        last_seen TEXT NOT NULL,
                        occurrence_count INTEGER NOT NULL DEFAULT 1
                    )
                    """);
            statement.executeUpdate("INSERT INTO telemetry_events "
                    + "(level, message, event_timestamp, cpu_usage, ram_usage) "
                    + "VALUES ('ERROR', 'written by an older build', '2026-01-01T00:00:00Z', 10.0, 20.0)");
        }

        engine = AnalyticsEngine.start(new EngineConfiguration(0, databaseUrl, API_KEY, "text",
                85.0, 80.0, 5, false, "", 1, 1, 1, 0, 0, "", "", 4, 0, 60, 100,
                false, "", "", "PKCS12", List.of("http://localhost:3000"), false, 10, 168, 60, "", 60, 5));

        try (Connection connection = DriverManager.getConnection(databaseUrl);
                Statement statement = connection.createStatement();
                ResultSet results = statement.executeQuery(
                        "SELECT host_id, agent_version, queue_depth FROM telemetry_events")) {
            assertTrue(results.next(), "the row written by the older build must survive");
            assertNull(results.getString("host_id"), "an old row has no identity, and that is allowed");
        }
    }

    @Test
    void aFailureAfterTheDatabaseIsOpenStillReleasesThePool() {
        // Regression for B12: the pool is allocated before the listener exists, so every failure
        // from there on has to release it. A missing keystore is the cheapest way to get there.
        EngineConfiguration missingKeystore = new EngineConfiguration(0,
                TestSupport.databaseUrl(temporaryDirectory, "keystore.db"), API_KEY, "text",
                85.0, 80.0, 5, false, "", 1, 1, 1, 0, 0, "", "", 4, 0, 60, 100,
                true, temporaryDirectory.resolve("absent.p12").toString(), "changeit", "PKCS12",
                List.of("http://localhost:3000"), false, 10, 168, 60, "", 60, 5);

        Database.forgetLastOpened();
        assertThrows(IOException.class, () -> AnalyticsEngine.start(missingKeystore));
        Database opened = Database.lastOpened();
        assertNotNull(opened, "this failure happens after the pool is opened");
        assertTrue(opened.isClosed(), "a failed start must not leave a pool open");
    }

    @Test
    void aNonPositiveSweepPeriodDoesNotPreventStartup() throws IOException {
        // Regression: the sweep period went straight to scheduleWithFixedDelay, which rejects
        // a non-positive delay, so RETENTION_SWEEP_MINUTES=0 with retention on killed startup.
        EngineConfiguration zeroSweep = new EngineConfiguration(0,
                TestSupport.databaseUrl(temporaryDirectory, "sweep.db"), API_KEY, "text",
                85.0, 80.0, 5, false, "", 1, 1, 1, 0, 0, "", "", 0, 7, 0, 0,
                false, "", "", "PKCS12", List.of("http://localhost:3000"), false, 10, 168, 60, "", 60, 5);

        assertEquals(60, zeroSweep.retentionSweepMinutes(), "a non-positive period falls back");
        assertEquals(10, zeroSweep.databasePoolSize(), "a non-positive pool size falls back");
        assertEquals(100, zeroSweep.rateLimitPerMinute(), "a non-positive rate limit falls back");

        engine = AnalyticsEngine.start(zeroSweep);
        assertNotNull(engine, "the engine must still start with retention enabled");
    }

    @Test
    void stopKeepsTheDatabaseOpenUntilInFlightWorkFinishes() throws Exception {
        // Regression: stop() used to close the connection pool before draining the request
        // executor, so work still running lost its database mid-flight. Driving this through
        // HTTP is unreliable - server.stop() either waits for the exchanges itself or tears
        // their connections down - so the ordering is asserted on the executor directly.
        AnalyticsEngine started = AnalyticsEngine.start(configuration("drain.db", 1, 100_000));
        engine = started;

        AtomicBoolean databaseWasStillOpen = new AtomicBoolean();
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskFinished = new CountDownLatch(1);
        started.requestExecutor().execute(() -> {
            taskStarted.countDown();
            try {
                Thread.sleep(1500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            databaseWasStillOpen.set(started.database() != null);
            taskFinished.countDown();
        });

        assertTrue(taskStarted.await(5, TimeUnit.SECONDS), "the task should reach the executor");
        started.stop();
        engine = null;

        assertTrue(taskFinished.await(5, TimeUnit.SECONDS), "stop must wait for in-flight work");
        assertTrue(databaseWasStillOpen.get(),
                "work in flight lost its database because the pool was closed before the drain");
        assertNull(started.database(), "once drained, the pool is released");
    }
}
