package com.aiknowledge.user.store;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Pending codes for the local profile; they end with the process. */
@Repository
@Profile("!mysql")
public class InMemoryEmailVerificationStore implements EmailVerificationStore {
    private final Map<Long, PendingCode> codes = new ConcurrentHashMap<>();

    @Override
    public Optional<PendingCode> find(Long userId) {
        return Optional.ofNullable(codes.get(userId));
    }

    @Override
    public void save(PendingCode code) {
        codes.put(code.userId(), code);
    }

    @Override
    public void recordFailure(Long userId) {
        codes.computeIfPresent(userId, (id, code) -> new PendingCode(code.userId(), code.email(), code.codeHash(),
                code.expiresAt(), code.failedAttempts() + 1, code.sentAt(), code.sentDay(), code.sentCount()));
    }
}
