package com.main;

/** A meaningful change in an alert lifecycle. Ordinary occurrences are retained
 * so the notification policy can decide whether a cooldown reminder is due. */
public record AlertTransition(AlertRecord alert, Type type) {
    public enum Type {
        OPENED("alert.opened"),
        REOPENED("alert.reopened"),
        ACKNOWLEDGED("alert.acknowledged"),
        RESOLVED("alert.resolved"),
        OCCURRENCE("alert.occurrence");

        private final String eventType;

        Type(String eventType) {
            this.eventType = eventType;
        }

        public String eventType() {
            return eventType;
        }
    }
}
