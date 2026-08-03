package com.aiknowledge.message.store;

import com.aiknowledge.message.entity.ChatMessageEntity;
import com.aiknowledge.message.entity.FaqEntity;
import com.aiknowledge.message.entity.FeedbackTicketEntity;
import com.aiknowledge.message.entity.NotificationEntity;
import com.aiknowledge.message.mapper.ChatMessageMapper;
import com.aiknowledge.message.mapper.FaqMapper;
import com.aiknowledge.message.mapper.FeedbackTicketMapper;
import com.aiknowledge.message.mapper.NotificationMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@Profile("mysql")
public class MySqlMessageStore implements MessageStore {
    private final ChatMessageMapper messageMapper;
    private final NotificationMapper notificationMapper;
    private final FeedbackTicketMapper ticketMapper;
    private final FaqMapper faqMapper;

    public MySqlMessageStore(
            ChatMessageMapper messageMapper,
            NotificationMapper notificationMapper,
            FeedbackTicketMapper ticketMapper,
            FaqMapper faqMapper
    ) {
        this.messageMapper = messageMapper;
        this.notificationMapper = notificationMapper;
        this.ticketMapper = ticketMapper;
        this.faqMapper = faqMapper;
    }

    @Override
    public ChatMessageEntity sendMessage(ChatMessageEntity message) {
        messageMapper.insert(message);
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
    public List<NotificationEntity> listNotifications(Long userId) {
        return notificationMapper.selectList(Wrappers.<NotificationEntity>lambdaQuery()
                .eq(userId != null, NotificationEntity::getUserId, userId)
                .orderByDesc(NotificationEntity::getCreatedAt));
    }

    @Override
    public List<FaqEntity> listFaqs() {
        return faqMapper.selectList(Wrappers.<FaqEntity>lambdaQuery()
                .eq(FaqEntity::getEnabled, 1)
                .orderByAsc(FaqEntity::getSortNo));
    }

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
}
