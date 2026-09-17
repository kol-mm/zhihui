package com.aiknowledge.user.security;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Locks an account name for a while after repeated failed logins. The gateway already limits each IP address;
 * this closes the gap where many addresses guess the same account. Unknown names are tracked the same way, so a
 * lock does not reveal whether an account exists. State lives in this process, which matches the single
 * user-service instance the stack runs.
 */
@Component
public class LoginAttemptGuard {
    static final int MAX_FAILURES = 5;
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private static final int MAX_TRACKED_NAMES = 50_000;

    private final Clock clock;
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    public LoginAttemptGuard() {
        this(Clock.systemUTC());
    }

    LoginAttemptGuard(Clock clock) {
        this.clock = clock;
    }

    /** Whole seconds until this name may try again, or 0 when it is not locked. */
    public long lockedForSeconds(String username) {
        Attempts current = attempts.get(key(username));
        if (current == null || current.lockedUntil() == null) return 0;
        long remaining = Duration.between(clock.instant(), current.lockedUntil()).toSeconds();
        return Math.max(0, remaining);
    }

    public void recordFailure(String username) {
        Instant now = clock.instant();
        if (attempts.size() >= MAX_TRACKED_NAMES) prune(now);
        attempts.compute(key(username), (name, current) -> {
            if (current == null || now.isAfter(current.windowStart().plus(FAILURE_WINDOW))
                    || (current.lockedUntil() != null && !now.isBefore(current.lockedUntil()))) {
                return new Attempts(1, now, null);
            }
            int failures = current.failures() + 1;
            return new Attempts(failures, current.windowStart(), failures >= MAX_FAILURES ? now.plus(LOCK_DURATION) : null);
        });
    }

    public void recordSuccess(String username) {
        attempts.remove(key(username));
    }

    private void prune(Instant now) {
        attempts.entrySet().removeIf(entry -> {
            Attempts value = entry.getValue();
            boolean windowOver = now.isAfter(value.windowStart().plus(FAILURE_WINDOW));
            boolean lockOver = value.lockedUntil() == null || !now.isBefore(value.lockedUntil());
            return windowOver && lockOver;
        });
    }

    private static String key(String username) {
        String value = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    private record Attempts(int failures, Instant windowStart, Instant lockedUntil) {
    }
}
