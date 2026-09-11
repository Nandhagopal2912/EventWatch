package com.main;

import com.sun.net.httpserver.*;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.sql.*;
import java.security.MessageDigest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.cdimascio.dotenv.Dotenv;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

public class AnalyticsEngine {
    private static final int MOVING_AVERAGE_WINDOW = 5;
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final int MAX_MESSAGE_LENGTH = 1000;
    private static final int MAX_REQUESTS_PER_MINUTE = 100;
    private static final int WORKER_THREADS = 8;
    private static final int WORK_QUEUE_CAPACITY = 500;
    private static final int TOP_ERROR_MESSAGES = 5;
    private static final int NOTIFICATION_HISTORY_LIMIT = 50;
    private static final long RATE_WINDOW_SWEEP_SECONDS = 60;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Metrics METRICS = new Metrics();
    // Correlation ids are per-request state, so the response helpers read them from here.
    private static final ThreadLocal<String> CORRELATION_ID = new ThreadLocal<>();
    private static String apiKey;
    private static boolean textLogging;
    private static String databaseUrl;
    private static int shutdownGraceSeconds = 5;
    private static ThreadPoolExecutor runningExecutor;
    private static ScheduledExecutorService runningMaintenance;
    private static AlertRepository alertRepository;
    private static AlertEngine alertEngine;
    private static QueryService queryService;
    private static EventRepository eventRepository;
    private static NotificationRepository notificationRepository;
    private static NotificationService notificationService;

    // Only the newest events are mirrored in memory; the full history stays in SQLite.
    private static final Deque<LogEntry> recentEvents = new ArrayDeque<>();
    private static final AtomicLong storedEventCount = new AtomicLong();
    private static final Map<String, RateWindow> rateWindows = new ConcurrentHashMap<>();

    private static class RateWindow {
        long startedAt = System.currentTimeMillis();
        int requestCount;

        synchronized boolean allow() {
            long now = System.currentTimeMillis();
            if (now - startedAt >= 60_000) {
                startedAt = now;
                requestCount = 0;
            }
            if (requestCount >= MAX_REQUESTS_PER_MINUTE) {
                return false;
            }
            requestCount++;
            return true;
        }

        synchronized boolean isExpired(long now) {
            return now - startedAt >= 60_000;
        }
    }

    static class LogEntry {
        String level;
        String message;
        String eventId;
        Instant timestamp;
        double cpuUsage;
        double ramUsage;

        LogEntry(String eventId, String level, String message, Instant timestamp, double cpuUsage, double ramUsage) {
            this.eventId = eventId;
            this.level = level;
            this.message = message;
            this.timestamp = timestamp;
            this.cpuUsage = cpuUsage;
            this.ramUsage = ramUsage;
        }
    }

    public static void main(String[] args) throws IOException {
        // Load the shared secret before opening the ingestion endpoint.
        Dotenv dotenv = Dotenv.configure()
                .directory("..")
                .ignoreIfMissing()
                .load();
        EngineConfiguration configuration = EngineConfiguration.fromDotenv(dotenv);
        HttpServer server = start(configuration);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            StructuredLogger.info("shutting down analytics engine", StructuredLogger.fields());
            stop(server);
        }));
    }

    /**
     * Starts the HTTP server and every collaborator it needs. Returns the running server so
     * {@code main} can register a shutdown hook and tests can stop it deterministically.
     */
    public static HttpServer start(EngineConfiguration configuration) throws IOException {
        textLogging = "text".equalsIgnoreCase(configuration.logFormat());
        StructuredLogger.configure(OBJECT_MAPPER, configuration.logFormat());
        apiKey = configuration.apiKey();
        databaseUrl = configuration.databaseUrl();
        shutdownGraceSeconds = configuration.shutdownGraceSeconds();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("EVENTWATCH_API_KEY is required");
        }

        recentEvents.clear();
        storedEventCount.set(0);
        rateWindows.clear();

        try {
            eventRepository = new EventRepository(configuration.databaseUrl());
            eventRepository.initializeSchema();
            loadRecentEvents();
            alertRepository = new AlertRepository(configuration.databaseUrl());
            notificationRepository = new NotificationRepository(configuration.databaseUrl());
            notificationService = new NotificationService(
                    notificationRepository,
                    OBJECT_MAPPER,
                    METRICS,
                    configuration.notificationsEnabled(),
                    configuration.notificationWebhookUrl(),
                    configuration.notificationTimeoutSeconds(),
                    configuration.notificationMaxAttempts(),
                    configuration.notificationRetryDelayMillis(),
                    configuration.notificationReminderSeconds());
            alertEngine = new AlertEngine(
                    alertRepository,
                    notificationService,
                    MOVING_AVERAGE_WINDOW,
                    configuration.cpuThreshold(),
                    configuration.ramThreshold(),
                    configuration.repeatedErrorThreshold());
            queryService = new QueryService(
                    eventRepository, alertRepository, OBJECT_MAPPER, MOVING_AVERAGE_WINDOW);
        } catch (SQLException exception) {
            throw new IOException("Unable to initialize SQLite database", exception);
        }
        StructuredLogger.info("loaded stored events",
                StructuredLogger.fields("stored_events", storedEventCount.get()));
        HttpServer server = HttpServer.create(new InetSocketAddress(configuration.port()), 0);

        server.createContext("/receive", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                long startedAt = System.nanoTime();
                // Honour the collector's correlation id so one event is traceable across services.
                String correlationId = exchange.getRequestHeaders().getFirst("X-Correlation-ID");
                CORRELATION_ID.set(correlationId);
                try {
                    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                        exchange.getResponseHeaders().set("Allow", "POST");
                        METRICS.recordEventRejected("method_not_allowed");
                        sendResponse(exchange, 405, "Method not allowed");
                        return;
                    }

                    // Authenticate and reject abusive requests before parsing or storing data.
                    String receivedKey = exchange.getRequestHeaders().getFirst("X-EventWatch-Key");
                    if (!isValidApiKey(receivedKey)) {
                        METRICS.recordEventRejected("unauthorized");
                        sendResponse(exchange, 401, "Unauthorized");
                        return;
                    }
                    String clientAddress = exchange.getRemoteAddress().getAddress().getHostAddress();
                    if (!rateWindows.computeIfAbsent(clientAddress, key -> new RateWindow()).allow()) {
                        METRICS.recordEventRejected("rate_limited");
                        sendResponse(exchange, 429, "Rate limit exceeded");
                        return;
                    }

                    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                    if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                        METRICS.recordEventRejected("content_type");
                        sendResponse(exchange, 415, "Content-Type must be application/json");
                        return;
                    }

                    byte[] bodyBytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
                    if (bodyBytes.length > MAX_REQUEST_BYTES) {
                        METRICS.recordEventRejected("too_large");
                        sendResponse(exchange, 413, "Request body too large");
                        return;
                    }
                    String body = new String(bodyBytes, StandardCharsets.UTF_8);

                    JsonNode json;
                    try {
                        json = OBJECT_MAPPER.readTree(body);
                    } catch (JsonProcessingException exception) {
                        METRICS.recordEventRejected("invalid_json");
                        sendResponse(exchange, 400, "Invalid JSON");
                        return;
                    }
                    if (json == null || !json.isObject()) {
                        METRICS.recordEventRejected("invalid_json");
                        sendResponse(exchange, 400, "JSON object required");
                        return;
                    }

                    // A queued event carries its correlation id in the payload, not the header.
                    if (correlationId == null || correlationId.isBlank()) {
                        correlationId = json.path("correlation_id").asText(null);
                        CORRELATION_ID.set(correlationId);
                    }

                    String validationError = validateEvent(json);
                    if (validationError != null) {
                        METRICS.recordEventRejected("validation");
                        StructuredLogger.warn("event rejected", StructuredLogger.fields(
                                "correlation_id", correlationId, "reason", validationError));
                        sendResponse(exchange, 400, validationError);
                        return;
                    }

                    String eventId = json.path("event_id").asText();
                    String level = json.path("level").asText().toUpperCase(Locale.ROOT);
                    String msg = json.path("msg").asText();
                    Instant timestamp = Instant.parse(json.path("timestamp").asText());
                    double cpuUsage = json.path("cpu_usage").asDouble();
                    double ramUsage = json.path("ram_usage").asDouble();

                    boolean stored;
                    try {
                        stored = storeEvent(new LogEntry(eventId, level, msg, timestamp, cpuUsage, ramUsage));
                        alertEngine.evaluate(recentEventsSnapshot());
                    } catch (SQLException exception) {
                        METRICS.recordDatabaseFailure();
                        StructuredLogger.error("database unavailable", StructuredLogger.fields(
                                "correlation_id", correlationId, "event_id", eventId,
                                "error", exception.getMessage()));
                        sendResponse(exchange, 503, "Database unavailable");
                        return;
                    }

                    if (stored) {
                        METRICS.recordEventReceived();
                    } else {
                        METRICS.recordEventDuplicate();
                    }
                    StructuredLogger.info(stored ? "event stored" : "duplicate event ignored",
                            StructuredLogger.fields(
                                    "correlation_id", correlationId, "event_id", eventId,
                                    "event_level", level, "cpu_usage", cpuUsage, "ram_usage", ramUsage));

                    generateDashboardReport();

                    sendResponse(exchange, 200, "Log processed successfully");
                } finally {
                    METRICS.observeProcessingDuration((System.nanoTime() - startedAt) / 1_000_000_000.0);
                    CORRELATION_ID.remove();
                }
            }
        });

        server.createContext("/health", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            try (Connection ignored = DriverManager.getConnection(databaseUrl)) {
                sendJsonResponse(exchange, 200,
                        "{\"status\":\"ok\",\"service\":\"java-analytics\","
                                + "\"message\":\"service is healthy\"}");
            } catch (SQLException exception) {
                sendJsonResponse(exchange, 503,
                        "{\"status\":\"error\",\"service\":\"java-analytics\","
                                + "\"message\":\"database unavailable\"}");
            }
        });

        server.createContext("/metrics", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            long activeAlerts = 0;
            try {
                activeAlerts = alertRepository.findActive().size();
            } catch (SQLException exception) {
                METRICS.recordDatabaseFailure();
            }
            byte[] body = METRICS.render(activeAlerts, storedEventCount.get())
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });

        server.createContext("/alerts", exchange -> {
            if (handleCorsPreflight(exchange)) {
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            if (!isValidApiKey(exchange.getRequestHeaders().getFirst("X-EventWatch-Key"))) {
                sendResponse(exchange, 401, "Unauthorized");
                return;
            }
            try {
                Map<String, String> parameters = queryParameters(exchange.getRequestURI().getRawQuery());
                String status = optionalUpper(parameters.get("status"));
                ArrayNode alerts = OBJECT_MAPPER.createArrayNode();
                for (AlertRecord alert : alertRepository.find(null, status)) {
                    String type = parameters.get("type");
                    if (type == null || type.equalsIgnoreCase(alert.getAlertType())) {
                        alerts.add(queryService.alertJson(alert));
                    }
                }
                sendJsonResponse(exchange, 200, alerts.toString());
            } catch (SQLException exception) {
                sendResponse(exchange, 503, "Alert storage unavailable");
            }
        });

        server.createContext("/events", exchange -> {
            if (handleCorsPreflight(exchange)) {
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            if (!isValidApiKey(exchange.getRequestHeaders().getFirst("X-EventWatch-Key"))) {
                sendResponse(exchange, 401, "Unauthorized");
                return;
            }
            try {
                Map<String, String> parameters = queryParameters(exchange.getRequestURI().getRawQuery());
                int limit = boundedInteger(parameters.get("limit"), QueryService.DEFAULT_LIMIT, QueryService.MAX_LIMIT);
                int offset = boundedInteger(parameters.get("offset"), 0, Integer.MAX_VALUE);
                Instant from = optionalInstant(parameters.get("from"));
                Instant to = optionalInstant(parameters.get("to"));
                String level = optionalUpper(parameters.get("level"));
                if (level != null && !Set.of("INFO", "WARN", "ERROR", "CRITICAL").contains(level)) {
                    sendResponse(exchange, 400, "level must be INFO, WARN, ERROR, or CRITICAL");
                    return;
                }
                if (from != null && to != null && from.isAfter(to)) {
                    sendResponse(exchange, 400, "from must be before to");
                    return;
                }
                sendJsonResponse(exchange, 200, queryService.events(level, from, to, limit, offset).toString());
            } catch (IllegalArgumentException exception) {
                sendResponse(exchange, 400, exception.getMessage());
            } catch (SQLException exception) {
                sendResponse(exchange, 503, "Event storage unavailable");
            }
        });

        server.createContext("/summary", exchange -> {
            if (handleCorsPreflight(exchange)) {
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            if (!isValidApiKey(exchange.getRequestHeaders().getFirst("X-EventWatch-Key"))) {
                sendResponse(exchange, 401, "Unauthorized");
                return;
            }
            try {
                sendJsonResponse(exchange, 200, queryService.summary().toString());
            } catch (SQLException exception) {
                sendResponse(exchange, 503, "Summary storage unavailable");
            }
        });

        server.createContext("/alerts/", exchange -> {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                addCorsHeaders(exchange);
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            boolean readRequest = "GET".equalsIgnoreCase(exchange.getRequestMethod());
            if (!readRequest && !"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            if (!isValidApiKey(exchange.getRequestHeaders().getFirst("X-EventWatch-Key"))) {
                sendResponse(exchange, 401, "Unauthorized");
                return;
            }
            String prefix = "/alerts/";
            String path = exchange.getRequestURI().getPath();
            if (!path.startsWith(prefix)) {
                sendResponse(exchange, 404, "Alert route not found");
                return;
            }
            // Alert keys never contain a separator, so the last segment is the action.
            String remainder = path.substring(prefix.length());
            int separator = remainder.lastIndexOf('/');
            String alertKey = separator < 0 ? remainder : remainder.substring(0, separator);
            String action = separator < 0 ? "" : remainder.substring(separator + 1);
            if (alertKey.isBlank()) {
                sendResponse(exchange, 404, "Alert route not found");
                return;
            }
            try {
                if (readRequest) {
                    if ("notifications".equals(action)) {
                        Map<String, String> parameters = queryParameters(exchange.getRequestURI().getRawQuery());
                        int limit = boundedInteger(parameters.get("limit"),
                                NOTIFICATION_HISTORY_LIMIT, QueryService.MAX_LIMIT);
                        sendJsonResponse(exchange, 200, queryService
                                .notifications(notificationRepository.findByAlertKey(alertKey, limit)).toString());
                        return;
                    }
                    if (!action.isEmpty()) {
                        sendResponse(exchange, 404, "Alert route not found");
                        return;
                    }
                    AlertRecord alert = alertRepository.findByKey(alertKey);
                    if (alert == null) {
                        sendResponse(exchange, 404, "Alert not found");
                    } else {
                        sendJsonResponse(exchange, 200, queryService.alertJson(alert).toString());
                    }
                    return;
                }
                AlertTransition transition;
                String outcome;
                if ("acknowledge".equals(action)) {
                    transition = alertRepository.acknowledge(alertKey);
                    outcome = "acknowledged";
                } else if ("resolve".equals(action)) {
                    transition = alertRepository.resolve(alertKey, Instant.now());
                    outcome = "resolved";
                } else {
                    sendResponse(exchange, 404, "Alert route not found");
                    return;
                }
                if (transition == null) {
                    sendResponse(exchange, 404, "Alert not found or already " + outcome);
                    return;
                }
                // Operator actions are lifecycle changes and notify like engine transitions.
                notificationService.handle(transition);
                sendResponse(exchange, 200, "Alert " + outcome);
            } catch (IllegalArgumentException exception) {
                sendResponse(exchange, 400, exception.getMessage());
            } catch (SQLException exception) {
                sendResponse(exchange, 503, "Alert storage unavailable");
            }
        });

        // Bound worker threads and queued requests to apply backpressure during spikes.
        ThreadPoolExecutor requestExecutor = new ThreadPoolExecutor(
                WORKER_THREADS,
                WORKER_THREADS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(WORK_QUEUE_CAPACITY),
                new ThreadPoolExecutor.CallerRunsPolicy());
        server.setExecutor(requestExecutor);

        // Without eviction the rate-limit map grows with every distinct client address.
        ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "eventwatch-maintenance");
            thread.setDaemon(true);
            return thread;
        });
        maintenance.scheduleWithFixedDelay(
                () -> rateWindows.values().removeIf(window -> window.isExpired(System.currentTimeMillis())),
                RATE_WINDOW_SWEEP_SECONDS, RATE_WINDOW_SWEEP_SECONDS, TimeUnit.SECONDS);

        runningExecutor = requestExecutor;
        runningMaintenance = maintenance;

        server.start();
        StructuredLogger.info("analytics engine started", StructuredLogger.fields(
                "port", server.getAddress().getPort(),
                "worker_threads", WORKER_THREADS,
                "queue_capacity", WORK_QUEUE_CAPACITY));
        return server;
    }

    /** Stops new work, drains the request executor, and releases background threads. */
    public static void stop(HttpServer server) {
        server.stop(shutdownGraceSeconds);
        if (runningMaintenance != null) {
            runningMaintenance.shutdownNow();
        }
        if (notificationService != null) {
            notificationService.shutdown();
        }
        if (runningExecutor == null) {
            return;
        }
        runningExecutor.shutdown();
        try {
            if (!runningExecutor.awaitTermination(shutdownGraceSeconds, TimeUnit.SECONDS)) {
                runningExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            runningExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    static Instant parseTimestamp(String value) {
        try {
            return value == null ? Instant.now() : Instant.parse(value);
        } catch (RuntimeException exception) {
            return Instant.now();
        }
    }

    static String validateEvent(JsonNode json) {
        Set<String> allowedLevels = Set.of("INFO", "WARN", "ERROR", "CRITICAL");
        if (!json.hasNonNull("event_id") || !json.path("event_id").isTextual()
                || json.path("event_id").asText().isBlank() || json.path("event_id").asText().length() > 128) {
            return "event_id must contain 1-128 characters";
        }
        if (!json.hasNonNull("level") || !json.path("level").isTextual()
                || !allowedLevels.contains(json.path("level").asText().toUpperCase(Locale.ROOT))) {
            return "level must be INFO, WARN, ERROR, or CRITICAL";
        }
        if (!json.hasNonNull("msg") || !json.path("msg").isTextual()) {
            return "msg must be a text value";
        }
        String message = json.path("msg").asText();
        if (message.isBlank() || message.length() > MAX_MESSAGE_LENGTH) {
            return "msg must contain 1-1000 characters";
        }
        if (!json.hasNonNull("timestamp") || !json.path("timestamp").isTextual()) {
            return "timestamp must be an ISO-8601 value";
        }
        try {
            Instant.parse(json.path("timestamp").asText());
        } catch (RuntimeException exception) {
            return "timestamp must be an ISO-8601 value";
        }
        if (!isValidPercentage(json, "cpu_usage") || !isValidPercentage(json, "ram_usage")) {
            return "cpu_usage and ram_usage must be numbers between 0 and 100";
        }
        return null;
    }

    static boolean isValidPercentage(JsonNode json, String fieldName) {
        if (!json.hasNonNull(fieldName) || !json.path(fieldName).isNumber()) {
            return false;
        }
        double value = json.path(fieldName).asDouble();
        return Double.isFinite(value) && value >= 0 && value <= 100;
    }

    private static void generateDashboardReport() {
        List<LogEntry> window = recentEventsSnapshot();
        double averageCpu = window.stream().mapToDouble(log -> log.cpuUsage).average().orElse(0.0);
        double averageRam = window.stream().mapToDouble(log -> log.ramUsage).average().orElse(0.0);
        // Counting in SQLite keeps the report independent of how much history exists.
        Map<String, Long> errorCounts;
        try {
            errorCounts = eventRepository.topErrorMessages(TOP_ERROR_MESSAGES);
        } catch (SQLException exception) {
            METRICS.recordDatabaseFailure();
            errorCounts = Map.of();
        }

        // The ASCII report would corrupt a JSON log stream, so each format gets its own shape.
        if (!textLogging) {
            StructuredLogger.info("telemetry snapshot", StructuredLogger.fields(
                    "total_events", storedEventCount.get(),
                    "window_size", window.size(),
                    "average_cpu", averageCpu,
                    "average_ram", averageRam,
                    "top_errors", errorCounts));
            return;
        }

        System.out.println("\n================ LIVE CLOUD ALERT DASHBOARD ================");
        System.out.println("Total Logs Processed (All Types): " + storedEventCount.get());
        System.out.printf("Last %d-event average: CPU %.1f%% | RAM %.1f%%%n",
                window.size(), averageCpu, averageRam);
        System.out.println("------------------------------------------------------------");
        if (errorCounts.isEmpty()) {
            System.out.println(" No critical errors detected yet.");
        } else {
            System.out.printf(" Top %d repeated error message(s):%n", errorCounts.size());
            errorCounts.forEach((errorMessage, count) -> System.out
                    .printf(" 🚨 [ERROR] \"%s\" -> occurred %d time(s)\n", errorMessage, count));
        }
        System.out.println("============================================================");

    }

    private static synchronized void loadRecentEvents() throws SQLException {
        // Restore only the analytics window; SQLite remains the source of truth for history.
        storedEventCount.set(eventRepository.count(null, null, null));
        List<LogEntry> newestFirst = eventRepository.recent(MOVING_AVERAGE_WINDOW);
        for (int index = newestFirst.size() - 1; index >= 0; index--) {
            recentEvents.addLast(newestFirst.get(index));
        }
    }

    private static synchronized List<LogEntry> recentEventsSnapshot() {
        return new ArrayList<>(recentEvents);
    }

    private static synchronized boolean storeEvent(LogEntry event) throws SQLException {
        // Commit to SQLite before adding the event to memory, preventing acknowledged
        // data loss.
        boolean inserted = eventRepository.insertIfAbsent(event);
        if (inserted) {
            storedEventCount.incrementAndGet();
            recentEvents.addLast(event);
            while (recentEvents.size() > MOVING_AVERAGE_WINDOW) {
                recentEvents.removeFirst();
            }
        }
        return inserted;
    }

    private static void sendResponse(HttpExchange exchange, int status, String response) throws IOException {
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("status", status >= 400 ? "error" : "ok");
        body.put("message", response);
        String correlationId = CORRELATION_ID.get();
        if (correlationId != null && !correlationId.isBlank()) {
            body.put("correlation_id", correlationId);
        }
        byte[] responseBytes = OBJECT_MAPPER.writeValueAsBytes(body);
        recordResponse(exchange, status);
        addCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, responseBytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(responseBytes);
        }
    }

    private static void sendJsonResponse(HttpExchange exchange, int status, String response) throws IOException {
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        recordResponse(exchange, status);
        addCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, responseBytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(responseBytes);
        }
    }

    // Routes are counted by their registered context path so the label set stays bounded.
    private static void recordResponse(HttpExchange exchange, int status) {
        METRICS.recordHttpRequest(exchange.getHttpContext().getPath(), status);
        String correlationId = CORRELATION_ID.get();
        if (correlationId != null && !correlationId.isBlank()) {
            exchange.getResponseHeaders().set("X-Correlation-ID", correlationId);
        }
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if ("http://localhost:3000".equals(origin) || "http://127.0.0.1:3000".equals(origin)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-EventWatch-Key");
    }

    private static boolean handleCorsPreflight(HttpExchange exchange) throws IOException {
        if (!"OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            return false;
        }
        addCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.sendResponseHeaders(204, -1);
        return true;
    }

    private static boolean isValidApiKey(String receivedKey) {
        return receivedKey != null && MessageDigest.isEqual(
                apiKey.getBytes(StandardCharsets.UTF_8),
                receivedKey.getBytes(StandardCharsets.UTF_8));
    }

    static Map<String, String> queryParameters(String rawQuery) {
        Map<String, String> parameters = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return parameters;
        }
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            parameters.put(key, value);
        }
        return parameters;
    }

    static String optionalUpper(String value) {
        return value == null || value.isBlank() ? null : value.toUpperCase(Locale.ROOT);
    }

    static Instant optionalInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("time filters must be ISO-8601 values");
        }
    }

    static int boundedInteger(String value, int fallback, int maximum) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0 || parsed > maximum || (maximum == QueryService.MAX_LIMIT && parsed == 0)) {
                throw new IllegalArgumentException("query limit or offset is outside the allowed range");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("query limit and offset must be numbers");
        }
    }

}
