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
    private final int movingWindowSize;
    private final double cpuThreshold;
    private final double ramThreshold;
    private final int repeatedErrorThreshold;

    public AlertEngine(AlertRepository repository, int movingWindowSize,
            double cpuThreshold, double ramThreshold, int repeatedErrorThreshold) {
        this.repository = repository;
        this.movingWindowSize = movingWindowSize;
        this.cpuThreshold = cpuThreshold;
        this.ramThreshold = ramThreshold;
        this.repeatedErrorThreshold = repeatedErrorThreshold;
    }

    public void evaluate(List<AnalyticsEngine.LogEntry> events) throws SQLException {
        if (events.isEmpty()) {
            return;
        }
        int start = Math.max(0, events.size() - movingWindowSize);
        List<AnalyticsEngine.LogEntry> recentEvents = events.subList(start, events.size());
        Instant now = recentEvents.get(recentEvents.size() - 1).timestamp;

        double averageCpu = recentEvents.stream()
                .mapToDouble(event -> event.cpuUsage)
                .average()
                .orElse(0.0);
        double averageRam = recentEvents.stream()
                .mapToDouble(event -> event.ramUsage)
                .average()
                .orElse(0.0);

        evaluateThreshold("cpu-high", "HIGH_CPU", averageCpu, cpuThreshold,
                "CPU average is %.1f%% (threshold %.1f%%)".formatted(averageCpu, cpuThreshold), now);
        evaluateThreshold("ram-high", "HIGH_RAM", averageRam, ramThreshold,
                "RAM average is %.1f%% (threshold %.1f%%)".formatted(averageRam, ramThreshold), now);

        Map<String, Integer> errorCounts = new HashMap<>();
        for (AnalyticsEngine.LogEntry event : recentEvents) {
            if ("ERROR".equalsIgnoreCase(event.level) || "CRITICAL".equalsIgnoreCase(event.level)) {
                errorCounts.merge(event.message, 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> entry : errorCounts.entrySet()) {
            String alertKey = "repeated-error-" + stableKey(entry.getKey());
            if (entry.getValue() >= repeatedErrorThreshold) {
                repository.saveOccurrence(new AlertRecord(alertKey, "REPEATED_ERROR",
                        entry.getKey() + " occurred " + entry.getValue() + " times", now));
            }
        }
    }

    private void evaluateThreshold(String alertKey, String alertType, double value,
            double threshold, String message, Instant timestamp) throws SQLException {
        if (value >= threshold) {
            repository.saveOccurrence(new AlertRecord(alertKey, alertType, message, timestamp));
        } else {
            repository.resolve(alertKey, timestamp);
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
