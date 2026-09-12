package com.main;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Locale;

/**
 * POST /receive — the ingestion route. Every rejection is counted by reason, and the checks run
 * cheapest first: an unauthenticated or rate-limited caller never reaches the parser.
 */
class ReceiveHandler implements HttpHandler {
    private final EngineContext context;
    private final HttpSupport http;

    ReceiveHandler(EngineContext context) {
        this.context = context;
        this.http = context.http();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        long startedAt = System.nanoTime();
        // Honour the correlation id from the collector so one event is traceable across services.
        String correlationId = exchange.getRequestHeaders().getFirst("X-Correlation-ID");
        HttpSupport.setCorrelationId(correlationId);
        Metrics metrics = context.metrics();
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                metrics.recordEventRejected("method_not_allowed");
                http.sendResponse(exchange, 405, "Method not allowed");
                return;
            }

            // Authenticate and reject abusive requests before parsing or storing data.
            if (!http.isAuthorized(exchange)) {
                metrics.recordEventRejected("unauthorized");
                http.sendResponse(exchange, 401, "Unauthorized");
                return;
            }
            String clientAddress = exchange.getRemoteAddress().getAddress().getHostAddress();
            if (!context.rateLimiter().allow(clientAddress)) {
                metrics.recordEventRejected("rate_limited");
                http.sendResponse(exchange, 429, "Rate limit exceeded");
                return;
            }

            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                metrics.recordEventRejected("content_type");
                http.sendResponse(exchange, 415, "Content-Type must be application/json");
                return;
            }

            byte[] bodyBytes = http.readBody(exchange);
            if (bodyBytes == null) {
                metrics.recordEventRejected("too_large");
                http.sendResponse(exchange, 413, "Request body too large");
                return;
            }

            JsonNode json;
            try {
                json = context.objectMapper().readTree(new String(bodyBytes, StandardCharsets.UTF_8));
            } catch (JsonProcessingException exception) {
                metrics.recordEventRejected("invalid_json");
                http.sendResponse(exchange, 400, "Invalid JSON");
                return;
            }
            if (json == null || !json.isObject()) {
                metrics.recordEventRejected("invalid_json");
                http.sendResponse(exchange, 400, "JSON object required");
                return;
            }

            // A queued event carries its correlation id in the payload, not the header.
            if (correlationId == null || correlationId.isBlank()) {
                correlationId = json.path("correlation_id").asText(null);
                HttpSupport.setCorrelationId(correlationId);
            }

            String validationError = EventValidation.validate(json);
            if (validationError != null) {
                metrics.recordEventRejected("validation");
                StructuredLogger.warn("event rejected", StructuredLogger.fields(
                        "correlation_id", correlationId, "reason", validationError));
                http.sendResponse(exchange, 400, validationError);
                return;
            }

            LogEntry event = EventValidation.toLogEntry(json);
            boolean stored;
            try {
                stored = context.recentEvents().store(event);
                context.alertEngine().evaluate(context.recentEvents().snapshot(event.hostId));
            } catch (SQLException exception) {
                metrics.recordDatabaseFailure();
                StructuredLogger.error("database unavailable", StructuredLogger.fields(
                        "correlation_id", correlationId, "event_id", event.eventId,
                        "error", exception.getMessage()));
                http.sendResponse(exchange, 503, "Database unavailable");
                return;
            }

            if (stored) {
                metrics.recordEventReceived();
            } else {
                metrics.recordEventDuplicate();
            }
            StructuredLogger.info(stored ? "event stored" : "duplicate event ignored",
                    StructuredLogger.fields(
                            "correlation_id", correlationId, "event_id", event.eventId,
                            "host_id", event.hostId, "hostname", event.hostname,
                            "event_level", event.level, "cpu_usage", event.cpuUsage,
                            "ram_usage", event.ramUsage));

            context.telemetryReport().publish();

            http.sendResponse(exchange, 200, "Log processed successfully");
        } finally {
            metrics.observeProcessingDuration((System.nanoTime() - startedAt) / 1_000_000_000.0);
            HttpSupport.clearCorrelationId();
        }
    }
}
