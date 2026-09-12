package com.main;

import java.time.Instant;

/**
 * One telemetry event, as it travels between the repositories, the alert engine, and the
 * in-memory window. Identity fields are optional because an agent older than phase 12 sends
 * none, and its events are attributed to {@link #UNKNOWN_HOST} rather than rejected.
 */
class LogEntry {
    static final String UNKNOWN_HOST = "unknown";

    String level;
    String message;
    String eventId;
    String hostId;
    String hostname;
    String agentVersion;
    Integer queueDepth;
    Instant timestamp;
    double cpuUsage;
    double ramUsage;
    // Null when the agent could not read any filesystem. An unknown disk is not an empty one,
    // so it stays absent rather than becoming a zero the rules would read as healthy.
    Double diskUsage;
    String diskPath;

    LogEntry(String eventId, String level, String message, Instant timestamp,
            double cpuUsage, double ramUsage) {
        this(eventId, level, message, timestamp, UNKNOWN_HOST, null, cpuUsage, ramUsage);
    }

    LogEntry(String eventId, String level, String message, Instant timestamp,
            String hostId, String hostname, double cpuUsage, double ramUsage) {
        this(eventId, level, message, timestamp, hostId, hostname, null, null, cpuUsage, ramUsage);
    }

    LogEntry(String eventId, String level, String message, Instant timestamp, String hostId,
            String hostname, String agentVersion, Integer queueDepth, double cpuUsage, double ramUsage) {
        this.agentVersion = agentVersion;
        this.queueDepth = queueDepth;
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
