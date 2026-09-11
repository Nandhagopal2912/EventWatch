package com.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ObservabilityTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final PrintStream originalOut = System.out;

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        StructuredLogger.configure(MAPPER, "json");
    }

    private String captureLog(String format, Runnable action) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        StructuredLogger.configure(MAPPER, format);
        action.run();
        System.setOut(originalOut);
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    void jsonLogLinesCarryTheReservedFields() throws Exception {
        String output = captureLog("json", () -> StructuredLogger.info("event stored",
                StructuredLogger.fields("correlation_id", "trace-1", "event_id", "e1")));

        JsonNode entry = MAPPER.readTree(output.trim());
        assertEquals("INFO", entry.path("level").asText());
        assertEquals("java-analytics", entry.path("service").asText());
        assertEquals("event stored", entry.path("message").asText());
        assertEquals("trace-1", entry.path("correlation_id").asText());
        assertFalse(entry.path("timestamp").asText().isBlank());
    }

    @Test
    void callerFieldsCannotOverwriteReservedKeys() throws Exception {
        // Regression: an event level named "level" used to clobber the log level.
        String output = captureLog("json", () -> StructuredLogger.info("event stored",
                StructuredLogger.fields("level", "ERROR", "service", "impostor", "message", "spoofed")));

        JsonNode entry = MAPPER.readTree(output.trim());
        assertEquals("INFO", entry.path("level").asText());
        assertEquals("java-analytics", entry.path("service").asText());
        assertEquals("event stored", entry.path("message").asText());
    }

    @Test
    void nullFieldValuesAreDropped() throws Exception {
        String output = captureLog("json", () -> StructuredLogger.info("partial",
                StructuredLogger.fields("correlation_id", null, "event_id", "e1")));

        JsonNode entry = MAPPER.readTree(output.trim());
        assertFalse(entry.has("correlation_id"));
        assertEquals("e1", entry.path("event_id").asText());
    }

    @Test
    void textFormatProducesReadableLines() {
        String output = captureLog("text", () -> StructuredLogger.info("event stored",
                StructuredLogger.fields("event_id", "e1")));
        assertTrue(output.startsWith("INFO "), output);
        assertTrue(output.contains("event stored"), output);
        assertTrue(output.contains("event_id=e1"), output);
    }

    @Test
    void metricsRenderCountersGaugesAndSummaries() {
        Metrics metrics = new Metrics();
        metrics.recordEventReceived();
        metrics.recordEventReceived();
        metrics.recordEventDuplicate();
        metrics.recordEventRejected("validation");
        metrics.recordEventRejected("validation");
        metrics.recordEventRejected("unauthorized");
        metrics.recordDatabaseFailure();
        metrics.recordNotification("DELIVERED");
        metrics.recordHttpRequest("/receive", 200);
        metrics.observeProcessingDuration(0.25);

        String output = metrics.render(3, 100);
        assertTrue(output.contains("eventwatch_events_received_total 2"), output);
        assertTrue(output.contains("eventwatch_events_duplicate_total 1"), output);
        assertTrue(output.contains("eventwatch_database_failures_total 1"), output);
        assertTrue(output.contains("eventwatch_events_rejected_total{reason=\"validation\"} 2"), output);
        assertTrue(output.contains("eventwatch_events_rejected_total{reason=\"unauthorized\"} 1"), output);
        assertTrue(output.contains("eventwatch_notifications_total{status=\"DELIVERED\"} 1"), output);
        assertTrue(output.contains("eventwatch_http_requests_total{path=\"/receive\",status=\"200\"} 1"), output);
        assertTrue(output.contains("eventwatch_alerts_active 3"), output);
        assertTrue(output.contains("eventwatch_events_stored 100"), output);
        assertTrue(output.contains("eventwatch_processing_duration_seconds_count 1"), output);
        assertTrue(output.contains("eventwatch_processing_duration_seconds_sum 0.25"), output);
    }

    @Test
    void everyMetricFamilyDeclaresHelpAndType() {
        String output = new Metrics().render(0, 0);
        long helpLines = output.lines().filter(line -> line.startsWith("# HELP ")).count();
        long typeLines = output.lines().filter(line -> line.startsWith("# TYPE ")).count();
        assertEquals(helpLines, typeLines);
        assertTrue(helpLines >= 8, "expected a metric family per counter, found " + helpLines);
    }

    @Test
    void labelValuesAreEscaped() {
        Metrics metrics = new Metrics();
        metrics.recordEventRejected("odd\"reason");
        assertTrue(metrics.render(0, 0).contains("reason=\"odd\\\"reason\""), "quotes must be escaped");
    }
}
