package com.aiknowledge.user.security;

import com.aiknowledge.common.LocalAuth;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ends sessions before their tokens expire. Tokens are stateless, so the gateway looks each one up in Redis:
 * a single token is revoked on logout, and every token of an account issued before a password change, a reset,
 * a role change or a suspension is revoked at once.
 *
 * <p>The default implementation keeps the records in memory, for tests and the local profile where no gateway
 * reads them; {@link RedisTokenRevocations} is used when {@code auth.revocation.store=redis}.
 */
public interface TokenRevocations {
    /** Redis keys, shared with the gateway (GatewaySessionFilter). */
    String TOKEN_KEY_PREFIX = "zh:auth:revoked-token:";
    String USER_KEY_PREFIX = "zh:auth:revoked-before:";

    /** Revokes one token until it would have expired anyway. */
    void revokeToken(String tokenId, long expiresAtEpochSecond);

    /** Revokes every token of the account issued before now. */
    void revokeUser(long userId);

    boolean isRevoked(LocalAuth.TokenInfo token);

    static TokenRevocations inMemory() {
        return new InMemory(Clock.systemUTC());
    }

    final class InMemory implements TokenRevocations {
        private final Clock clock;
        private final Map<String, Long> tokens = new ConcurrentHashMap<>();
        private final Map<Long, Long> users = new ConcurrentHashMap<>();

        InMemory(Clock clock) {
            this.clock = clock;
        }

        @Override
        public void revokeToken(String tokenId, long expiresAtEpochSecond) {
            if (tokenId == null || tokenId.isBlank()) return;
            long now = clock.instant().getEpochSecond();
            tokens.entrySet().removeIf(entry -> entry.getValue() <= now);
            tokens.put(tokenId, expiresAtEpochSecond);
        }

        @Override
        public void revokeUser(long userId) {
            users.put(userId, clock.instant().getEpochSecond());
        }

        @Override
        public boolean isRevoked(LocalAuth.TokenInfo token) {
            if (token == null) return false;
            Long revokedBefore = users.get(token.userId());
            return tokens.containsKey(token.tokenId())
                    || (revokedBefore != null && token.issuedAt() < revokedBefore);
        }
    }
}
