package com.aiknowledge.user.security;

import com.aiknowledge.common.LocalAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Stores revocations in Redis, where the gateway reads them. Records expire once every token they cover has
 * expired. If Redis cannot be reached the change itself still goes through and the failure is logged; the
 * gateway likewise keeps serving without the check (the agreed trade-off: availability over strictness).
 */
public class RedisTokenRevocations implements TokenRevocations {
    private static final Logger log = LoggerFactory.getLogger(RedisTokenRevocations.class);
    /** Covers the clock skew LocalAuth tolerates on top of the token lifetime. */
    private static final long SKEW_SECONDS = 120;

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisTokenRevocations(StringRedisTemplate redis) {
        this(redis, Clock.systemUTC());
    }

    RedisTokenRevocations(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public void revokeToken(String tokenId, long expiresAtEpochSecond) {
        if (tokenId == null || tokenId.isBlank()) return;
        long remaining = expiresAtEpochSecond - clock.instant().getEpochSecond();
        if (remaining <= 0) return;
        try {
            redis.opsForValue().set(TOKEN_KEY_PREFIX + tokenId, "1", Duration.ofSeconds(remaining + SKEW_SECONDS));
        } catch (RuntimeException error) {
            log.error("Could not record a logout in Redis; that token stays usable until it expires: {}", error.toString());
        }
    }

    @Override
    public void revokeUser(long userId) {
        long now = clock.instant().getEpochSecond();
        try {
            redis.opsForValue().set(USER_KEY_PREFIX + userId, String.valueOf(now),
                    Duration.ofSeconds(LocalAuth.tokenLifetimeSeconds() + SKEW_SECONDS));
        } catch (RuntimeException error) {
            log.error("Could not revoke the sessions of user {} in Redis; they stay usable until they expire: {}", userId, error.toString());
        }
    }

    @Override
    public boolean isRevoked(LocalAuth.TokenInfo token) {
        if (token == null) return false;
        try {
            List<String> values = redis.opsForValue().multiGet(List.of(
                    TOKEN_KEY_PREFIX + token.tokenId(), USER_KEY_PREFIX + token.userId()));
            if (values == null) return false;
            if (values.get(0) != null) return true;
            String revokedBefore = values.get(1);
            return revokedBefore != null && token.issuedAt() < Long.parseLong(revokedBefore);
        } catch (RuntimeException error) {
            log.warn("Could not check session revocation in Redis: {}", error.toString());
            return false;
        }
    }
}
