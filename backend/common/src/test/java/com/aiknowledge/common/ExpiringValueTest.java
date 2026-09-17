package com.aiknowledge.common;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExpiringValueTest {
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-17T08:00:00Z");

        void advance(Duration step) { now = now.plus(step); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();
    private final AtomicInteger loads = new AtomicInteger();
    private final ExpiringValue<Integer> value = new ExpiringValue<>(Duration.ofSeconds(60), clock);

    @Test
    void keepsTheValueUntilItExpires() {
        assertEquals(1, value.get(loads::incrementAndGet));
        clock.advance(Duration.ofSeconds(59));
        assertEquals(1, value.get(loads::incrementAndGet));
        clock.advance(Duration.ofSeconds(1));
        assertEquals(2, value.get(loads::incrementAndGet));
        assertEquals(2, loads.get());
    }

    @Test
    void invalidateDropsTheValueAtOnce() {
        assertEquals(1, value.get(loads::incrementAndGet));
        value.invalidate();
        assertEquals(2, value.get(loads::incrementAndGet));
        assertEquals(2, value.get(loads::incrementAndGet));
    }

    @Test
    void aLoadOverlappingAChangeIsNotKept() {
        // The change lands while the value is being read, so that read may predate it.
        assertEquals(1, value.get(() -> { value.invalidate(); return loads.incrementAndGet(); }));
        assertEquals(2, value.get(loads::incrementAndGet));
        assertEquals(2, value.get(loads::incrementAndGet));
    }

    @Test
    void aFailedLoadKeepsNothing() {
        assertThrows(IllegalStateException.class, () -> value.get(() -> { throw new IllegalStateException("db down"); }));
        assertEquals(1, value.get(loads::incrementAndGet));
    }

    @Test
    void rejectsAnEmptyLifetime() {
        assertThrows(IllegalArgumentException.class, () -> new ExpiringValue<>(Duration.ZERO));
    }
}
