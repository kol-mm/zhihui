package com.aiknowledge.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * One value that is reloaded at most once per time-to-live, for numbers that are expensive to compute and fine
 * to show a little stale. {@link #invalidate()} drops the value at once; a load that was already running when it
 * was called is returned to its caller but not kept, so a change is never hidden behind an older result.
 */
public final class ExpiringValue<T> {
    private record Entry<T>(T value, Instant loadedAt, long generation) { }

    private final Duration timeToLive;
    private final Clock clock;
    private final AtomicLong generation = new AtomicLong();
    private volatile Entry<T> entry;

    public ExpiringValue(Duration timeToLive) {
        this(timeToLive, Clock.systemUTC());
    }

    ExpiringValue(Duration timeToLive, Clock clock) {
        if (timeToLive == null || timeToLive.isNegative() || timeToLive.isZero()) {
            throw new IllegalArgumentException("time to live must be positive");
        }
        this.timeToLive = timeToLive;
        this.clock = Objects.requireNonNull(clock);
    }

    /** The kept value while it is fresh; otherwise loads a new one, one caller at a time. */
    public T get(Supplier<T> loader) {
        Entry<T> current = entry;
        if (isFresh(current)) return current.value();
        synchronized (this) {
            current = entry;
            if (isFresh(current)) return current.value();
            long loadingGeneration = generation.get();
            Instant startedAt = clock.instant();
            T value = loader.get();
            entry = new Entry<>(value, startedAt, loadingGeneration);
            return value;
        }
    }

    public void invalidate() {
        generation.incrementAndGet();
    }

    private boolean isFresh(Entry<T> current) {
        return current != null
                && current.generation() == generation.get()
                && clock.instant().isBefore(current.loadedAt().plus(timeToLive));
    }
}
