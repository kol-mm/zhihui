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
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashMap;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.aiknowledge.common.TimeCursor;

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
        if (message.getCreatedAt() == null) {
            ChatMessageEntity stored = messageMapper.selectById(message.getId());
            if (stored != null) message.setCreatedAt(stored.getCreatedAt());
        }
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
    public List<ChatMessageEntity> pageMessages(Long sessionId, Long beforeId, Long afterId, int limit) {
        var query = Wrappers.<ChatMessageEntity>lambdaQuery()
                .eq(ChatMessageEntity::getSessionId, sessionId);
        if (beforeId != null) query.lt(ChatMessageEntity::getId, beforeId);
        if (afterId != null) query.gt(ChatMessageEntity::getId, afterId);
        if (afterId != null) query.orderByAsc(ChatMessageEntity::getId);
        else query.orderByDesc(ChatMessageEntity::getId);
        List<ChatMessageEntity> page = new java.util.ArrayList<>(messageMapper.selectList(query.last("LIMIT " + limit)));
        if (afterId == null) java.util.Collections.reverse(page);
        return page;
    }

    @Override
    public Map<Long, ChatMessageEntity> latestMessages(Collection<Long> sessionIds) {
        Map<Long, ChatMessageEntity> latest = new HashMap<>();
        if (sessionIds == null || sessionIds.isEmpty()) return latest;
        List<Long> messageIds = messageMapper.selectObjs(new QueryWrapper<ChatMessageEntity>()
                        .select("MAX(id)")
                        .in("session_id", sessionIds)
                        .groupBy("session_id"))
                .stream().filter(java.util.Objects::nonNull).map(value -> ((Number) value).longValue()).toList();
        if (messageIds.isEmpty()) return latest;
        messageMapper.selectBatchIds(messageIds).forEach(message -> latest.put(message.getSessionId(), message));
        return latest;
    }

    @Override
    public Map<Long, Long> countMessages(Collection<Long> sessionIds) {
        Map<Long, Long> counts = new HashMap<>();
        if (sessionIds == null || sessionIds.isEmpty()) return counts;
        messageMapper.selectMaps(new QueryWrapper<ChatMessageEntity>()
                        .select("session_id AS session_id", "COUNT(*) AS message_count")
                        .in("session_id", sessionIds)
                        .groupBy("session_id"))
                .forEach(row -> counts.put(((Number) row.get("session_id")).longValue(), ((Number) row.get("message_count")).longValue()));
        return counts;
    }

    @Override
    public Optional<ChatMessageEntity> latestMessage(Long sessionId) {
        return Optional.ofNullable(messageMapper.selectOne(Wrappers.<ChatMessageEntity>lambdaQuery()
                .eq(ChatMessageEntity::getSessionId, sessionId)
                .orderByDesc(ChatMessageEntity::getId)
                .last("LIMIT 1")));
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
                .orderByDesc(NotificationEntity::getId));
    }

    @Override
    public List<NotificationEntity> pageNotifications(Long userId, Long beforeId, int limit, boolean unreadOnly) {
        return notificationMapper.selectList(Wrappers.<NotificationEntity>lambdaQuery()
                .eq(userId != null, NotificationEntity::getUserId, userId)
                .eq(unreadOnly, NotificationEntity::getIsRead, 0)
                .lt(beforeId != null && beforeId > 0, NotificationEntity::getId, beforeId)
                .orderByDesc(NotificationEntity::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    @Override
    public long countUnreadNotifications(Long userId) {
        Long count = notificationMapper.selectCount(Wrappers.<NotificationEntity>lambdaQuery()
                .eq(userId != null, NotificationEntity::getUserId, userId)
                .eq(NotificationEntity::getIsRead, 0));
        return count == null ? 0 : count;
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

    @Override
    public TicketAnalytics ticketAnalytics(LocalDateTime since) {
        Map<String, Long> byType = new LinkedHashMap<>();
        long total = 0L;
        long resolved = 0L;
        // The resolved count rides on the same window scan: as a separate status = 'RESOLVED' query, MySQL
        // preferred the status index and walked every resolved ticket ever filed.
        for (Map<String, Object> row : ticketMapper.selectMaps(new QueryWrapper<FeedbackTicketEntity>()
                .select("type AS ticket_type", "COUNT(*) AS ticket_count",
                        "IFNULL(SUM(CASE WHEN status = 'RESOLVED' THEN 1 ELSE 0 END), 0) AS resolved_count")
                .ge("created_at", since)
                .groupBy("type"))) {
            long count = ticketCount(row.get("ticket_count"));
            byType.put(String.valueOf(row.get("ticket_type")), count);
            total += count;
            resolved += ticketCount(row.get("resolved_count"));
        }
        return new TicketAnalytics(
                total,
                byType.getOrDefault("BUG", 0L),
                byType.getOrDefault("SUGGESTION", 0L),
                byType.getOrDefault("SUPPORT", 0L),
                resolved);
    }

    @Override
    public List<DailyCount> dailyTicketCounts(LocalDateTime since) {
        return ticketMapper.selectMaps(new QueryWrapper<FeedbackTicketEntity>()
                        .select("DATE(created_at) AS day", "COUNT(*) AS ticket_count")
                        .ge("created_at", since)
                        .groupBy("DATE(created_at)")
                        .orderByAsc("DATE(created_at)"))
                .stream()
                .map(row -> new DailyCount(String.valueOf(row.get("day")), ticketCount(row.get("ticket_count"))))
                .toList();
    }

    private static long ticketCount(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }


    @Override
    public TicketTotals ticketTotals(Long userId) {
        // Grouping by (type, status) lets MySQL answer from idx_feedback_type_status alone instead of reading
        // every ticket row; the handful of groups are folded into the totals here.
        QueryWrapper<FeedbackTicketEntity> wrapper = new QueryWrapper<FeedbackTicketEntity>()
                .select("type AS ticket_type", "status AS ticket_status", "COUNT(*) AS ticket_count")
                .groupBy("type", "status");
        if (userId != null) wrapper.eq("user_id", userId);
        Map<String, Long> byType = new LinkedHashMap<>();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        long total = 0L;
        for (Map<String, Object> row : ticketMapper.selectMaps(wrapper)) {
            long count = ticketCount(row.get("ticket_count"));
            byType.merge(String.valueOf(row.get("ticket_type")), count, Long::sum);
            byStatus.merge(String.valueOf(row.get("ticket_status")), count, Long::sum);
            total += count;
        }
        return new TicketTotals(
                total,
                byStatus.getOrDefault("PENDING", 0L),
                byStatus.getOrDefault("PROCESSING", 0L),
                byStatus.getOrDefault("RESOLVED", 0L),
                byType.getOrDefault("BUG", 0L),
                byType.getOrDefault("SUGGESTION", 0L),
                byType.getOrDefault("SUPPORT", 0L));
    }

    @Override
    public Map<Long, Map<String, Long>> ticketWorkload(Long userId) {
        QueryWrapper<FeedbackTicketEntity> wrapper = new QueryWrapper<FeedbackTicketEntity>()
                .select("assignee_user_id AS assignee_user_id",
                        "COUNT(*) AS assigned_count",
                        "IFNULL(SUM(CASE WHEN status = 'PROCESSING' THEN 1 ELSE 0 END), 0) AS processing_count",
                        "IFNULL(SUM(CASE WHEN status = 'RESOLVED' THEN 1 ELSE 0 END), 0) AS resolved_count")
                .isNotNull("assignee_user_id")
                .groupBy("assignee_user_id")
                .orderByAsc("assignee_user_id");
        if (userId != null) wrapper.eq("user_id", userId);
        Map<Long, Map<String, Long>> workload = new LinkedHashMap<>();
        for (Map<String, Object> row : ticketMapper.selectMaps(wrapper)) {
            Map<String, Long> counts = new LinkedHashMap<>();
            counts.put("assigned", ticketCount(row.get("assigned_count")));
            counts.put("processing", ticketCount(row.get("processing_count")));
            counts.put("resolved", ticketCount(row.get("resolved_count")));
            workload.put(((Number) row.get("assignee_user_id")).longValue(), counts);
        }
        return workload;
    }

    @Override
    public long countFaqs() {
        Long count = faqMapper.selectCount(Wrappers.<FaqEntity>lambdaQuery());
        return count == null ? 0L : count;
    }

    @Override
    public long countNotifications(Long userId) {
        Long count = notificationMapper.selectCount(Wrappers.<NotificationEntity>lambdaQuery()
                .eq(userId != null, NotificationEntity::getUserId, userId));
        return count == null ? 0L : count;
    }


    @Override
    public List<FeedbackTicketEntity> pageTickets(TicketPageQuery query) {
        if (query.limit() <= 0) return List.of();
        return ticketMapper.selectList(ticketFilter(query)
                .lt(query.beforeId() != null, FeedbackTicketEntity::getId, query.beforeId())
                .orderByDesc(FeedbackTicketEntity::getId)
                .last("LIMIT " + query.limit()));
    }

    @Override
    public long countTickets(TicketPageQuery query) {
        Long count = ticketMapper.selectCount(ticketFilter(query));
        return count == null ? 0L : count;
    }

    private LambdaQueryWrapper<FeedbackTicketEntity> ticketFilter(TicketPageQuery query) {
        LambdaQueryWrapper<FeedbackTicketEntity> wrapper = Wrappers.<FeedbackTicketEntity>lambdaQuery()
                .eq(query.userId() != null, FeedbackTicketEntity::getUserId, query.userId())
                .eq(query.status() != null && !query.status().isBlank(), FeedbackTicketEntity::getStatus, query.status());
        String keyword = query.keyword() == null ? "" : query.keyword().trim();
        if (!keyword.isEmpty()) {
            // The table searches by ticket and reporter id as well as by text, so ids are matched as text.
            wrapper.and(match -> match.like(FeedbackTicketEntity::getContent, keyword)
                    .or().like(FeedbackTicketEntity::getOfficialReply, keyword)
                    .or().like(FeedbackTicketEntity::getType, keyword)
                    .or().apply("CAST(id AS CHAR) LIKE CONCAT('%', {0}, '%')", keyword)
                    .or().apply("CAST(user_id AS CHAR) LIKE CONCAT('%', {0}, '%')", keyword));
        }
        return wrapper;
    }


    @Override
    public List<ChatSessionEntity> pageAdminSessions(AdminSessionQuery query) {
        if (query.limit() <= 0) return List.of();
        LambdaQueryWrapper<ChatSessionEntity> wrapper = adminSessionFilter(query);
        TimeCursor before = query.before();
        if (before != null) {
            wrapper.and(after -> after.lt(ChatSessionEntity::getUpdatedAt, before.time())
                    .or(tie -> tie.eq(ChatSessionEntity::getUpdatedAt, before.time()).lt(ChatSessionEntity::getId, before.id())));
        }
        return sessionMapper.selectList(wrapper
                .orderByDesc(ChatSessionEntity::getUpdatedAt, ChatSessionEntity::getId)
                .last("LIMIT " + query.limit()));
    }

    @Override
    public long countAdminSessions(AdminSessionQuery query) {
        Long count = sessionMapper.selectCount(adminSessionFilter(query));
        return count == null ? 0L : count;
    }

    private LambdaQueryWrapper<ChatSessionEntity> adminSessionFilter(AdminSessionQuery query) {
        LambdaQueryWrapper<ChatSessionEntity> wrapper = Wrappers.lambdaQuery();
        String keyword = query.keyword() == null ? "" : query.keyword().trim();
        if (!keyword.isEmpty()) {
            // Moderators look for conversations by participant or by what was said anywhere in them. The hint makes
            // MySQL scan the messages once and keep the matching conversation ids; left alone it probed every
            // conversation's messages in turn, which took about three times as long on 600k messages.
            wrapper.and(match -> match.like(ChatSessionEntity::getStatus, keyword)
                    .or().apply("CAST(id AS CHAR) LIKE CONCAT('%', {0}, '%')", keyword)
                    .or().apply("CAST(user_a_id AS CHAR) LIKE CONCAT('%', {0}, '%')", keyword)
                    .or().apply("CAST(user_b_id AS CHAR) LIKE CONCAT('%', {0}, '%')", keyword)
                    .or().apply("id IN (SELECT /*+ SUBQUERY(MATERIALIZATION) */ session_id FROM chat_message "
                            + "WHERE content LIKE CONCAT('%', {0}, '%'))", keyword));
        }
        return wrapper;
    }

}
