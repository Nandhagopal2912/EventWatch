package com.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
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

    private HttpServer server;

    @AfterEach
    void stopEngine() {
        if (server != null) {
            AnalyticsEngine.stop(server);
            server = null;
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
                false, "", "", "PKCS12", List.of("http://localhost:3000"), false);
    }

    @Test
    void stopReleasesTheConnectionPool() throws IOException {
        server = AnalyticsEngine.start(configuration("stop.db", 0, 100));
        assertNotNull(AnalyticsEngine.database(), "a started engine holds a database");

        AnalyticsEngine.stop(server);
        server = null;
        assertNull(AnalyticsEngine.database(), "stop must release the pool, not leak it");
    }

    @Test
    void aMissingApiKeyIsRejectedWithoutOpeningAPool() {
        EngineConfiguration withoutKey = new EngineConfiguration(0,
                TestSupport.databaseUrl(temporaryDirectory, "nokey.db"), "  ", "text",
                85.0, 80.0, 5, false, "", 1, 1, 1, 0, 0, "", "", 4, 0, 60, 100,
                false, "", "", "PKCS12", List.of("http://localhost:3000"), false);

        IOException failure = assertThrows(IOException.class, () -> AnalyticsEngine.start(withoutKey));
        assertTrue(failure.getMessage().contains("EVENTWATCH_API_KEY"), failure.getMessage());
        assertNull(AnalyticsEngine.database(), "a rejected configuration must not leave a pool open");
    }

    @Test
    void anUnusableDatabaseUrlFailsWithoutLeakingAPool() {
        EngineConfiguration unusable = new EngineConfiguration(0,
                "jdbc:mysql://localhost/eventwatch", API_KEY, "text",
                85.0, 80.0, 5, false, "", 1, 1, 1, 0, 0, "", "", 4, 0, 60, 100,
                false, "", "", "PKCS12", List.of("http://localhost:3000"), false);

        assertThrows(IOException.class, () -> AnalyticsEngine.start(unusable));
        assertNull(AnalyticsEngine.database(), "a failed start must not leave a pool open");
    }

    @Test
    void aNonPositiveSweepPeriodDoesNotPreventStartup() throws IOException {
        // Regression: the sweep period went straight to scheduleWithFixedDelay, which rejects
        // a non-positive delay, so RETENTION_SWEEP_MINUTES=0 with retention on killed startup.
        EngineConfiguration zeroSweep = new EngineConfiguration(0,
                TestSupport.databaseUrl(temporaryDirectory, "sweep.db"), API_KEY, "text",
                85.0, 80.0, 5, false, "", 1, 1, 1, 0, 0, "", "", 0, 7, 0, 0,
                false, "", "", "PKCS12", List.of("http://localhost:3000"), false);

        assertEquals(60, zeroSweep.retentionSweepMinutes(), "a non-positive period falls back");
        assertEquals(10, zeroSweep.databasePoolSize(), "a non-positive pool size falls back");
        assertEquals(100, zeroSweep.rateLimitPerMinute(), "a non-positive rate limit falls back");

        server = AnalyticsEngine.start(zeroSweep);
        assertNotNull(server, "the engine must still start with retention enabled");
    }

    @Test
    void stopKeepsTheDatabaseOpenUntilInFlightWorkFinishes() throws Exception {
        // Regression: stop() used to close the connection pool before draining the request
        // executor, so work still running lost its database mid-flight. Driving this through
        // HTTP is unreliable - server.stop() either waits for the exchanges itself or tears
        // their connections down - so the ordering is asserted on the executor directly.
        server = AnalyticsEngine.start(configuration("drain.db", 1, 100_000));

        AtomicBoolean databaseWasStillOpen = new AtomicBoolean();
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskFinished = new CountDownLatch(1);
        AnalyticsEngine.requestExecutor().execute(() -> {
            taskStarted.countDown();
            try {
                Thread.sleep(1500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            databaseWasStillOpen.set(AnalyticsEngine.database() != null);
            taskFinished.countDown();
        });

        assertTrue(taskStarted.await(5, TimeUnit.SECONDS), "the task should reach the executor");
        AnalyticsEngine.stop(server);
        server = null;

        assertTrue(taskFinished.await(5, TimeUnit.SECONDS), "stop must wait for in-flight work");
        assertTrue(databaseWasStillOpen.get(),
                "work in flight lost its database because the pool was closed before the drain");
        assertNull(AnalyticsEngine.database(), "once drained, the pool is released");
    }
}
