package com.main;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.sql.SQLException;

/** GET /health — unauthenticated, and it probes storage rather than only answering. */
class HealthHandler implements HttpHandler {
    private final EngineContext context;
    private final HttpSupport http;

    HealthHandler(EngineContext context) {
        this.context = context;
        this.http = context.http();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            http.sendResponse(exchange, 405, "Method not allowed");
            return;
        }
        try {
            context.database().checkReachable();
            http.sendJsonResponse(exchange, 200,
                    "{\"status\":\"ok\",\"service\":\"java-analytics\","
                            + "\"message\":\"service is healthy\"}");
        } catch (SQLException exception) {
            context.metrics().recordDatabaseFailure();
            http.sendJsonResponse(exchange, 503,
                    "{\"status\":\"error\",\"service\":\"java-analytics\","
                            + "\"message\":\"database unavailable\"}");
        }
    }
}
