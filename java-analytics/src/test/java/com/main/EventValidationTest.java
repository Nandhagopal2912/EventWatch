package com.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EventValidationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode event(String overrides) throws Exception {
        String base = """
                {"event_id":"abc","level":"ERROR","msg":"disk failure",
                 "timestamp":"2026-09-11T10:00:00Z","cpu_usage":10.5,"ram_usage":20.5%s}
                """.formatted(overrides.isEmpty() ? "" : "," + overrides);
        return MAPPER.readTree(base);
    }

    @Test
    void acceptsAWellFormedEvent() throws Exception {
        assertNull(EventValidation.validate(event("")));
    }

    @Test
    void acceptsAnUnknownAdditiveField() throws Exception {
        // The contract allows new fields; correlation_id was added in phase 9.
        assertNull(EventValidation.validate(event("\"correlation_id\":\"trace-1\"")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"level\":\"ERROR\",\"msg\":\"m\",\"timestamp\":\"2026-09-11T10:00:00Z\",\"cpu_usage\":1,\"ram_usage\":1}",
            "{\"event_id\":\"\",\"level\":\"ERROR\",\"msg\":\"m\",\"timestamp\":\"2026-09-11T10:00:00Z\",\"cpu_usage\":1,\"ram_usage\":1}",
            "{\"event_id\":123,\"level\":\"ERROR\",\"msg\":\"m\",\"timestamp\":\"2026-09-11T10:00:00Z\",\"cpu_usage\":1,\"ram_usage\":1}"
    })
    void rejectsABadEventId(String body) throws Exception {
        String error = EventValidation.validate(MAPPER.readTree(body));
        assertNotNull(error);
        assertTrue(error.contains("event_id"), error);
    }

    @Test
    void rejectsAnUnknownLevel() throws Exception {
        String body = """
                {"event_id":"a","level":"TRACE","msg":"m","timestamp":"2026-09-11T10:00:00Z",
                 "cpu_usage":1,"ram_usage":1}""";
        assertEquals("level must be INFO, WARN, ERROR, or CRITICAL",
                EventValidation.validate(MAPPER.readTree(body)));
    }

    @Test
    void acceptsEveryAllowedLevelInAnyCase() throws Exception {
        for (String level : new String[] {"info", "WARN", "Error", "critical"}) {
            String body = """
                    {"event_id":"a","level":"%s","msg":"m","timestamp":"2026-09-11T10:00:00Z",
                     "cpu_usage":1,"ram_usage":1}""".formatted(level);
            assertNull(EventValidation.validate(MAPPER.readTree(body)), level);
        }
    }

    @Test
    void rejectsABlankOrOversizedMessage() throws Exception {
        String blank = """
                {"event_id":"a","level":"INFO","msg":"   ","timestamp":"2026-09-11T10:00:00Z",
                 "cpu_usage":1,"ram_usage":1}""";
        assertEquals("msg must contain 1-1000 characters",
                EventValidation.validate(MAPPER.readTree(blank)));

        String oversized = """
                {"event_id":"a","level":"INFO","msg":"%s","timestamp":"2026-09-11T10:00:00Z",
                 "cpu_usage":1,"ram_usage":1}""".formatted("x".repeat(1001));
        assertEquals("msg must contain 1-1000 characters",
                EventValidation.validate(MAPPER.readTree(oversized)));
    }

    @Test
    void acceptsAMessageAtTheLengthLimit() throws Exception {
        String body = """
                {"event_id":"a","level":"INFO","msg":"%s","timestamp":"2026-09-11T10:00:00Z",
                 "cpu_usage":1,"ram_usage":1}""".formatted("x".repeat(1000));
        assertNull(EventValidation.validate(MAPPER.readTree(body)));
    }

    @Test
    void rejectsANonIsoTimestamp() throws Exception {
        String body = """
                {"event_id":"a","level":"INFO","msg":"m","timestamp":"11-09-2026",
                 "cpu_usage":1,"ram_usage":1}""";
        assertEquals("timestamp must be an ISO-8601 value",
                EventValidation.validate(MAPPER.readTree(body)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.1", "100.1", "\"40\""})
    void rejectsOutOfRangeOrNonNumericUsage(String cpuUsage) throws Exception {
        String body = """
                {"event_id":"a","level":"INFO","msg":"m","timestamp":"2026-09-11T10:00:00Z",
                 "cpu_usage":%s,"ram_usage":1}""".formatted(cpuUsage);
        assertEquals("cpu_usage and ram_usage must be numbers between 0 and 100",
                EventValidation.validate(MAPPER.readTree(body)));
    }

    @Test
    void acceptsUsageAtBothBounds() throws Exception {
        String body = """
                {"event_id":"a","level":"INFO","msg":"m","timestamp":"2026-09-11T10:00:00Z",
                 "cpu_usage":0,"ram_usage":100}""";
        assertNull(EventValidation.validate(MAPPER.readTree(body)));
    }

    @Test
    void boundedIntegerAppliesFallbacksAndLimits() {
        assertEquals(50, RequestParameters.boundedInteger(null, 50, QueryService.MAX_LIMIT));
        assertEquals(50, RequestParameters.boundedInteger("", 50, QueryService.MAX_LIMIT));
        assertEquals(10, RequestParameters.boundedInteger("10", 50, QueryService.MAX_LIMIT));
        assertThrowsIllegalArgument(() -> RequestParameters.boundedInteger("0", 50, QueryService.MAX_LIMIT));
        assertThrowsIllegalArgument(() -> RequestParameters.boundedInteger("201", 50, QueryService.MAX_LIMIT));
        assertThrowsIllegalArgument(() -> RequestParameters.boundedInteger("-1", 50, QueryService.MAX_LIMIT));
        assertThrowsIllegalArgument(() -> RequestParameters.boundedInteger("abc", 50, QueryService.MAX_LIMIT));
    }

    @Test
    void optionalInstantParsesOrRejects() {
        assertNull(RequestParameters.optionalInstant(null));
        assertNull(RequestParameters.optionalInstant("  "));
        assertEquals(Instant.parse("2026-09-11T10:00:00Z"),
                RequestParameters.optionalInstant("2026-09-11T10:00:00Z"));
        assertThrowsIllegalArgument(() -> RequestParameters.optionalInstant("yesterday"));
    }

    @Test
    void queryParametersDecodeEscapedValues() {
        Map<String, String> parameters = RequestParameters.parse("level=ERROR&msg=disk%20full&empty=");
        assertEquals("ERROR", parameters.get("level"));
        assertEquals("disk full", parameters.get("msg"));
        assertEquals("", parameters.get("empty"));
        assertTrue(RequestParameters.parse(null).isEmpty());
        assertTrue(RequestParameters.parse("").isEmpty());
    }

    @Test
    void optionalUpperNormalizesOnlyRealValues() {
        assertNull(RequestParameters.optionalUpper(null));
        assertNull(RequestParameters.optionalUpper("  "));
        assertEquals("ERROR", RequestParameters.optionalUpper("error"));
    }

    private void assertThrowsIllegalArgument(Runnable action) {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, action::run);
    }
}
