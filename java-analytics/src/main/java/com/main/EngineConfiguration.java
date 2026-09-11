package com.main;

import io.github.cdimascio.dotenv.Dotenv;

/**
 * Every runtime setting the analytics engine needs, so it can be started from
 * {@code main} with dotenv values or from a test with explicit ones.
 */
public record EngineConfiguration(
        int port,
        String databaseUrl,
        String apiKey,
        String logFormat,
        double cpuThreshold,
        double ramThreshold,
        int repeatedErrorThreshold,
        boolean notificationsEnabled,
        String notificationWebhookUrl,
        int notificationTimeoutSeconds,
        int notificationMaxAttempts,
        long notificationRetryDelayMillis,
        long notificationReminderSeconds,
        int shutdownGraceSeconds,
        String databaseUser,
        String databasePassword,
        int databasePoolSize,
        int retentionDays,
        int retentionSweepMinutes,
        int rateLimitPerMinute) {

    /**
     * Settings where zero or negative is not a weaker setting but a crash: the pool rejects a
     * size below one, and the scheduler rejects a sweep period below one.
     */
    public EngineConfiguration {
        databasePoolSize = databasePoolSize > 0 ? databasePoolSize : 10;
        retentionSweepMinutes = retentionSweepMinutes > 0 ? retentionSweepMinutes : 60;
        rateLimitPerMinute = rateLimitPerMinute > 0 ? rateLimitPerMinute : 100;
    }

    public static EngineConfiguration fromDotenv(Dotenv dotenv) {
        // An explicit DATABASE_URL selects the backend; otherwise SQLite keeps the local default.
        String databasePath = value(dotenv, "DATABASE_PATH", "events.db");
        String databaseUrl = value(dotenv, "DATABASE_URL", null);
        if (databaseUrl == null || databaseUrl.isBlank()) {
            databaseUrl = "jdbc:sqlite:" + databasePath;
        }
        return new EngineConfiguration(
                intValue(dotenv, "HTTP_PORT", 8080),
                databaseUrl,
                value(dotenv, "EVENTWATCH_API_KEY", null),
                value(dotenv, "LOG_FORMAT", "json"),
                doubleValue(dotenv, "CPU_ALERT_THRESHOLD", 85.0),
                doubleValue(dotenv, "RAM_ALERT_THRESHOLD", 80.0),
                intValue(dotenv, "REPEATED_ERROR_THRESHOLD", 5),
                Boolean.parseBoolean(value(dotenv, "NOTIFICATIONS_ENABLED", "false")),
                value(dotenv, "NOTIFICATION_WEBHOOK_URL", ""),
                intValue(dotenv, "NOTIFICATION_TIMEOUT_SECONDS", 5),
                intValue(dotenv, "NOTIFICATION_MAX_ATTEMPTS", 3),
                intValue(dotenv, "NOTIFICATION_RETRY_DELAY_MILLIS", 1000),
                intValue(dotenv, "NOTIFICATION_REMINDER_SECONDS", 900),
                intValue(dotenv, "SHUTDOWN_GRACE_SECONDS", 5),
                value(dotenv, "DATABASE_USER", ""),
                value(dotenv, "DATABASE_PASSWORD", ""),
                intValue(dotenv, "DATABASE_POOL_SIZE", 10),
                intValue(dotenv, "RETENTION_DAYS", 0),
                intValue(dotenv, "RETENTION_SWEEP_MINUTES", 60),
                intValue(dotenv, "RATE_LIMIT_PER_MINUTE", 100));
    }

    /**
     * A minimal configuration for tests: ephemeral port, temporary database, no notifications,
     * and no shutdown grace period so a suite does not pay it once per test.
     */
    public static EngineConfiguration forTesting(String databaseUrl, String apiKey) {
        return new EngineConfiguration(0, databaseUrl, apiKey, "text",
                85.0, 80.0, 5, false, "", 1, 1, 1, 0, 0, "", "", 2, 0, 60, 100);
    }

    private static String value(Dotenv dotenv, String name, String fallback) {
        String environment = System.getenv(name);
        return dotenv.get(name, environment == null ? fallback : environment);
    }

    private static double doubleValue(Dotenv dotenv, String name, double fallback) {
        try {
            return Double.parseDouble(value(dotenv, name, Double.toString(fallback)));
        } catch (NumberFormatException | NullPointerException exception) {
            return fallback;
        }
    }

    private static int intValue(Dotenv dotenv, String name, int fallback) {
        try {
            return Integer.parseInt(value(dotenv, name, Integer.toString(fallback)));
        } catch (NumberFormatException | NullPointerException exception) {
            return fallback;
        }
    }
}
