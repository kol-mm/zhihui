package com.aiknowledge.gateway;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Answers whether a session was ended early. user-service writes the records (TokenRevocations there); keys and
 * meaning must match. With Redis unreachable the answer is "not revoked" and a warning is logged at most once a
 * minute: sessions keep working rather than the whole site signing everyone out.
 */
@FunctionalInterface
interface SessionRevocations {
    String TOKEN_KEY_PREFIX = "zh:auth:revoked-token:";
    String USER_KEY_PREFIX = "zh:auth:revoked-before:";

    Mono<Boolean> isRevoked(SessionTokens.Token token);

    final class Redis implements SessionRevocations {
        private static final Logger log = LoggerFactory.getLogger(SessionRevocations.class);
        private static final long WARN_INTERVAL_MILLIS = 60_000;
        /** Long enough to outlive any token that could still be presented. */
        private static final Duration REMEMBER_FOR = Duration.ofHours(12);
        private static final int REMEMBER_AT_MOST = 50_000;

        private final ReactiveStringRedisTemplate redis;
        private final Duration timeout;
        private final AtomicLong lastWarning = new AtomicLong();
        /**
         * Tokens Redis has already reported as revoked. With Redis unreachable the answer is still "not
         * revoked" for anything unknown — the site stays usable — but a logout or suspension this gateway has
         * already seen keeps biting, so an outage only misses the revocations made during it.
         */
        private final Cache<String, Boolean> knownRevoked = Caffeine.newBuilder()
                .maximumSize(REMEMBER_AT_MOST)
                .expireAfterWrite(REMEMBER_FOR)
                .build();

        Redis(ReactiveStringRedisTemplate redis, Duration timeout) {
            this.redis = redis;
            this.timeout = timeout;
        }

        private static String cacheKey(SessionTokens.Token token) {
            return token.tokenId() + '@' + token.userId() + '@' + token.issuedAt();
        }

        @Override
        public Mono<Boolean> isRevoked(SessionTokens.Token token) {
            String key = cacheKey(token);
            if (Boolean.TRUE.equals(knownRevoked.getIfPresent(key))) {
                return Mono.just(true);
            }
            return redis.opsForValue()
                    .multiGet(List.of(TOKEN_KEY_PREFIX + token.tokenId(), USER_KEY_PREFIX + token.userId()))
                    .map(values -> revoked(token, values))
                    .defaultIfEmpty(false)
                    .doOnNext(revoked -> {
                        if (revoked) knownRevoked.put(key, true);
                    })
                    .timeout(timeout)
                    .onErrorResume(error -> {
                        long now = System.currentTimeMillis();
                        long previous = lastWarning.get();
                        if (now - previous >= WARN_INTERVAL_MILLIS && lastWarning.compareAndSet(previous, now)) {
                            log.warn("Session revocation check skipped, Redis is unavailable; sessions already "
                                    + "known to be revoked are still refused: {}", error.toString());
                        }
                        return Mono.just(false);
                    });
        }

        /** Visible for tests: how many revoked sessions this instance is remembering. */
        long remembered() {
            knownRevoked.cleanUp();
            return knownRevoked.estimatedSize();
        }

        static boolean revoked(SessionTokens.Token token, List<String> values) {
            if (values == null || values.size() < 2) return false;
            if (values.get(0) != null) return true;
            String revokedBefore = values.get(1);
            try {
                return revokedBefore != null && token.issuedAt() < Long.parseLong(revokedBefore.trim());
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
    }
}
