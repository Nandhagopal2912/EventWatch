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
    private static final int WORKER_THREADS = 8;
    private static final int WORK_QUEUE_CAPACITY = 500;
    private static final int TOP_ERROR_MESSAGES = 5;
    static final String UNKNOWN_HOST = "unknown";
    private static final int MAX_TRACKED_HOSTS = 1000;
    private static final int MAX_RESTORED_HOSTS = 50;
    private static final int MAX_IDENTITY_LENGTH = 128;
    static final int MAX_HOSTS_LISTED = 200;
    private static final int NOTIFICATION_HISTORY_LIMIT = 50;
    private static final long RATE_WINDOW_SWEEP_SECONDS = 60;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Metrics METRICS = new Metrics();
    // Correlation ids are per-request state, so the response helpers read them from here.
    private static final ThreadLocal<String> CORRELATION_ID = new ThreadLocal<>();
    private static String apiKey;
    private static boolean textLogging;
    private static Database database;
    private static List<String> allowedOrigins = List.of();
    private static boolean metricsRequireKey;
    // A hardcoded ceiling would bind long before storage does, so it is configuration.
    private static int maxRequestsPerMinute = 100;
    private static int shutdownGraceSeconds = 5;
    private static RetentionService retentionService;
    private static ThreadPoolExecutor runningExecutor;
    private static ScheduledExecutorService runningMaintenance;
    private static AlertRepository alertRepository;
    private static AlertEngine alertEngine;
    private static AlertRules alertRules;
    private static QueryService queryService;
    private static EventRepository eventRepository;
    private static NotificationRepository notificationRepository;
    private static NotificationService notificationService;

    // Only the newest events per machine are mirrored in memory; history stays in SQLite.
    // A shared window would average unrelated hosts and make every alert meaningless.
    private static final Map<String, Deque<LogEntry>> recentEventsByHost =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Deque<LogEntry>> eldest) {
                    return size() > MAX_TRACKED_HOSTS;
                }
            };
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
            if (requestCount >= maxRequestsPerMinute) {
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
        String hostId;
        String hostname;
        Instant timestamp;
        double cpuUsage;
        double ramUsage;

        LogEntry(String eventId, String level, String message, Instant timestamp,
                double cpuUsage, double ramUsage) {
            this(eventId, level, message, timestamp, UNKNOWN_HOST, null, cpuUsage, ramUsage);
        }

        LogEntry(String eventId, String level, String message, Instant timestamp,
                String hostId, String hostname, double cpuUsage, double ramUsage) {
            this.eventId = eventId;
            this.level = level;
            this.message = message;
            this.timestamp = timestamp;
            // Events from an agent older than phase 12 carry no identity.
            this.hostId = hostId == null || hostId.isBlank() ? UNKNOWN_HOST : hostId;
            this.hostname = hostname;
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
        // Validate before opening the pool; a throw after this point has to release it.
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("EVENTWATCH_API_KEY is required");
        }
        maxRequestsPerMinute = configuration.rateLimitPerMinute();
        allowedOrigins = configuration.corsAllowedOrigins();
        metricsRequireKey = configuration.metricsRequireKey();
        shutdownGraceSeconds = configuration.shutdownGraceSeconds();

        recentEventsByHost.clear();
        storedEventCount.set(0);
        rateWindows.clear();

        try {
            database = Database.open(configuration);
        } catch (RuntimeException exception) {
            // An unreachable backend surfaces as an unchecked pool error; make it readable.
            throw new IOException("Unable to open the database at " + configuration.databaseUrl(), exception);
        }

        try {
            database.initializeSchema();
            eventRepository = new EventRepository(database.connections(), database.dialect());
            loadRecentEvents();
            alertRepository = new AlertRepository(database.connections());
            notificationRepository = new NotificationRepository(database.connections());
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
            // The .env thresholds become the fallback tier beneath stored rules.
            alertRules = new AlertRules(
                    new AlertRuleRepository(database.connections()),
                    configuration.cpuThreshold(),
                    configuration.ramThreshold(),
                    configuration.repeatedErrorThreshold(),
                    MOVING_AVERAGE_WINDOW);
            alertEngine = new AlertEngine(
                    alertRepository,
                    notificationService,
                    MOVING_AVERAGE_WINDOW,
                    alertRules);
            queryService = new QueryService(
                    eventRepository, alertRepository, OBJECT_MAPPER, MOVING_AVERAGE_WINDOW);
            retentionService = new RetentionService(
                    database.connections(), METRICS, configuration.retentionDays());
        } catch (SQLException | RuntimeException exception) {
            String backend = database.dialect().name();
            releaseDatabase();
            throw new IOException("Unable to initialize the " + backend + " database", exception);
        }
        StructuredLogger.info("loaded stored events",
                StructuredLogger.fields("stored_events", storedEventCount.get()));
        HttpServer server;
        try {
            server = TlsSupport.createServer(configuration);
        } catch (IOException | RuntimeException exception) {
            releaseDatabase();
            throw exception;
        }

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
                    String hostId = textOrNull(json, "host_id");
                    String hostname = textOrNull(json, "hostname");

                    boolean stored;
                    LogEntry event = new LogEntry(eventId, level, msg, timestamp,
                            hostId, hostname, cpuUsage, ramUsage);
                    try {
                        stored = storeEvent(event);
                        alertEngine.evaluate(recentEventsSnapshot(event.hostId));
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
                                    "host_id", event.hostId, "hostname", hostname,
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
            try {
                database.checkReachable();
                sendJsonResponse(exchange, 200,
                        "{\"status\":\"ok\",\"service\":\"java-analytics\","
                                + "\"message\":\"service is healthy\"}");
            } catch (SQLException exception) {
                METRICS.recordDatabaseFailure();
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
            // Scrapers rarely send custom headers, so this is opt-in rather than the default.
            if (metricsRequireKey && !isValidApiKey(exchange.getRequestHeaders().getFirst("X-EventWatch-Key"))) {
                sendResponse(exchange, 401, "Unauthorized");
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
                String hostId = parameters.get("host_id");
                if (hostId != null && hostId.isBlank()) {
                    hostId = null;
                }
                if (level != null && !Set.of("INFO", "WARN", "ERROR", "CRITICAL").contains(level)) {
                    sendResponse(exchange, 400, "level must be INFO, WARN, ERROR, or CRITICAL");
                    return;
                }
                if (from != null && to != null && from.isAfter(to)) {
                    sendResponse(exchange, 400, "from must be before to");
                    return;
                }
                sendJsonResponse(exchange, 200,
                        queryService.events(level, hostId, from, to, limit, offset).toString());
            } catch (IllegalArgumentException exception) {
                sendResponse(exchange, 400, exception.getMessage());
            } catch (SQLException exception) {
                sendResponse(exchange, 503, "Event storage unavailable");
            }
        });

        server.createContext("/rules", exchange -> {
            if (handleCorsPreflight(exchange)) {
                return;
            }
            if (!isValidApiKey(exchange.getRequestHeaders().getFirst("X-EventWatch-Key"))) {
                sendResponse(exchange, 401, "Unauthorized");
                return;
            }
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            String path = exchange.getRequestURI().getPath();
            boolean effectiveRoute = "/rules/effective".equals(path);
            if (!effectiveRoute && !"/rules".equals(path)) {
                sendResponse(exchange, 404, "Rule route not found");
                return;
            }
            try {
                Map<String, String> parameters = queryParameters(exchange.getRequestURI().getRawQuery());
                if (effectiveRoute) {
                    if (!"GET".equals(method)) {
                        sendResponse(exchange, 405, "Method not allowed");
                        return;
                    }
                    String hostId = parameters.get("host_id");
                    if (hostId == null || hostId.isBlank()) {
                        sendResponse(exchange, 400, "host_id is required");
                        return;
                    }
                    sendJsonResponse(exchange, 200,
                            queryService.effectiveRules(hostId, alertRules.effectiveFor(hostId)).toString());
                    return;
                }
                switch (method) {
                    case "GET" -> sendJsonResponse(exchange, 200,
                            queryService.rules(alertRules.all(), alertRules.defaults()).toString());
                    case "PUT" -> {
                        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                        if (contentType == null
                                || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                            sendResponse(exchange, 415, "Content-Type must be application/json");
                            return;
                        }
                        byte[] bodyBytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
                        if (bodyBytes.length > MAX_REQUEST_BYTES) {
                            sendResponse(exchange, 413, "Request body too large");
                            return;
                        }
                        JsonNode json;
                        try {
                            json = OBJECT_MAPPER.readTree(new String(bodyBytes, StandardCharsets.UTF_8));
                        } catch (JsonProcessingException exception) {
                            sendResponse(exchange, 400, "Invalid JSON");
                            return;
                        }
                        if (json == null || !json.isObject()) {
                            sendResponse(exchange, 400, "JSON object required");
                            return;
                        }
                        JsonNode hostNode = json.path("host_id");
                        if (!hostNode.isMissingNode() && !hostNode.isNull() && !hostNode.isTextual()) {
                            sendResponse(exchange, 400, "host_id must be a text value");
                            return;
                        }
                        if (!json.path("threshold").isNumber()) {
                            sendResponse(exchange, 400, "threshold must be a number");
                            return;
                        }
                        JsonNode enabledNode = json.path("enabled");
                        if (!enabledNode.isMissingNode() && !enabledNode.isBoolean()) {
                            sendResponse(exchange, 400, "enabled must be true or false");
                            return;
                        }
                        AlertRule saved = alertRules.save(
                                optionalUpper(textOrNull(json, "rule_type")),
                                hostNode.isTextual() ? hostNode.asText() : null,
                                json.path("threshold").asDouble(),
                                enabledNode.isMissingNode() || enabledNode.asBoolean());
                        StructuredLogger.info("alert rule saved", StructuredLogger.fields(
                                "rule_type", saved.ruleType(), "scope", saved.scope(),
                                "threshold", saved.threshold(), "enabled", saved.enabled()));
                        sendJsonResponse(exchange, 200, queryService.ruleJson(saved).toString());
                    }
                    case "DELETE" -> {
                        String hostId = parameters.get("host_id");
                        boolean removed = alertRules.delete(
                                optionalUpper(parameters.get("rule_type")),
                                hostId == null || hostId.isEmpty() ? null : hostId);
                        if (removed) {
                            sendResponse(exchange, 200, "Rule removed");
                        } else {
                            sendResponse(exchange, 404, "No such rule");
                        }
                    }
                    default -> {
                        exchange.getResponseHeaders().set("Allow", "GET, PUT, DELETE");
                        sendResponse(exchange, 405, "Method not allowed");
                    }
                }
            } catch (IllegalArgumentException exception) {
                sendResponse(exchange, 400, exception.getMessage());
            } catch (SQLException exception) {
                METRICS.recordDatabaseFailure();
                sendResponse(exchange, 503, "Rule storage unavailable");
            }
        });

        server.createContext("/hosts", exchange -> {
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
                int limit = boundedInteger(parameters.get("limit"), MAX_HOSTS_LISTED, QueryService.MAX_LIMIT);
                sendJsonResponse(exchange, 200, queryService.hosts(limit).toString());
            } catch (IllegalArgumentException exception) {
                sendResponse(exchange, 400, exception.getMessage());
            } catch (SQLException exception) {
                sendResponse(exchange, 503, "Host storage unavailable");
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
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
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
        if (retentionService.enabled()) {
            long sweepMinutes = configuration.retentionSweepMinutes();
            maintenance.scheduleWithFixedDelay(retentionService::sweep, 1, sweepMinutes, TimeUnit.MINUTES);
            StructuredLogger.info("retention enabled", StructuredLogger.fields(
                    "retention_days", configuration.retentionDays(),
                    "sweep_minutes", sweepMinutes));
        }

        runningExecutor = requestExecutor;
        runningMaintenance = maintenance;

        server.start();
        StructuredLogger.info("analytics engine started", StructuredLogger.fields(
                "port", server.getAddress().getPort(),
                "tls", configuration.tlsEnabled(),
                "database", database.dialect().name(),
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
        if (runningExecutor != null) {
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
        // Closing the pool first would fail the requests the drain above exists to finish.
        releaseDatabase();
    }

    private static void releaseDatabase() {
        if (database != null) {
            database.close();
            database = null;
        }
    }

    /** Visible for tests that assert the pool is released rather than leaked. */
    static Database database() {
        return database;
    }

    /** Visible for tests that assert work in flight keeps its database until it finishes. */
    static ThreadPoolExecutor requestExecutor() {
        return runningExecutor;
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
        // Identity is optional so an agent older than phase 12 still reports, but bounded
        // when present: these values become alert keys and label values.
        String identityError = validateIdentity(json, "host_id");
        if (identityError == null) {
            identityError = validateIdentity(json, "hostname");
        }
        return identityError;
    }

    private static String validateIdentity(JsonNode json, String fieldName) {
        if (!json.has(fieldName) || json.path(fieldName).isNull()) {
            return null;
        }
        if (!json.path(fieldName).isTextual()) {
            return fieldName + " must be a text value";
        }
        String value = json.path(fieldName).asText();
        if (value.length() > MAX_IDENTITY_LENGTH) {
            return fieldName + " must contain at most " + MAX_IDENTITY_LENGTH + " characters";
        }
        return null;
    }

    static String textOrNull(JsonNode json, String fieldName) {
        JsonNode value = json.path(fieldName);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
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
        // Restore each machine's window; SQLite remains the source of truth for history.
        storedEventCount.set(eventRepository.count(null, null, null));
        List<LogEntry> newestFirst = eventRepository.recent(MOVING_AVERAGE_WINDOW * MAX_RESTORED_HOSTS);
        for (int index = newestFirst.size() - 1; index >= 0; index--) {
            rememberEvent(newestFirst.get(index));
        }
    }

    private static synchronized void rememberEvent(LogEntry event) {
        Deque<LogEntry> window = recentEventsByHost.computeIfAbsent(event.hostId, key -> new ArrayDeque<>());
        window.addLast(event);
        while (window.size() > MOVING_AVERAGE_WINDOW) {
            window.removeFirst();
        }
    }

    private static synchronized List<LogEntry> recentEventsSnapshot(String hostId) {
        Deque<LogEntry> window = recentEventsByHost.get(hostId);
        return window == null ? List.of() : new ArrayList<>(window);
    }

    /** The newest events across every tracked machine, for the terminal report. */
    private static synchronized List<LogEntry> recentEventsSnapshot() {
        List<LogEntry> combined = new ArrayList<>();
        for (Deque<LogEntry> window : recentEventsByHost.values()) {
            combined.addAll(window);
        }
        combined.sort(Comparator.comparing(event -> event.timestamp));
        int start = Math.max(0, combined.size() - MOVING_AVERAGE_WINDOW);
        return new ArrayList<>(combined.subList(start, combined.size()));
    }

    private static synchronized boolean storeEvent(LogEntry event) throws SQLException {
        // Commit to SQLite before adding the event to memory, preventing acknowledged
        // data loss.
        boolean inserted = eventRepository.insertIfAbsent(event);
        if (inserted) {
            storedEventCount.incrementAndGet();
            rememberEvent(event);
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
        // The allowlist is configuration; a hardcoded origin made the dashboard undeployable.
        if (origin != null && allowedOrigins.contains(origin)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-EventWatch-Key");
    }

    private static boolean handleCorsPreflight(HttpExchange exchange) throws IOException {
        if (!"OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            return false;
        }
        addCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
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
