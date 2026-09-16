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
}
