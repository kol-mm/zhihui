package com.aiknowledge.user.store;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Password reset requests. A member asks from the login page (PENDING); an admin issues a one-time code
 * (ISSUED) and passes it on outside the site; the member sets a new password with it (COMPLETED). Too many
 * wrong codes end the request (EXPIRED), and an admin can close one without issuing a code (CLOSED).
 * Only a hash of the code is stored.
 */
public interface PasswordResetStore {
    String PENDING = "PENDING";
    String ISSUED = "ISSUED";
    String COMPLETED = "COMPLETED";
    String EXPIRED = "EXPIRED";
    String CLOSED = "CLOSED";

    record ResetRequest(Long id, Long userId, String username, String contact, String status, String codeHash,
                        LocalDateTime codeExpiresAt, int failedAttempts, Long handledBy, String note,
                        LocalDateTime createdAt, LocalDateTime updatedAt) {
        public boolean open() {
            return PENDING.equals(status) || ISSUED.equals(status);
        }
    }

    /** The account's request that is still PENDING or ISSUED, if any. */
    Optional<ResetRequest> findOpen(Long userId);

    Optional<ResetRequest> find(Long id);

    ResetRequest create(Long userId, String username, String contact);

    /** A repeated request from the member: keeps the request and its state, refreshes the contact note. */
    Optional<ResetRequest> refresh(Long id, String contact);

    /** Stores a new code (replacing any earlier one) and resets the attempt count; works on any unfinished request. */
    Optional<ResetRequest> issue(Long id, String codeHash, LocalDateTime expiresAt, Long adminId);

    /** Counts a wrong code; the request becomes EXPIRED once {@code maxAttempts} is reached. */
    Optional<ResetRequest> recordFailure(Long id, int maxAttempts);

    /**
     * Moves an ISSUED request to COMPLETED. False when another attempt got there first or the code was replaced,
     * so a code is only ever used once.
     */
    boolean complete(Long id, String codeHash);

    /** Closes an unfinished request without resetting anything. */
    Optional<ResetRequest> close(Long id, Long adminId, String note);

    /** Newest first; {@code status} is optional and {@code beforeId} is the cursor. */
    List<ResetRequest> page(String status, Long beforeId, int limit);

    long count(String status);
}
