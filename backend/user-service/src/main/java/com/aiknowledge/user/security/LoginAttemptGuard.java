package com.aiknowledge.user.security;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Slows down password guessing without handing anyone the power to lock somebody else out.
 *
 * <p>Failures are counted twice. Per account and address: after {@link #MAX_FAILURES_PER_ADDRESS} wrong passwords
 * from one address, that address may not try that account for a while — but the owner, signing in from anywhere
 * else, is unaffected. Per account: after {@link #MAX_FAILURES_PER_ACCOUNT} wrong passwords from all addresses
 * together, the account is closed to everyone for a while, which is what stops guessing spread across many
 * addresses. Locking an account for everyone therefore takes several addresses and a solved captcha for every
 * attempt, where it used to take five attempts from anywhere.
 *
 * <p>The address is the one the gateway vouches for (see GatewaySecurityFilter); it replaces whatever the browser
 * sent under that name. IPv6 addresses are counted by their /64, since one subscriber is normally given a whole
 * /64 and could otherwise pose as billions of addresses. Unknown names are tracked exactly like real ones, so a
 * lock never reveals whether an account exists. State lives in this process, which matches the single
 * user-service instance the stack runs.
 */
@Component
public class LoginAttemptGuard {
    static final int MAX_FAILURES_PER_ADDRESS = 5;
    static final int MAX_FAILURES_PER_ACCOUNT = 20;
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private static final int MAX_TRACKED = 50_000;
    /** Where the address is missing or is not an address at all; all such attempts share one count. */
    static final String UNKNOWN_ADDRESS = "unknown";
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern IPV6_GROUP = Pattern.compile("[0-9a-f]{1,4}");

    private final Clock clock;
    private final Map<Pair, Attempts> byAddress = new ConcurrentHashMap<>();
    private final Map<String, Attempts> byAccount = new ConcurrentHashMap<>();

    public LoginAttemptGuard() {
        this(Clock.systemUTC());
    }

    LoginAttemptGuard(Clock clock) {
        this.clock = clock;
    }

    /** Whole seconds until this name may be tried again from this address, or 0 when it may be tried now. */
    public long lockedForSeconds(String username, String address) {
        Instant now = clock.instant();
        return Math.max(remaining(byAccount.get(account(username)), now),
                remaining(byAddress.get(new Pair(account(username), source(address))), now));
    }

    public void recordFailure(String username, String address) {
        Instant now = clock.instant();
        if (byAddress.size() >= MAX_TRACKED) prune(byAddress, now);
        if (byAccount.size() >= MAX_TRACKED) prune(byAccount, now);
        count(byAddress, new Pair(account(username), source(address)), MAX_FAILURES_PER_ADDRESS, now);
        count(byAccount, account(username), MAX_FAILURES_PER_ACCOUNT, now);
    }

    /**
     * A correct password from this address clears this address's count only. The account's count stays until its
     * window passes: signing in proves nothing about the other addresses that were guessing.
     */
    public void recordSuccess(String username, String address) {
        byAddress.remove(new Pair(account(username), source(address)));
    }

    /** After the password itself has been reset, guesses at the old one no longer matter from anywhere. */
    public void clearAccount(String username) {
        String account = account(username);
        byAccount.remove(account);
        byAddress.keySet().removeIf(pair -> pair.account().equals(account));
    }

    private static long remaining(Attempts attempts, Instant now) {
        if (attempts == null || attempts.lockedUntil() == null) return 0;
        return Math.max(0, Duration.between(now, attempts.lockedUntil()).toSeconds());
    }

    private static <K> void count(Map<K, Attempts> counts, K key, int limit, Instant now) {
        counts.compute(key, (ignored, current) -> {
            if (current == null || now.isAfter(current.windowStart().plus(FAILURE_WINDOW))
                    || (current.lockedUntil() != null && !now.isBefore(current.lockedUntil()))) {
                return new Attempts(1, now, null);
            }
            int failures = current.failures() + 1;
            return new Attempts(failures, current.windowStart(),
                    failures >= limit ? now.plus(LOCK_DURATION) : current.lockedUntil());
        });
    }

    private static <K> void prune(Map<K, Attempts> counts, Instant now) {
        counts.entrySet().removeIf(entry -> {
            Attempts value = entry.getValue();
            boolean windowOver = now.isAfter(value.windowStart().plus(FAILURE_WINDOW));
            boolean lockOver = value.lockedUntil() == null || !now.isBefore(value.lockedUntil());
            return windowOver && lockOver;
        });
    }

    private static String account(String username) {
        String value = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    /**
     * The count an address belongs to: an IPv4 address as it is, an IPv6 address by its /64, anything else
     * {@link #UNKNOWN_ADDRESS}. Only the text is read — nothing is ever looked up — and IPv6 is brought to one
     * spelling first, so writing the same address another way cannot start a fresh count.
     */
    static String source(String address) {
        String value = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        var ipv4 = IPV4.matcher(value);
        if (ipv4.matches()) {
            for (int group = 1; group <= 4; group++) {
                if (Integer.parseInt(ipv4.group(group)) > 255) return UNKNOWN_ADDRESS;
            }
            return value;
        }
        List<Integer> groups = ipv6Groups(value);
        if (groups == null) return UNKNOWN_ADDRESS;
        return String.format("%x:%x:%x:%x::/64", groups.get(0), groups.get(1), groups.get(2), groups.get(3));
    }

    /** The eight groups of a plain IPv6 address, with "::" expanded, or null when it is not one. */
    private static List<Integer> ipv6Groups(String value) {
        if (value.isEmpty() || value.length() > 39 || !value.contains(":")) return null;
        String[] halves = value.split("::", -1);
        if (halves.length > 2) return null;
        List<Integer> head = hexGroups(halves[0]);
        List<Integer> tail = halves.length == 2 ? hexGroups(halves[1]) : List.of();
        if (head == null || tail == null) return null;
        int missing = 8 - head.size() - tail.size();
        if (halves.length == 1 ? missing != 0 : missing < 1) return null;
        List<Integer> groups = new ArrayList<>(head);
        for (int index = 0; index < (halves.length == 2 ? missing : 0); index++) groups.add(0);
        groups.addAll(tail);
        return groups;
    }

    private static List<Integer> hexGroups(String part) {
        if (part.isEmpty()) return List.of();
        List<Integer> groups = new ArrayList<>();
        for (String group : part.split(":", -1)) {
            if (!IPV6_GROUP.matcher(group).matches()) return null;
            groups.add(Integer.parseInt(group, 16));
        }
        return groups;
    }

    private record Pair(String account, String address) {
    }

    private record Attempts(int failures, Instant windowStart, Instant lockedUntil) {
    }
}
