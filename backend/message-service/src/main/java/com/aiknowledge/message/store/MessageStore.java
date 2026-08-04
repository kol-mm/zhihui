package com.aiknowledge.message.store;

import com.aiknowledge.message.entity.ChatMessageEntity;
import com.aiknowledge.message.entity.FaqEntity;
import com.aiknowledge.message.entity.FeedbackTicketEntity;
import com.aiknowledge.message.entity.NotificationEntity;

import java.util.List;
import java.util.Optional;

public interface MessageStore {
    ChatMessageEntity sendMessage(ChatMessageEntity message);

    List<ChatMessageEntity> listMessages(Long sessionId);

    int clearMessages(Long sessionId);

    boolean deleteMessage(Long messageId);

    List<Long> listSessionIds();

    List<NotificationEntity> listNotifications(Long userId);

    List<FaqEntity> listFaqs();

    FaqEntity saveFaq(FaqEntity faq);

    boolean deleteFaq(Long faqId);

    FeedbackTicketEntity createTicket(FeedbackTicketEntity ticket);

    List<FeedbackTicketEntity> listTickets(Long userId);

    Optional<FeedbackTicketEntity> replyTicket(Long ticketId, String status, String reply);
}
