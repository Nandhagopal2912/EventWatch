package com.main;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * The running summary printed after every stored event. Under {@code LOG_FORMAT=json} the same
 * numbers go out as one log line instead, because ASCII art in stdout breaks a log shipper.
 */
class TelemetryReport {
    private static final int TOP_ERROR_MESSAGES = 5;

    private final RecentEvents recentEvents;
    private final EventRepository eventRepository;
    private final Metrics metrics;
    private final boolean textLogging;

    TelemetryReport(RecentEvents recentEvents, EventRepository eventRepository, Metrics metrics,
            boolean textLogging) {
        this.recentEvents = recentEvents;
        this.eventRepository = eventRepository;
        this.metrics = metrics;
        this.textLogging = textLogging;
    }

    void publish() {
        List<LogEntry> window = recentEvents.snapshot();
        double averageCpu = window.stream().mapToDouble(log -> log.cpuUsage).average().orElse(0.0);
        double averageRam = window.stream().mapToDouble(log -> log.ramUsage).average().orElse(0.0);
        // Counting in the database keeps the report independent of how much history exists.
        Map<String, Long> errorCounts;
        try {
            errorCounts = eventRepository.topErrorMessages(TOP_ERROR_MESSAGES);
        } catch (SQLException exception) {
            metrics.recordDatabaseFailure();
            errorCounts = Map.of();
        }

        if (!textLogging) {
            StructuredLogger.info("telemetry snapshot", StructuredLogger.fields(
                    "total_events", recentEvents.total(),
                    "window_size", window.size(),
                    "average_cpu", averageCpu,
                    "average_ram", averageRam,
                    "top_errors", errorCounts));
            return;
        }

        System.out.println("\n================ LIVE CLOUD ALERT DASHBOARD ================");
        System.out.println("Total Logs Processed (All Types): " + recentEvents.total());
        System.out.printf("Last %d-event average: CPU %.1f%% | RAM %.1f%%%n",
                window.size(), averageCpu, averageRam);
        System.out.println("------------------------------------------------------------");
        if (errorCounts.isEmpty()) {
            System.out.println(" No critical errors detected yet.");
        } else {
            System.out.printf(" Top %d repeated error message(s):%n", errorCounts.size());
            errorCounts.forEach((errorMessage, count) -> System.out
                    .printf(" \uD83D\uDEA8 [ERROR] \"%s\" -> occurred %d time(s)\n", errorMessage, count));
        }
        System.out.println("============================================================");

    }
}
