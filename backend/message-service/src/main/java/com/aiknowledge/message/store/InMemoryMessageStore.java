package com.aiknowledge.message.store;

import com.aiknowledge.common.LocalJsonStore;
import com.aiknowledge.message.entity.ChatMessageEntity;
import com.aiknowledge.message.entity.ChatSessionEntity;
import com.aiknowledge.message.entity.FaqEntity;
import com.aiknowledge.message.entity.FeedbackTicketEntity;
import com.aiknowledge.message.entity.NotificationEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Repository
@Profile("!mysql")
public class InMemoryMessageStore implements MessageStore {
    private final Path storePath = LocalJsonStore.dataFile("messages.json");
    private final AtomicLong messageIds = new AtomicLong(1000);
    private final AtomicLong sessionIds = new AtomicLong(0);
    private final AtomicLong notificationIds = new AtomicLong(2000);
    private final AtomicLong ticketIds = new AtomicLong(3000);
    private final AtomicLong faqIds = new AtomicLong(4000);
    private final List<ChatMessageEntity> messages = new CopyOnWriteArrayList<>();
    private final List<ChatSessionEntity> sessions = new CopyOnWriteArrayList<>();
    private final List<NotificationEntity> notifications = new CopyOnWriteArrayList<>();
    private final List<FeedbackTicketEntity> tickets = new CopyOnWriteArrayList<>();
    private final List<FaqEntity> faqs = new CopyOnWriteArrayList<>();

    public InMemoryMessageStore() {
        State state = LocalJsonStore.read(storePath, State.class, new State());
        if ((state.messages != null && !state.messages.isEmpty())
                || (state.notifications != null && !state.notifications.isEmpty())
                || (state.tickets != null && !state.tickets.isEmpty())
                || (state.faqs != null && !state.faqs.isEmpty())) {
            if (state.messages != null) {
                messages.addAll(state.messages);
            }
            if (state.sessions != null) {
                sessions.addAll(state.sessions);
            }
            if (state.notifications != null) {
                notifications.addAll(state.notifications);
            }
            if (state.tickets != null) {
                tickets.addAll(state.tickets);
            }
            if (state.faqs != null) {
                faqs.addAll(state.faqs);
            }
            messageIds.set(maxId(messages, 1000L));
            migrateLegacySessions();
            sessionIds.set(maxId(sessions, 0L));
            notificationIds.set(maxId(notifications, 2000L));
            ticketIds.set(maxId(tickets, 3000L));
            faqIds.set(maxId(faqs, 4000L));
            return;
        }

        NotificationEntity notification = new NotificationEntity();
        notification.setId(notificationIds.incrementAndGet());
        notification.setUserId(1L);
        notification.setType("SYSTEM");
        notification.setTitle("本地环境已就绪");
        notification.setContent("消息服务正在以本地存储模式运行。");
        notification.setIsRead(0);
        notification.setCreatedAt(LocalDateTime.now());
        notifications.add(notification);

        FaqEntity faq = new FaqEntity();
        faq.setId(faqIds.incrementAndGet());
        faq.setQuestion("支持哪些文件格式？");
        faq.setAnswer("当前版本支持上传 Word、PDF、TXT 和 Markdown 文件，并可用于搜索和 AI 问答。");
        faq.setSortNo(1);
        faq.setEnabled(1);
        faqs.add(faq);
        persist();
    }

    @Override
    public ChatSessionEntity getOrCreateSession(Long firstUserId, Long secondUserId) {
        Long userAId = Math.min(firstUserId, secondUserId);
        Long userBId = Math.max(firstUserId, secondUserId);
        Optional<ChatSessionEntity> existing = sessions.stream()
                .filter(session -> session.getUserAId().equals(userAId) && session.getUserBId().equals(userBId))
                .findFirst();
        if (existing.isPresent()) return existing.get();
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(sessionIds.incrementAndGet());
        session.setUserAId(userAId);
        session.setUserBId(userBId);
        session.setStatus("ACTIVE");
        session.setUpdatedAt(LocalDateTime.now());
        sessions.add(session);
        persist();
        return session;
    }

    @Override
    public Optional<ChatSessionEntity> findSession(Long sessionId) {
        return sessions.stream().filter(session -> session.getId().equals(sessionId)).findFirst();
    }

    @Override
    public List<ChatSessionEntity> listSessions(Long userId) {
        return sessions.stream()
                .filter(session -> userId == null || session.getUserAId().equals(userId) || session.getUserBId().equals(userId))
                .sorted(Comparator.comparing(ChatSessionEntity::getUpdatedAt).reversed())
                .toList();
    }

    @Override
    public Optional<ChatSessionEntity> updateSessionStatus(Long sessionId, String status) {
        Optional<ChatSessionEntity> found = findSession(sessionId);
        found.ifPresent(session -> {
            session.setStatus(status);
            session.setUpdatedAt(LocalDateTime.now());
            persist();
        });
        return found;
    }

    @Override
    public ChatMessageEntity sendMessage(ChatMessageEntity message) {
        message.setId(messageIds.incrementAndGet());
        message.setCreatedAt(LocalDateTime.now());
        messages.add(message);
        findSession(message.getSessionId()).ifPresent(session -> session.setUpdatedAt(message.getCreatedAt()));
        persist();
        return message;
    }

    @Override
    public List<ChatMessageEntity> listMessages(Long sessionId) {
        return messages.stream()
                .filter(message -> sessionId == null || message.getSessionId().equals(sessionId))
                .sorted(Comparator.comparing(ChatMessageEntity::getCreatedAt))
                .toList();
    }

    @Override
    public int clearMessages(Long sessionId) {
        int before = messages.size();
        messages.removeIf(message -> sessionId == null || message.getSessionId().equals(sessionId));
        persist();
        return before - messages.size();
    }

    @Override
    public int clearUserMessages(Long userId) {
        var sessionIds = sessions.stream()
                .filter(session -> session.getUserAId().equals(userId) || session.getUserBId().equals(userId))
                .map(ChatSessionEntity::getId).collect(java.util.stream.Collectors.toSet());
        int before = messages.size();
        messages.removeIf(message -> sessionIds.contains(message.getSessionId()));
        persist();
        return before - messages.size();
    }

    @Override
    public boolean deleteSession(Long sessionId) {
        clearMessages(sessionId);
        boolean removed = sessions.removeIf(session -> session.getId().equals(sessionId));
        if (removed) persist();
        return removed;
    }

    @Override
    public boolean deleteMessage(Long messageId) {
        boolean removed = messages.removeIf(message -> message.getId().equals(messageId));
        if (removed) persist();
        return removed;
    }

    @Override
    public Optional<ChatMessageEntity> findMessage(Long messageId) {
        return messages.stream().filter(message -> message.getId().equals(messageId)).findFirst();
    }

    @Override
    public List<NotificationEntity> listNotifications(Long userId) {
        return notifications.stream()
                .filter(notification -> userId == null || notification.getUserId().equals(userId))
                .sorted(Comparator.comparing(NotificationEntity::getCreatedAt).reversed())
                .toList();
    }

    @Override
    public NotificationEntity saveNotification(NotificationEntity notification) {
        notification.setId(notificationIds.incrementAndGet());
        notification.setIsRead(0);
        notification.setCreatedAt(LocalDateTime.now());
        notifications.add(notification);
        persist();
        return notification;
    }

    @Override
    public Optional<NotificationEntity> markNotificationRead(Long userId, Long notificationId) {
        Optional<NotificationEntity> found = notifications.stream()
                .filter(item -> item.getId().equals(notificationId) && item.getUserId().equals(userId))
                .findFirst();
        found.ifPresent(item -> { item.setIsRead(1); persist(); });
        return found;
    }

    @Override
    public int markAllNotificationsRead(Long userId) {
        int[] count = {0};
        notifications.stream().filter(item -> item.getUserId().equals(userId) && (item.getIsRead() == null || item.getIsRead() == 0))
                .forEach(item -> { item.setIsRead(1); count[0]++; });
        if (count[0] > 0) persist();
        return count[0];
    }

    @Override
    public List<FaqEntity> listFaqs() {
        return faqs.stream()
                .filter(faq -> faq.getEnabled() == null || faq.getEnabled() == 1)
                .sorted(Comparator.comparing(FaqEntity::getSortNo, Comparator.nullsLast(Integer::compareTo)))
                .toList();
    }

    @Override
    public FaqEntity saveFaq(FaqEntity faq) {
        if (faq.getId() == null) {
            faq.setId(faqIds.incrementAndGet());
            faqs.add(faq);
        } else {
            faqs.removeIf(item -> item.getId().equals(faq.getId()));
            faqs.add(faq);
        }
        persist();
        return faq;
    }

    @Override
    public boolean deleteFaq(Long faqId) {
        boolean removed = faqs.removeIf(item -> item.getId().equals(faqId));
        if (removed) persist();
        return removed;
    }

    @Override
    public FeedbackTicketEntity createTicket(FeedbackTicketEntity ticket) {
        ticket.setId(ticketIds.incrementAndGet());
        ticket.setCreatedAt(LocalDateTime.now());
        ticket.setUpdatedAt(LocalDateTime.now());
        tickets.add(ticket);
        persist();
        return ticket;
    }

    @Override
    public List<FeedbackTicketEntity> listTickets(Long userId) {
        return tickets.stream()
                .filter(ticket -> userId == null || ticket.getUserId().equals(userId))
                .sorted(Comparator.comparing(FeedbackTicketEntity::getCreatedAt).reversed())
                .toList();
    }

    @Override
    public Optional<FeedbackTicketEntity> replyTicket(Long ticketId, String status, String reply) {
        Optional<FeedbackTicketEntity> found = tickets.stream()
                .filter(ticket -> ticket.getId().equals(ticketId))
                .findFirst();
        found.ifPresent(ticket -> {
            ticket.setStatus(status);
            ticket.setOfficialReply(reply);
            ticket.setUpdatedAt(LocalDateTime.now());
            persist();
        });
        return found;
    }

    private long maxId(List<?> items, long fallback) {
        return items.stream()
                .map(item -> {
                    if (item instanceof ChatMessageEntity message) {
                        return message.getId();
                    }
                    if (item instanceof ChatSessionEntity session) {
                        return session.getId();
                    }
                    if (item instanceof NotificationEntity notification) {
                        return notification.getId();
                    }
                    if (item instanceof FeedbackTicketEntity ticket) {
                        return ticket.getId();
                    }
                    if (item instanceof FaqEntity faq) {
                        return faq.getId();
                    }
                    return null;
                })
                .filter(id -> id != null)
                .mapToLong(Long::longValue)
                .max()
                .orElse(fallback);
    }

    private void persist() {
        State state = new State();
        state.messages = new ArrayList<>(messages);
        state.sessions = new ArrayList<>(sessions);
        state.notifications = new ArrayList<>(notifications);
        state.tickets = new ArrayList<>(tickets);
        state.faqs = new ArrayList<>(faqs);
        LocalJsonStore.write(storePath, state);
    }

    public static class State {
        public List<ChatMessageEntity> messages = new ArrayList<>();
        public List<ChatSessionEntity> sessions = new ArrayList<>();
        public List<NotificationEntity> notifications = new ArrayList<>();
        public List<FeedbackTicketEntity> tickets = new ArrayList<>();
        public List<FaqEntity> faqs = new ArrayList<>();
    }

    private void migrateLegacySessions() {
        for (Long legacyId : messages.stream().map(ChatMessageEntity::getSessionId).distinct().toList()) {
            if (sessions.stream().anyMatch(session -> session.getId().equals(legacyId))) continue;
            ChatSessionEntity session = new ChatSessionEntity();
            session.setId(legacyId);
            session.setUserAId(1L);
            session.setUserBId(2L);
            session.setStatus("ACTIVE");
            session.setUpdatedAt(messages.stream()
                    .filter(message -> legacyId.equals(message.getSessionId()))
                    .map(ChatMessageEntity::getCreatedAt)
                    .max(LocalDateTime::compareTo)
                    .orElse(LocalDateTime.now()));
            sessions.add(session);
        }
    }
}
