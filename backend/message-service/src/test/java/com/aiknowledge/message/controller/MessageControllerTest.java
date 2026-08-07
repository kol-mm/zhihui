package com.aiknowledge.message.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.common.PlatformConfigClient;
import com.aiknowledge.common.UserRelationClient;
import com.aiknowledge.message.entity.NotificationEntity;
import com.aiknowledge.message.event.LocalEventBusService;
import com.aiknowledge.message.store.InMemoryMessageStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessageControllerTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/message-" + System.nanoTime());
    }

    private final MessageController controller =
            new MessageController(
                    new InMemoryMessageStore(),
                    new LocalEventBusService("local", "127.0.0.1", 5672, "ai-knowledge.events")
            );
    private final String userAuth = "Bearer " + LocalAuth.issueToken("demo");

    @Test
    void disabledNotificationsRejectUserAccess() {
        PlatformConfigClient config = mock(PlatformConfigClient.class);
        when(config.enabled("notifications_enabled", true)).thenReturn(false);
        MessageController disabled = new MessageController(
                new InMemoryMessageStore(),
                new LocalEventBusService("local", "127.0.0.1", 5672, "ai-knowledge.events"),
                config
        );
        assertEquals(500, disabled.notifications(userAuth, 1L).code());
        assertEquals(500, disabled.markAllNotificationsRead(userAuth).code());
    }

    @Test
    void sentMessageCanBeListedAndCleared() {
        ApiResponse<Map<String, Object>> session = controller.createSession(userAuth, Map.of(
                "userId", 1L,
                "targetUserId", 7L
        ));
        Long sessionId = ((Number) session.data().get("id")).longValue();
        ApiResponse<Map<String, Object>> sent = controller.send(userAuth, Map.of(
                "sessionId", sessionId,
                "senderId", 1L,
                "content", "hello"
        ));
        assertEquals(0, sent.code());

        ApiResponse<List<Map<String, Object>>> messages = controller.list(userAuth, sessionId, 1L);
        assertEquals(0, messages.code());
        assertEquals(1, messages.data().size());
        assertEquals("hello", messages.data().get(0).get("content"));
        assertEquals(7L, controller.sessions(userAuth, 1L).data().get(0).get("otherUserId"));
        assertEquals("hello", controller.sessions(userAuth, 1L).data().get(0).get("lastMessage"));

        Long messageId = ((Number) messages.data().get(0).get("id")).longValue();
        assertEquals(true, controller.deleteMessage(userAuth, Map.of("messageId", messageId, "userId", 999L)).data().get("removed"));

        controller.send(userAuth, Map.of("sessionId", sessionId, "senderId", 999L, "content", "again"));

        ApiResponse<Map<String, Object>> cleared = controller.clear(userAuth, Map.of("sessionId", sessionId, "userId", 999L));
        assertEquals(0, cleared.code());
        assertEquals(1, cleared.data().get("removed"));
    }

    @Test
    void nonParticipantCannotReadOrSendPrivateMessages() {
        var session = controller.createSession(userAuth, Map.of("userId", 999L, "targetUserId", 2L));
        Long sessionId = ((Number) session.data().get("id")).longValue();
        String outsiderAuth = "Bearer " + LocalAuth.issueToken("outsider", 3L, "USER");

        assertEquals(500, controller.send(outsiderAuth, Map.of(
                "sessionId", sessionId,
                "senderId", 3L,
                "content", "not allowed"
        )).code());
        assertEquals(500, controller.list(outsiderAuth, sessionId, 3L).code());
    }

    @Test
    void recipientCannotDeleteTheSendersMessage() {
        var session = controller.createSession(userAuth, Map.of("targetUserId", 7L));
        Long sessionId = ((Number) session.data().get("id")).longValue();
        Long messageId = ((Number) controller.send(userAuth, Map.of(
                "sessionId", sessionId,
                "content", "sender-owned"
        )).data().get("id")).longValue();
        String recipientAuth = "Bearer " + LocalAuth.issueToken("recipient", 7L, "USER");

        assertEquals(500, controller.deleteMessage(recipientAuth, Map.of("messageId", messageId)).code());
        assertEquals(0, controller.deleteMessage(userAuth, Map.of("messageId", messageId)).code());
    }

    @Test
    void blockedUsersCannotStartAConversation() {
        UserRelationClient relations = mock(UserRelationClient.class);
        when(relations.interactionAllowed(1L, 7L)).thenReturn(false);
        MessageController restricted = new MessageController(
                new InMemoryMessageStore(),
                new LocalEventBusService("local", "127.0.0.1", 5672, "ai-knowledge.events"),
                null,
                relations
        );
        assertEquals(500, restricted.createSession(userAuth, Map.of("targetUserId", 7L)).code());
    }

    @Test
    void eventBusStoresBusinessEventsAndExposesStatus() {
        controller.createTicket(userAuth, Map.of(
                "userId", 1L,
                "type", "SUPPORT",
                "content", "need help"
        ));

        ApiResponse<Map<String, Object>> status = controller.eventStatus();
        assertEquals(0, status.code());
        assertEquals("local", status.data().get("mode"));
        assertEquals(false, status.data().get("rabbitReady"));

        String adminAuth = "Bearer " + LocalAuth.issueToken("admin", 2L, "ADMIN");
        assertEquals(500, controller.events(userAuth, 20).code());
        ApiResponse<List<Map<String, Object>>> events = controller.events(adminAuth, 20);
        assertEquals(0, events.code());
        assertFalse(events.data().isEmpty());
        assertEquals("FEEDBACK_TICKET_CREATED", events.data().get(0).get("type"));
    }

    @Test
    void faqsAndNotificationsHaveLocalSeedData() {
        ApiResponse<List<Map<String, Object>>> faqs = controller.faqs();
        assertEquals(0, faqs.code());
        assertFalse(faqs.data().isEmpty());

        ApiResponse<List<Map<String, Object>>> notifications = controller.notifications(userAuth, 1L);
        assertEquals(0, notifications.code());
        assertFalse(notifications.data().isEmpty());
    }

    @Test
    void historicalPlatformNotificationsAreLocalizedWithoutChangingUserContent() {
        InMemoryMessageStore store = new InMemoryMessageStore();
        NotificationEntity notification = new NotificationEntity();
        notification.setUserId(1L);
        notification.setType("COMMENT");
        notification.setTitle("Your post received a new comment");
        notification.setContent("Post #4: notification integration passed");
        store.saveNotification(notification);
        MessageController localized = new MessageController(
                store,
                new LocalEventBusService("local", "127.0.0.1", 5672, "ai-knowledge.events")
        );

        Map<String, Object> view = localized.notifications(userAuth, 1L).data().stream()
                .filter(item -> notification.getId().equals(item.get("id")))
                .findFirst().orElseThrow();
        assertEquals("你的帖子收到新评论", view.get("title"));
        assertEquals("帖子 #4：notification integration passed", view.get("content"));
    }

    @Test
    void privateMessageCreatesRecipientNotificationThatCanBeRead() {
        var session = controller.createSession(userAuth, Map.of("targetUserId", 7L));
        Long sessionId = ((Number) session.data().get("id")).longValue();
        controller.send(userAuth, Map.of("sessionId", sessionId, "content", "notification body"));

        String recipientAuth = "Bearer " + LocalAuth.issueToken("recipient", 7L, "USER");
        var notifications = controller.notifications(recipientAuth, 7L);
        Map<String, Object> createdNotification = notifications.data().stream()
                .filter(item -> "notification body".equals(item.get("content")))
                .findFirst().orElseThrow();
        assertEquals("MESSAGE", createdNotification.get("type"));
        assertEquals(false, createdNotification.get("read"));

        Long notificationId = ((Number) createdNotification.get("id")).longValue();
        assertEquals(500, controller.markNotificationRead(userAuth, Map.of("notificationId", notificationId)).code());
        assertEquals(0, controller.markNotificationRead(recipientAuth, Map.of("notificationId", notificationId)).code());
        assertEquals(true, controller.notifications(recipientAuth, 7L).data().stream()
                .filter(item -> notificationId.equals(item.get("id"))).findFirst().orElseThrow().get("read"));
        assertEquals(0, controller.markAllNotificationsRead(recipientAuth).code());
    }

    @Test
    void adminCanCreateAndDeleteFaq() {
        String auth = "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("admin");
        var created = controller.saveFaq(auth, Map.of("question", "How?", "answer", "Locally", "sortNo", 2));
        assertEquals("How?", created.data().get("question"));
        Long faqId = ((Number) created.data().get("id")).longValue();
        assertEquals(true, controller.deleteFaq(auth, Map.of("faqId", faqId)).data().get("removed"));
    }

    @Test
    void feedbackTicketCanBeCreatedAndListed() {
        ApiResponse<Map<String, Object>> created = controller.createTicket(userAuth, Map.of(
                "userId", 1L,
                "type", "BUG",
                "content", "button is not clickable"
        ));
        assertEquals(0, created.code());
        assertEquals("PENDING", created.data().get("status"));

        ApiResponse<List<Map<String, Object>>> tickets = controller.tickets(userAuth, 1L);
        assertEquals(0, tickets.code());
        assertFalse(tickets.data().isEmpty());
        assertEquals("button is not clickable", tickets.data().get(0).get("content"));

        Long ticketId = ((Number) created.data().get("id")).longValue();
        String adminAuth = "Bearer " + LocalAuth.issueToken("admin");
        assertEquals(0, controller.replyTicket(adminAuth, Map.of(
                "ticketId", ticketId,
                "status", "RESOLVED",
                "reply", "The button has been fixed."
        )).code());
        assertEquals(true, controller.notifications(userAuth, 1L).data().stream()
                .anyMatch(item -> "FEEDBACK".equals(item.get("type"))
                        && "The button has been fixed.".equals(item.get("content"))));
    }
}
