package com.main;

import java.time.Instant;

/**
 * One minted credential for one machine. The plaintext token exists only at mint time — this
 * record, like every row read back from storage, carries the hash and never the secret itself.
 */
public record AgentCredential(
        String id, String hostId, String label, String tokenHash,
        Instant createdAt, Instant lastUsedAt, Instant revokedAt) {

    public boolean revoked() {
        return revokedAt != null;
    }
}
