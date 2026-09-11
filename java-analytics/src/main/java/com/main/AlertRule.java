package com.main;

import java.time.Instant;

/**
 * One configured alert rule. A scope of {@link #ALL_HOSTS} applies fleet-wide; any other scope
 * is a host id and overrides the fleet-wide rule for that machine only.
 */
public record AlertRule(String ruleType, String scope, double threshold, boolean enabled, Instant updatedAt) {
    /**
     * A sentinel rather than NULL: SQLite and PostgreSQL both treat NULLs as distinct in a
     * unique key, so NULL would allow two fleet-wide rules of the same type.
     */
    public static final String ALL_HOSTS = "*";

    public boolean fleetWide() {
        return ALL_HOSTS.equals(scope);
    }
}
