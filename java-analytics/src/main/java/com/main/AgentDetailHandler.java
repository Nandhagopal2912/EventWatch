package com.main;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;

/** DELETE /agents/{id} revokes a credential. Revoking twice is not an error the caller sees. */
class AgentDetailHandler extends ApiHandler {
    private static final String PREFIX = "/agents/";

    AgentDetailHandler(EngineContext context) {
        super(context, "DELETE", "Agent storage unavailable", true);
    }

    @Override
    protected void handleRequest(HttpExchange exchange, String method) throws IOException, SQLException {
        String path = exchange.getRequestURI().getPath();
        String id = URLDecoder.decode(path.substring(PREFIX.length()), StandardCharsets.UTF_8);
        if (id.isBlank()) {
            http.sendResponse(exchange, 404, "Agent route not found");
            return;
        }
        if (context.agents().revoke(id, Instant.now())) {
            context.metrics().recordAgentCredential("revoked");
            StructuredLogger.info("agent credential revoked", StructuredLogger.fields("agent_id", id));
            http.sendResponse(exchange, 200, "Agent credential revoked");
        } else {
            http.sendResponse(exchange, 404, "No such agent credential");
        }
    }
}
