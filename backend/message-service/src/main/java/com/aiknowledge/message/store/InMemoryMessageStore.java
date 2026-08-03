package com.aiknowledge.message.store;

import com.aiknowledge.common.LocalJsonStore;
import com.aiknowledge.message.entity.ChatMessageEntity;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Repository
@Profile("!mysql")
public class InMemoryMessageStore implements MessageStore {
    private final Path storePath = LocalJsonStore.dataFile("messages.json");
    private final AtomicLong messageIds = new AtomicLong(1000);
    private final AtomicLong notificationIds = new AtomicLong(2000);
    private final AtomicLong ticketIds = new AtomicLong(3000);
    private final AtomicLong faqIds = new AtomicLong(4000);
    private final List<ChatMessageEntity> messages = new CopyOnWriteArrayList<>();
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
            notificationIds.set(maxId(notifications, 2000L));
            ticketIds.set(maxId(tickets, 3000L));
            faqIds.set(maxId(faqs, 4000L));
            return;
        }

        NotificationEntity notification = new NotificationEntity();
        notification.setId(notificationIds.incrementAndGet());
        notification.setUserId(1L);
        notification.setType("SYSTEM");
        notification.setTitle("Local environment is ready");
        notification.setContent("Message service is running in local memory mode.");
        notification.setIsRead(0);
        notification.setCreatedAt(LocalDateTime.now());
        notifications.add(notification);

        FaqEntity faq = new FaqEntity();
        faq.setId(faqIds.incrementAndGet());
        faq.setQuestion("Which file formats are supported?");
        faq.setAnswer("The MVP plans to support Word, PDF, TXT and MD.");
        faq.setSortNo(1);
        faq.setEnabled(1);
        faqs.add(faq);
        persist();
    }

    @Override
    public ChatMessageEntity sendMessage(ChatMessageEntity message) {
        message.setId(messageIds.incrementAndGet());
        message.setCreatedAt(LocalDateTime.now());
        messages.add(message);
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
    public List<NotificationEntity> listNotifications(Long userId) {
        return notifications.stream()
                .filter(notification -> userId == null || notification.getUserId().equals(userId))
                .sorted(Comparator.comparing(NotificationEntity::getCreatedAt).reversed())
                .toList();
    }

    @Override
    public List<FaqEntity> listFaqs() {
        return faqs.stream()
                .filter(faq -> faq.getEnabled() == null || faq.getEnabled() == 1)
                .sorted(Comparator.comparing(FaqEntity::getSortNo, Comparator.nullsLast(Integer::compareTo)))
                .toList();
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

    private long maxId(List<?> items, long fallback) {
        return items.stream()
                .map(item -> {
                    if (item instanceof ChatMessageEntity message) {
                        return message.getId();
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
        state.notifications = new ArrayList<>(notifications);
        state.tickets = new ArrayList<>(tickets);
        state.faqs = new ArrayList<>(faqs);
        LocalJsonStore.write(storePath, state);
    }

    public static class State {
        public List<ChatMessageEntity> messages = new ArrayList<>();
        public List<NotificationEntity> notifications = new ArrayList<>();
        public List<FeedbackTicketEntity> tickets = new ArrayList<>();
        public List<FaqEntity> faqs = new ArrayList<>();
    }
}
