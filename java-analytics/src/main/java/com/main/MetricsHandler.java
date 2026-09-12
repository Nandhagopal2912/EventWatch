package com.main;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

/**
 * GET /metrics in Prometheus text format. It writes its own response rather than using the JSON
 * envelope, so a scrape is not counted as an HTTP request by the metrics it is reporting.
 */
class MetricsHandler implements HttpHandler {
    private final EngineContext context;
    private final HttpSupport http;
    private final boolean requireKey;

    MetricsHandler(EngineContext context, boolean requireKey) {
        this.context = context;
        this.http = context.http();
        this.requireKey = requireKey;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            http.sendResponse(exchange, 405, "Method not allowed");
            return;
        }
        // Scrapers rarely send custom headers, so this is opt-in rather than the default.
        if (requireKey && !http.isAuthorized(exchange)) {
            http.sendResponse(exchange, 401, "Unauthorized");
            return;
        }
        long activeAlerts = 0;
        try {
            activeAlerts = context.alerts().findActive().size();
        } catch (SQLException exception) {
            context.metrics().recordDatabaseFailure();
        }
        byte[] body = context.metrics().render(activeAlerts, context.recentEvents().total())
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
