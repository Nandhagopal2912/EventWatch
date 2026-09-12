package com.main;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public class QueryService {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    /** The fleet listing repeats a per-host lookup, so the page size stays bounded. */
    public static final int MAX_HOSTS_LISTED = 200;

    private final EventRepository eventRepository;
    private final AlertRepository alertRepository;
    private final ObjectMapper objectMapper;
    private final int movingWindowSize;

    public QueryService(EventRepository eventRepository, AlertRepository alertRepository,
            ObjectMapper objectMapper, int movingWindowSize) {
        this.eventRepository = eventRepository;
        this.alertRepository = alertRepository;
        this.objectMapper = objectMapper;
        this.movingWindowSize = movingWindowSize;
    }

    public ObjectNode events(String level, String hostId, Instant from, Instant to, int limit, int offset)
            throws SQLException {
        List<LogEntry> events = eventRepository.find(level, hostId, from, to, limit, offset);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("limit", limit);
        response.put("offset", offset);
        response.put("total", eventRepository.count(level, hostId, from, to));
        ArrayNode items = response.putArray("items");
        for (LogEntry event : events) {
            items.add(eventJson(event));
        }
        return response;
    }

    public ObjectNode summary() throws SQLException {
        List<LogEntry> recent = eventRepository.recent(movingWindowSize);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("total_events", eventRepository.count(null, null, null));
        response.put("active_alerts", alertRepository.findActive().size());
        response.put("hosts", eventRepository.hosts(QueryService.MAX_HOSTS_LISTED).size());
        if (recent.isEmpty()) {
            response.putNull("latest_event");
            response.put("average_cpu", 0.0);
            response.put("average_ram", 0.0);
        } else {
            response.set("latest_event", eventJson(recent.get(0)));
            response.put("average_cpu", recent.stream().mapToDouble(event -> event.cpuUsage).average().orElse(0.0));
            response.put("average_ram", recent.stream().mapToDouble(event -> event.ramUsage).average().orElse(0.0));
        }
        return response;
    }

    public ObjectNode alertJson(AlertRecord alert) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("alert_key", alert.getAlertKey());
        response.put("alert_type", alert.getAlertType());
        response.put("host_id", alert.getHostId());
        response.put("message", alert.getMessage());
        response.put("status", alert.getStatus().name());
        response.put("first_seen", alert.getFirstSeen().toString());
        response.put("last_seen", alert.getLastSeen().toString());
        response.put("occurrence_count", alert.getOccurrenceCount());
        return response;
    }

    public ArrayNode notifications(List<NotificationRecord> deliveries) {
        ArrayNode items = objectMapper.createArrayNode();
        for (NotificationRecord delivery : deliveries) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("alert_key", delivery.alertKey());
            node.put("event_type", delivery.eventType());
            node.put("delivery_status", delivery.deliveryStatus());
            if (delivery.httpStatus() == null) {
                node.putNull("http_status");
            } else {
                node.put("http_status", delivery.httpStatus());
            }
            if (delivery.errorMessage() == null) {
                node.putNull("error_message");
            } else {
                node.put("error_message", delivery.errorMessage());
            }
            node.put("attempt_number", delivery.attemptNumber());
            node.put("attempted_at", delivery.attemptedAt().toString());
            items.add(node);
        }
        return items;
    }

    /** Every issued credential, newest first. The token itself never appears here. */
    public ArrayNode agents(List<AgentCredential> credentials) {
        ArrayNode items = objectMapper.createArrayNode();
        for (AgentCredential credential : credentials) {
            items.add(agentJson(credential));
        }
        return items;
    }

    public ObjectNode agentJson(AgentCredential credential) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", credential.id());
        node.put("host_id", credential.hostId());
        if (credential.label() == null) {
            node.putNull("label");
        } else {
            node.put("label", credential.label());
        }
        node.put("created_at", credential.createdAt().toString());
        if (credential.lastUsedAt() == null) {
            node.putNull("last_used_at");
        } else {
            node.put("last_used_at", credential.lastUsedAt().toString());
        }
        if (credential.revokedAt() == null) {
            node.putNull("revoked_at");
        } else {
            node.put("revoked_at", credential.revokedAt().toString());
        }
        return node;
    }

    /** The one response that ever carries the plaintext token, at the moment it is minted. */
    public ObjectNode agentMinted(AgentCredential credential, String token) {
        ObjectNode node = agentJson(credential);
        node.put("token", token);
        return node;
    }

    /** Stored rules plus the configuration defaults they override. */
    public ObjectNode rules(List<AlertRule> rules, Map<String, Double> defaults) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode defaultNode = response.putObject("defaults");
        for (String ruleType : AlertRules.RULE_TYPES) {
            defaultNode.put(ruleType, defaults.get(ruleType));
        }
        ArrayNode items = response.putArray("rules");
        for (AlertRule rule : rules) {
            items.add(ruleJson(rule));
        }
        return response;
    }

    public ObjectNode ruleJson(AlertRule rule) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("rule_type", rule.ruleType());
        // A fleet-wide rule has no host; the storage sentinel never leaks into the API.
        if (rule.fleetWide()) {
            node.putNull("host_id");
        } else {
            node.put("host_id", rule.scope());
        }
        node.put("threshold", rule.threshold());
        node.put("enabled", rule.enabled());
        node.put("updated_at", rule.updatedAt().toString());
        return node;
    }

    /** What applies to one machine, and which tier it came from. */
    public ObjectNode effectiveRules(String hostId, List<AlertRules.EffectiveRule> effective) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("host_id", hostId);
        ArrayNode items = response.putArray("rules");
        for (AlertRules.EffectiveRule rule : effective) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("rule_type", rule.ruleType());
            node.put("threshold", rule.threshold());
            node.put("enabled", rule.enabled());
            node.put("source", rule.source().name().toLowerCase(java.util.Locale.ROOT));
            items.add(node);
        }
        return response;
    }

    /** One row per machine the analytics service has heard from. */
    public ArrayNode hosts(int limit, AlertRules rules, Instant now) throws SQLException {
        ArrayNode items = objectMapper.createArrayNode();
        for (EventRepository.HostSummary host : eventRepository.hosts(limit)) {
            items.add(hostJson(host, rules, now));
        }
        return items;
    }

    /** Everything known about one machine, for the drill-down view. */
    public ObjectNode hostDetail(EventRepository.HostSummary host, AlertRules rules, Instant now,
            List<LogEntry> recentEvents, Map<String, Long> levelCounts,
            List<AlertRecord> activeAlerts) {
        ObjectNode response = hostJson(host, rules, now);
        response.put("first_seen", host.firstSeen().toString());

        ObjectNode averages = response.putObject("averages");
        averages.put("cpu", recentEvents.stream().mapToDouble(event -> event.cpuUsage).average().orElse(0.0));
        averages.put("ram", recentEvents.stream().mapToDouble(event -> event.ramUsage).average().orElse(0.0));
        averages.put("window", recentEvents.size());

        ObjectNode levels = response.putObject("levels");
        levelCounts.forEach(levels::put);

        ArrayNode alerts = response.putArray("alerts");
        for (AlertRecord alert : activeAlerts) {
            alerts.add(alertJson(alert));
        }
        ArrayNode effective = response.putArray("rules");
        for (AlertRules.EffectiveRule rule : rules.effectiveFor(host.hostId())) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("rule_type", rule.ruleType());
            node.put("threshold", rule.threshold());
            node.put("enabled", rule.enabled());
            node.put("source", rule.source().name().toLowerCase(java.util.Locale.ROOT));
            effective.add(node);
        }
        return response;
    }

    private ObjectNode hostJson(EventRepository.HostSummary host, AlertRules rules, Instant now) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("host_id", host.hostId());
        node.put("hostname", host.hostname());
        node.put("agent_version", host.agentVersion());
        if (host.queueDepth() == null) {
            node.putNull("queue_depth");
        } else {
            node.put("queue_depth", host.queueDepth());
        }
        if (host.diskUsage() == null) {
            node.putNull("disk_usage");
            node.putNull("disk_path");
        } else {
            node.put("disk_usage", host.diskUsage());
            node.put("disk_path", host.diskPath());
        }
        node.put("event_count", host.eventCount());
        node.put("last_seen", host.lastSeen().toString());

        long silentSeconds = Math.max(0, Duration.between(host.lastSeen(), now).getSeconds());
        node.put("silent_seconds", silentSeconds);
        AlertRules.EffectiveRule silence = rules.effective(AlertRules.AGENT_SILENT, host.hostId());
        boolean silent = silence.enabled() && silentSeconds >= (long) silence.threshold() * 60;
        node.put("status", silent ? "silent" : "reporting");
        return node;
    }

    private ObjectNode eventJson(LogEntry event) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("event_id", event.eventId);
        response.put("host_id", event.hostId);
        response.put("hostname", event.hostname);
        response.put("level", event.level);
        response.put("msg", event.message);
        response.put("timestamp", event.timestamp.toString());
        response.put("cpu_usage", event.cpuUsage);
        response.put("ram_usage", event.ramUsage);
        // Absent on a row from an older agent, and null rather than zero says so honestly.
        if (event.diskUsage == null) {
            response.putNull("disk_usage");
            response.putNull("disk_path");
        } else {
            response.put("disk_usage", event.diskUsage);
            response.put("disk_path", event.diskPath);
        }
        return response;
    }
}
