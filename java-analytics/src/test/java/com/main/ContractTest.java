package com.main;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The fixture in {@code testdata/} is shared with the Go test suite, so both sides of the
 * pipeline are checked against one canonical event.
 */
class ContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path FIXTURE = Path.of("..", "testdata", "event-contract.json");

    private JsonNode contract() throws IOException {
        assertTrue(Files.exists(FIXTURE), "the shared contract fixture is missing: " + FIXTURE.toAbsolutePath());
        return MAPPER.readTree(Files.readString(FIXTURE));
    }

    @Test
    void theSharedContractEventIsAccepted() throws IOException {
        assertNull(EventValidation.validate(contract()),
                "the canonical collector payload must pass validation unchanged");
    }

    @Test
    void theContractCarriesEveryDocumentedField() throws IOException {
        JsonNode contract = contract();
        for (String field : new String[] {
                "event_id", "correlation_id", "host_id", "hostname", "agent_version", "queue_depth",
                "level", "msg", "timestamp", "cpu_usage", "ram_usage", "disk_usage", "disk_path"}) {
            assertTrue(contract.hasNonNull(field), "the contract must document " + field);
        }
        assertEquals(13, contract.size(), "an undocumented field was added to the contract fixture");
        assertTrue(contract.path("timestamp").asText().endsWith("Z"), "timestamps are UTC");
        assertEquals(Instant.parse("2026-09-04T18:46:00Z"), Instant.parse(contract.path("timestamp").asText()));
    }

    @Test
    void theContractRemainsValidWithoutItsOptionalFields() throws IOException {
        // The collector omits correlation_id when it is empty, an agent older than phase 12 sends
        // no identity at all, and a machine whose filesystems cannot be read sends no disk. All
        // of them must still validate.
        for (String optional : new String[] {
                "correlation_id", "host_id", "hostname", "agent_version", "queue_depth",
                "disk_usage", "disk_path"}) {
            ObjectNode without = ((ObjectNode) contract()).deepCopy();
            without.remove(optional);
            assertNull(EventValidation.validate(without), "removing " + optional + " must still validate");
        }
    }

    @Test
    void identityFieldsAreBoundedWhenPresent() throws IOException {
        ObjectNode oversized = ((ObjectNode) contract()).deepCopy();
        oversized.put("host_id", "x".repeat(129));
        assertTrue(EventValidation.validate(oversized) != null,
                "an unbounded host id would become an unbounded alert key");

        ObjectNode wrongType = ((ObjectNode) contract()).deepCopy();
        wrongType.put("hostname", 42);
        assertTrue(EventValidation.validate(wrongType) != null);

        ObjectNode negativeQueue = ((ObjectNode) contract()).deepCopy();
        negativeQueue.put("queue_depth", -1);
        assertTrue(EventValidation.validate(negativeQueue) != null,
                "a negative queue depth is not a measurement");

        ObjectNode impossibleDisk = ((ObjectNode) contract()).deepCopy();
        impossibleDisk.put("disk_usage", 140.0);
        assertTrue(EventValidation.validate(impossibleDisk) != null,
                "disk usage is a percentage like the other two");

        ObjectNode oversizedMount = ((ObjectNode) contract()).deepCopy();
        oversizedMount.put("disk_path", "/".repeat(129));
        assertTrue(EventValidation.validate(oversizedMount) != null,
                "a mount path is bounded like every other text field");
    }

    @Test
    void removingAnyRequiredFieldBreaksValidation() throws IOException {
        for (String field : new String[] {
                "event_id", "level", "msg", "timestamp", "cpu_usage", "ram_usage"}) {
            ObjectNode broken = ((ObjectNode) contract()).deepCopy();
            broken.remove(field);
            assertTrue(EventValidation.validate(broken) != null,
                    "removing " + field + " should fail validation");
        }
    }
}
