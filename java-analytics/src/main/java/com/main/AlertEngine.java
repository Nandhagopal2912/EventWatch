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
    private final AlertRules rules;

    public AlertEngine(AlertRepository repository, NotificationService notificationService,
            int movingWindowSize, AlertRules rules) {
        this.repository = repository;
        this.notificationService = notificationService;
        this.movingWindowSize = movingWindowSize;
        this.rules = rules;
    }

    /**
     * Evaluates one machine's window against the rules that apply to that machine. Alert keys
     * are scoped to the host, so a fleet sharing one analytics service raises one alert per
     * machine instead of fighting over a single row.
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

        // This machine just reported, so it is no longer silent.
        notify(repository.resolve(alertKey(AgentSilenceMonitor.ALERT_RULE, hostId), now));

        evaluateThreshold(alertKey("cpu-high", hostId), rules.effective(AlertRules.HIGH_CPU, hostId),
                hostId, "CPU", averageCpu, now);
        evaluateThreshold(alertKey("ram-high", hostId), rules.effective(AlertRules.HIGH_RAM, hostId),
                hostId, "RAM", averageRam, now);

        AlertRules.EffectiveRule errorRule = rules.effective(AlertRules.REPEATED_ERROR, hostId);
        if (!errorRule.enabled()) {
            return;
        }
        Map<String, Integer> errorCounts = new HashMap<>();
        for (AnalyticsEngine.LogEntry event : recentEvents) {
            if ("ERROR".equalsIgnoreCase(event.level) || "CRITICAL".equalsIgnoreCase(event.level)) {
                errorCounts.merge(event.message, 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> entry : errorCounts.entrySet()) {
            String key = alertKey("repeated-error-" + stableKey(entry.getKey()), hostId);
            if (entry.getValue() >= errorRule.threshold()) {
                notify(repository.saveOccurrence(new AlertRecord(key, AlertRules.REPEATED_ERROR, hostId,
                        entry.getKey() + " occurred " + entry.getValue() + " times", now)));
            }
        }
    }

    private void evaluateThreshold(String alertKey, AlertRules.EffectiveRule rule, String hostId,
            String resource, double value, Instant timestamp) throws SQLException {
        // A disabled rule no longer describes this machine, so an alert it raised is resolved.
        if (rule.enabled() && value >= rule.threshold()) {
            String message = "%s average is %.1f%% (threshold %.1f%%)".formatted(resource, value, rule.threshold());
            notify(repository.saveOccurrence(new AlertRecord(alertKey, rule.ruleType(), hostId, message, timestamp)));
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
