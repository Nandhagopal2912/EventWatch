package com.main;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.sql.SQLException;

/** GET /summary — totals, active alerts, and the moving-window averages. */
class SummaryHandler extends ApiHandler {
    SummaryHandler(EngineContext context) {
        super(context, "GET", "Summary storage unavailable");
    }

    @Override
    protected void handleRequest(HttpExchange exchange, String method) throws IOException, SQLException {
        http.sendJsonResponse(exchange, 200, context.queries().summary().toString());
    }
}
