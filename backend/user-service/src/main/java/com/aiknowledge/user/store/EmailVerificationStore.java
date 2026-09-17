package com.aiknowledge.user.store;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/** The pending email verification code of each account; only a hash of the code is kept. */
public interface EmailVerificationStore {
    record PendingCode(Long userId, String email, String codeHash, LocalDateTime expiresAt, int failedAttempts,
                       LocalDateTime sentAt, LocalDate sentDay, int sentCount) {
    }

    Optional<PendingCode> find(Long userId);

    /** Replaces the account's code. */
    void save(PendingCode code);

    void recordFailure(Long userId);
}
