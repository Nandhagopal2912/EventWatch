package com.main;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates and hashes per-agent ingestion tokens.
 *
 * <p>The token is 256 random bits, the same entropy {@link SessionStore} uses for a session, and
 * for the same reason: high enough that hashing it for storage needs no salt or slow KDF the way
 * a human password would. SHA-256 of the raw bytes is what GitHub and GitLab do for personal
 * access tokens, and it is fast enough that an ingestion request pays nothing noticeable for it.
 */
final class AgentTokens {
    /** Prefixed so a leaked token is identifiable by a secret scanner or a plain grep. */
    private static final String TOKEN_PREFIX = "ewa_";
    private static final int TOKEN_BYTES = 32;
    private static final int ID_BYTES = 12;

    private static final SecureRandom RANDOM = new SecureRandom();

    private AgentTokens() {
    }

    static String generateId() {
        return encode(ID_BYTES);
    }

    static String generateToken() {
        return TOKEN_PREFIX + encode(TOKEN_BYTES);
    }

    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 is mandated by every JDK security provider; this cannot happen in practice.
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String encode(int byteCount) {
        byte[] bytes = new byte[byteCount];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
