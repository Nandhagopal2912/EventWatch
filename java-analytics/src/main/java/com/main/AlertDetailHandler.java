package com.main;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;

/**
 * GET /alerts/{key}, its notification history, and the two operator actions. The key never
 * contains a separator, so the last path segment is the action.
 */
class AlertDetailHandler extends ApiHandler {
    private static final String PREFIX = "/alerts/";
    private static final int NOTIFICATION_HISTORY_LIMIT = 50;

    AlertDetailHandler(EngineContext context) {
        super(context, "GET, POST", "Alert storage unavailable");
    }

    @Override
    protected void handleRequest(HttpExchange exchange, String method) throws IOException, SQLException {
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith(PREFIX)) {
            http.sendResponse(exchange, 404, "Alert route not found");
            return;
        }
        String remainder = path.substring(PREFIX.length());
        int separator = remainder.lastIndexOf('/');
        String alertKey = separator < 0 ? remainder : remainder.substring(0, separator);
        String action = separator < 0 ? "" : remainder.substring(separator + 1);
        if (alertKey.isBlank()) {
            http.sendResponse(exchange, 404, "Alert route not found");
            return;
        }
        if ("GET".equals(method)) {
            read(exchange, alertKey, action);
        } else {
            act(exchange, alertKey, action);
        }
    }

    private void read(HttpExchange exchange, String alertKey, String action)
            throws IOException, SQLException {
        if ("notifications".equals(action)) {
            Map<String, String> parameters = RequestParameters.parse(exchange.getRequestURI().getRawQuery());
            int limit = RequestParameters.boundedInteger(
                    parameters.get("limit"), NOTIFICATION_HISTORY_LIMIT, QueryService.MAX_LIMIT);
            http.sendJsonResponse(exchange, 200, context.queries()
                    .notifications(context.notifications().findByAlertKey(alertKey, limit)).toString());
            return;
        }
        if (!action.isEmpty()) {
            http.sendResponse(exchange, 404, "Alert route not found");
            return;
        }
        AlertRecord alert = context.alerts().findByKey(alertKey);
        if (alert == null) {
            http.sendResponse(exchange, 404, "Alert not found");
        } else {
            http.sendJsonResponse(exchange, 200, context.queries().alertJson(alert).toString());
        }
    }

    private void act(HttpExchange exchange, String alertKey, String action)
            throws IOException, SQLException {
        AlertTransition transition;
        String outcome;
        if ("acknowledge".equals(action)) {
            transition = context.alerts().acknowledge(alertKey);
            outcome = "acknowledged";
        } else if ("resolve".equals(action)) {
            transition = context.alerts().resolve(alertKey, Instant.now());
            outcome = "resolved";
        } else {
            http.sendResponse(exchange, 404, "Alert route not found");
            return;
        }
        if (transition == null) {
            http.sendResponse(exchange, 404, "Alert not found or already " + outcome);
            return;
        }
        // Operator actions are lifecycle changes and notify like engine transitions.
        context.notificationService().handle(transition);
        http.sendResponse(exchange, 200, "Alert " + outcome);
    }
}
