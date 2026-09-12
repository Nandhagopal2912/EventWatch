package com.main;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.cdimascio.dotenv.Dotenv;
import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * One running analytics service: an HTTP server, the collaborators in its {@link EngineContext},
 * and the two thread pools it owns.
 *
 * <p>This is an instance rather than a set of static fields so that several engines can run in
 * one JVM. That is what the integration tests need in order to run in parallel, and it removes
 * the last reason the routing had to live in one method.
 */
public class AnalyticsEngine {
    private static final int WORKER_THREADS = 8;
    private static final int WORK_QUEUE_CAPACITY = 500;
    private static final long RATE_WINDOW_SWEEP_SECONDS = 60;
    // Logging format is a property of the process, not of one engine, so its mapper is shared.
    private static final ObjectMapper LOGGING_MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final EngineContext context;
    private final ThreadPoolExecutor requestExecutor;
    private final ScheduledExecutorService maintenance;
    private final int shutdownGraceSeconds;
    private volatile boolean stopped;

    private AnalyticsEngine(HttpServer server, EngineContext context, ThreadPoolExecutor requestExecutor,
            ScheduledExecutorService maintenance, int shutdownGraceSeconds) {
        this.server = server;
        this.context = context;
        this.requestExecutor = requestExecutor;
        this.maintenance = maintenance;
        this.shutdownGraceSeconds = shutdownGraceSeconds;
    }

    public static void main(String[] args) throws IOException {
        // Load the shared secret before opening the ingestion endpoint.
        Dotenv dotenv = Dotenv.configure()
                .directory("..")
                .ignoreIfMissing()
                .load();
        AnalyticsEngine engine = start(EngineConfiguration.fromDotenv(dotenv));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            StructuredLogger.info("shutting down analytics engine", StructuredLogger.fields());
            engine.stop();
        }));
    }

    /**
     * Starts the HTTP server and every collaborator it needs. Returns the running engine so
     * {@code main} can register a shutdown hook and tests can stop it deterministically.
     */
    public static AnalyticsEngine start(EngineConfiguration configuration) throws IOException {
        StructuredLogger.configure(LOGGING_MAPPER, configuration.logFormat());
        // Validate before opening the pool; a throw after this point has to release it.
        if (configuration.apiKey() == null || configuration.apiKey().isBlank()) {
            throw new IOException("EVENTWATCH_API_KEY is required");
        }

        Database database;
        try {
            database = Database.open(configuration);
        } catch (RuntimeException exception) {
            // An unreachable backend surfaces as an unchecked pool error; make it readable.
            throw new IOException("Unable to open the database at " + configuration.databaseUrl(), exception);
        }

        EngineContext context;
        try {
            database.initializeSchema();
            context = new EngineContext(configuration, database);
        } catch (SQLException | RuntimeException exception) {
            String backend = database.dialect().name();
            database.close();
            throw new IOException("Unable to initialize the " + backend + " database", exception);
        }
        StructuredLogger.info("loaded stored events",
                StructuredLogger.fields("stored_events", context.recentEvents().total()));

        HttpServer server;
        try {
            server = TlsSupport.createServer(configuration);
        } catch (IOException | RuntimeException exception) {
            database.close();
            throw exception;
        }
        registerRoutes(server, context, configuration);

        // Bound worker threads and queued requests to apply backpressure during spikes.
        ThreadPoolExecutor requestExecutor = new ThreadPoolExecutor(
                WORKER_THREADS,
                WORKER_THREADS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(WORK_QUEUE_CAPACITY),
                new ThreadPoolExecutor.CallerRunsPolicy());
        server.setExecutor(requestExecutor);
        ScheduledExecutorService maintenance = scheduleMaintenance(context, configuration);

        server.start();
        StructuredLogger.info("analytics engine started", StructuredLogger.fields(
                "port", server.getAddress().getPort(),
                "tls", configuration.tlsEnabled(),
                "database", database.dialect().name(),
                "worker_threads", WORKER_THREADS,
                "queue_capacity", WORK_QUEUE_CAPACITY));
        return new AnalyticsEngine(server, context, requestExecutor, maintenance,
                configuration.shutdownGraceSeconds());
    }

    private static void registerRoutes(HttpServer server, EngineContext context,
            EngineConfiguration configuration) {
        server.createContext("/receive", new ReceiveHandler(context));
        server.createContext("/health", new HealthHandler(context));
        server.createContext("/metrics", new MetricsHandler(context, configuration.metricsRequireKey()));
        server.createContext("/events", new EventsHandler(context));
        server.createContext("/hosts", new HostsHandler(context));
        server.createContext("/summary", new SummaryHandler(context));
        server.createContext("/rules", new RulesHandler(context));
        server.createContext("/alerts", new AlertsHandler(context));
        // Registered after /alerts so the longer prefix wins for keyed routes.
        server.createContext("/alerts/", new AlertDetailHandler(context));
        server.createContext("/session", new SessionHandler(context));
        server.createContext("/agents", new AgentsHandler(context));
        server.createContext("/agents/", new AgentDetailHandler(context));

        // The dashboard is served last and at the root, so every API path above claims its own
        // longer prefix first. Serving it here is what makes the session cookie same-origin.
        java.nio.file.Path dashboard = context.dashboardDirectory();
        if (dashboard != null) {
            server.createContext("/", new DashboardHandler(context, dashboard));
            StructuredLogger.info("serving the dashboard",
                    StructuredLogger.fields("directory", dashboard.toAbsolutePath().toString()));
        } else {
            StructuredLogger.info("dashboard not served; DASHBOARD_DIR is unset or missing",
                    StructuredLogger.fields());
        }
    }

    private static ScheduledExecutorService scheduleMaintenance(EngineContext context,
            EngineConfiguration configuration) {
        ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "eventwatch-maintenance");
            thread.setDaemon(true);
            return thread;
        });

        // Without eviction the rate-limit map grows with every distinct client address, and
        // expired sessions would sit in memory until someone happened to present one.
        maintenance.scheduleWithFixedDelay(() -> {
            context.rateLimiter().sweepExpired();
            context.sessionRateLimiter().sweepExpired();
            context.sessions().sweepExpired(java.time.Instant.now());
        }, RATE_WINDOW_SWEEP_SECONDS, RATE_WINDOW_SWEEP_SECONDS, TimeUnit.SECONDS);

        if (context.retention().enabled()) {
            long sweepMinutes = configuration.retentionSweepMinutes();
            maintenance.scheduleWithFixedDelay(context.retention()::sweep, 1, sweepMinutes, TimeUnit.MINUTES);
            StructuredLogger.info("retention enabled", StructuredLogger.fields(
                    "retention_days", configuration.retentionDays(),
                    "sweep_minutes", sweepMinutes));
        }

        // Silence is the one condition no event can reveal, so it runs on the timer.
        long silenceSweepSeconds = configuration.agentSilenceSweepSeconds();
        maintenance.scheduleWithFixedDelay(context.agentSilenceMonitor()::sweep,
                silenceSweepSeconds, silenceSweepSeconds, TimeUnit.SECONDS);

        // The service cannot report its own death, so it tells an outside endpoint it is alive.
        if (context.watchdogHeartbeat().enabled()) {
            long watchdogSeconds = configuration.watchdogIntervalSeconds();
            maintenance.scheduleWithFixedDelay(context.watchdogHeartbeat()::ping,
                    watchdogSeconds, watchdogSeconds, TimeUnit.SECONDS);
            StructuredLogger.info("watchdog heartbeat enabled",
                    StructuredLogger.fields("interval_seconds", watchdogSeconds));
        }
        return maintenance;
    }

    /** Stops new work, drains the request executor, and releases background threads. */
    public void stop() {
        server.stop(shutdownGraceSeconds);
        maintenance.shutdownNow();
        context.notificationService().shutdown();
        requestExecutor.shutdown();
        try {
            if (!requestExecutor.awaitTermination(shutdownGraceSeconds, TimeUnit.SECONDS)) {
                requestExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            requestExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // Closing the pool first would fail the requests the drain above exists to finish.
        context.database().close();
        stopped = true;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** Visible for tests that assert the pool is released rather than leaked. */
    Database database() {
        return stopped ? null : context.database();
    }

    /** Visible for tests that drive a heartbeat without waiting for the timer. */
    WatchdogHeartbeat watchdogHeartbeat() {
        return context.watchdogHeartbeat();
    }

    /** Visible for tests that drive a silence sweep without waiting for the timer. */
    AgentSilenceMonitor agentSilenceMonitor() {
        return context.agentSilenceMonitor();
    }

    /** Visible for tests that assert work in flight keeps its database until it finishes. */
    ThreadPoolExecutor requestExecutor() {
        return requestExecutor;
    }
}
