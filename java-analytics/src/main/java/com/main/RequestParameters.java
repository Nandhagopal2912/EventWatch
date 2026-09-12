package com.main;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Query-string parsing shared by every read endpoint. Nothing here touches the engine, so the
 * parsing rules can be tested without starting a server.
 */
final class RequestParameters {
    private RequestParameters() {
    }

    static Map<String, String> parse(String rawQuery) {
        Map<String, String> parameters = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return parameters;
        }
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            parameters.put(key, value);
        }
        return parameters;
    }

    static String optionalUpper(String value) {
        return value == null || value.isBlank() ? null : value.toUpperCase(Locale.ROOT);
    }

    static Instant optionalInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("time filters must be ISO-8601 values");
        }
    }

    static int boundedInteger(String value, int fallback, int maximum) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0 || parsed > maximum || (maximum == QueryService.MAX_LIMIT && parsed == 0)) {
                throw new IllegalArgumentException("query limit or offset is outside the allowed range");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("query limit and offset must be numbers");
        }
    }
}
