package com.main;

import java.time.Instant;

public class AlertRecord {
    private final String alertKey;
    private final String alertType;
    private final String message;
    private AlertStatus status;
    private final Instant firstSeen;
    private Instant lastSeen;
    private int occurrenceCount;

    public AlertRecord(String alertKey, String alertType, String message, Instant firstSeen) {
        this.alertKey = alertKey;
        this.alertType = alertType;
        this.message = message;
        this.status = AlertStatus.OPEN;
        this.firstSeen = firstSeen;
        this.lastSeen = firstSeen;
        this.occurrenceCount = 1;
    }

    public AlertRecord(String alertKey, String alertType, String message,
            AlertStatus status, Instant firstSeen, Instant lastSeen, int occurrenceCount) {
        this.alertKey = alertKey;
        this.alertType = alertType;
        this.message = message;
        this.status = status;
        this.firstSeen = firstSeen;
        this.lastSeen = lastSeen;
        this.occurrenceCount = occurrenceCount;
    }

    public String getAlertKey() {
        return alertKey;
    }

    public String getAlertType() {
        return alertType;
    }

    public String getMessage() {
        return message;
    }

    public AlertStatus getStatus() {
        return status;
    }

    public Instant getFirstSeen() {
        return firstSeen;
    }

    public Instant getLastSeen() {
        return lastSeen;
    }

    public int getOccurrenceCount() {
        return occurrenceCount;
    }

    public void recordOccurrence(Instant occurrenceTime) {
        status = AlertStatus.OPEN;
        lastSeen = occurrenceTime;
        occurrenceCount++;
    }

    public void resolve() {
        status = AlertStatus.RESOLVED;
    }
}
