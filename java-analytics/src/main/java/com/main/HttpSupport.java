package com.main;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Everything the route handlers share about speaking HTTP: authentication, the CORS policy, the
 * response envelope, and the correlation id that ties a response back to its request.
 *
 * <p>One instance per engine, because the key and the origin allowlist are configuration.
 */
class HttpSupport {
    static final int MAX_REQUEST_BYTES = 64 * 1024;
    static final String API_KEY_HEADER = "X-EventWatch-Key";
    // One API-wide policy. Two copies of this string is exactly how the phase 14 preflight bug
    // happened: an edit fixed the first occurrence and left the second advertising less.
    private static final String ALLOWED_METHODS = "GET, POST, PUT, DELETE, OPTIONS";

    // Correlation ids are per-request state, so the response helpers read them from here.
    private static final ThreadLocal<String> CORRELATION_ID = new ThreadLocal<>();

    private final ObjectMapper objectMapper;
    private final Metrics metrics;
    private final String apiKey;
    private final List<String> allowedOrigins;

    HttpSupport(ObjectMapper objectMapper, Metrics metrics, String apiKey, List<String> allowedOrigins) {
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.apiKey = apiKey;
        this.allowedOrigins = allowedOrigins;
    }

    static void setCorrelationId(String correlationId) {
        CORRELATION_ID.set(correlationId);
    }

    static String correlationId() {
        return CORRELATION_ID.get();
    }

    static void clearCorrelationId() {
        CORRELATION_ID.remove();
    }

    boolean isAuthorized(HttpExchange exchange) {
        return isValidApiKey(exchange.getRequestHeaders().getFirst(API_KEY_HEADER));
    }

    boolean isValidApiKey(String receivedKey) {
        return receivedKey != null && MessageDigest.isEqual(
                apiKey.getBytes(StandardCharsets.UTF_8),
                receivedKey.getBytes(StandardCharsets.UTF_8));
    }

    /** Reads the request body, or returns null when it exceeds {@link #MAX_REQUEST_BYTES}. */
    byte[] readBody(HttpExchange exchange) throws IOException {
        byte[] bodyBytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        return bodyBytes.length > MAX_REQUEST_BYTES ? null : bodyBytes;
    }

    void sendResponse(HttpExchange exchange, int status, String message) throws IOException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("status", status >= 400 ? "error" : "ok");
        body.put("message", message);
        String correlationId = CORRELATION_ID.get();
        if (correlationId != null && !correlationId.isBlank()) {
            body.put("correlation_id", correlationId);
        }
        write(exchange, status, objectMapper.writeValueAsBytes(body));
    }

    void sendJsonResponse(HttpExchange exchange, int status, String response) throws IOException {
        write(exchange, status, response.getBytes(StandardCharsets.UTF_8));
    }

    private void write(HttpExchange exchange, int status, byte[] responseBytes) throws IOException {
        recordResponse(exchange, status);
        addCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, responseBytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(responseBytes);
        }
    }

    // Routes are counted by their registered context path so the label set stays bounded.
    private void recordResponse(HttpExchange exchange, int status) {
        metrics.recordHttpRequest(exchange.getHttpContext().getPath(), status);
        String correlationId = CORRELATION_ID.get();
        if (correlationId != null && !correlationId.isBlank()) {
            exchange.getResponseHeaders().set("X-Correlation-ID", correlationId);
        }
    }

    void addCorsHeaders(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        // The allowlist is configuration; a hardcoded origin made the dashboard undeployable.
        if (origin != null && allowedOrigins.contains(origin)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, " + API_KEY_HEADER);
    }

    boolean handleCorsPreflight(HttpExchange exchange) throws IOException {
        if (!"OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            return false;
        }
        addCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", ALLOWED_METHODS);
        exchange.sendResponseHeaders(204, -1);
        return true;
    }
}
