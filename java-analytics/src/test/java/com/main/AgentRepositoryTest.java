package com.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Storage for per-agent credentials: the hash, never the token, and a throttled last-used. */
class AgentRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    private Database database;
    private AgentRepository repository;

    @BeforeEach
    void setUp() throws SQLException {
        database = Database.open(EngineConfiguration.forTesting(
                TestSupport.databaseUrl(temporaryDirectory, "agents.db"), "agents-secret"));
        database.initializeSchema();
        repository = new AgentRepository(database.connections());
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private AgentCredential credential(String id, String hostId, String token) {
        return new AgentCredential(id, hostId, "label", AgentTokens.hash(token), Instant.now(), null, null);
    }

    @Test
    void onlyTheHashIsStoredNeverThePlaintext() throws SQLException {
        String token = AgentTokens.generateToken();
        repository.create(credential("a1", "web-01", token));

        Optional<AgentCredential> found = repository.findByTokenHash(AgentTokens.hash(token));
        assertTrue(found.isPresent());
        assertNotEquals(token, found.get().tokenHash(), "the hash must not equal the raw token");
        assertEquals(AgentTokens.hash(token), found.get().tokenHash());
    }

    @Test
    void anUnknownTokenHashIsNotFound() throws SQLException {
        assertTrue(repository.findByTokenHash(AgentTokens.hash("never-issued")).isEmpty());
    }

    @Test
    void multipleActiveTokensPerHostAreAllowed() throws SQLException {
        // Rotation needs this: mint the replacement before revoking the original.
        String first = AgentTokens.generateToken();
        String second = AgentTokens.generateToken();
        repository.create(credential("a1", "web-01", first));
        repository.create(credential("a2", "web-01", second));

        assertTrue(repository.findByTokenHash(AgentTokens.hash(first)).isPresent());
        assertTrue(repository.findByTokenHash(AgentTokens.hash(second)).isPresent());
        assertEquals(2, repository.findAll().size());
    }

    @Test
    void revokingMarksTheCredentialButKeepsTheRow() throws SQLException {
        String token = AgentTokens.generateToken();
        repository.create(credential("a1", "web-01", token));

        assertTrue(repository.revoke("a1", Instant.now()));

        AgentCredential after = repository.findByTokenHash(AgentTokens.hash(token)).orElseThrow();
        assertTrue(after.revoked(), "the row is kept for history, not deleted");
    }

    @Test
    void revokingTwiceReportsNothingHappenedTheSecondTime() throws SQLException {
        repository.create(credential("a1", "web-01", AgentTokens.generateToken()));
        assertTrue(repository.revoke("a1", Instant.now()));
        assertFalse(repository.revoke("a1", Instant.now()), "already revoked, same as unknown to the caller");
    }

    @Test
    void revokingAnUnknownIdReportsFalse() throws SQLException {
        assertFalse(repository.revoke("does-not-exist", Instant.now()));
    }

    @Test
    void lastUsedStartsNull() throws SQLException {
        repository.create(credential("a1", "web-01", AgentTokens.generateToken()));
        assertNull(repository.findAll().get(0).lastUsedAt());
    }

    @Test
    void touchRecordsUseOnTheFirstCall() throws SQLException {
        repository.create(credential("a1", "web-01", AgentTokens.generateToken()));
        Instant now = Instant.now();

        repository.touch("a1", now, Duration.ofMinutes(5));

        assertEquals(now, repository.findAll().get(0).lastUsedAt());
    }

    @Test
    void touchIsThrottledRatherThanWritingEveryTime() throws SQLException {
        repository.create(credential("a1", "web-01", AgentTokens.generateToken()));
        Instant first = Instant.now();
        repository.touch("a1", first, Duration.ofMinutes(5));

        // Inside the throttle window: the earlier timestamp must survive.
        repository.touch("a1", first.plusSeconds(30), Duration.ofMinutes(5));
        assertEquals(first, repository.findAll().get(0).lastUsedAt(),
                "a write inside the throttle window must not move the timestamp");

        // Past the window: this one must land.
        Instant later = first.plus(Duration.ofMinutes(10));
        repository.touch("a1", later, Duration.ofMinutes(5));
        assertEquals(later, repository.findAll().get(0).lastUsedAt());
    }

    @Test
    void findAllOrdersNewestFirst() throws SQLException {
        repository.create(credential("a1", "web-01", AgentTokens.generateToken()));
        repository.create(new AgentCredential("a2", "web-02",
                null, AgentTokens.hash(AgentTokens.generateToken()), Instant.now().plusSeconds(60), null, null));

        List<AgentCredential> all = repository.findAll();
        assertEquals("a2", all.get(0).id());
        assertEquals("a1", all.get(1).id());
    }

    @Test
    void aLabelIsOptional() throws SQLException {
        repository.create(new AgentCredential("a1", "web-01", null,
                AgentTokens.hash(AgentTokens.generateToken()), Instant.now(), null, null));
        assertNull(repository.findAll().get(0).label());
    }
}
