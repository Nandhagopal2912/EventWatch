package com.main;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;

/**
 * GET /agents lists issued credentials; POST /agents mints one. Minting is the only place the
 * plaintext token ever exists outside the caller's own record of it — the response is not
 * reproducible, and the operator is told so is nowhere else.
 */
class AgentsHandler extends ApiHandler {
    private static final int MAX_LABEL_LENGTH = 200;

    AgentsHandler(EngineContext context) {
        super(context, "GET, POST", "Agent storage unavailable", true);
    }

    @Override
    protected void handleRequest(HttpExchange exchange, String method) throws IOException, SQLException {
        if ("GET".equals(method)) {
            http.sendJsonResponse(exchange, 200, context.queries().agents(context.agents().findAll()).toString());
            return;
        }
        mint(exchange);
    }

    private void mint(HttpExchange exchange) throws IOException, SQLException {
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
        if (json == null || !json.isObject()) {
            http.sendResponse(exchange, 400, "JSON object required");
            return;
        }

        String hostId = EventValidation.textOrNull(json, "host_id");
        if (hostId == null) {
            http.sendResponse(exchange, 400, "host_id is required");
            return;
        }
        if (hostId.length() > EventValidation.MAX_IDENTITY_LENGTH) {
            http.sendResponse(exchange, 400,
                    "host_id must contain at most " + EventValidation.MAX_IDENTITY_LENGTH + " characters");
            return;
        }
        JsonNode labelNode = json.path("label");
        if (!labelNode.isMissingNode() && !labelNode.isNull() && !labelNode.isTextual()) {
            http.sendResponse(exchange, 400, "label must be a text value");
            return;
        }
        String label = EventValidation.textOrNull(json, "label");
        if (label != null && label.length() > MAX_LABEL_LENGTH) {
            http.sendResponse(exchange, 400, "label must contain at most " + MAX_LABEL_LENGTH + " characters");
            return;
        }

        String token = AgentTokens.generateToken();
        AgentCredential credential = new AgentCredential(
                AgentTokens.generateId(), hostId, label, AgentTokens.hash(token), Instant.now(), null, null);
        context.agents().create(credential);
        context.metrics().recordAgentCredential("minted");
        StructuredLogger.info("agent credential minted", StructuredLogger.fields(
                "agent_id", credential.id(), "host_id", hostId));

        http.sendJsonResponse(exchange, 201, context.queries().agentMinted(credential, token).toString());
    }
}
