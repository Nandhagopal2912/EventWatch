package com.main;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

/**
 * Raises an alert for a machine that has stopped reporting. Nothing else notices silence:
 * every other rule needs an event to evaluate, so an agent that dies is invisible to them.
 *
 * The alert is resolved by {@link AlertEngine} when the machine reports again.
 */
public class AgentSilenceMonitor {
    static final String ALERT_RULE = "agent-silent";

    private final EventRepository events;
    private final AlertRepository alerts;
    private final AlertRules rules;
    private final NotificationService notificationService;
    private final Metrics metrics;
    private final int maxHosts;
    private final Duration forgetAfter;

    public AgentSilenceMonitor(EventRepository events, AlertRepository alerts, AlertRules rules,
            NotificationService notificationService, Metrics metrics, int maxHosts, Duration forgetAfter) {
        this.events = events;
        this.alerts = alerts;
        this.rules = rules;
        this.notificationService = notificationService;
        this.metrics = metrics;
        this.maxHosts = maxHosts;
        this.forgetAfter = forgetAfter;
    }

    /** Timer entry point: nothing may escape, or the scheduler cancels the task for good. */
    public void sweep() {
        try {
            check(Instant.now());
        } catch (Exception exception) {
            metrics.recordDatabaseFailure();
            StructuredLogger.error("agent silence sweep failed",
                    StructuredLogger.fields("error", String.valueOf(exception)));
        }
    }

    /** Returns how many machines are currently silent. */
    int check(Instant now) throws SQLException {
        int silent = 0;
        for (EventRepository.HostSummary host : events.hosts(maxHosts)) {
            AlertRules.EffectiveRule rule = rules.effective(AlertRules.AGENT_SILENT, host.hostId());
            String alertKey = AlertEngine.alertKey(ALERT_RULE, host.hostId());
            Duration silence = Duration.between(host.lastSeen(), now);

            if (!rule.enabled()) {
                // The rule no longer applies, so an alert it raised must not keep firing.
                notify(alerts.resolve(alertKey, now));
                continue;
            }
            // A machine gone far longer than the window was decommissioned, not lost. Alerting
            // on it forever would make every upgrade noisy with machines nobody runs any more.
            if (silence.compareTo(forgetAfter) > 0) {
                continue;
            }
            if (silence.toMinutes() >= (long) rule.threshold()) {
                String message = "no events for %d minutes (threshold %d)"
                        .formatted(silence.toMinutes(), (long) rule.threshold());
                notify(alerts.saveOccurrence(new AlertRecord(alertKey, AlertRules.AGENT_SILENT,
                        host.hostId(), message, now)));
                silent++;
            }
        }
        metrics.recordSilentAgents(silent);
        return silent;
    }

    private void notify(AlertTransition transition) {
        if (transition != null && notificationService != null) {
            notificationService.handle(transition);
        }
    }
}
