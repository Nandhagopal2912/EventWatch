package com.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.cdimascio.dotenv.Dotenv;
import org.junit.jupiter.api.Test;

/**
 * The defaults a fresh install gets when it sets only an API key. Most of them are conveniences;
 * the retention one deletes data, so it is pinned here deliberately rather than left to whoever
 * next edits the record.
 */
class ConfigurationDefaultsTest {

    /** An empty dotenv, so every value comes from the in-code fallback. */
    private EngineConfiguration defaults() {
        return EngineConfiguration.fromDotenv(
                Dotenv.configure().directory("no-such-directory").ignoreIfMissing().load());
    }

    @Test
    void retentionIsOnByDefaultBecauseSamplingNeverStops() {
        // The agent samples every 60 seconds, so history grows at about 1,440 rows per machine per
        // day whether or not anyone uses the system. Keeping everything forever is not a default
        // anybody chose; it is one they would discover months later.
        assertEquals(30, defaults().retentionDays(),
                "a fresh install prunes telemetry older than 30 days");
        assertTrue(defaults().retentionSweepMinutes() > 0,
                "a non-positive sweep period would be rejected by the scheduler");
    }

    @Test
    void theAlertThresholdsAreThePublishedOnes() {
        EngineConfiguration configuration = defaults();
        assertEquals(85.0, configuration.cpuThreshold());
        assertEquals(80.0, configuration.ramThreshold());
        assertEquals(90.0, configuration.diskThreshold());
        assertEquals(5, configuration.repeatedErrorThreshold());
        assertEquals(10, configuration.agentSilenceMinutes());
    }

    @Test
    void theSharedKeyStillIngestsUntilAnOperatorTurnsItOff() {
        // Turning this off by default would break every existing agent on upgrade.
        assertTrue(defaults().sharedKeyIngestionEnabled());
    }
}
