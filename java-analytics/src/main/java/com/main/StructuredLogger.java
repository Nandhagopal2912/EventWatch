package com.main;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Emits one JSON object per log line so the output can be shipped and queried.
 * Set LOG_FORMAT=text to restore human-readable lines during local demonstrations.
 */
public final class StructuredLogger {
    private static final String SERVICE = "java-analytics";
    private static ObjectMapper objectMapper = new ObjectMapper();
    private static boolean jsonFormat = true;

    private StructuredLogger() {
    }

    public static void configure(ObjectMapper mapper, String format) {
        objectMapper = mapper;
        jsonFormat = !"text".equalsIgnoreCase(format);
    }

    public static void info(String message, Map<String, Object> fields) {
        write("INFO", message, fields, false);
    }

    public static void warn(String message, Map<String, Object> fields) {
        write("WARN", message, fields, false);
    }

    public static void error(String message, Map<String, Object> fields) {
        write("ERROR", message, fields, true);
    }

    /** Convenience for the common single-field case. */
    public static Map<String, Object> fields(Object... keysAndValues) {
        Map<String, Object> fields = new TreeMap<>();
        for (int index = 0; index + 1 < keysAndValues.length; index += 2) {
            Object value = keysAndValues[index + 1];
            if (value != null) {
                fields.put(String.valueOf(keysAndValues[index]), value);
            }
        }
        return fields;
    }

    private static synchronized void write(String level, String message,
            Map<String, Object> fields, boolean toErrorStream) {
        String line;
        if (jsonFormat) {
            ObjectNode entry = objectMapper.createObjectNode();
            // Caller fields are written first so the reserved keys below always win.
            fields.forEach((key, value) -> entry.putPOJO(key, value));
            entry.put("timestamp", Instant.now().toString());
            entry.put("level", level);
            entry.put("service", SERVICE);
            entry.put("message", message);
            line = entry.toString();
        } else {
            StringBuilder builder = new StringBuilder();
            builder.append(String.format(Locale.ROOT, "%-5s %s", level, message));
            fields.forEach((key, value) -> builder.append(' ').append(key).append('=').append(value));
            line = builder.toString();
        }
        if (toErrorStream) {
            System.err.println(line);
        } else {
            System.out.println(line);
        }
    }
}
