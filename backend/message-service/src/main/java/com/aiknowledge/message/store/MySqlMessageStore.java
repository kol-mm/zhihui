package com.aiknowledge.message.store;

import com.aiknowledge.message.entity.ChatMessageEntity;
import com.aiknowledge.message.entity.ChatSessionEntity;
import com.aiknowledge.message.entity.FaqEntity;
import com.aiknowledge.message.entity.FeedbackTicketEntity;
import com.aiknowledge.message.entity.NotificationEntity;
import com.aiknowledge.message.mapper.ChatMessageMapper;
import com.aiknowledge.message.mapper.ChatSessionMapper;
import com.aiknowledge.message.mapper.FaqMapper;
import com.aiknowledge.message.mapper.FeedbackTicketMapper;
import com.aiknowledge.message.mapper.NotificationMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("mysql")
public class MySqlMessageStore implements MessageStore {
    private final ChatMessageMapper messageMapper;
    private final ChatSessionMapper sessionMapper;
    private final NotificationMapper notificationMapper;
    private final FeedbackTicketMapper ticketMapper;
    private final FaqMapper faqMapper;

    public MySqlMessageStore(
            ChatMessageMapper messageMapper,
            ChatSessionMapper sessionMapper,
            NotificationMapper notificationMapper,
            FeedbackTicketMapper ticketMapper,
            FaqMapper faqMapper
    ) {
        this.messageMapper = messageMapper;
        this.sessionMapper = sessionMapper;
        this.notificationMapper = notificationMapper;
        this.ticketMapper = ticketMapper;
        this.faqMapper = faqMapper;
    }

    @Override
    public ChatSessionEntity getOrCreateSession(Long firstUserId, Long secondUserId) {
        Long userAId = Math.min(firstUserId, secondUserId);
        Long userBId = Math.max(firstUserId, secondUserId);
        ChatSessionEntity existing = sessionMapper.selectOne(Wrappers.<ChatSessionEntity>lambdaQuery()
                .eq(ChatSessionEntity::getUserAId, userAId)
                .eq(ChatSessionEntity::getUserBId, userBId)
                .last("LIMIT 1"));
        if (existing != null) return existing;
        ChatSessionEntity session = new ChatSessionEntity();
        session.setUserAId(userAId);
        session.setUserBId(userBId);
        session.setStatus("ACTIVE");
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.insert(session);
        return session;
    }

    @Override
    public Optional<ChatSessionEntity> findSession(Long sessionId) {
        return Optional.ofNullable(sessionMapper.selectById(sessionId));
    }

    @Override
    public List<ChatSessionEntity> listSessions(Long userId) {
        return sessionMapper.selectList(Wrappers.<ChatSessionEntity>lambdaQuery()
                .and(userId != null, query -> query.eq(ChatSessionEntity::getUserAId, userId)
                        .or().eq(ChatSessionEntity::getUserBId, userId))
                .orderByDesc(ChatSessionEntity::getUpdatedAt));
    }

    @Override
    public Optional<ChatSessionEntity> updateSessionStatus(Long sessionId, String status) {
        ChatSessionEntity session = sessionMapper.selectById(sessionId);
        if (session == null) return Optional.empty();
        session.setStatus(status);
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
        return Optional.of(session);
    }

    @Override
    public ChatMessageEntity sendMessage(ChatMessageEntity message) {
        messageMapper.insert(message);
        ChatSessionEntity session = sessionMapper.selectById(message.getSessionId());
        if (session != null) {
            session.setUpdatedAt(message.getCreatedAt() == null ? LocalDateTime.now() : message.getCreatedAt());
            sessionMapper.updateById(session);
        }
        return message;
    }

    @Override
    public List<ChatMessageEntity> listMessages(Long sessionId) {
        return messageMapper.selectList(Wrappers.<ChatMessageEntity>lambdaQuery()
                .eq(sessionId != null, ChatMessageEntity::getSessionId, sessionId)
                .orderByAsc(ChatMessageEntity::getCreatedAt));
    }

    @Override
    public int clearMessages(Long sessionId) {
        return messageMapper.delete(Wrappers.<ChatMessageEntity>lambdaQuery()
                .eq(sessionId != null, ChatMessageEntity::getSessionId, sessionId));
    }

    @Override
    public int clearUserMessages(Long userId) {
        List<Long> sessionIds = listSessions(userId).stream().map(ChatSessionEntity::getId).toList();
        if (sessionIds.isEmpty()) return 0;
        return messageMapper.delete(Wrappers.<ChatMessageEntity>lambdaQuery()
                .in(ChatMessageEntity::getSessionId, sessionIds));
    }

    @Override
    public boolean deleteSession(Long sessionId) {
        clearMessages(sessionId);
        return sessionMapper.deleteById(sessionId) > 0;
    }

    @Override public boolean deleteMessage(Long messageId) { return messageMapper.deleteById(messageId) > 0; }
    @Override public Optional<ChatMessageEntity> findMessage(Long messageId) {
        return Optional.ofNullable(messageMapper.selectById(messageId));
    }

    @Override
    public List<NotificationEntity> listNotifications(Long userId) {
        return notificationMapper.selectList(Wrappers.<NotificationEntity>lambdaQuery()
                .eq(userId != null, NotificationEntity::getUserId, userId)
                .orderByDesc(NotificationEntity::getCreatedAt));
    }

    @Override
    public NotificationEntity saveNotification(NotificationEntity notification) {
        notificationMapper.insert(notification);
        return notification;
    }

    @Override
    public Optional<NotificationEntity> markNotificationRead(Long userId, Long notificationId) {
        NotificationEntity notification = notificationMapper.selectOne(Wrappers.<NotificationEntity>lambdaQuery()
                .eq(NotificationEntity::getId, notificationId).eq(NotificationEntity::getUserId, userId));
        if (notification == null) return Optional.empty();
        notification.setIsRead(1);
        notificationMapper.updateById(notification);
        return Optional.of(notification);
    }

    @Override
    public int markAllNotificationsRead(Long userId) {
        NotificationEntity update = new NotificationEntity();
        update.setIsRead(1);
        return notificationMapper.update(update, Wrappers.<NotificationEntity>lambdaUpdate()
                .eq(NotificationEntity::getUserId, userId).eq(NotificationEntity::getIsRead, 0));
    }

    @Override
    public List<FaqEntity> listFaqs() {
        return faqMapper.selectList(Wrappers.<FaqEntity>lambdaQuery()
                .eq(FaqEntity::getEnabled, 1)
                .orderByAsc(FaqEntity::getSortNo));
    }

    @Override public FaqEntity saveFaq(FaqEntity faq) {
        if (faq.getId() == null) faqMapper.insert(faq); else faqMapper.updateById(faq);
        return faq;
    }
    @Override public boolean deleteFaq(Long faqId) { return faqMapper.deleteById(faqId) > 0; }

    @Override
    public FeedbackTicketEntity createTicket(FeedbackTicketEntity ticket) {
        ticketMapper.insert(ticket);
        return ticket;
    }

    @Override
    public List<FeedbackTicketEntity> listTickets(Long userId) {
        return ticketMapper.selectList(Wrappers.<FeedbackTicketEntity>lambdaQuery()
                .eq(userId != null, FeedbackTicketEntity::getUserId, userId)
                .orderByDesc(FeedbackTicketEntity::getCreatedAt));
    }

    @Override
    public Optional<FeedbackTicketEntity> replyTicket(Long ticketId, String status, String reply) {
        FeedbackTicketEntity ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            return Optional.empty();
        }
        ticket.setStatus(status);
        ticket.setOfficialReply(reply);
        ticket.setClosedAt("RESOLVED".equals(status) ? LocalDateTime.now() : null);
        ticket.setUpdatedAt(LocalDateTime.now());
        ticketMapper.updateById(ticket);
        return Optional.of(ticket);
    }

    @Override
    public Optional<FeedbackTicketEntity> assignTicket(Long ticketId, Long assigneeUserId) {
        FeedbackTicketEntity ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) return Optional.empty();
        ticket.setAssigneeUserId(assigneeUserId);
        ticket.setAssignedAt(assigneeUserId == null ? null : LocalDateTime.now());
        if (assigneeUserId != null && "PENDING".equals(ticket.getStatus())) ticket.setStatus("PROCESSING");
        ticket.setUpdatedAt(LocalDateTime.now());
        ticketMapper.updateById(ticket);
        return Optional.of(ticket);
    }
}
