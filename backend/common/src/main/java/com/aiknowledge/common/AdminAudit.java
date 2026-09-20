package com.aiknowledge.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Records what administrators change: account decisions, moderation, deletions and platform settings. Each service
 * reports its own actions; the user service keeps the log. Recording never blocks or fails the action itself.
 */
public interface AdminAudit {
    /** Account and permission changes. */
    String ACCOUNTS = "ACCOUNT";
    /** Knowledge files, categories and their reports. */
    String KNOWLEDGE = "KNOWLEDGE";
    /** Posts, comments and drafts. */
    String COMMUNITY = "COMMUNITY";
    /** Private conversations and messages. */
    String MESSAGES = "MESSAGE";
    /** Tickets and FAQ. */
    String SUPPORT = "SUPPORT";
    /** Platform and AI settings. */
    String SYSTEM = "SYSTEM";

    List<String> CATEGORIES = List.of(ACCOUNTS, KNOWLEDGE, COMMUNITY, MESSAGES, SUPPORT, SYSTEM);

    /** Records the event for the administrator the authorization belongs to; ignored for anyone else. */
    void record(String authorization, Event event);

    AdminAudit NONE = (authorization, event) -> { };

    /**
     * One action.
     *
     * @param action        what was done, e.g. {@code USER_STATUS}
     * @param targetLabel   a readable name for the target (a username, a title), kept as it was at the time
     * @param subjectUserId the member the action concerns (an account, or the owner of the content), if any
     * @param summary       a short Chinese sentence describing the change
     * @param detail        structured facts, e.g. {@code changes}; never secrets
     */
    record Event(String action, String category, String targetType, Object targetId, String targetLabel,
                 Long subjectUserId, String summary, Map<String, Object> detail) {
        public Event {
            Objects.requireNonNull(action, "action");
            if (!CATEGORIES.contains(category)) throw new IllegalArgumentException("unknown audit category " + category);
            detail = detail == null ? Map.of() : detail;
        }

        public static Event of(String action, String category, String targetType, Object targetId, String targetLabel,
                               Long subjectUserId, String summary) {
            return new Event(action, category, targetType, targetId, targetLabel, subjectUserId, summary, Map.of());
        }

        public Event with(String key, Object value) {
            Map<String, Object> next = new LinkedHashMap<>(detail);
            next.put(key, value);
            return new Event(action, category, targetType, targetId, targetLabel, subjectUserId, summary, next);
        }

        public Event withChanges(Changes changes) {
            return changes.isEmpty() ? this : with("changes", changes.list());
        }
    }

    /** Field changes for {@link Event#withChanges}; unchanged fields are left out. */
    final class Changes {
        private final List<Map<String, Object>> items = new ArrayList<>();

        public Changes add(String field, String label, Object before, Object after) {
            if (Objects.equals(normalize(before), normalize(after))) return this;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("field", field);
            item.put("label", label);
            item.put("before", normalize(before));
            item.put("after", normalize(after));
            items.add(item);
            return this;
        }

        /** A change whose values must not be kept, such as a new password. */
        public Changes addHidden(String field, String label) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("field", field);
            item.put("label", label);
            item.put("hidden", true);
            items.add(item);
            return this;
        }

        public boolean isEmpty() {
            return items.isEmpty();
        }

        public List<Map<String, Object>> list() {
            return List.copyOf(items);
        }

        /** "角色、账号状态" */
        public String labels() {
            return String.join("、", items.stream().map(item -> String.valueOf(item.get("label"))).toList());
        }

        private static Object normalize(Object value) {
            if (value instanceof String text) return text.isEmpty() ? null : text;
            return value;
        }
    }
}
