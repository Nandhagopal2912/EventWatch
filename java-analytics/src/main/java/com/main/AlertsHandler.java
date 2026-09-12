package com.main;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;

/** GET /alerts — the alert list, optionally filtered by status and type. */
class AlertsHandler extends ApiHandler {
    AlertsHandler(EngineContext context) {
        super(context, "GET", "Alert storage unavailable");
    }

    @Override
    protected void handleRequest(HttpExchange exchange, String method) throws IOException, SQLException {
        Map<String, String> parameters = RequestParameters.parse(exchange.getRequestURI().getRawQuery());
        String status = RequestParameters.optionalUpper(parameters.get("status"));
        String type = parameters.get("type");
        ArrayNode alerts = context.objectMapper().createArrayNode();
        for (AlertRecord alert : context.alerts().find(null, status)) {
            if (type == null || type.equalsIgnoreCase(alert.getAlertType())) {
                alerts.add(context.queries().alertJson(alert));
            }
        }
        http.sendJsonResponse(exchange, 200, alerts.toString());
    }
}
