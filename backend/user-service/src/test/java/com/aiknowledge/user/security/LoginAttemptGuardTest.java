package com.aiknowledge.user.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginAttemptGuardTest {
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-17T08:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void locksAfterRepeatedFailuresAndUnlocksLater() {
        MutableClock clock = new MutableClock();
        LoginAttemptGuard guard = new LoginAttemptGuard(clock);
        for (int i = 1; i < LoginAttemptGuard.MAX_FAILURES; i++) guard.recordFailure("Alice");
        assertEquals(0, guard.lockedForSeconds("alice"));

        guard.recordFailure(" ALICE ");
        long locked = guard.lockedForSeconds("alice");
        assertTrue(locked > 0 && locked <= LoginAttemptGuard.LOCK_DURATION.toSeconds(), String.valueOf(locked));
        assertEquals(0, guard.lockedForSeconds("bob"));

        clock.advance(LoginAttemptGuard.LOCK_DURATION.plusSeconds(1));
        assertEquals(0, guard.lockedForSeconds("alice"));
        // The first failure after a lock starts a fresh count.
        guard.recordFailure("alice");
        assertEquals(0, guard.lockedForSeconds("alice"));
    }

    @Test
    void failuresOutsideTheWindowDoNotAccumulate() {
        MutableClock clock = new MutableClock();
        LoginAttemptGuard guard = new LoginAttemptGuard(clock);
        for (int i = 1; i < LoginAttemptGuard.MAX_FAILURES; i++) guard.recordFailure("carol");
        clock.advance(LoginAttemptGuard.FAILURE_WINDOW.plusSeconds(1));
        guard.recordFailure("carol");
        assertEquals(0, guard.lockedForSeconds("carol"));
    }

    @Test
    void successClearsTheCount() {
        LoginAttemptGuard guard = new LoginAttemptGuard(new MutableClock());
        for (int i = 1; i < LoginAttemptGuard.MAX_FAILURES; i++) guard.recordFailure("dave");
        guard.recordSuccess("dave");
        guard.recordFailure("dave");
        assertEquals(0, guard.lockedForSeconds("dave"));
    }
}
