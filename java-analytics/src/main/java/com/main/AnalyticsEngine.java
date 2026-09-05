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
import io.github.cdimascio.dotenv.Dotenv;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class AnalyticsEngine {
    private static final int MOVING_AVERAGE_WINDOW = 5;
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final int MAX_MESSAGE_LENGTH = 1000;
    private static final int MAX_REQUESTS_PER_MINUTE = 100;
    private static final String DATABASE_URL = "jdbc:sqlite:events.db";
    private static String apiKey;

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
        Instant timestamp;
        double cpuUsage;
        double ramUsage;

        LogEntry(String level, String message, Instant timestamp, double cpuUsage, double ramUsage) {
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

                    ObjectMapper objectMapper = new ObjectMapper();
                    JsonNode json;
                    try {
                        json = objectMapper.readTree(body);
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

                    String level = json.path("level").asText().toUpperCase(Locale.ROOT);
                    String msg = json.path("msg").asText();
                    Instant timestamp = Instant.parse(json.path("timestamp").asText());
                    double cpuUsage = json.path("cpu_usage").asDouble();
                    double ramUsage = json.path("ram_usage").asDouble();

                    try {
                        storeEvent(new LogEntry(level, msg, timestamp, cpuUsage, ramUsage));
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

        server.setExecutor(null); // created default executer

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
                        level TEXT NOT NULL,
                        message TEXT NOT NULL,
                        event_timestamp TEXT NOT NULL,
                        cpu_usage REAL NOT NULL,
                        ram_usage REAL NOT NULL,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }
    }

    private static void loadStoredEvents() throws SQLException {
        // Rebuild the in-memory analytics window from durable SQLite records.
        String query = "SELECT level, message, event_timestamp, cpu_usage, ram_usage "
                + "FROM telemetry_events ORDER BY id";
        try (Connection connection = DriverManager.getConnection(DATABASE_URL);
                PreparedStatement statement = connection.prepareStatement(query);
                ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                logStorage.add(new LogEntry(
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
        String query = "INSERT INTO telemetry_events "
                + "(level, message, event_timestamp, cpu_usage, ram_usage) VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = DriverManager.getConnection(DATABASE_URL);
                PreparedStatement statement = connection.prepareStatement(query)) {
            connection.setAutoCommit(false);
            statement.setString(1, event.level);
            statement.setString(2, event.message);
            statement.setString(3, event.timestamp.toString());
            statement.setDouble(4, event.cpuUsage);
            statement.setDouble(5, event.ramUsage);
            statement.executeUpdate();
            connection.commit();
            logStorage.add(event);
        }
    }

    private static void sendResponse(HttpExchange exchange, int status, String response) throws IOException {
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
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

}
