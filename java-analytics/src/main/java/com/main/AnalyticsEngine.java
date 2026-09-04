package com.main;

import com.sun.net.httpserver.*;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.sql.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class AnalyticsEngine {
    private static final int MOVING_AVERAGE_WINDOW = 5;
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final String DATABASE_URL = "jdbc:sqlite:events.db";

    private static final List<LogEntry> logStorage = new CopyOnWriteArrayList<>();

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
                    byte[] bodyBytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
                    if (bodyBytes.length > MAX_REQUEST_BYTES) {
                        sendResponse(exchange, 413, "Request body too large");
                        return;
                    }
                    String body = new String(bodyBytes, StandardCharsets.UTF_8);

                    // String the information from the body we got from the request using a helper
                    // extractJsonValue
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

                    String level = json.path("level").asText("INFO");
                    String msg = json.path("msg").asText("Unknown Event");
                    Instant timestamp = parseTimestamp(json.path("timestamp").asText(null));
                    double cpuUsage = json.path("cpu_usage").asDouble(0.0);
                    double ramUsage = json.path("ram_usage").asDouble(0.0);

                    try {
                        storeEvent(new LogEntry(level, msg, timestamp, cpuUsage, ramUsage));
                    } catch (SQLException exception) {
                        sendResponse(exchange, 503, "Database unavailable");
                        return;
                    }

                    // generate the dashborad in terminal using a helper function.
                    generateDashboardReport();

                    // Send a success message to the browser or endpoint.
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

    // helper function to create the dashbord

    private static void generateDashboardReport() {
        // 1.Filter out non-errors
        // 2.Group by messages text
        // 3.Count occurences of each error

        Map<String, Long> errorCounts = logStorage.stream()
                .filter(log -> "ERROR".equalsIgnoreCase(log.level))
                .collect(Collectors.groupingBy(log -> log.message, Collectors.counting()));

        // Get the total count of logs processed

        long totalProcessed = logStorage.size();
        List<LogEntry> logSnapshot = new ArrayList<>(logStorage);
        int windowStart = Math.max(0, logSnapshot.size() - MOVING_AVERAGE_WINDOW);
        List<LogEntry> recentLogs = logSnapshot.subList(windowStart, logSnapshot.size());
        double averageCpu = recentLogs.stream().mapToDouble(log -> log.cpuUsage).average().orElse(0.0);
        double averageRam = recentLogs.stream().mapToDouble(log -> log.ramUsage).average().orElse(0.0);

        // Print the dashboard in terminal

        // Print the dashboard directly to the terminal
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

}
