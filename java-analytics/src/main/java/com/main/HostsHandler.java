package com.main;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** GET /hosts and /hosts/{host_id} — the fleet listing and one machine's drill-down. */
class HostsHandler extends ApiHandler {
    private static final String PREFIX = "/hosts/";

    HostsHandler(EngineContext context) {
        super(context, "GET", "Host storage unavailable");
    }

    @Override
    protected void handleRequest(HttpExchange exchange, String method) throws IOException, SQLException {
        String path = exchange.getRequestURI().getPath();
        Instant now = Instant.now();
        if (path.startsWith(PREFIX)) {
            sendHostDetail(exchange, URLDecoder.decode(
                    path.substring(PREFIX.length()), StandardCharsets.UTF_8), now);
            return;
        }
        Map<String, String> parameters = RequestParameters.parse(exchange.getRequestURI().getRawQuery());
        int limit = RequestParameters.boundedInteger(
                parameters.get("limit"), QueryService.MAX_HOSTS_LISTED, QueryService.MAX_LIMIT);
        http.sendJsonResponse(exchange, 200,
                context.queries().hosts(limit, context.alertRules(), now).toString());
    }

    private void sendHostDetail(HttpExchange exchange, String hostId, Instant now)
            throws IOException, SQLException {
        EventRepository.HostSummary host = hostId.isBlank() ? null : context.events().host(hostId);
        if (host == null) {
            http.sendResponse(exchange, 404, "Host not found");
            return;
        }
        // Few enough alerts to filter here rather than widen the shared alert query.
        List<AlertRecord> hostAlerts = new ArrayList<>();
        for (AlertRecord alert : context.alerts().findActive()) {
            if (hostId.equals(alert.getHostId())) {
                hostAlerts.add(alert);
            }
        }
        http.sendJsonResponse(exchange, 200, context.queries().hostDetail(
                host, context.alertRules(), now,
                context.events().find(null, hostId, null, null, EngineContext.MOVING_AVERAGE_WINDOW, 0),
                context.events().levelCounts(hostId),
                hostAlerts).toString());
    }
}
