package com.main;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Everything the route handlers share about speaking HTTP: authentication, the response
 * envelope, the session cookie, and the correlation id that ties a response back to its request.
 *
 * <p>One instance per engine, because the key and the session store belong to that engine.
 */
class HttpSupport {
    static final int MAX_REQUEST_BYTES = 64 * 1024;
    static final String API_KEY_HEADER = "X-EventWatch-Key";
    static final String SESSION_COOKIE = "eventwatch_session";

    // Correlation ids are per-request state, so the response helpers read them from here.
    private static final ThreadLocal<String> CORRELATION_ID = new ThreadLocal<>();

    private final ObjectMapper objectMapper;
    private final Metrics metrics;
    private final String apiKey;
    private final SessionStore sessions;
    private final boolean secureCookies;

    HttpSupport(ObjectMapper objectMapper, Metrics metrics, String apiKey, SessionStore sessions,
            boolean secureCookies) {
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.apiKey = apiKey;
        this.sessions = sessions;
        this.secureCookies = secureCookies;
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

    /**
     * Two ways in, for two kinds of caller. An agent or a script presents the shared key on every
     * request; a browser presents a session cookie, so the key never has to live in page memory.
     */
    boolean isAuthorized(HttpExchange exchange) {
        if (isValidApiKey(exchange.getRequestHeaders().getFirst(API_KEY_HEADER))) {
            return true;
        }
        return sessions.isValid(cookie(exchange, SESSION_COOKIE), Instant.now());
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

    static String cookie(HttpExchange exchange, String name) {
        for (String header : exchange.getRequestHeaders().getOrDefault("Cookie", java.util.List.of())) {
            for (String pair : header.split(";")) {
                int equals = pair.indexOf('=');
                if (equals > 0 && pair.substring(0, equals).trim().equals(name)) {
                    return pair.substring(equals + 1).trim();
                }
            }
        }
        return null;
    }

    /**
     * HttpOnly keeps the token out of reach of any script on the page, so an XSS hole cannot read
     * it. SameSite=Strict means a request from another site never carries it, which is what stands
     * in for a CSRF token here. Secure is set whenever the listener is TLS.
     */
    void setSessionCookie(HttpExchange exchange, String token) {
        exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=" + token
                + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + sessions.secondsToLive()
                + (secureCookies ? "; Secure" : ""));
    }

    void clearSessionCookie(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE
                + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"
                + (secureCookies ? "; Secure" : ""));
    }

    void sendResponse(HttpExchange exchange, int status, String message) throws IOException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("status", status >= 400 ? "error" : "ok");
        body.put("message", message);
        String correlationId = CORRELATION_ID.get();
        if (correlationId != null && !correlationId.isBlank()) {
            body.put("correlation_id", correlationId);
        }
        write(exchange, status, objectMapper.writeValueAsBytes(body), "application/json; charset=UTF-8");
    }

    void sendJsonResponse(HttpExchange exchange, int status, String response) throws IOException {
        write(exchange, status, response.getBytes(StandardCharsets.UTF_8), "application/json; charset=UTF-8");
    }

    void sendBytes(HttpExchange exchange, int status, byte[] body, String contentType) throws IOException {
        write(exchange, status, body, contentType);
    }

    private void write(HttpExchange exchange, int status, byte[] responseBytes, String contentType)
            throws IOException {
        recordResponse(exchange, status);
        exchange.getResponseHeaders().set("Content-Type", contentType);
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
}
