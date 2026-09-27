package com.aiknowledge.user.store;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Platform API keys. A key is found by the SHA-256 digest of its secret; the secret itself is never stored.
 *
 * <p>A revoked key is kept rather than deleted, so its history — who made it, what it could do, when it was
 * last used and who revoked it — stays readable, and none of the operations below will bring it back.
 */
public interface ApiKeyStore {
    record ApiKey(Long id, String name, String prefix, String keyHash, List<String> scopes, Long actingUserId,
                  Long createdBy, LocalDateTime expiresAt, LocalDateTime lastUsedAt, LocalDateTime revokedAt,
                  Long revokedBy, LocalDateTime createdAt, LocalDateTime updatedAt) {
        public boolean revoked() {
            return revokedAt != null;
        }

        public boolean expired(LocalDateTime now) {
            return expiresAt != null && !expiresAt.isAfter(now);
        }

        public String status(LocalDateTime now) {
            if (revoked()) return "REVOKED";
            return expired(now) ? "EXPIRED" : "ACTIVE";
        }
    }

    ApiKey create(String name, String prefix, String keyHash, List<String> scopes, Long actingUserId,
                  Long createdBy, LocalDateTime expiresAt);

    Optional<ApiKey> find(Long id);

    Optional<ApiKey> findByHash(String keyHash);

    /** Newest first. */
    List<ApiKey> list();

    /** Replaces the key's settings; empty when it has gone or was revoked. */
    Optional<ApiKey> update(Long id, String name, List<String> scopes, Long actingUserId, LocalDateTime expiresAt);

    /** Gives the key a new secret, which ends the old one at once; empty when it has gone or was revoked. */
    Optional<ApiKey> rotate(Long id, String prefix, String keyHash);

    /** Empty when the key has gone or was already revoked. */
    Optional<ApiKey> revoke(Long id, Long revokedBy);

    /**
     * Records that the key was just used. Written at most once a minute per key: this is called on every request
     * a key makes, and knowing it was used a minute ago is as useful as knowing the second.
     */
    void touch(Long id, LocalDateTime usedAt);
}
