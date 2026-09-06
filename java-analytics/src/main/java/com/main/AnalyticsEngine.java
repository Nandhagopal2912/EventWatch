package com.main;

import com.sun.net.httpserver.*;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class AnalyticsEngine {
    private static final int MOVING_AVERAGE_WINDOW = 5;
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final int MAX_MESSAGE_LENGTH = 1000;
    private static final int MAX_REQUESTS_PER_MINUTE = 100;
    private static final int WORKER_THREADS = 8;
    private static final int WORK_QUEUE_CAPACITY = 500;
    private static final String DATABASE_URL = "jdbc:sqlite:events.db";
    private static final String DATABASE_UNIQUE_INDEX = "idx_telemetry_events_event_id";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static String apiKey;
    private static AlertRepository alertRepository;
    private static AlertEngine alertEngine;

    private static final List<LogEntry> logStorage = new CopyOnWriteArrayList<>();
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
            loadStoredEvents();
            alertRepository = new AlertRepository(DATABASE_URL);
            alertEngine = new AlertEngine(
                    alertRepository,
                    MOVING_AVERAGE_WINDOW,
                    getDoubleConfig(dotenv, "CPU_ALERT_THRESHOLD", 85.0),
                    getDoubleConfig(dotenv, "RAM_ALERT_THRESHOLD", 80.0),
                    getIntConfig(dotenv, "REPEATED_ERROR_THRESHOLD", 5));
        } catch (SQLException exception) {
            throw new IOException("Unable to initialize SQLite database", exception);
        }
        System.out.println("Loaded " + logStorage.size() + " stored events.");
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
                        alertEngine.evaluate(new ArrayList<>(logStorage));
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
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            try {
                ArrayNode alerts = OBJECT_MAPPER.createArrayNode();
                for (AlertRecord alert : alertRepository.findActive()) {
                    ObjectNode alertJson = alerts.addObject();
                    alertJson.put("alert_key", alert.getAlertKey());
                    alertJson.put("alert_type", alert.getAlertType());
                    alertJson.put("message", alert.getMessage());
                    alertJson.put("status", alert.getStatus().name());
                    alertJson.put("first_seen", alert.getFirstSeen().toString());
                    alertJson.put("last_seen", alert.getLastSeen().toString());
                    alertJson.put("occurrence_count", alert.getOccurrenceCount());
                }
                sendJsonResponse(exchange, 200, alerts.toString());
            } catch (SQLException exception) {
                sendResponse(exchange, 503, "Alert storage unavailable");
            }
        });

        server.createContext("/alerts/", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method not allowed");
                return;
            }
            if (!isValidApiKey(exchange.getRequestHeaders().getFirst("X-EventWatch-Key"))) {
                sendResponse(exchange, 401, "Unauthorized");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String prefix = "/alerts/";
            String acknowledgeSuffix = "/acknowledge";
            String resolveSuffix = "/resolve";
            String suffix = path.endsWith(acknowledgeSuffix) ? acknowledgeSuffix : resolveSuffix;
            if (!path.startsWith(prefix) || (!path.endsWith(acknowledgeSuffix) && !path.endsWith(resolveSuffix))) {
                sendResponse(exchange, 404, "Alert route not found");
                return;
            }
            String alertKey = path.substring(prefix.length(), path.length() - suffix.length());
            try {
                boolean changed;
                String action;
                if (acknowledgeSuffix.equals(suffix)) {
                    changed = alertRepository.acknowledge(alertKey);
                    action = "acknowledged";
                } else {
                    changed = alertRepository.resolve(alertKey, Instant.now());
                    action = "resolved";
                }
                if (changed) {
                    sendResponse(exchange, 200, "Alert " + action);
                } else {
                    sendResponse(exchange, 404, "Alert not found or already " + action);
                }
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

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down Java analytics engine...");
            server.stop(5);
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
        Map<String, Long> errorCounts = logStorage.stream()
                .filter(log -> "ERROR".equalsIgnoreCase(log.level))
                .collect(Collectors.groupingBy(log -> log.message, Collectors.counting()));

        long totalProcessed = logStorage.size();
        List<LogEntry> logSnapshot = new ArrayList<>(logStorage);
        // Limit the trend calculation to the most recent events.
        int windowStart = Math.max(0, logSnapshot.size() - MOVING_AVERAGE_WINDOW);
        List<LogEntry> recentLogs = logSnapshot.subList(windowStart, logSnapshot.size());
        double averageCpu = recentLogs.stream().mapToDouble(log -> log.cpuUsage).average().orElse(0.0);
        double averageRam = recentLogs.stream().mapToDouble(log -> log.ramUsage).average().orElse(0.0);

        System.out.println("\n================ LIVE CLOUD ALERT DASHBOARD ================");
        System.out.println("Total Logs Processed (All Types): " + totalProcessed);
        System.out.printf("Last %d-event average: CPU %.1f%% | RAM %.1f%%%n",
                recentLogs.size(), averageCpu, averageRam);
        System.out.println("------------------------------------------------------------");
        if (errorCounts.isEmpty()) {
            System.out.println(" No critical errors detected yet.");
        } else {
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
        }
    }

    private static void loadStoredEvents() throws SQLException {
        // Rebuild the in-memory analytics window from durable SQLite records.
        String query = "SELECT event_id, level, message, event_timestamp, cpu_usage, ram_usage "
                + "FROM telemetry_events ORDER BY id";
        try (Connection connection = DriverManager.getConnection(DATABASE_URL);
                PreparedStatement statement = connection.prepareStatement(query);
                ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                logStorage.add(new LogEntry(
                        results.getString("event_id"),
                        results.getString("level"),
                        results.getString("message"),
                        parseTimestamp(results.getString("event_timestamp")),
                        results.getDouble("cpu_usage"),
                        results.getDouble("ram_usage")));
            }
        }
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
                logStorage.add(event);
            }
        }
    }

    private static void sendResponse(HttpExchange exchange, int status, String response) throws IOException {
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("status", status >= 400 ? "error" : "ok");
        body.put("message", response);
        byte[] responseBytes = OBJECT_MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, responseBytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(responseBytes);
        }
    }

    private static void sendJsonResponse(HttpExchange exchange, int status, String response) throws IOException {
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, responseBytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(responseBytes);
        }
    }

    private static boolean isValidApiKey(String receivedKey) {
        return receivedKey != null && MessageDigest.isEqual(
                apiKey.getBytes(StandardCharsets.UTF_8),
                receivedKey.getBytes(StandardCharsets.UTF_8));
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
