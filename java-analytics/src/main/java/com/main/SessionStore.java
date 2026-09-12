package com.main;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Operator sessions, held in memory for the life of the process.
 *
 * <p>A token is 256 bits from {@link SecureRandom} and is never derived from the API key, so a
 * stolen session cannot be turned back into the key that minted it. Nothing is persisted: a
 * restart signs everyone out, which is the honest behaviour for a single instance and avoids
 * storing a second long-lived secret next to the telemetry.
 */
class SessionStore {
    private static final int TOKEN_BYTES = 32;
    /** A bound, like every other map in this service: sessions are created by an open endpoint. */
    private static final int MAX_SESSIONS = 1000;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Instant> expiryByToken = new ConcurrentHashMap<>();
    private final Duration timeToLive;
    private final Metrics metrics;

    SessionStore(Duration timeToLive, Metrics metrics) {
        this.timeToLive = timeToLive;
        this.metrics = metrics;
    }

    String create(Instant now) {
        sweepExpired(now);
        if (expiryByToken.size() >= MAX_SESSIONS) {
            // Evicting the session closest to expiry beats refusing to sign anyone in.
            expiryByToken.entrySet().stream()
                    .min(Map.Entry.comparingByValue(Comparator.naturalOrder()))
                    .ifPresent(oldest -> expiryByToken.remove(oldest.getKey()));
        }
        byte[] token = new byte[TOKEN_BYTES];
        random.nextBytes(token);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        expiryByToken.put(encoded, now.plus(timeToLive));
        metrics.recordActiveSessions(expiryByToken.size());
        return encoded;
    }

    boolean isValid(String token, Instant now) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Instant expiry = expiryByToken.get(token);
        if (expiry == null) {
            return false;
        }
        if (!expiry.isAfter(now)) {
            expiryByToken.remove(token);
            metrics.recordActiveSessions(expiryByToken.size());
            return false;
        }
        return true;
    }

    void revoke(String token) {
        if (token != null && expiryByToken.remove(token) != null) {
            metrics.recordActiveSessions(expiryByToken.size());
        }
    }

    void sweepExpired(Instant now) {
        if (expiryByToken.values().removeIf(expiry -> !expiry.isAfter(now))) {
            metrics.recordActiveSessions(expiryByToken.size());
        }
    }

    long secondsToLive() {
        return timeToLive.toSeconds();
    }

    int size() {
        return expiryByToken.size();
    }
}
