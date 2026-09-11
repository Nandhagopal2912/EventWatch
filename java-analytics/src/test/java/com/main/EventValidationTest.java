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
        assertNull(AnalyticsEngine.validateEvent(event("")));
    }

    @Test
    void acceptsAnUnknownAdditiveField() throws Exception {
        // The contract allows new fields; correlation_id was added in phase 9.
        assertNull(AnalyticsEngine.validateEvent(event("\"correlation_id\":\"trace-1\"")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"level\":\"ERROR\",\"msg\":\"m\",\"timestamp\":\"2026-09-11T10:00:00Z\",\"cpu_usage\":1,\"ram_usage\":1}",
            "{\"event_id\":\"\",\"level\":\"ERROR\",\"msg\":\"m\",\"timestamp\":\"2026-09-11T10:00:00Z\",\"cpu_usage\":1,\"ram_usage\":1}",
            "{\"event_id\":123,\"level\":\"ERROR\",\"msg\":\"m\",\"timestamp\":\"2026-09-11T10:00:00Z\",\"cpu_usage\":1,\"ram_usage\":1}"
    })
    void rejectsABadEventId(String body) throws Exception {
        String error = AnalyticsEngine.validateEvent(MAPPER.readTree(body));
        assertNotNull(error);
        assertTrue(error.contains("event_id"), error);
    }

    @Test
    void rejectsAnUnknownLevel() throws Exception {
        String body = """
                {"event_id":"a","level":"TRACE","msg":"m","timestamp":"2026-09-11T10:00:00Z",
                 "cpu_usage":1,"ram_usage":1}""";
        assertEquals("level must be INFO, WARN, ERROR, or CRITICAL",
                AnalyticsEngine.validateEvent(MAPPER.readTree(body)));
    }

    @Test
    void acceptsEveryAllowedLevelInAnyCase() throws Exception {
        for (String level : new String[] {"info", "WARN", "Error", "critical"}) {
            String body = """
                    {"event_id":"a","level":"%s","msg":"m","timestamp":"2026-09-11T10:00:00Z",
                     "cpu_usage":1,"ram_usage":1}""".formatted(level);
            assertNull(AnalyticsEngine.validateEvent(MAPPER.readTree(body)), level);
        }
    }

    @Test
    void rejectsABlankOrOversizedMessage() throws Exception {
        String blank = """
                {"event_id":"a","level":"INFO","msg":"   ","timestamp":"2026-09-11T10:00:00Z",
                 "cpu_usage":1,"ram_usage":1}""";
        assertEquals("msg must contain 1-1000 characters",
                AnalyticsEngine.validateEvent(MAPPER.readTree(blank)));

        String oversized = """
                {"event_id":"a","level":"INFO","msg":"%s","timestamp":"2026-09-11T10:00:00Z",
                 "cpu_usage":1,"ram_usage":1}""".formatted("x".repeat(1001));
        assertEquals("msg must contain 1-1000 characters",
                AnalyticsEngine.validateEvent(MAPPER.readTree(oversized)));
    }

    @Test
    void acceptsAMessageAtTheLengthLimit() throws Exception {
        String body = """
                {"event_id":"a","level":"INFO","msg":"%s","timestamp":"2026-09-11T10:00:00Z",
                 "cpu_usage":1,"ram_usage":1}""".formatted("x".repeat(1000));
        assertNull(AnalyticsEngine.validateEvent(MAPPER.readTree(body)));
    }

    @Test
    void rejectsANonIsoTimestamp() throws Exception {
        String body = """
                {"event_id":"a","level":"INFO","msg":"m","timestamp":"11-09-2026",
                 "cpu_usage":1,"ram_usage":1}""";
        assertEquals("timestamp must be an ISO-8601 value",
                AnalyticsEngine.validateEvent(MAPPER.readTree(body)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.1", "100.1", "\"40\""})
    void rejectsOutOfRangeOrNonNumericUsage(String cpuUsage) throws Exception {
        String body = """
                {"event_id":"a","level":"INFO","msg":"m","timestamp":"2026-09-11T10:00:00Z",
                 "cpu_usage":%s,"ram_usage":1}""".formatted(cpuUsage);
        assertEquals("cpu_usage and ram_usage must be numbers between 0 and 100",
                AnalyticsEngine.validateEvent(MAPPER.readTree(body)));
    }

    @Test
    void acceptsUsageAtBothBounds() throws Exception {
        String body = """
                {"event_id":"a","level":"INFO","msg":"m","timestamp":"2026-09-11T10:00:00Z",
                 "cpu_usage":0,"ram_usage":100}""";
        assertNull(AnalyticsEngine.validateEvent(MAPPER.readTree(body)));
    }

    @Test
    void boundedIntegerAppliesFallbacksAndLimits() {
        assertEquals(50, AnalyticsEngine.boundedInteger(null, 50, QueryService.MAX_LIMIT));
        assertEquals(50, AnalyticsEngine.boundedInteger("", 50, QueryService.MAX_LIMIT));
        assertEquals(10, AnalyticsEngine.boundedInteger("10", 50, QueryService.MAX_LIMIT));
        assertThrowsIllegalArgument(() -> AnalyticsEngine.boundedInteger("0", 50, QueryService.MAX_LIMIT));
        assertThrowsIllegalArgument(() -> AnalyticsEngine.boundedInteger("201", 50, QueryService.MAX_LIMIT));
        assertThrowsIllegalArgument(() -> AnalyticsEngine.boundedInteger("-1", 50, QueryService.MAX_LIMIT));
        assertThrowsIllegalArgument(() -> AnalyticsEngine.boundedInteger("abc", 50, QueryService.MAX_LIMIT));
    }

    @Test
    void optionalInstantParsesOrRejects() {
        assertNull(AnalyticsEngine.optionalInstant(null));
        assertNull(AnalyticsEngine.optionalInstant("  "));
        assertEquals(Instant.parse("2026-09-11T10:00:00Z"),
                AnalyticsEngine.optionalInstant("2026-09-11T10:00:00Z"));
        assertThrowsIllegalArgument(() -> AnalyticsEngine.optionalInstant("yesterday"));
    }

    @Test
    void parseTimestampFallsBackInsteadOfThrowing() {
        assertEquals(Instant.parse("2026-09-11T10:00:00Z"),
                AnalyticsEngine.parseTimestamp("2026-09-11T10:00:00Z"));
        assertNotNull(AnalyticsEngine.parseTimestamp("not-a-time"));
        assertNotNull(AnalyticsEngine.parseTimestamp(null));
    }

    @Test
    void queryParametersDecodeEscapedValues() {
        Map<String, String> parameters = AnalyticsEngine.queryParameters("level=ERROR&msg=disk%20full&empty=");
        assertEquals("ERROR", parameters.get("level"));
        assertEquals("disk full", parameters.get("msg"));
        assertEquals("", parameters.get("empty"));
        assertTrue(AnalyticsEngine.queryParameters(null).isEmpty());
        assertTrue(AnalyticsEngine.queryParameters("").isEmpty());
    }

    @Test
    void optionalUpperNormalizesOnlyRealValues() {
        assertNull(AnalyticsEngine.optionalUpper(null));
        assertNull(AnalyticsEngine.optionalUpper("  "));
        assertEquals("ERROR", AnalyticsEngine.optionalUpper("error"));
    }

    private void assertThrowsIllegalArgument(Runnable action) {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, action::run);
    }
}
