package com.main;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Everything one running engine owns, built once and handed to every route handler.
 *
 * <p>These used to be static fields, which meant one engine per JVM: two instances would have
 * shared a database handle, an event window, and a rate-limit map. Holding them here instead is
 * what lets the integration tests run several engines side by side.
 */
class EngineContext {
    static final int MOVING_AVERAGE_WINDOW = 5;

    private final EngineConfiguration configuration;
    private final ObjectMapper objectMapper;
    private final Metrics metrics;
    private final Database database;
    private final HttpSupport http;
    private final EventRepository eventRepository;
    private final AlertRepository alertRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;
    private final AlertRules alertRules;
    private final AlertEngine alertEngine;
    private final QueryService queryService;
    private final RetentionService retentionService;
    private final AgentSilenceMonitor agentSilenceMonitor;
    private final WatchdogHeartbeat watchdogHeartbeat;
    private final RecentEvents recentEvents;
    private final RateLimiter rateLimiter;
    private final RateLimiter sessionRateLimiter;
    private final SessionStore sessions;
    private final TelemetryReport telemetryReport;

    EngineContext(EngineConfiguration configuration, Database database) throws SQLException {
        this.configuration = configuration;
        this.database = database;
        this.objectMapper = new ObjectMapper();
        this.metrics = new Metrics();
        this.sessions = new SessionStore(
                Duration.ofMinutes(configuration.sessionTimeToLiveMinutes()), metrics);
        this.http = new HttpSupport(objectMapper, metrics, configuration.apiKey(), sessions,
                configuration.tlsEnabled());
        this.rateLimiter = new RateLimiter(configuration.rateLimitPerMinute());
        // Sign-in is the one open endpoint that checks a secret, so it gets its own tighter limit.
        this.sessionRateLimiter = new RateLimiter(configuration.sessionRateLimitPerMinute());

        this.eventRepository = new EventRepository(database.connections(), database.dialect());
        this.recentEvents = new RecentEvents(eventRepository, MOVING_AVERAGE_WINDOW);
        this.recentEvents.restore();
        this.alertRepository = new AlertRepository(database.connections());
        this.notificationRepository = new NotificationRepository(database.connections());
        this.notificationService = new NotificationService(
                notificationRepository,
                objectMapper,
                metrics,
                configuration.notificationsEnabled(),
                configuration.notificationWebhookUrl(),
                configuration.notificationTimeoutSeconds(),
                configuration.notificationMaxAttempts(),
                configuration.notificationRetryDelayMillis(),
                configuration.notificationReminderSeconds());
        // The .env thresholds become the fallback tier beneath stored rules.
        this.alertRules = new AlertRules(
                new AlertRuleRepository(database.connections()),
                configuration.cpuThreshold(),
                configuration.ramThreshold(),
                configuration.repeatedErrorThreshold(),
                configuration.agentSilenceMinutes(),
                MOVING_AVERAGE_WINDOW);
        this.alertEngine = new AlertEngine(
                alertRepository, notificationService, MOVING_AVERAGE_WINDOW, alertRules);
        this.queryService = new QueryService(
                eventRepository, alertRepository, objectMapper, MOVING_AVERAGE_WINDOW);
        this.retentionService = new RetentionService(
                database.connections(), metrics, configuration.retentionDays());
        this.agentSilenceMonitor = new AgentSilenceMonitor(
                eventRepository, alertRepository, alertRules, notificationService, metrics,
                QueryService.MAX_HOSTS_LISTED,
                Duration.ofHours(configuration.agentSilenceForgetHours()));
        this.watchdogHeartbeat = new WatchdogHeartbeat(database, eventRepository, alertRepository,
                metrics, objectMapper, configuration.watchdogUrl(),
                configuration.watchdogTimeoutSeconds());
        this.telemetryReport = new TelemetryReport(recentEvents, eventRepository, metrics,
                "text".equalsIgnoreCase(configuration.logFormat()));
    }

    EngineConfiguration configuration() {
        return configuration;
    }

    ObjectMapper objectMapper() {
        return objectMapper;
    }

    Metrics metrics() {
        return metrics;
    }

    Database database() {
        return database;
    }

    HttpSupport http() {
        return http;
    }

    EventRepository events() {
        return eventRepository;
    }

    AlertRepository alerts() {
        return alertRepository;
    }

    NotificationRepository notifications() {
        return notificationRepository;
    }

    NotificationService notificationService() {
        return notificationService;
    }

    AlertRules alertRules() {
        return alertRules;
    }

    AlertEngine alertEngine() {
        return alertEngine;
    }

    QueryService queries() {
        return queryService;
    }

    RetentionService retention() {
        return retentionService;
    }

    AgentSilenceMonitor agentSilenceMonitor() {
        return agentSilenceMonitor;
    }

    WatchdogHeartbeat watchdogHeartbeat() {
        return watchdogHeartbeat;
    }

    RecentEvents recentEvents() {
        return recentEvents;
    }

    RateLimiter rateLimiter() {
        return rateLimiter;
    }

    RateLimiter sessionRateLimiter() {
        return sessionRateLimiter;
    }

    SessionStore sessions() {
        return sessions;
    }

    /** The dashboard directory to serve, or null when none is configured or it is absent. */
    Path dashboardDirectory() {
        String configured = configuration.dashboardDirectory();
        if (configured == null || configured.isBlank()) {
            return null;
        }
        Path directory = Path.of(configured);
        return java.nio.file.Files.isDirectory(directory) ? directory : null;
    }

    TelemetryReport telemetryReport() {
        return telemetryReport;
    }
}
