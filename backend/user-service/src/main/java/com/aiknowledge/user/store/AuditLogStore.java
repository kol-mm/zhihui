package com.aiknowledge.user.store;

import com.aiknowledge.common.AuditEntry;

import java.time.LocalDateTime;
import java.util.List;

/** The administrator action log. Entries are only added, and removed once older than the retention period. */
public interface AuditLogStore {
    /** A stored entry; {@code createdAt} is storage (UTC) wall time. */
    record StoredEntry(long id, LocalDateTime createdAt, AuditEntry entry) {
    }

    /**
     * Filters for one page, newest first.
     *
     * @param actor    an administrator's id, or part of their username
     * @param keyword  part of the target's name or the summary, or the exact target id
     * @param from     inclusive, storage wall time
     * @param to       exclusive, storage wall time
     * @param beforeId the id of the last entry already shown
     */
    record Query(String category, String action, String actor, Long subjectUserId, String keyword,
                 LocalDateTime from, LocalDateTime to, Long beforeId, int limit) {
    }

    StoredEntry save(AuditEntry entry);

    List<StoredEntry> page(Query query);

    int removeBefore(LocalDateTime cutoff);

    static boolean matches(StoredEntry stored, Query query) {
        AuditEntry entry = stored.entry();
        if (query.beforeId() != null && stored.id() >= query.beforeId()) return false;
        if (blank(query.category()) != null && !query.category().equals(entry.category())) return false;
        if (blank(query.action()) != null && !query.action().equals(entry.action())) return false;
        if (query.subjectUserId() != null && !query.subjectUserId().equals(entry.subjectUserId())) return false;
        if (query.from() != null && stored.createdAt().isBefore(query.from())) return false;
        if (query.to() != null && !stored.createdAt().isBefore(query.to())) return false;
        String actor = blank(query.actor());
        if (actor != null) {
            Long actorId = numeric(actor);
            boolean byId = actorId != null && actorId.equals(entry.actorId());
            boolean byName = entry.actorName() != null && entry.actorName().toLowerCase().contains(actor.toLowerCase());
            if (!byId && !byName) return false;
        }
        String keyword = blank(query.keyword());
        if (keyword != null) {
            String lower = keyword.toLowerCase();
            boolean found = keyword.equals(entry.targetId())
                    || (entry.targetLabel() != null && entry.targetLabel().toLowerCase().contains(lower))
                    || (entry.summary() != null && entry.summary().toLowerCase().contains(lower));
            if (!found) return false;
        }
        return true;
    }

    static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static Long numeric(String value) {
        return value != null && value.matches("\\d{1,18}") ? Long.valueOf(value) : null;
    }
}
