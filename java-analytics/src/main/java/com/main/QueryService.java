package com.main;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

public class QueryService {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

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

    public ObjectNode events(String level, Instant from, Instant to, int limit, int offset)
            throws SQLException {
        List<AnalyticsEngine.LogEntry> events = eventRepository.find(level, from, to, limit, offset);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("limit", limit);
        response.put("offset", offset);
        response.put("total", eventRepository.count(level, from, to));
        ArrayNode items = response.putArray("items");
        for (AnalyticsEngine.LogEntry event : events) {
            items.add(eventJson(event));
        }
        return response;
    }

    public ObjectNode summary() throws SQLException {
        List<AnalyticsEngine.LogEntry> recent = eventRepository.recent(movingWindowSize);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("total_events", eventRepository.count(null, null, null));
        response.put("active_alerts", alertRepository.findActive().size());
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
        response.put("message", alert.getMessage());
        response.put("status", alert.getStatus().name());
        response.put("first_seen", alert.getFirstSeen().toString());
        response.put("last_seen", alert.getLastSeen().toString());
        response.put("occurrence_count", alert.getOccurrenceCount());
        return response;
    }

    private ObjectNode eventJson(AnalyticsEngine.LogEntry event) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("event_id", event.eventId);
        response.put("level", event.level);
        response.put("msg", event.message);
        response.put("timestamp", event.timestamp.toString());
        response.put("cpu_usage", event.cpuUsage);
        response.put("ram_usage", event.ramUsage);
        return response;
    }
}
