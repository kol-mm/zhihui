package com.aiknowledge.user.store;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Profile changes waiting for review. A member has at most one open change; submitting again replaces it, so the
 * queue never fills up with one member's edits.
 */
public interface ProfileAuditStore {
    String PENDING = "PENDING";
    String APPROVED = "APPROVED";
    String REJECTED = "REJECTED";

    record ProfileChange(Long id, Long userId, String nickname, String signature, String beforeNickname,
                         String beforeSignature, String status, String reason, Long reviewerId,
                         LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /** keyword matches the change id, the member id or the proposed nickname; beforeId is the keyset cursor. */
    record ChangeQuery(String keyword, String status, Long beforeId, int limit) {
    }

    Optional<ProfileChange> findOpen(Long userId);

    /** The member's newest change, whatever its state; what their own profile page reports. */
    Optional<ProfileChange> findLatest(Long userId);

    Optional<ProfileChange> find(Long changeId);

    /** Replaces the member's open change, keeping its place in the queue, or starts one. */
    ProfileChange submit(Long userId, String nickname, String signature, String beforeNickname, String beforeSignature);

    /** Decides an open change; empty when it was already decided or has gone. */
    Optional<ProfileChange> resolve(Long changeId, String status, String reason, Long reviewerId);

    List<ProfileChange> listChanges();

    default List<ProfileChange> pageChanges(ChangeQuery query) {
        return matchingChanges(query)
                .filter(change -> query.beforeId() == null || change.id() < query.beforeId())
                .limit(Math.max(query.limit(), 0))
                .toList();
    }

    default long countChanges(ChangeQuery query) {
        return matchingChanges(query).count();
    }

    default long countPending() {
        return listChanges().stream().filter(change -> PENDING.equals(change.status())).count();
    }

    private Stream<ProfileChange> matchingChanges(ChangeQuery query) {
        String keyword = query.keyword() == null ? "" : query.keyword().trim().toLowerCase();
        String status = query.status() == null ? "" : query.status().trim();
        return listChanges().stream()
                .sorted(java.util.Comparator.comparingLong(ProfileChange::id).reversed())
                .filter(change -> status.isBlank() || status.equals(change.status()))
                .filter(change -> keyword.isEmpty()
                        || String.valueOf(change.id()).contains(keyword)
                        || String.valueOf(change.userId()).contains(keyword)
                        || (change.nickname() != null && change.nickname().toLowerCase().contains(keyword)));
    }
}
