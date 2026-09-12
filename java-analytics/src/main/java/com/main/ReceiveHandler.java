package com.main;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * POST /receive — the ingestion route. Every rejection is counted by reason, and the checks run
 * cheapest first: an unauthenticated or rate-limited caller never reaches the parser.
 *
 * <p>Unlike every other authenticated route, this one does not accept a session cookie — a
 * browser signed in to the dashboard has no reason to submit synthetic telemetry, and narrowing
 * what a stolen session can do costs nothing here.
 */
class ReceiveHandler implements HttpHandler {
    /** last_used_at is accurate to this window, not to the request: a write per event would not
     *  tell an operator anything a five-minute resolution does not, and it would not be free. */
    private static final Duration LAST_USED_THROTTLE = Duration.ofMinutes(5);

    private final EngineContext context;
    private final HttpSupport http;

    ReceiveHandler(EngineContext context) {
        this.context = context;
        this.http = context.http();
    }

    /** Which credential authorized the request, and the host it may report as, if bound. */
    private record Authorization(String method, String boundHostId, String agentId) {
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
            Authorization authorization;
            try {
                authorization = authorize(exchange);
            } catch (SQLException exception) {
                metrics.recordDatabaseFailure();
                http.sendResponse(exchange, 503, "Database unavailable");
                return;
            }
            if (authorization == null) {
                metrics.recordEventRejected("unauthorized");
                http.sendResponse(exchange, 401, "Unauthorized");
                return;
            }
            metrics.recordReceiveAuth(authorization.method());

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
            if (authorization.boundHostId() != null) {
                // The token, not the payload, is the source of truth for identity: a per-agent
                // credential can only ever report as the host it was minted for. This is what
                // actually stops one agent from spoofing another's host_id, which the shared key
                // never prevented.
                String claimedHostId = EventValidation.textOrNull(json, "host_id");
                if (claimedHostId != null && !claimedHostId.equals(authorization.boundHostId())) {
                    StructuredLogger.warn("agent token host mismatch, using the bound host",
                            StructuredLogger.fields("correlation_id", correlationId,
                                    "claimed_host_id", claimedHostId, "bound_host_id", authorization.boundHostId(),
                                    "agent_id", authorization.agentId()));
                }
                event.hostId = authorization.boundHostId();
                try {
                    context.agents().touch(authorization.agentId(), Instant.now(), LAST_USED_THROTTLE);
                } catch (SQLException exception) {
                    // Bookkeeping, not the ingestion path itself: never fail an event over this.
                    StructuredLogger.warn("could not record agent token use", StructuredLogger.fields(
                            "agent_id", authorization.agentId(), "error", exception.getMessage()));
                }
            }
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

    /**
     * Resolves the presented {@code X-EventWatch-Key} as either the fleet-wide shared key or a
     * per-agent token, or returns null when it is neither. The two are tried in the same
     * comparison shape on purpose: a revoked or unknown token must fail exactly as a wrong shared
     * key does, so a caller cannot tell which kind of credential it guessed wrong.
     */
    private Authorization authorize(HttpExchange exchange) throws SQLException {
        String presented = exchange.getRequestHeaders().getFirst(HttpSupport.API_KEY_HEADER);
        if (presented == null || presented.isBlank()) {
            return null;
        }
        if (context.configuration().sharedKeyIngestionEnabled() && http.isValidApiKey(presented)) {
            return new Authorization("shared_key", null, null);
        }
        return context.agents().findByTokenHash(AgentTokens.hash(presented))
                .filter(credential -> !credential.revoked())
                .map(credential -> new Authorization("agent_token", credential.hostId(), credential.id()))
                .orElse(null);
    }
}
