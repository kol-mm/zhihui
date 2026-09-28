package com.aiknowledge.user.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static com.aiknowledge.user.security.LoginAttemptGuard.FAILURE_WINDOW;
import static com.aiknowledge.user.security.LoginAttemptGuard.LOCK_DURATION;
import static com.aiknowledge.user.security.LoginAttemptGuard.MAX_FAILURES_PER_ACCOUNT;
import static com.aiknowledge.user.security.LoginAttemptGuard.MAX_FAILURES_PER_ADDRESS;
import static com.aiknowledge.user.security.LoginAttemptGuard.UNKNOWN_ADDRESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginAttemptGuardTest {
    private static final String ATTACKER = "203.0.113.7";
    private static final String OWNER = "198.51.100.20";

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

    private final MutableClock clock = new MutableClock();
    private final LoginAttemptGuard guard = new LoginAttemptGuard(clock);

    private void fail(String username, String address, int times) {
        for (int i = 0; i < times; i++) guard.recordFailure(username, address);
    }

    private boolean locked(String username, String address) {
        return guard.lockedForSeconds(username, address) > 0;
    }

    @Test
    void oneAddressIsLockedOutOfOneAccountAfterFiveFailures() {
        fail("Alice", ATTACKER, MAX_FAILURES_PER_ADDRESS - 1);
        assertEquals(0, guard.lockedForSeconds("alice", ATTACKER));

        guard.recordFailure(" ALICE ", ATTACKER);
        long seconds = guard.lockedForSeconds("alice", ATTACKER);
        assertTrue(seconds > 0 && seconds <= LOCK_DURATION.toSeconds(), String.valueOf(seconds));
        assertEquals(0, guard.lockedForSeconds("bob", ATTACKER));
    }

    /** The point of counting by address: five wrong guesses from a stranger no longer shut the owner out. */
    @Test
    void theOwnerElsewhereIsNotLockedOutByAStrangersGuesses() {
        fail("alice", ATTACKER, MAX_FAILURES_PER_ADDRESS);

        assertTrue(locked("alice", ATTACKER));
        assertTrue(!locked("alice", OWNER));
    }

    /** Guessing spread over many addresses still closes the account, for everyone, at the account-wide limit. */
    @Test
    void guessesFromManyAddressesCloseTheAccountForEveryone() {
        int addresses = MAX_FAILURES_PER_ACCOUNT / MAX_FAILURES_PER_ADDRESS;
        for (int i = 1; i < addresses; i++) fail("alice", "203.0.113." + i, MAX_FAILURES_PER_ADDRESS);
        fail("alice", "203.0.113.200", MAX_FAILURES_PER_ADDRESS - 1);
        assertTrue(!locked("alice", OWNER));

        guard.recordFailure("alice", "203.0.113.201");
        assertTrue(locked("alice", OWNER));
        assertTrue(locked("alice", "192.0.2.99"));
        assertTrue(!locked("bob", OWNER));

        clock.advance(LOCK_DURATION.plusSeconds(1));
        assertTrue(!locked("alice", OWNER));
    }

    @Test
    void locksEndAndAFreshFailureStartsAFreshCount() {
        fail("alice", ATTACKER, MAX_FAILURES_PER_ADDRESS);
        clock.advance(LOCK_DURATION.plusSeconds(1));
        assertTrue(!locked("alice", ATTACKER));

        guard.recordFailure("alice", ATTACKER);
        assertTrue(!locked("alice", ATTACKER));
    }

    @Test
    void failuresOutsideTheWindowDoNotAccumulate() {
        fail("carol", ATTACKER, MAX_FAILURES_PER_ADDRESS - 1);
        clock.advance(FAILURE_WINDOW.plusSeconds(1));
        guard.recordFailure("carol", ATTACKER);
        assertTrue(!locked("carol", ATTACKER));

        for (int i = 0; i < MAX_FAILURES_PER_ACCOUNT - 1; i++) guard.recordFailure("carol", "198.18.0." + i);
        clock.advance(FAILURE_WINDOW.plusSeconds(1));
        guard.recordFailure("carol", "198.18.1.1");
        assertTrue(!locked("carol", OWNER));
    }

    /** Signing in proves nothing about the other addresses, so it clears only its own address's count. */
    @Test
    void aSuccessClearsOnlyItsOwnAddress() {
        fail("dave", OWNER, MAX_FAILURES_PER_ADDRESS - 1);
        guard.recordSuccess("dave", OWNER);
        guard.recordFailure("dave", OWNER);
        assertTrue(!locked("dave", OWNER));

        fail("dave", ATTACKER, MAX_FAILURES_PER_ADDRESS);
        guard.recordSuccess("dave", OWNER);
        assertTrue(locked("dave", ATTACKER));

        // The account-wide count is kept too: 5 + 5 + 5 so far from the owner's and two other addresses, then 5 more.
        fail("dave", "192.0.2.1", MAX_FAILURES_PER_ADDRESS);
        guard.recordSuccess("dave", OWNER);
        fail("dave", "192.0.2.2", MAX_FAILURES_PER_ADDRESS);
        assertTrue(locked("dave", OWNER));
    }

    @Test
    void aPasswordResetClearsEveryCountForTheAccount() {
        fail("erin", ATTACKER, MAX_FAILURES_PER_ADDRESS);
        for (int i = 0; i < MAX_FAILURES_PER_ACCOUNT; i++) guard.recordFailure("erin", "198.18.2." + i);
        fail("frank", ATTACKER, MAX_FAILURES_PER_ADDRESS);

        guard.clearAccount(" ERIN ");

        assertTrue(!locked("erin", ATTACKER));
        assertTrue(!locked("erin", OWNER));
        assertTrue(locked("frank", ATTACKER));
    }

    /** One subscriber is normally given a whole /64; counting each address in it apart would give them billions. */
    @Test
    void ipv6AddressesInOneSlash64ShareACount() {
        for (int i = 1; i <= MAX_FAILURES_PER_ADDRESS; i++) guard.recordFailure("gina", "2001:db8:1:2:0:0:0:" + Integer.toHexString(i));
        assertTrue(locked("gina", "2001:db8:1:2:ffff:ffff:ffff:ffff"));
        assertTrue(locked("gina", "2001:0DB8:0001:0002::abcd"));
        assertTrue(!locked("gina", "2001:db8:1:3:0:0:0:1"));
    }

    @Test
    void addressesAreReadAsTextAndSpelledOneWay() {
        assertEquals("203.0.113.7", LoginAttemptGuard.source(" 203.0.113.7 "));
        assertEquals("2001:db8:0:0::/64", LoginAttemptGuard.source("2001:db8::1"));
        assertEquals("2001:db8:0:0::/64", LoginAttemptGuard.source("2001:0db8:0000:0000:0000:0000:0000:0002"));
        assertEquals("0:0:0:0::/64", LoginAttemptGuard.source("::1"));
        for (String notAnAddress : new String[]{null, "", "unknown", "abc", "cafe", "999.1.1.1", "1.2.3", "1.2.3.4.5",
                "1::2::3", "1:2:3:4:5:6:7:8:9", "12345::1", "::g", "1.2.3.4, 5.6.7.8", "example.com"}) {
            assertEquals(UNKNOWN_ADDRESS, LoginAttemptGuard.source(notAnAddress), String.valueOf(notAnAddress));
        }
    }

    @Test
    void missingAndMalformedAddressesShareOneCount() {
        fail("hank", null, 2);
        fail("hank", "", 1);
        fail("hank", "not-an-address", MAX_FAILURES_PER_ADDRESS - 3);
        assertTrue(locked("hank", null));
        assertTrue(!locked("hank", OWNER));
    }
}
