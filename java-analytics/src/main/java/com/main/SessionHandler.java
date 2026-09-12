package com.main;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;

/**
 * POST /session exchanges the API key for a session cookie, GET /session reports whether one is
 * already held, and DELETE /session ends it.
 *
 * <p>This is the one route that must accept an unauthenticated request, so it is the one route
 * that can be brute-forced. It has its own rate limit, tighter than ingestion, and it never says
 * anything about a rejected key beyond that it was rejected.
 */
class SessionHandler implements HttpHandler {
    private final EngineContext context;
    private final HttpSupport http;

    SessionHandler(EngineContext context) {
        this.context = context;
        this.http = context.http();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        switch (method) {
            case "GET" -> describe(exchange);
            case "POST" -> signIn(exchange);
            case "DELETE" -> signOut(exchange);
            default -> {
                exchange.getResponseHeaders().set("Allow", "GET, POST, DELETE");
                http.sendResponse(exchange, 405, "Method not allowed");
            }
        }
    }

    /**
     * Answers "am I already signed in?" so a reload can pick up a session the browser still
     * holds. Without it the page would ask for the key again and mint a second token, which is
     * the opposite of what the cookie is for.
     */
    private void describe(HttpExchange exchange) throws IOException {
        if (http.isAuthorized(exchange)) {
            http.sendResponse(exchange, 200, "Signed in");
        } else {
            http.sendResponse(exchange, 401, "Unauthorized");
        }
    }

    private void signIn(HttpExchange exchange) throws IOException {
        String clientAddress = exchange.getRemoteAddress().getAddress().getHostAddress();
        if (!context.sessionRateLimiter().allow(clientAddress)) {
            context.metrics().recordSession("rate_limited");
            http.sendResponse(exchange, 429, "Too many sign-in attempts");
            return;
        }

        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            http.sendResponse(exchange, 415, "Content-Type must be application/json");
            return;
        }
        byte[] bodyBytes = http.readBody(exchange);
        if (bodyBytes == null) {
            http.sendResponse(exchange, 413, "Request body too large");
            return;
        }
        JsonNode json;
        try {
            json = context.objectMapper().readTree(new String(bodyBytes, StandardCharsets.UTF_8));
        } catch (JsonProcessingException exception) {
            http.sendResponse(exchange, 400, "Invalid JSON");
            return;
        }
        String presentedKey = json == null ? null : EventValidation.textOrNull(json, "api_key");
        if (!http.isValidApiKey(presentedKey)) {
            context.metrics().recordSession("rejected");
            // Deliberately the same message and status as any other bad key.
            StructuredLogger.warn("session refused", StructuredLogger.fields("client", clientAddress));
            http.sendResponse(exchange, 401, "Unauthorized");
            return;
        }

        String token = context.sessions().create(Instant.now());
        context.metrics().recordSession("created");
        StructuredLogger.info("session created", StructuredLogger.fields("client", clientAddress));
        http.setSessionCookie(exchange, token);
        http.sendResponse(exchange, 200, "Signed in");
    }

    private void signOut(HttpExchange exchange) throws IOException {
        context.sessions().revoke(HttpSupport.cookie(exchange, HttpSupport.SESSION_COOKIE));
        context.metrics().recordSession("ended");
        // Clearing the cookie regardless keeps sign-out idempotent from the browser's side.
        http.clearSessionCookie(exchange);
        http.sendResponse(exchange, 200, "Signed out");
    }
}
