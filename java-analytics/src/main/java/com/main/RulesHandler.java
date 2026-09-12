package com.main;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;

/** The alert-rule editor: GET, PUT and DELETE on /rules, plus GET /rules/effective. */
class RulesHandler extends ApiHandler {
    RulesHandler(EngineContext context) {
        super(context, "GET, PUT, DELETE", "Rule storage unavailable", true);
    }

    @Override
    protected void handleRequest(HttpExchange exchange, String method) throws IOException, SQLException {
        String path = exchange.getRequestURI().getPath();
        boolean effectiveRoute = "/rules/effective".equals(path);
        if (!effectiveRoute && !"/rules".equals(path)) {
            http.sendResponse(exchange, 404, "Rule route not found");
            return;
        }
        Map<String, String> parameters = RequestParameters.parse(exchange.getRequestURI().getRawQuery());
        if (effectiveRoute) {
            sendEffectiveRules(exchange, method, parameters);
            return;
        }
        switch (method) {
            case "GET" -> http.sendJsonResponse(exchange, 200, context.queries()
                    .rules(context.alertRules().all(), context.alertRules().defaults()).toString());
            case "PUT" -> save(exchange);
            default -> delete(exchange, parameters);
        }
    }

    private void sendEffectiveRules(HttpExchange exchange, String method, Map<String, String> parameters)
            throws IOException, SQLException {
        if (!"GET".equals(method)) {
            http.sendResponse(exchange, 405, "Method not allowed");
            return;
        }
        String hostId = parameters.get("host_id");
        if (hostId == null || hostId.isBlank()) {
            http.sendResponse(exchange, 400, "host_id is required");
            return;
        }
        http.sendJsonResponse(exchange, 200, context.queries()
                .effectiveRules(hostId, context.alertRules().effectiveFor(hostId)).toString());
    }

    private void save(HttpExchange exchange) throws IOException, SQLException {
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
        JsonNode hostNode = json.path("host_id");
        if (!hostNode.isMissingNode() && !hostNode.isNull() && !hostNode.isTextual()) {
            http.sendResponse(exchange, 400, "host_id must be a text value");
            return;
        }
        if (!json.path("threshold").isNumber()) {
            http.sendResponse(exchange, 400, "threshold must be a number");
            return;
        }
        JsonNode enabledNode = json.path("enabled");
        if (!enabledNode.isMissingNode() && !enabledNode.isBoolean()) {
            http.sendResponse(exchange, 400, "enabled must be true or false");
            return;
        }
        AlertRule saved = context.alertRules().save(
                RequestParameters.optionalUpper(EventValidation.textOrNull(json, "rule_type")),
                hostNode.isTextual() ? hostNode.asText() : null,
                json.path("threshold").asDouble(),
                enabledNode.isMissingNode() || enabledNode.asBoolean());
        StructuredLogger.info("alert rule saved", StructuredLogger.fields(
                "rule_type", saved.ruleType(), "scope", saved.scope(),
                "threshold", saved.threshold(), "enabled", saved.enabled()));
        http.sendJsonResponse(exchange, 200, context.queries().ruleJson(saved).toString());
    }

    private void delete(HttpExchange exchange, Map<String, String> parameters)
            throws IOException, SQLException {
        String hostId = parameters.get("host_id");
        boolean removed = context.alertRules().delete(
                RequestParameters.optionalUpper(parameters.get("rule_type")),
                hostId == null || hostId.isEmpty() ? null : hostId);
        if (removed) {
            http.sendResponse(exchange, 200, "Rule removed");
        } else {
            http.sendResponse(exchange, 404, "No such rule");
        }
    }
}
