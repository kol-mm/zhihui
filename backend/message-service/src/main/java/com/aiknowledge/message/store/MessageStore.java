package com.aiknowledge.message.store;

import com.aiknowledge.message.entity.ChatMessageEntity;
import com.aiknowledge.message.entity.ChatSessionEntity;
import com.aiknowledge.message.entity.FaqEntity;
import com.aiknowledge.message.entity.FeedbackTicketEntity;
import com.aiknowledge.message.entity.NotificationEntity;

import java.util.List;
import java.util.Optional;

public interface MessageStore {
    ChatSessionEntity getOrCreateSession(Long firstUserId, Long secondUserId);

    Optional<ChatSessionEntity> findSession(Long sessionId);

    List<ChatSessionEntity> listSessions(Long userId);

    ChatMessageEntity sendMessage(ChatMessageEntity message);

    List<ChatMessageEntity> listMessages(Long sessionId);

    int clearMessages(Long sessionId);

    boolean deleteMessage(Long messageId);

    Optional<ChatMessageEntity> findMessage(Long messageId);

    List<NotificationEntity> listNotifications(Long userId);

    NotificationEntity saveNotification(NotificationEntity notification);

    Optional<NotificationEntity> markNotificationRead(Long userId, Long notificationId);

    int markAllNotificationsRead(Long userId);

    List<FaqEntity> listFaqs();

    FaqEntity saveFaq(FaqEntity faq);

    boolean deleteFaq(Long faqId);

    FeedbackTicketEntity createTicket(FeedbackTicketEntity ticket);

    List<FeedbackTicketEntity> listTickets(Long userId);

    Optional<FeedbackTicketEntity> replyTicket(Long ticketId, String status, String reply);
}
