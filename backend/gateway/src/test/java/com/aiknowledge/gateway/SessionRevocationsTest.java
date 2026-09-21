package com.aiknowledge.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * With Redis unreachable the site keeps working: an unknown session is treated as valid rather than signing
 * everyone out. What must not happen is forgetting a revocation the gateway has already been told about.
 */
class SessionRevocationsTest {
    private final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    private final ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);

    private SessionRevocations.Redis revocations() {
        when(redis.opsForValue()).thenReturn(values);
        return new SessionRevocations.Redis(redis, Duration.ofSeconds(1));
    }

    private SessionTokens.Token token(String id, long userId) {
        return new SessionTokens.Token(userId, id, 1_700_000_000L, 1_700_003_600L);
    }

    private void redisSays(List<String> answer) {
        when(values.multiGet(anyList())).thenReturn(Mono.just(answer));
    }

    private void redisIsDown() {
        when(values.multiGet(anyList())).thenReturn(Mono.error(new IllegalStateException("down")));
    }

    @Test
    void aRevokedSessionIsRefused() {
        SessionRevocations.Redis checker = revocations();
        redisSays(java.util.Arrays.asList("1", null));

        assertTrue(checker.isRevoked(token("abc", 7)).block());
    }

    @Test
    void anOrdinarySessionIsAllowed() {
        SessionRevocations.Redis checker = revocations();
        redisSays(java.util.Arrays.asList(null, null));

        assertFalse(checker.isRevoked(token("abc", 7)).block());
    }

    @Test
    void aRevocationAlreadySeenSurvivesAnOutage() {
        SessionRevocations.Redis checker = revocations();
        redisSays(java.util.Arrays.asList("1", null));
        assertTrue(checker.isRevoked(token("abc", 7)).block(), "first answer comes from Redis");

        redisIsDown();

        assertTrue(checker.isRevoked(token("abc", 7)).block(),
                "the session was already known to be revoked, so it stays refused");
    }

    @Test
    void anUnknownSessionStillWorksDuringAnOutage() {
        SessionRevocations.Redis checker = revocations();
        redisIsDown();

        assertFalse(checker.isRevoked(token("never-seen", 9)).block(),
                "failing closed here would sign everyone out whenever Redis blinks");
    }

    @Test
    void onlyRevokedSessionsAreRemembered() {
        SessionRevocations.Redis checker = revocations();
        redisSays(java.util.Arrays.asList(null, null));
        checker.isRevoked(token("ordinary", 1)).block();
        assertEquals(0, checker.remembered());

        redisSays(java.util.Arrays.asList("1", null));
        checker.isRevoked(token("revoked", 2)).block();

        assertEquals(1, checker.remembered());
    }

    @Test
    void aSessionRevokedByTimestampIsRememberedToo() {
        SessionRevocations.Redis checker = revocations();
        // Everything issued before this moment is revoked; the token above was issued at 1_700_000_000.
        redisSays(java.util.Arrays.asList(null, "1700000001"));
        assertTrue(checker.isRevoked(token("by-time", 3)).block());

        redisIsDown();

        assertTrue(checker.isRevoked(token("by-time", 3)).block());
    }

    @Test
    void aDifferentSessionOfTheSameMemberIsNotAssumedRevoked() {
        SessionRevocations.Redis checker = revocations();
        redisSays(java.util.Arrays.asList("1", null));
        checker.isRevoked(token("first", 5)).block();

        redisIsDown();

        assertFalse(checker.isRevoked(token("second", 5)).block(),
                "the cache is per session, not per member");
    }
}
