package com.aiknowledge.user.security;

import com.aiknowledge.common.LocalAuth;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenRevocationsTest {
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-17T08:00:00Z");

        void advance(Duration step) { now = now.plus(step); }
        long seconds() { return now.getEpochSecond(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();

    @Test
    void revokingAnAccountEndsOnlyTokensIssuedBeforeIt() {
        TokenRevocations revocations = new TokenRevocations.InMemory(clock);
        var earlier = new LocalAuth.TokenInfo(7L, "a", clock.seconds() - 60, clock.seconds() + 3600);
        var otherUser = new LocalAuth.TokenInfo(8L, "b", clock.seconds() - 60, clock.seconds() + 3600);
        revocations.revokeUser(7L);
        var reissued = new LocalAuth.TokenInfo(7L, "c", clock.seconds(), clock.seconds() + 3600);

        assertTrue(revocations.isRevoked(earlier));
        assertFalse(revocations.isRevoked(otherUser));
        assertFalse(revocations.isRevoked(reissued));
        assertFalse(revocations.isRevoked(null));
    }

    @Test
    void revokingATokenLeavesTheAccountsOtherSessions() {
        TokenRevocations revocations = new TokenRevocations.InMemory(clock);
        var loggedOut = new LocalAuth.TokenInfo(7L, "a", clock.seconds(), clock.seconds() + 60);
        var otherDevice = new LocalAuth.TokenInfo(7L, "b", clock.seconds(), clock.seconds() + 60);
        revocations.revokeToken("a", loggedOut.expiresAt());
        revocations.revokeToken("", loggedOut.expiresAt());

        assertTrue(revocations.isRevoked(loggedOut));
        assertFalse(revocations.isRevoked(otherDevice));
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisRecordsExpireWithTheTokensTheyCover() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisTokenRevocations revocations = new RedisTokenRevocations(redis, clock);

        revocations.revokeToken("abc", clock.seconds() + 600);
        verify(values).set("zh:auth:revoked-token:abc", "1", Duration.ofSeconds(720));
        revocations.revokeUser(9L);
        verify(values).set("zh:auth:revoked-before:9", String.valueOf(clock.seconds()),
                Duration.ofSeconds(LocalAuth.tokenLifetimeSeconds() + 120));

        when(values.multiGet(anyList())).thenReturn(Arrays.asList(null, String.valueOf(clock.seconds())));
        assertTrue(revocations.isRevoked(new LocalAuth.TokenInfo(9L, "x", clock.seconds() - 1, clock.seconds() + 60)));
        assertFalse(revocations.isRevoked(new LocalAuth.TokenInfo(9L, "y", clock.seconds(), clock.seconds() + 60)));
        when(values.multiGet(anyList())).thenReturn(Arrays.asList("1", null));
        assertTrue(revocations.isRevoked(new LocalAuth.TokenInfo(9L, "abc", clock.seconds(), clock.seconds() + 60)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUnreachableRedisDoesNotBreakSignOutOrRequests() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisConnectionFailureException down = new RedisConnectionFailureException("down");
        doThrow(down).when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.multiGet(anyList())).thenThrow(down);
        RedisTokenRevocations revocations = new RedisTokenRevocations(redis, clock);

        assertDoesNotThrow(() -> revocations.revokeToken("abc", clock.seconds() + 60));
        assertDoesNotThrow(() -> revocations.revokeUser(1L));
        assertFalse(revocations.isRevoked(new LocalAuth.TokenInfo(1L, "abc", clock.seconds(), clock.seconds() + 60)));
        // Tokens that already expired need no record at all.
        revocations.revokeToken("old", clock.seconds() - 1);
        verify(values, never()).set(eq("zh:auth:revoked-token:old"), anyString(), any(Duration.class));
    }
}
