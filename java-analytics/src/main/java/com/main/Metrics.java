package com.main;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Prometheus text-format counters, hand-rolled so the analytics service keeps
 * its small dependency set and its 128 MB heap budget.
 */
public final class Metrics {
    private final AtomicLong eventsReceived = new AtomicLong();
    private final AtomicLong eventsDuplicate = new AtomicLong();
    private final AtomicLong databaseFailures = new AtomicLong();
    private final AtomicLong silentAgents = new AtomicLong();
    private final AtomicLong processingObserved = new AtomicLong();
    private final DoubleAdder processingSeconds = new DoubleAdder();
    private final Map<String, AtomicLong> eventsRejected = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> notifications = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> watchdogPings = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> httpRequests = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sessions = new ConcurrentHashMap<>();
    private final AtomicLong sessionsActive = new AtomicLong();

    public void recordEventReceived() {
        eventsReceived.incrementAndGet();
    }

    public void recordEventDuplicate() {
        eventsDuplicate.incrementAndGet();
    }

    /** reason is a fixed slug such as validation, unauthorized, or rate_limited. */
    public void recordEventRejected(String reason) {
        eventsRejected.computeIfAbsent(reason, key -> new AtomicLong()).incrementAndGet();
    }

    /** Machines currently past their silence threshold, refreshed by each sweep. */
    public void recordSilentAgents(long count) {
        silentAgents.set(count);
    }

    public void recordDatabaseFailure() {
        databaseFailures.incrementAndGet();
    }

    public void observeProcessingDuration(double seconds) {
        processingSeconds.add(seconds);
        processingObserved.incrementAndGet();
    }

    /** outcome is delivered, failed, or skipped when the database was unreachable. */
    public void recordWatchdogPing(String outcome) {
        watchdogPings.computeIfAbsent(outcome, key -> new AtomicLong()).incrementAndGet();
    }

    /** status is DELIVERED, FAILED, or SUPPRESSED. */
    public void recordNotification(String status) {
        notifications.computeIfAbsent(status, key -> new AtomicLong()).incrementAndGet();
    }

    /** outcome is created, rejected, ended, or rate_limited. */
    public void recordSession(String outcome) {
        sessions.computeIfAbsent(outcome, key -> new AtomicLong()).incrementAndGet();
    }

    /** Sessions currently valid, refreshed whenever the store changes. */
    public void recordActiveSessions(long count) {
        sessionsActive.set(count);
    }

    public void recordHttpRequest(String path, int status) {
        httpRequests.computeIfAbsent(path + " " + status, key -> new AtomicLong()).incrementAndGet();
    }

    public String render(long activeAlerts, long storedEvents) {
        StringBuilder builder = new StringBuilder();
        counter(builder, "eventwatch_events_received_total",
                "Telemetry events accepted and stored.", eventsReceived.get());
        counter(builder, "eventwatch_events_duplicate_total",
                "Repeat deliveries of an event id that was already stored.", eventsDuplicate.get());
        counter(builder, "eventwatch_database_failures_total",
                "SQLite operations that failed.", databaseFailures.get());

        labelledCounter(builder, "eventwatch_events_rejected_total",
                "Events refused before storage, by reason.", "reason", eventsRejected);
        labelledCounter(builder, "eventwatch_notifications_total",
                "Webhook delivery attempts by outcome.", "status", notifications);
        labelledCounter(builder, "eventwatch_watchdog_pings_total",
                "Dead-man switch heartbeats by outcome.", "outcome", watchdogPings);
        labelledCounter(builder, "eventwatch_sessions_total",
                "Operator sign-in attempts by outcome.", "outcome", sessions);
        httpCounter(builder);

        gauge(builder, "eventwatch_alerts_active",
                "Alerts currently OPEN or ACKNOWLEDGED.", activeAlerts);
        gauge(builder, "eventwatch_agents_silent",
                "Machines that have stopped reporting within the forget window.", silentAgents.get());
        gauge(builder, "eventwatch_events_stored",
                "Telemetry rows currently held in SQLite.", storedEvents);
        gauge(builder, "eventwatch_sessions_active",
                "Operator sessions currently valid.", sessionsActive.get());

        builder.append("# HELP eventwatch_processing_duration_seconds Time spent handling one ingestion request.\n");
        builder.append("# TYPE eventwatch_processing_duration_seconds summary\n");
        builder.append("eventwatch_processing_duration_seconds_sum ")
                .append(processingSeconds.sum()).append('\n');
        builder.append("eventwatch_processing_duration_seconds_count ")
                .append(processingObserved.get()).append('\n');
        return builder.toString();
    }

    private void counter(StringBuilder builder, String name, String help, long value) {
        builder.append("# HELP ").append(name).append(' ').append(help).append('\n');
        builder.append("# TYPE ").append(name).append(" counter\n");
        builder.append(name).append(' ').append(value).append('\n');
    }

    private void gauge(StringBuilder builder, String name, String help, long value) {
        builder.append("# HELP ").append(name).append(' ').append(help).append('\n');
        builder.append("# TYPE ").append(name).append(" gauge\n");
        builder.append(name).append(' ').append(value).append('\n');
    }

    private void labelledCounter(StringBuilder builder, String name, String help,
            String label, Map<String, AtomicLong> values) {
        builder.append("# HELP ").append(name).append(' ').append(help).append('\n');
        builder.append("# TYPE ").append(name).append(" counter\n");
        values.forEach((key, value) -> builder.append(name).append('{').append(label)
                .append("=\"").append(escape(key)).append("\"} ").append(value.get()).append('\n'));
    }

    private void httpCounter(StringBuilder builder) {
        String name = "eventwatch_http_requests_total";
        builder.append("# HELP ").append(name).append(" HTTP responses by route and status code.\n");
        builder.append("# TYPE ").append(name).append(" counter\n");
        httpRequests.forEach((key, value) -> {
            int separator = key.lastIndexOf(' ');
            String path = key.substring(0, separator);
            String status = key.substring(separator + 1);
            builder.append(name).append("{path=\"").append(escape(path))
                    .append("\",status=\"").append(escape(status)).append("\"} ")
                    .append(value.get()).append('\n');
        });
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
