package com.aiknowledge.message.store;

import com.aiknowledge.message.entity.ChatMessageEntity;
import com.aiknowledge.message.entity.ChatSessionEntity;
import com.aiknowledge.message.entity.FaqEntity;
import com.aiknowledge.message.entity.FeedbackTicketEntity;
import com.aiknowledge.message.entity.NotificationEntity;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.TreeMap;

public interface MessageStore {
    ChatSessionEntity getOrCreateSession(Long firstUserId, Long secondUserId);

    Optional<ChatSessionEntity> findSession(Long sessionId);

    List<ChatSessionEntity> listSessions(Long userId);

    Optional<ChatSessionEntity> updateSessionStatus(Long sessionId, String status);

    ChatMessageEntity sendMessage(ChatMessageEntity message);

    List<ChatMessageEntity> listMessages(Long sessionId);

    default List<ChatMessageEntity> pageMessages(Long sessionId, Long beforeId, Long afterId, int limit) {
        List<ChatMessageEntity> ordered = listMessages(sessionId).stream()
                .filter(message -> beforeId == null || message.getId() < beforeId)
                .filter(message -> afterId == null || message.getId() > afterId)
                .sorted(java.util.Comparator.comparing(ChatMessageEntity::getId))
                .toList();
        if (afterId != null) return ordered.stream().limit(limit).toList();
        return ordered.subList(Math.max(0, ordered.size() - limit), ordered.size());
    }

    default Optional<ChatMessageEntity> latestMessage(Long sessionId) {
        return listMessages(sessionId).stream()
                .max(java.util.Comparator.comparing(ChatMessageEntity::getId));
    }

    /** Message count per session; sessions without messages are absent from the result. */
    default Map<Long, Long> countMessages(Collection<Long> sessionIds) {
        Map<Long, Long> counts = new java.util.HashMap<>();
        for (Long sessionId : sessionIds) {
            int size = listMessages(sessionId).size();
            if (size > 0) counts.put(sessionId, (long) size);
        }
        return counts;
    }

    /** Latest message per session; sessions without messages are absent from the result. */
    default Map<Long, ChatMessageEntity> latestMessages(Collection<Long> sessionIds) {
        Map<Long, ChatMessageEntity> latest = new java.util.HashMap<>();
        for (Long sessionId : sessionIds) latestMessage(sessionId).ifPresent(message -> latest.put(sessionId, message));
        return latest;
    }

    int clearMessages(Long sessionId);

    int clearUserMessages(Long userId);

    boolean deleteSession(Long sessionId);

    boolean deleteMessage(Long messageId);

    Optional<ChatMessageEntity> findMessage(Long messageId);

    List<NotificationEntity> listNotifications(Long userId);

    /** Newest-first page of notifications, optionally unread only; beforeId is the paging cursor. */
    default List<NotificationEntity> pageNotifications(Long userId, Long beforeId, int limit, boolean unreadOnly) {
        return listNotifications(userId).stream()
                .filter(notification -> !unreadOnly || notification.getIsRead() == null || notification.getIsRead() == 0)
                .filter(notification -> beforeId == null || beforeId <= 0 || notification.getId() < beforeId)
                .sorted(java.util.Comparator.comparing(NotificationEntity::getId).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    default long countUnreadNotifications(Long userId) {
        return listNotifications(userId).stream()
                .filter(notification -> notification.getIsRead() == null || notification.getIsRead() == 0)
                .count();
    }

    NotificationEntity saveNotification(NotificationEntity notification);

    Optional<NotificationEntity> markNotificationRead(Long userId, Long notificationId);

    int markAllNotificationsRead(Long userId);

    List<FaqEntity> listFaqs();

    FaqEntity saveFaq(FaqEntity faq);

    boolean deleteFaq(Long faqId);

    FeedbackTicketEntity createTicket(FeedbackTicketEntity ticket);

    List<FeedbackTicketEntity> listTickets(Long userId);

    Optional<FeedbackTicketEntity> replyTicket(Long ticketId, String status, String reply);

    Optional<FeedbackTicketEntity> assignTicket(Long ticketId, Long assigneeUserId);

    /** Ticket totals behind the admin analytics page, for tickets created since the cutoff. */
    record TicketAnalytics(long total, long bug, long suggestion, long support, long resolved) { }

    /** One day of a trend series, keyed by ISO date. */
    record DailyCount(String date, long count) { }

    /**
     * Aggregates feedback tickets created since {@code since}. MySQL answers this with grouped
     * queries; the in-memory profile folds the same numbers over the ticket list.
     */
    default TicketAnalytics ticketAnalytics(LocalDateTime since) {
        List<FeedbackTicketEntity> tickets = analyticsTickets(since);
        return new TicketAnalytics(
                tickets.size(),
                tickets.stream().filter(ticket -> "BUG".equals(ticket.getType())).count(),
                tickets.stream().filter(ticket -> "SUGGESTION".equals(ticket.getType())).count(),
                tickets.stream().filter(ticket -> "SUPPORT".equals(ticket.getType())).count(),
                tickets.stream().filter(ticket -> "RESOLVED".equals(ticket.getStatus())).count());
    }

    /** New tickets per day; days without tickets are left out and filled in by the caller. */
    default List<DailyCount> dailyTicketCounts(LocalDateTime since) {
        Map<String, Long> perDay = new LinkedHashMap<>();
        for (FeedbackTicketEntity ticket : analyticsTickets(since)) {
            if (ticket.getCreatedAt() == null) continue;
            perDay.merge(ticket.getCreatedAt().toLocalDate().toString(), 1L, Long::sum);
        }
        return perDay.entrySet().stream().map(entry -> new DailyCount(entry.getKey(), entry.getValue())).toList();
    }

    private List<FeedbackTicketEntity> analyticsTickets(LocalDateTime since) {
        return listTickets(null).stream()
                .filter(ticket -> ticket.getCreatedAt() == null || !ticket.getCreatedAt().isBefore(since))
                .toList();
    }


    /** Counters behind the feedback admin overview, optionally narrowed to one reporter. */
    record TicketTotals(long total, long pending, long processing, long resolved,
                        long bug, long suggestion, long support) { }

    /** MySQL answers this with one grouped query; the in-memory profile folds the ticket list. */
    default TicketTotals ticketTotals(Long userId) {
        List<FeedbackTicketEntity> tickets = listTickets(userId);
        return new TicketTotals(
                tickets.size(),
                tickets.stream().filter(ticket -> "PENDING".equals(ticket.getStatus())).count(),
                tickets.stream().filter(ticket -> "PROCESSING".equals(ticket.getStatus())).count(),
                tickets.stream().filter(ticket -> "RESOLVED".equals(ticket.getStatus())).count(),
                tickets.stream().filter(ticket -> "BUG".equals(ticket.getType())).count(),
                tickets.stream().filter(ticket -> "SUGGESTION".equals(ticket.getType())).count(),
                tickets.stream().filter(ticket -> "SUPPORT".equals(ticket.getType())).count());
    }

    /** Assigned, in-progress and resolved ticket counts per support agent, ordered by agent id. */
    default Map<Long, Map<String, Long>> ticketWorkload(Long userId) {
        Map<Long, Map<String, Long>> workload = new TreeMap<>();
        for (FeedbackTicketEntity ticket : listTickets(userId)) {
            if (ticket.getAssigneeUserId() == null) continue;
            Map<String, Long> counts = workload.computeIfAbsent(ticket.getAssigneeUserId(), ignored -> {
                Map<String, Long> initial = new LinkedHashMap<>();
                initial.put("assigned", 0L);
                initial.put("processing", 0L);
                initial.put("resolved", 0L);
                return initial;
            });
            counts.put("assigned", counts.get("assigned") + 1);
            if ("PROCESSING".equals(ticket.getStatus())) counts.put("processing", counts.get("processing") + 1);
            if ("RESOLVED".equals(ticket.getStatus())) counts.put("resolved", counts.get("resolved") + 1);
        }
        return new LinkedHashMap<>(workload);
    }

    /** Number of published FAQ entries, without reading them. */
    default long countFaqs() {
        return listFaqs().size();
    }

    /** Number of notifications, without reading them. */
    default long countNotifications(Long userId) {
        return listNotifications(userId).size();
    }


    /**
     * One page of the admin ticket table.
     *
     * @param userId   only this reporter's tickets (optional)
     * @param status   PENDING, PROCESSING or RESOLVED (optional)
     * @param keyword  matches the ticket id, reporter id, type, content or official reply (optional)
     * @param beforeId cursor: only tickets with a smaller id, because the table reads newest first
     */
    record TicketPageQuery(Long userId, String status, String keyword, Long beforeId, int limit) { }

    /** MySQL pages this by keyset; the in-memory profile filters the ticket list the same way. */
    default List<FeedbackTicketEntity> pageTickets(TicketPageQuery query) {
        return matchingTickets(query)
                .filter(ticket -> query.beforeId() == null || ticket.getId() < query.beforeId())
                .limit(Math.max(query.limit(), 0))
                .toList();
    }

    /** How many tickets match the filters, ignoring the cursor. */
    default long countTickets(TicketPageQuery query) {
        return matchingTickets(query).count();
    }

    private java.util.stream.Stream<FeedbackTicketEntity> matchingTickets(TicketPageQuery query) {
        String keyword = query.keyword() == null ? "" : query.keyword().trim().toLowerCase();
        return listTickets(query.userId()).stream()
                .sorted(java.util.Comparator.comparing(FeedbackTicketEntity::getId).reversed())
                .filter(ticket -> query.status() == null || query.status().isBlank() || query.status().equals(ticket.getStatus()))
                .filter(ticket -> keyword.isEmpty()
                        || String.valueOf(ticket.getId()).contains(keyword)
                        || String.valueOf(ticket.getUserId()).contains(keyword)
                        || (ticket.getType() != null && ticket.getType().toLowerCase().contains(keyword))
                        || (ticket.getContent() != null && ticket.getContent().toLowerCase().contains(keyword))
                        || (ticket.getOfficialReply() != null && ticket.getOfficialReply().toLowerCase().contains(keyword)));
    }

}
