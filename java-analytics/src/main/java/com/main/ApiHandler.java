package com.main;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Locale;

/**
 * The cross-cutting half of every authenticated read/write route: the CORS preflight, the method
 * check, the API key, and the two failures that every route turns into the same status — a bad
 * parameter into 400 and unreachable storage into 503.
 *
 * <p>Subclasses implement only what their route actually does.
 */
abstract class ApiHandler implements HttpHandler {
    protected final EngineContext context;
    protected final HttpSupport http;
    private final String allowedMethods;
    private final String storageUnavailableMessage;
    private final boolean recordStorageFailure;

    ApiHandler(EngineContext context, String allowedMethods, String storageUnavailableMessage,
            boolean recordStorageFailure) {
        this.context = context;
        this.http = context.http();
        this.allowedMethods = allowedMethods;
        this.storageUnavailableMessage = storageUnavailableMessage;
        this.recordStorageFailure = recordStorageFailure;
    }

    ApiHandler(EngineContext context, String allowedMethods, String storageUnavailableMessage) {
        this(context, allowedMethods, storageUnavailableMessage, false);
    }

    @Override
    public final void handle(HttpExchange exchange) throws IOException {
        if (http.handleCorsPreflight(exchange)) {
            return;
        }
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        if (!isAllowed(method)) {
            exchange.getResponseHeaders().set("Allow", allowedMethods);
            http.sendResponse(exchange, 405, "Method not allowed");
            return;
        }
        if (!http.isAuthorized(exchange)) {
            http.sendResponse(exchange, 401, "Unauthorized");
            return;
        }
        try {
            handleRequest(exchange, method);
        } catch (IllegalArgumentException exception) {
            http.sendResponse(exchange, 400, exception.getMessage());
        } catch (SQLException exception) {
            if (recordStorageFailure) {
                context.metrics().recordDatabaseFailure();
            }
            http.sendResponse(exchange, 503, storageUnavailableMessage);
        }
    }

    private boolean isAllowed(String method) {
        for (String allowed : allowedMethods.split(",")) {
            if (allowed.trim().equals(method)) {
                return true;
            }
        }
        return false;
    }

    protected abstract void handleRequest(HttpExchange exchange, String method)
            throws IOException, SQLException;
}
