package com.main;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides which threshold applies to a machine. The most specific rule wins: a rule for that
 * host, then a fleet-wide rule, then the value from {@code .env}. With no rules stored, every
 * machine behaves exactly as it did before rules existed.
 */
public class AlertRules {
    public static final String HIGH_CPU = "HIGH_CPU";
    public static final String HIGH_RAM = "HIGH_RAM";
    public static final String REPEATED_ERROR = "REPEATED_ERROR";
    public static final String AGENT_SILENT = "AGENT_SILENT";
    public static final List<String> RULE_TYPES =
            List.of(HIGH_CPU, HIGH_RAM, REPEATED_ERROR, AGENT_SILENT);
    /** A week: beyond this a silence threshold is a decommissioning policy, not an alert. */
    private static final int MAX_SILENCE_MINUTES = 10_080;
    private static final int MAX_HOST_ID_LENGTH = 128;

    /** Where an effective rule came from, most specific first. */
    public enum Source {
        HOST, FLEET, DEFAULT
    }

    public record EffectiveRule(String ruleType, double threshold, boolean enabled, Source source) {
    }

    private final AlertRuleRepository repository;
    private final Map<String, Double> defaults;
    private final int movingWindowSize;
    // Every ingested event reads the rules, so they are cached and replaced wholesale on write.
    private volatile Map<String, AlertRule> cache = Map.of();

    public AlertRules(AlertRuleRepository repository, double cpuThreshold, double ramThreshold,
            int repeatedErrorThreshold, int agentSilenceMinutes, int movingWindowSize) throws SQLException {
        this.repository = repository;
        this.movingWindowSize = movingWindowSize;
        Map<String, Double> configured = new LinkedHashMap<>();
        configured.put(HIGH_CPU, cpuThreshold);
        configured.put(HIGH_RAM, ramThreshold);
        configured.put(REPEATED_ERROR, (double) repeatedErrorThreshold);
        configured.put(AGENT_SILENT, (double) agentSilenceMinutes);
        this.defaults = Map.copyOf(configured);
        if (repeatedErrorThreshold > movingWindowSize) {
            StructuredLogger.warn("REPEATED_ERROR_THRESHOLD can never fire", StructuredLogger.fields(
                    "threshold", repeatedErrorThreshold, "window", movingWindowSize));
        }
        reload();
    }

    /** Rules backed by configuration alone, for callers with no database. */
    public static AlertRules defaultsOnly(double cpuThreshold, double ramThreshold,
            int repeatedErrorThreshold, int agentSilenceMinutes, int movingWindowSize) {
        try {
            return new AlertRules(null, cpuThreshold, ramThreshold, repeatedErrorThreshold,
                    agentSilenceMinutes, movingWindowSize);
        } catch (SQLException exception) {
            throw new IllegalStateException("a rule set without a repository cannot fail to load", exception);
        }
    }

    public EffectiveRule effective(String ruleType, String hostId) {
        Map<String, AlertRule> rules = cache;
        AlertRule hostRule = hostId == null ? null : rules.get(key(ruleType, hostId));
        if (hostRule != null) {
            return new EffectiveRule(ruleType, hostRule.threshold(), hostRule.enabled(), Source.HOST);
        }
        AlertRule fleetRule = rules.get(key(ruleType, AlertRule.ALL_HOSTS));
        if (fleetRule != null) {
            return new EffectiveRule(ruleType, fleetRule.threshold(), fleetRule.enabled(), Source.FLEET);
        }
        return new EffectiveRule(ruleType, defaults.get(ruleType), true, Source.DEFAULT);
    }

    /** Every rule type as it applies to one machine. */
    public List<EffectiveRule> effectiveFor(String hostId) {
        List<EffectiveRule> effective = new ArrayList<>();
        for (String ruleType : RULE_TYPES) {
            effective.add(effective(ruleType, hostId));
        }
        return effective;
    }

    public List<AlertRule> all() {
        List<AlertRule> rules = new ArrayList<>(cache.values());
        rules.sort(Comparator.comparing(AlertRule::ruleType).thenComparing(AlertRule::scope));
        return rules;
    }

    public Map<String, Double> defaults() {
        return defaults;
    }

    public synchronized AlertRule save(String ruleType, String hostId, double threshold, boolean enabled)
            throws SQLException {
        String error = validate(ruleType, hostId, threshold);
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        requireRepository();
        AlertRule rule = new AlertRule(ruleType, scopeOf(hostId), threshold, enabled, Instant.now());
        repository.save(rule);
        reload();
        return rule;
    }

    /** Removes an override so the next broader rule applies. Returns false if there was none. */
    public synchronized boolean delete(String ruleType, String hostId) throws SQLException {
        String error = validateScope(ruleType, hostId);
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        requireRepository();
        boolean removed = repository.delete(ruleType, scopeOf(hostId));
        reload();
        return removed;
    }

    /** Returns a readable reason the rule is unusable, or null when it is valid. */
    public String validate(String ruleType, String hostId, double threshold) {
        String error = validateScope(ruleType, hostId);
        if (error != null) {
            return error;
        }
        if (!Double.isFinite(threshold)) {
            return "threshold must be a finite number";
        }
        if (AGENT_SILENT.equals(ruleType)) {
            if (threshold != Math.rint(threshold) || threshold < 1 || threshold > MAX_SILENCE_MINUTES) {
                return "an AGENT_SILENT threshold must be a whole number of minutes from 1 to "
                        + MAX_SILENCE_MINUTES;
            }
        } else if (REPEATED_ERROR.equals(ruleType)) {
            if (threshold != Math.rint(threshold) || threshold < 1 || threshold > movingWindowSize) {
                return "a REPEATED_ERROR threshold must be a whole number from 1 to " + movingWindowSize
                        + ": the rule sees only the last " + movingWindowSize
                        + " events, so a larger threshold could never fire";
            }
        } else if (threshold < 0 || threshold > 100) {
            return "a " + ruleType + " threshold must be a percentage between 0 and 100";
        }
        return null;
    }

    private String validateScope(String ruleType, String hostId) {
        if (ruleType == null || !RULE_TYPES.contains(ruleType)) {
            return "rule_type must be one of HIGH_CPU, HIGH_RAM, REPEATED_ERROR, AGENT_SILENT";
        }
        if (hostId == null) {
            return null;
        }
        if (hostId.isBlank()) {
            return "host_id must not be blank; omit it for a fleet-wide rule";
        }
        if (hostId.length() > MAX_HOST_ID_LENGTH) {
            return "host_id must contain at most " + MAX_HOST_ID_LENGTH + " characters";
        }
        if (AlertRule.ALL_HOSTS.equals(hostId)) {
            return "host_id \"*\" is reserved for fleet-wide rules; omit host_id instead";
        }
        return null;
    }

    private synchronized void reload() throws SQLException {
        if (repository == null) {
            return;
        }
        Map<String, AlertRule> fresh = new HashMap<>();
        for (AlertRule rule : repository.findAll()) {
            fresh.put(key(rule.ruleType(), rule.scope()), rule);
        }
        cache = Map.copyOf(fresh);
    }

    private void requireRepository() {
        if (repository == null) {
            throw new IllegalStateException("this rule set is configuration-only and cannot be changed");
        }
    }

    private static String key(String ruleType, String scope) {
        return ruleType + "@" + scope;
    }

    private static String scopeOf(String hostId) {
        return hostId == null ? AlertRule.ALL_HOSTS : hostId;
    }
}
