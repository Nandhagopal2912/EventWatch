package com.main;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

/** Sends versioned JSON webhook notifications only when the alert policy permits it. */
public class NotificationService {
    private final NotificationRepository repository;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final URI webhookUrl;
    private final int timeoutSeconds;
    private final int maxAttempts;
    private final long retryDelayMillis;
    private final long reminderSeconds;
    private final HttpClient client = HttpClient.newHttpClient();
    private final ConcurrentHashMap<String, Instant> reminderScheduledAt = new ConcurrentHashMap<>();
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "eventwatch-notification-dispatcher");
        thread.setDaemon(true);
        return thread;
    });

    public NotificationService(NotificationRepository repository, ObjectMapper objectMapper,
            boolean enabled, String webhookUrl, int timeoutSeconds, int maxAttempts,
            long retryDelayMillis, long reminderSeconds) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.webhookUrl = parseWebhookUrl(webhookUrl);
        this.timeoutSeconds = timeoutSeconds;
        this.maxAttempts = maxAttempts;
        this.retryDelayMillis = retryDelayMillis;
        this.reminderSeconds = reminderSeconds;
    }

    public void handle(AlertTransition transition) {
        if (!enabled || webhookUrl == null) {
            return;
        }
        try {
            String eventType = eventTypeToDeliver(transition);
            if (eventType != null) {
                dispatcher.submit(() -> deliver(transition.alert(), eventType));
            }
        } catch (SQLException exception) {
            System.err.println("Unable to evaluate notification policy: " + exception.getMessage());
        }
    }

    private String eventTypeToDeliver(AlertTransition transition) throws SQLException {
        return switch (transition.type()) {
            case OPENED, REOPENED, ACKNOWLEDGED, RESOLVED -> transition.type().eventType();
            case OCCURRENCE -> reminderIsDue(transition.alert()) ? "alert.reminder" : null;
        };
    }

    private synchronized boolean reminderIsDue(AlertRecord alert) throws SQLException {
        if (reminderSeconds <= 0) {
            return false;
        }
        Instant lastDelivery = repository.lastDeliveredAt(alert.getAlertKey());
        Instant scheduled = reminderScheduledAt.get(alert.getAlertKey());
        Instant mostRecent = lastDelivery;
        if (scheduled != null && (mostRecent == null || scheduled.isAfter(mostRecent))) {
            mostRecent = scheduled;
        }
        Instant now = Instant.now();
        if (mostRecent == null || mostRecent.plusSeconds(reminderSeconds).isAfter(now)) {
            return false;
        }
        // Reserve the interval before dispatching so concurrent occurrences cannot create a burst.
        reminderScheduledAt.put(alert.getAlertKey(), now);
        return true;
    }

    private void deliver(AlertRecord alert, String eventType) {
        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(payload(alert, eventType));
        } catch (IOException exception) {
            recordFailure(alert.getAlertKey(), eventType, null, describe(exception), 1);
            return;
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Integer status = null;
            String failure = null;
            try {
                HttpRequest request = HttpRequest.newBuilder(webhookUrl)
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "EventWatch/1.0")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                        .build();
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                status = response.statusCode();
                if (status >= 200 && status < 300) {
                    repository.record(new NotificationRecord(alert.getAlertKey(), eventType,
                            "DELIVERED", status, null, attempt, Instant.now()));
                    return;
                }
                failure = "Webhook returned HTTP " + status;
            } catch (IOException exception) {
                failure = "Webhook request failed: " + describe(exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                failure = "Webhook dispatch interrupted";
            } catch (SQLException exception) {
                System.err.println("Unable to record notification delivery: " + exception.getMessage());
                return;
            }
            recordFailure(alert.getAlertKey(), eventType, status, failure, attempt);
            // A malformed/unauthorized webhook request will not succeed by retrying.
            if (status != null && status >= 400 && status < 500 && status != 429) {
                return;
            }
            if (attempt < maxAttempts && !pauseBeforeRetry()) {
                return;
            }
        }
    }

    private ObjectNode payload(AlertRecord alert, String eventType) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("schema_version", "eventwatch.notification.v1");
        envelope.put("event_type", eventType);
        envelope.put("occurred_at", Instant.now().toString());
        ObjectNode source = envelope.putObject("source");
        source.put("service", "eventwatch-analytics");
        source.put("channel", "webhook");
        ObjectNode alertNode = envelope.putObject("alert");
        alertNode.put("alert_key", alert.getAlertKey());
        alertNode.put("alert_type", alert.getAlertType());
        alertNode.put("status", alert.getStatus().name());
        alertNode.put("message", alert.getMessage());
        alertNode.put("first_seen", alert.getFirstSeen().toString());
        alertNode.put("last_seen", alert.getLastSeen().toString());
        alertNode.put("occurrence_count", alert.getOccurrenceCount());
        return envelope;
    }

    private void recordFailure(String alertKey, String eventType, Integer status, String message, int attempt) {
        try {
            repository.record(new NotificationRecord(alertKey, eventType, "FAILED", status,
                    message == null ? "Unknown delivery failure" : message, attempt, Instant.now()));
        } catch (SQLException exception) {
            System.err.println("Unable to record notification failure: " + exception.getMessage());
        }
    }

    // Connection failures often carry no message, so fall back to the exception type.
    private static String describe(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private boolean pauseBeforeRetry() {
        try {
            Thread.sleep(retryDelayMillis);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void shutdown() {
        dispatcher.shutdown();
        try {
            if (!dispatcher.awaitTermination(5, TimeUnit.SECONDS)) {
                dispatcher.shutdownNow();
            }
        } catch (InterruptedException exception) {
            dispatcher.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static URI parseWebhookUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null ? uri : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
