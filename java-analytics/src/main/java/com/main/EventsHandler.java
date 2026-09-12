package com.main;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** GET /events — the filtered, paged telemetry query behind the dashboard's event table. */
class EventsHandler extends ApiHandler {
    private static final Set<String> LEVELS = Set.of("INFO", "WARN", "ERROR", "CRITICAL");

    EventsHandler(EngineContext context) {
        super(context, "GET", "Event storage unavailable");
    }

    @Override
    protected void handleRequest(HttpExchange exchange, String method) throws IOException, SQLException {
        Map<String, String> parameters = RequestParameters.parse(exchange.getRequestURI().getRawQuery());
        int limit = RequestParameters.boundedInteger(
                parameters.get("limit"), QueryService.DEFAULT_LIMIT, QueryService.MAX_LIMIT);
        int offset = RequestParameters.boundedInteger(parameters.get("offset"), 0, Integer.MAX_VALUE);
        Instant from = RequestParameters.optionalInstant(parameters.get("from"));
        Instant to = RequestParameters.optionalInstant(parameters.get("to"));
        String level = RequestParameters.optionalUpper(parameters.get("level"));
        String hostId = parameters.get("host_id");
        if (hostId != null && hostId.isBlank()) {
            hostId = null;
        }
        if (level != null && !LEVELS.contains(level)) {
            http.sendResponse(exchange, 400, "level must be INFO, WARN, ERROR, or CRITICAL");
            return;
        }
        if (from != null && to != null && from.isAfter(to)) {
            http.sendResponse(exchange, 400, "from must be before to");
            return;
        }
        http.sendJsonResponse(exchange, 200,
                context.queries().events(level, hostId, from, to, limit, offset).toString());
    }
}
