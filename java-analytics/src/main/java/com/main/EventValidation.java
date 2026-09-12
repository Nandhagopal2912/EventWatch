package com.main;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * The ingestion contract, enforced in one place. Every rule here is asserted against
 * {@code testdata/event-contract.json} by both the Java and the Go suites, so a change on one
 * side fails the other.
 */
final class EventValidation {
    static final int MAX_MESSAGE_LENGTH = 1000;
    static final int MAX_IDENTITY_LENGTH = 128;
    private static final Set<String> ALLOWED_LEVELS = Set.of("INFO", "WARN", "ERROR", "CRITICAL");

    private EventValidation() {
    }

    /** Returns the reason the event is unacceptable, or null when it is valid. */
    static String validate(JsonNode json) {
        if (!json.hasNonNull("event_id") || !json.path("event_id").isTextual()
                || json.path("event_id").asText().isBlank() || json.path("event_id").asText().length() > 128) {
            return "event_id must contain 1-128 characters";
        }
        if (!json.hasNonNull("level") || !json.path("level").isTextual()
                || !ALLOWED_LEVELS.contains(json.path("level").asText().toUpperCase(Locale.ROOT))) {
            return "level must be INFO, WARN, ERROR, or CRITICAL";
        }
        if (!json.hasNonNull("msg") || !json.path("msg").isTextual()) {
            return "msg must be a text value";
        }
        String message = json.path("msg").asText();
        if (message.isBlank() || message.length() > MAX_MESSAGE_LENGTH) {
            return "msg must contain 1-1000 characters";
        }
        if (!json.hasNonNull("timestamp") || !json.path("timestamp").isTextual()) {
            return "timestamp must be an ISO-8601 value";
        }
        try {
            Instant.parse(json.path("timestamp").asText());
        } catch (RuntimeException exception) {
            return "timestamp must be an ISO-8601 value";
        }
        if (!isValidPercentage(json, "cpu_usage") || !isValidPercentage(json, "ram_usage")) {
            return "cpu_usage and ram_usage must be numbers between 0 and 100";
        }
        // Identity is optional so an agent older than phase 12 still reports, but bounded
        // when present: these values become alert keys and label values.
        String identityError = validateIdentity(json, "host_id");
        if (identityError == null) {
            identityError = validateIdentity(json, "hostname");
        }
        if (identityError == null) {
            identityError = validateIdentity(json, "agent_version");
        }
        if (identityError != null) {
            return identityError;
        }
        JsonNode queueDepth = json.path("queue_depth");
        if (!queueDepth.isMissingNode() && !queueDepth.isNull()
                && (!queueDepth.isIntegralNumber() || queueDepth.asLong() < 0)) {
            return "queue_depth must be a whole number of zero or more";
        }
        return null;
    }

    private static String validateIdentity(JsonNode json, String fieldName) {
        if (!json.has(fieldName) || json.path(fieldName).isNull()) {
            return null;
        }
        if (!json.path(fieldName).isTextual()) {
            return fieldName + " must be a text value";
        }
        String value = json.path(fieldName).asText();
        if (value.length() > MAX_IDENTITY_LENGTH) {
            return fieldName + " must contain at most " + MAX_IDENTITY_LENGTH + " characters";
        }
        return null;
    }

    static String textOrNull(JsonNode json, String fieldName) {
        JsonNode value = json.path(fieldName);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    static boolean isValidPercentage(JsonNode json, String fieldName) {
        if (!json.hasNonNull(fieldName) || !json.path(fieldName).isNumber()) {
            return false;
        }
        double value = json.path(fieldName).asDouble();
        return Double.isFinite(value) && value >= 0 && value <= 100;
    }

    /** Reads an event into the model, assuming {@link #validate} has already passed. */
    static LogEntry toLogEntry(JsonNode json) {
        JsonNode depthNode = json.path("queue_depth");
        return new LogEntry(
                json.path("event_id").asText(),
                json.path("level").asText().toUpperCase(Locale.ROOT),
                json.path("msg").asText(),
                Instant.parse(json.path("timestamp").asText()),
                textOrNull(json, "host_id"),
                textOrNull(json, "hostname"),
                textOrNull(json, "agent_version"),
                depthNode.isIntegralNumber() ? depthNode.asInt() : null,
                json.path("cpu_usage").asDouble(),
                json.path("ram_usage").asDouble());
    }
}
