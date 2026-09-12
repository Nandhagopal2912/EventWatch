package com.main;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Per-agent ingestion credentials. Portable SQL, so it runs unchanged on both backends, like
 * every other repository here.
 */
public class AgentRepository {
    private final ConnectionProvider connections;

    public AgentRepository(ConnectionProvider connections) {
        this.connections = connections;
    }

    public void create(AgentCredential credential) throws SQLException {
        String query = "INSERT INTO agents (id, host_id, label, token_hash, created_at) "
                + "VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = connections.getConnection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, credential.id());
            statement.setString(2, credential.hostId());
            statement.setString(3, credential.label());
            statement.setString(4, credential.tokenHash());
            statement.setString(5, credential.createdAt().toString());
            statement.executeUpdate();
        }
    }

    /**
     * Looks up a credential by the hash of a presented token, revoked or not: the caller decides
     * what a revoked match means, but the lookup itself must not distinguish "revoked" from
     * "never issued" at the SQL level, or the two would need different queries to leak nothing.
     */
    public Optional<AgentCredential> findByTokenHash(String tokenHash) throws SQLException {
        String query = "SELECT id, host_id, label, token_hash, created_at, last_used_at, revoked_at "
                + "FROM agents WHERE token_hash = ?";
        try (Connection connection = connections.getConnection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, tokenHash);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(toCredential(results)) : Optional.empty();
            }
        }
    }

    public List<AgentCredential> findAll() throws SQLException {
        String query = "SELECT id, host_id, label, token_hash, created_at, last_used_at, revoked_at "
                + "FROM agents ORDER BY created_at DESC";
        List<AgentCredential> credentials = new ArrayList<>();
        try (Connection connection = connections.getConnection();
                PreparedStatement statement = connection.prepareStatement(query);
                ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                credentials.add(toCredential(results));
            }
        }
        return credentials;
    }

    /**
     * Records use without a write on every ingested event: an agent can submit hundreds of
     * events a minute, and a timestamp accurate to the nearest {@code throttle} window is all
     * "is this credential still alive" needs. The condition is in the WHERE clause so the whole
     * thing is one statement rather than a read followed by a conditional write.
     */
    public void touch(String id, Instant now, java.time.Duration throttle) throws SQLException {
        String query = "UPDATE agents SET last_used_at = ? "
                + "WHERE id = ? AND (last_used_at IS NULL OR last_used_at < ?)";
        try (Connection connection = connections.getConnection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, now.toString());
            statement.setString(2, id);
            statement.setString(3, now.minus(throttle).toString());
            statement.executeUpdate();
        }
    }

    /** Returns false when there was no such active credential to revoke. */
    public boolean revoke(String id, Instant now) throws SQLException {
        String query = "UPDATE agents SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL";
        try (Connection connection = connections.getConnection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, now.toString());
            statement.setString(2, id);
            return statement.executeUpdate() > 0;
        }
    }

    private AgentCredential toCredential(ResultSet results) throws SQLException {
        return new AgentCredential(
                results.getString("id"),
                results.getString("host_id"),
                results.getString("label"),
                results.getString("token_hash"),
                Instant.parse(results.getString("created_at")),
                parseNullable(results.getString("last_used_at")),
                parseNullable(results.getString("revoked_at")));
    }

    private static Instant parseNullable(String value) {
        return value == null ? null : Instant.parse(value);
    }
}
