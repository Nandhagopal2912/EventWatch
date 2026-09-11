package com.main;

import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AlertEngine {
    private final AlertRepository repository;
    private final NotificationService notificationService;
    private final int movingWindowSize;
    private final double cpuThreshold;
    private final double ramThreshold;
    private final int repeatedErrorThreshold;

    public AlertEngine(AlertRepository repository, NotificationService notificationService,
            int movingWindowSize, double cpuThreshold, double ramThreshold, int repeatedErrorThreshold) {
        this.repository = repository;
        this.notificationService = notificationService;
        this.movingWindowSize = movingWindowSize;
        this.cpuThreshold = cpuThreshold;
        this.ramThreshold = ramThreshold;
        this.repeatedErrorThreshold = repeatedErrorThreshold;
    }

    /**
     * Evaluates one machine's window. Alert keys are scoped to the host, so a fleet sharing
     * one analytics service raises one alert per machine instead of fighting over a single row.
     */
    public void evaluate(List<AnalyticsEngine.LogEntry> events) throws SQLException {
        if (events.isEmpty()) {
            return;
        }
        int start = Math.max(0, events.size() - movingWindowSize);
        List<AnalyticsEngine.LogEntry> recentEvents = events.subList(start, events.size());
        Instant now = recentEvents.get(recentEvents.size() - 1).timestamp;
        String hostId = recentEvents.get(recentEvents.size() - 1).hostId;

        double averageCpu = recentEvents.stream()
                .mapToDouble(event -> event.cpuUsage)
                .average()
                .orElse(0.0);
        double averageRam = recentEvents.stream()
                .mapToDouble(event -> event.ramUsage)
                .average()
                .orElse(0.0);

        evaluateThreshold(alertKey("cpu-high", hostId), "HIGH_CPU", hostId, averageCpu, cpuThreshold,
                "CPU average is %.1f%% (threshold %.1f%%)".formatted(averageCpu, cpuThreshold), now);
        evaluateThreshold(alertKey("ram-high", hostId), "HIGH_RAM", hostId, averageRam, ramThreshold,
                "RAM average is %.1f%% (threshold %.1f%%)".formatted(averageRam, ramThreshold), now);

        Map<String, Integer> errorCounts = new HashMap<>();
        for (AnalyticsEngine.LogEntry event : recentEvents) {
            if ("ERROR".equalsIgnoreCase(event.level) || "CRITICAL".equalsIgnoreCase(event.level)) {
                errorCounts.merge(event.message, 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> entry : errorCounts.entrySet()) {
            String key = alertKey("repeated-error-" + stableKey(entry.getKey()), hostId);
            if (entry.getValue() >= repeatedErrorThreshold) {
                notify(repository.saveOccurrence(new AlertRecord(key, "REPEATED_ERROR", hostId,
                        entry.getKey() + " occurred " + entry.getValue() + " times", now)));
            }
        }
    }

    private void evaluateThreshold(String alertKey, String alertType, String hostId, double value,
            double threshold, String message, Instant timestamp) throws SQLException {
        if (value >= threshold) {
            notify(repository.saveOccurrence(new AlertRecord(alertKey, alertType, hostId, message, timestamp)));
        } else {
            notify(repository.resolve(alertKey, timestamp));
        }
    }

    /** Alert keys appear in URLs, so the host is appended rather than embedded with a separator. */
    static String alertKey(String rule, String hostId) {
        return rule + "@" + (hostId == null || hostId.isBlank() ? AnalyticsEngine.UNKNOWN_HOST : hostId);
    }

    // Delivery is the notification service's concern; the rules only report what changed.
    private void notify(AlertTransition transition) {
        if (transition != null && notificationService != null) {
            notificationService.handle(transition);
        }
    }

    private String stableKey(String message) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
