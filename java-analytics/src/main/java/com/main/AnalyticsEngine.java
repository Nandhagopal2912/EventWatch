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
    private static final String DATABASE_URL = "jdbc:sqlite:events.db";
    private static final String DATABASE_UNIQUE_INDEX = "idx_telemetry_events_event_id";
    private static final int TOP_ERROR_MESSAGES = 5;
    private static final int NOTIFICATION_HISTORY_LIMIT = 50;
    private static final long RATE_WINDOW_SWEEP_SECONDS = 60;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static String apiKey;
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
        apiKey = dotenv.get("EVENTWATCH_API_KEY", System.getenv("EVENTWATCH_API_KEY"));
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("EVENTWATCH_API_KEY is required");
        }

        try {
            initializeDatabase();
            eventRepository = new EventRepository(DATABASE_URL);
            loadRecentEvents();
            alertRepository = new AlertRepository(DATABASE_URL);
            notificationRepository = new NotificationRepository(DATABASE_URL);
            notificationService = new NotificationService(
                    notificationRepository,
                    OBJECT_MAPPER,
                    Boolean.parseBoolean(getConfig(dotenv, "NOTIFICATIONS_ENABLED", "false")),
                    getConfig(dotenv, "NOTIFICATION_WEBHOOK_URL", ""),
                    getIntConfig(dotenv, "NOTIFICATION_TIMEOUT_SECONDS", 5),
                    getIntConfig(dotenv, "NOTIFICATION_MAX_ATTEMPTS", 3),
                    getIntConfig(dotenv, "NOTIFICATION_RETRY_DELAY_MILLIS", 1000),
                    getIntConfig(dotenv, "NOTIFICATION_REMINDER_SECONDS", 900));
            alertEngine = new AlertEngine(
                    alertRepository,
                    notificationService,
                    MOVING_AVERAGE_WINDOW,
                    getDoubleConfig(dotenv, "CPU_ALERT_THRESHOLD", 85.0),
                    getDoubleConfig(dotenv, "RAM_ALERT_THRESHOLD", 80.0),
                    getIntConfig(dotenv, "REPEATED_ERROR_THRESHOLD", 5));
            queryService = new QueryService(
                    eventRepository, alertRepository, OBJECT_MAPPER, MOVING_AVERAGE_WINDOW);
        } catch (SQLException exception) {
            throw new IOException("Unable to initialize SQLite database", exception);
        }
        System.out.println("Loaded " + storedEventCount.get() + " stored events.");
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/receive", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    // Authenticate and reject abusive requests before parsing or storing data.
                    String receivedKey = exchange.getRequestHeaders().getFirst("X-EventWatch-Key");
                    if (!isValidApiKey(receivedKey)) {
                        sendResponse(exchange, 401, "Unauthorized");
                        return;
                    }
                    String clientAddress = exchange.getRemoteAddress().getAddress().getHostAddress();
                    if (!rateWindows.computeIfAbsent(clientAddress, key -> new RateWindow()).allow()) {
                        sendResponse(exchange, 429, "Rate limit exceeded");
                        return;
                    }

                    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                    if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                        sendResponse(exchange, 415, "Content-Type must be application/json");
                        return;
                    }

                    byte[] bodyBytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
                    if (bodyBytes.length > MAX_REQUEST_BYTES) {
                        sendResponse(exchange, 413, "Request body too large");
                        return;
                    }
                    String body = new String(bodyBytes, StandardCharsets.UTF_8);

                    JsonNode json;
                    try {
                        json = OBJECT_MAPPER.readTree(body);
                    } catch (JsonProcessingException exception) {
                        sendResponse(exchange, 400, "Invalid JSON");
                        return;
                    }
                    if (json == null || !json.isObject()) {
                        sendResponse(exchange, 400, "JSON object required");
                        return;
                    }

                    String validationError = validateEvent(json);
                    if (validationError != null) {
                        sendResponse(exchange, 400, validationError);
                        return;
                    }

                    String eventId = json.path("event_id").asText();
                    String level = json.path("level").asText().toUpperCase(Locale.ROOT);
                    String msg = json.path("msg").asText();
                    Instant timestamp = Instant.parse(json.path("timestamp").asText());
                    double cpuUsage = json.path("cpu_usage").asDouble();
                    double ramUsage = json.path("ram_usage").asDouble();

                    try {
                        storeEvent(new LogEntry(eventId, level, msg, timestamp, cpuUsage, ramUsage));
                        alertEngine.evaluate(recentEventsSnapshot());
                    } catch (SQLException exception) {
                        sendResponse(exchange, 503, "Database unavailable");
                        return;
                    }

                    generateDashboardReport();

                    sendResponse(exchange, 200, "Log processed successfully");
                }

                else {
                    exchange.getResponseHeaders().set("Allow", "POST");
                    sendResponse(exchange, 405, "Method not allowed");

                }
            }
        });

        server.createContext("/health", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            try (Connection ignored = DriverManager.getConnection(DATABASE_URL)) {
                sendJsonResponse(exchange, 200,
                        "{\"status\":\"ok\",\"service\":\"java-analytics\","
                                + "\"message\":\"service is healthy\"}");
            } catch (SQLException exception) {
                sendJsonResponse(exchange, 503,
                        "{\"status\":\"error\",\"service\":\"java-analytics\","
                                + "\"message\":\"database unavailable\"}");
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

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down Java analytics engine...");
            server.stop(5);
            maintenance.shutdownNow();
            notificationService.shutdown();
            requestExecutor.shutdown();
            try {
                if (!requestExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    requestExecutor.shutdownNow();
                }
            } catch (InterruptedException exception) {
                requestExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }));

        System.out.println("Waiting for logs....(Test with one or two entries first)\n");

        server.start();
    }

    private static Instant parseTimestamp(String value) {
        try {
            return value == null ? Instant.now() : Instant.parse(value);
        } catch (RuntimeException exception) {
            return Instant.now();
        }
    }

    private static String validateEvent(JsonNode json) {
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

    private static boolean isValidPercentage(JsonNode json, String fieldName) {
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
            errorCounts = Map.of();
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

    private static void initializeDatabase() throws SQLException {
        // Create the schema on first startup so no manual database setup is required.
        try (Connection connection = DriverManager.getConnection(DATABASE_URL);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS telemetry_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        event_id TEXT,
                        level TEXT NOT NULL,
                        message TEXT NOT NULL,
                        event_timestamp TEXT NOT NULL,
                        cpu_usage REAL NOT NULL,
                        ram_usage REAL NOT NULL,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            try {
                statement.executeUpdate("ALTER TABLE telemetry_events ADD COLUMN event_id TEXT");
            } catch (SQLException exception) {
                if (!exception.getMessage().toLowerCase(Locale.ROOT).contains("duplicate column")) {
                    throw exception;
                }
            }
            statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS " + DATABASE_UNIQUE_INDEX
                    + " ON telemetry_events(event_id) WHERE event_id IS NOT NULL");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_telemetry_events_level "
                    + "ON telemetry_events(level, event_timestamp)");
        }
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

    private static synchronized void storeEvent(LogEntry event) throws SQLException {
        // Commit to SQLite before adding the event to memory, preventing acknowledged
        // data loss.
        String query = "INSERT OR IGNORE INTO telemetry_events "
                + "(event_id, level, message, event_timestamp, cpu_usage, ram_usage) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection connection = DriverManager.getConnection(DATABASE_URL);
                PreparedStatement statement = connection.prepareStatement(query)) {
            connection.setAutoCommit(false);
            statement.setString(1, event.eventId);
            statement.setString(2, event.level);
            statement.setString(3, event.message);
            statement.setString(4, event.timestamp.toString());
            statement.setDouble(5, event.cpuUsage);
            statement.setDouble(6, event.ramUsage);
            int inserted = statement.executeUpdate();
            connection.commit();
            if (inserted > 0) {
                storedEventCount.incrementAndGet();
                recentEvents.addLast(event);
                while (recentEvents.size() > MOVING_AVERAGE_WINDOW) {
                    recentEvents.removeFirst();
                }
            }
        }
    }

    private static void sendResponse(HttpExchange exchange, int status, String response) throws IOException {
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("status", status >= 400 ? "error" : "ok");
        body.put("message", response);
        byte[] responseBytes = OBJECT_MAPPER.writeValueAsBytes(body);
        addCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, responseBytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(responseBytes);
        }
    }

    private static void sendJsonResponse(HttpExchange exchange, int status, String response) throws IOException {
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        addCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, responseBytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(responseBytes);
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

    private static Map<String, String> queryParameters(String rawQuery) {
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

    private static String optionalUpper(String value) {
        return value == null || value.isBlank() ? null : value.toUpperCase(Locale.ROOT);
    }

    private static Instant optionalInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("time filters must be ISO-8601 values");
        }
    }

    private static int boundedInteger(String value, int fallback, int maximum) {
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

    private static String getConfig(Dotenv dotenv, String name, String fallback) {
        return dotenv.get(name, System.getenv().getOrDefault(name, fallback));
    }

    private static double getDoubleConfig(Dotenv dotenv, String name, double fallback) {
        try {
            return Double.parseDouble(getConfig(dotenv, name, Double.toString(fallback)));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static int getIntConfig(Dotenv dotenv, String name, int fallback) {
        try {
            return Integer.parseInt(getConfig(dotenv, name, Integer.toString(fallback)));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

}
