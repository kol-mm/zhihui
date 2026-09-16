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
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void privateMessagesAndFeedbackCanBeDisabled() {
        PlatformConfigClient config = mock(PlatformConfigClient.class);
        when(config.enabled("private_messages_enabled", true)).thenReturn(false);
        when(config.enabled("feedback_enabled", true)).thenReturn(false);
        MessageController disabled = new MessageController(
                new InMemoryMessageStore(),
                new LocalEventBusService("local", "127.0.0.1", 5672, "ai-knowledge.events"),
                config
        );

        assertEquals(500, disabled.createSession(userAuth, Map.of("targetUserId", 7L)).code());
        assertEquals(500, disabled.createTicket(userAuth, Map.of("type", "BUG", "content", "disabled")).code());
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
    void userCanClearAllOwnMessagesAndDeleteAConversation() {
        Long firstSessionId = ((Number) controller.createSession(userAuth, Map.of("targetUserId", 7L)).data().get("id")).longValue();
        Long secondSessionId = ((Number) controller.createSession(userAuth, Map.of("targetUserId", 8L)).data().get("id")).longValue();
        controller.send(userAuth, Map.of("sessionId", firstSessionId, "content", "first"));
        controller.send(userAuth, Map.of("sessionId", secondSessionId, "content", "second"));

        assertEquals(2, controller.clearAllForUser(userAuth).data().get("removed"));
        assertTrue(controller.list(userAuth, firstSessionId, 1L).data().isEmpty());
        assertEquals(true, controller.deleteSession(userAuth, Map.of("sessionId", firstSessionId)).data().get("removed"));
        assertFalse(controller.sessions(userAuth, 1L).data().stream().anyMatch(item -> firstSessionId.equals(item.get("id"))));
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
    void messagesCanBePagedBackwardAndPolledForward() {
        Long sessionId = ((Number) controller.createSession(userAuth, Map.of("targetUserId", 7L)).data().get("id")).longValue();
        for (int i = 1; i <= 5; i++) {
            controller.send(userAuth, Map.of("sessionId", sessionId, "content", "m" + i));
        }

        List<Map<String, Object>> latest = controller.page(userAuth, sessionId, null, null, 2).data();
        assertEquals(List.of("m4", "m5"), latest.stream().map(item -> item.get("content")).toList());

        Long oldestLoaded = ((Number) latest.get(0).get("id")).longValue();
        List<Map<String, Object>> older = controller.page(userAuth, sessionId, oldestLoaded, null, 2).data();
        assertEquals(List.of("m2", "m3"), older.stream().map(item -> item.get("content")).toList());

        Long newestLoaded = ((Number) latest.get(1).get("id")).longValue();
        assertTrue(controller.page(userAuth, sessionId, null, newestLoaded, 30).data().isEmpty());
        controller.send(userAuth, Map.of("sessionId", sessionId, "content", "m6"));
        List<Map<String, Object>> newer = controller.page(userAuth, sessionId, null, newestLoaded, 30).data();
        assertEquals(List.of("m6"), newer.stream().map(item -> item.get("content")).toList());

        assertEquals(500, controller.page(userAuth, sessionId, oldestLoaded, newestLoaded, 30).code());
        String outsiderAuth = "Bearer " + LocalAuth.issueToken("outsider", 3L, "USER");
        assertEquals(500, controller.page(outsiderAuth, sessionId, null, null, 30).code());
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
    void notificationsPageWithCursorAndTrackUnreadCount() {
        InMemoryMessageStore store = new InMemoryMessageStore();
        MessageController isolated = new MessageController(
                store, new LocalEventBusService("local", "127.0.0.1", 5672, "ai-knowledge.events"));
        String readerAuth = "Bearer " + LocalAuth.issueToken("notice-reader", 61L, "USER");
        for (int i = 1; i <= 5; i++) {
            NotificationEntity notification = new NotificationEntity();
            notification.setUserId(61L);
            notification.setType("SYSTEM");
            notification.setTitle("通知 " + i);
            notification.setContent("内容 " + i);
            store.saveNotification(notification);
        }
        assertEquals(5L, ((Number) isolated.unreadNotificationCount(readerAuth, null).data().get("unread")).longValue());

        Map<String, Object> first = isolated.notificationPage(readerAuth, null, null, 2, false).data();
        List<Map<String, Object>> firstItems = itemsOf(first);
        assertEquals(2, firstItems.size());
        assertEquals("通知 5", firstItems.get(0).get("title"));
        assertEquals(true, first.get("hasMore"));
        assertEquals(5L, ((Number) first.get("unread")).longValue());

        Map<String, Object> second = isolated.notificationPage(readerAuth, null, ((Number) first.get("nextCursor")).longValue(), 2, false).data();
        assertEquals("通知 3", itemsOf(second).get(0).get("title"));
        assertFalse(itemsOf(second).stream().anyMatch(item -> firstItems.stream().anyMatch(seen -> seen.get("id").equals(item.get("id")))));

        Long readMe = ((Number) firstItems.get(0).get("id")).longValue();
        assertEquals(4L, ((Number) isolated.markNotificationRead(readerAuth, Map.of("notificationId", readMe)).data().get("unread")).longValue());
        assertEquals(4, itemsOf(isolated.notificationPage(readerAuth, null, null, 20, true).data()).size());

        assertEquals(0L, ((Number) isolated.markAllNotificationsRead(readerAuth).data().get("unread")).longValue());
        assertEquals(0, itemsOf(isolated.notificationPage(readerAuth, null, null, 20, true).data()).size());
        assertEquals(5, itemsOf(isolated.notificationPage(readerAuth, null, null, 20, false).data()).size());
        assertEquals(500, isolated.notificationPage("Bearer invalid", null, null, 20, false).code());
        assertEquals(500, isolated.notificationPage(readerAuth, null, -1L, 20, false).code());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    @Test
    void adminSessionListCountsMessagesPerSession() {
        Long busy = ((Number) controller.createSession(userAuth, Map.of("targetUserId", 41L)).data().get("id")).longValue();
        Long quiet = ((Number) controller.createSession(userAuth, Map.of("targetUserId", 42L)).data().get("id")).longValue();
        for (int i = 0; i < 3; i++) controller.send(userAuth, Map.of("sessionId", busy, "content", "busy " + i));
        String adminAuth = "Bearer " + LocalAuth.issueToken("admin", 2L, "ADMIN");

        Map<Long, Object> counts = new java.util.HashMap<>();
        Map<Long, Object> lastMessages = new java.util.HashMap<>();
        controller.adminSessions(adminAuth).data().forEach(view -> {
            counts.put(((Number) view.get("id")).longValue(), view.get("messageCount"));
            lastMessages.put(((Number) view.get("id")).longValue(), view.get("lastMessage"));
        });
        assertEquals(3L, counts.get(busy));
        assertEquals(0L, counts.get(quiet));
        assertEquals("busy 2", lastMessages.get(busy));
        assertEquals("", lastMessages.get(quiet));
        assertEquals(500, controller.adminSessions(userAuth).code());
    }

    @Test
    void adminCanRestrictArchiveAndRestoreAConversation() {
        Long sessionId = ((Number) controller.createSession(userAuth, Map.of("targetUserId", 7L)).data().get("id")).longValue();
        String adminAuth = "Bearer " + LocalAuth.issueToken("admin", 2L, "ADMIN");

        assertEquals("RESTRICTED", controller.updateSessionStatus(adminAuth, Map.of(
                "sessionId", sessionId, "status", "RESTRICTED")).data().get("status"));
        assertEquals(500, controller.send(userAuth, Map.of("sessionId", sessionId, "content", "blocked")).code());
        assertEquals("ARCHIVED", controller.updateSessionStatus(adminAuth, Map.of(
                "sessionId", sessionId, "status", "ARCHIVED")).data().get("status"));
        assertEquals(0, controller.list(userAuth, sessionId, 1L).code());
        assertEquals("ACTIVE", controller.updateSessionStatus(adminAuth, Map.of(
                "sessionId", sessionId, "status", "ACTIVE")).data().get("status"));
        assertEquals(0, controller.send(userAuth, Map.of("sessionId", sessionId, "content", "restored")).code());
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
        assertEquals(false, status.data().containsKey("rabbitHost"));
        assertEquals(false, status.data().containsKey("rabbitPort"));
        assertEquals(false, status.data().containsKey("exchange"));

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

    @Test
    void adminCanAssignTicketsAndViewSupportWorkload() {
        Long ticketId = ((Number) controller.createTicket(userAuth, Map.of(
                "type", "SUPPORT", "content", "need a support agent"
        )).data().get("id")).longValue();
        String adminAuth = "Bearer " + LocalAuth.issueToken("admin", 2L, "ADMIN");

        Map<String, Object> assigned = controller.assignTicket(adminAuth, Map.of(
                "ticketId", ticketId, "assigneeUserId", 2L
        )).data();
        assertEquals(2L, assigned.get("assigneeUserId"));
        assertEquals("PROCESSING", assigned.get("status"));

        Map<String, Object> overview = controller.feedbackAdminOverview(adminAuth, null).data();
        assertTrue(((Number) overview.get("supportTickets")).longValue() >= 1L);
        @SuppressWarnings("unchecked")
        Map<Long, Map<String, Long>> workload = (Map<Long, Map<String, Long>>) overview.get("supportWorkload");
        assertEquals(1L, workload.get(2L).get("assigned"));
    }

    @Test
    void feedbackAnalyticsGroupsTicketsByTypeAndDay() {
        String adminAuth = "Bearer " + LocalAuth.issueToken("admin", 2L, "ADMIN");
        // The in-memory store carries data from the other tests, so every total is asserted as a delta.
        Map<String, Object> before = controller.feedbackAdminAnalytics(adminAuth, 30).data();
        long baseTotal = analyticsNumber(before.get("total"));
        long baseBug = analyticsNumber(before.get("bug"));
        long baseSuggestion = analyticsNumber(before.get("suggestion"));
        long baseResolved = analyticsNumber(before.get("resolved"));

        controller.createTicket(userAuth, Map.of("type", "BUG", "content", "统计缺陷 " + System.nanoTime()));
        Long ticketId = ((Number) controller.createTicket(userAuth, Map.of(
                "type", "SUGGESTION", "content", "统计建议 " + System.nanoTime())).data().get("id")).longValue();
        controller.replyTicket(adminAuth, Map.of("ticketId", ticketId, "status", "RESOLVED", "reply", "已处理"));

        Map<String, Object> after = controller.feedbackAdminAnalytics(adminAuth, 30).data();
        assertEquals(baseTotal + 2, analyticsNumber(after.get("total")));
        assertEquals(baseBug + 1, analyticsNumber(after.get("bug")));
        assertEquals(baseSuggestion + 1, analyticsNumber(after.get("suggestion")));
        assertEquals(baseResolved + 1, analyticsNumber(after.get("resolved")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trend = (List<Map<String, Object>>) after.get("trend");
        assertEquals(14, trend.size());
        assertEquals(java.time.LocalDate.now().toString(), trend.get(13).get("date"));
        assertTrue(analyticsNumber(trend.get(13).get("count")) >= 2);
        assertEquals(7, ((List<?>) controller.feedbackAdminAnalytics(adminAuth, 7).data().get("trend")).size());

        assertEquals(500, controller.feedbackAdminAnalytics(userAuth, 30).code());
    }

    private static long analyticsNumber(Object value) {
        return value instanceof Number found ? found.longValue() : 0L;
    }


    @Test
    void adminOverviewsCountTicketsFaqsAndNotifications() {
        String adminAuth = "Bearer " + LocalAuth.issueToken("admin", 2L, "ADMIN");
        Map<String, Object> before = controller.feedbackAdminOverview(adminAuth, null).data();
        long baseTickets = analyticsNumber(before.get("tickets"));
        long baseSupport = analyticsNumber(before.get("supportTickets"));
        long baseProcessing = analyticsNumber(before.get("processingTickets"));
        long baseFaqs = analyticsNumber(before.get("faqs"));

        Long ticketId = ((Number) controller.createTicket(userAuth, Map.of(
                "type", "SUPPORT", "content", "概览工单 " + System.nanoTime())).data().get("id")).longValue();
        controller.assignTicket(adminAuth, Map.of("ticketId", ticketId, "assigneeUserId", 7L));
        controller.saveFaq(adminAuth, Map.of("question", "概览问题 " + System.nanoTime(), "answer", "概览答案"));

        Map<String, Object> after = controller.feedbackAdminOverview(adminAuth, null).data();
        assertEquals(baseTickets + 1, analyticsNumber(after.get("tickets")));
        assertEquals(baseSupport + 1, analyticsNumber(after.get("supportTickets")));
        assertEquals(baseProcessing + 1, analyticsNumber(after.get("processingTickets")));
        assertEquals(baseFaqs + 1, analyticsNumber(after.get("faqs")));
        @SuppressWarnings("unchecked")
        Map<Long, Map<String, Long>> workload = (Map<Long, Map<String, Long>>) after.get("supportWorkload");
        assertEquals(1L, workload.get(7L).get("assigned"));
        assertEquals(1L, workload.get(7L).get("processing"));
        assertEquals(0L, workload.get(7L).get("resolved"));

        // Narrowed to one user the count matches that user's list; unscoped it covers everyone.
        long mine = analyticsNumber(controller.messageAdminOverview(adminAuth, 1L).data().get("notifications"));
        assertEquals(controller.notifications(userAuth, null).data().size(), mine);
        assertTrue(analyticsNumber(controller.messageAdminOverview(adminAuth, null).data().get("notifications")) >= mine);
    }


    @Test
    void ticketPageFiltersAndPagesServerSide() {
        String adminAuth = "Bearer " + LocalAuth.issueToken("admin", 2L, "ADMIN");
        String marker = "工单分页" + System.nanoTime();
        List<Long> created = new java.util.ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            created.add(((Number) controller.createTicket(userAuth, Map.of(
                    "type", i == 3 ? "SUPPORT" : "BUG", "content", marker + " " + i)).data().get("id")).longValue());
        }
        controller.replyTicket(adminAuth, Map.of("ticketId", created.get(0), "status", "RESOLVED", "reply", "已处理"));

        // Newest first, so the walk starts at the last ticket created.
        Map<String, Object> first = controller.ticketsPage(adminAuth, null, null, marker, null, 2).data();
        assertEquals(List.of(created.get(2), created.get(1)), ticketIds(first));
        assertEquals(true, first.get("hasMore"));
        assertEquals(3L, ((Number) first.get("total")).longValue());

        Map<String, Object> second = controller.ticketsPage(
                adminAuth, null, null, marker, ((Number) first.get("nextCursor")).longValue(), 2).data();
        assertEquals(List.of(created.get(0)), ticketIds(second));
        assertEquals(false, second.get("hasMore"));
        assertEquals(null, second.get("total"));

        assertEquals(List.of(created.get(0)), ticketIds(controller.ticketsPage(adminAuth, null, "RESOLVED", marker, null, 20).data()));
        assertEquals(2L, ((Number) controller.ticketsPage(adminAuth, null, "PENDING", marker, null, 20)
                .data().get("total")).longValue());
        assertEquals(List.of(created.get(2)), ticketIds(controller.ticketsPage(adminAuth, null, null, marker + " 3", null, 20).data()));

        // A member only ever sees their own tickets, whatever userId they ask for.
        String otherAuth = "Bearer " + LocalAuth.issueToken("other-reporter", 4321L, "USER");
        assertTrue(ticketIds(controller.ticketsPage(otherAuth, null, null, marker, null, 20).data()).isEmpty());
        assertEquals(500, controller.ticketsPage(otherAuth, 1L, null, null, null, 20).code());
        assertEquals(500, controller.ticketsPage(adminAuth, null, null, null, -1L, 20).code());
    }

    @SuppressWarnings("unchecked")
    private static List<Long> ticketIds(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("items")).stream()
                .map(item -> ((Number) item.get("id")).longValue()).toList();
    }


    @Test
    void governanceSessionPageOrdersByActivityAndSearchesMessages() throws InterruptedException {
        String adminAuth = "Bearer " + LocalAuth.issueToken("admin", 2L, "ADMIN");
        String marker = "治理私信" + System.nanoTime();
        List<Long> sessions = new java.util.ArrayList<>();
        for (long target = 801L; target <= 803L; target++) {
            Long sessionId = ((Number) controller.createSession(userAuth, Map.of("userId", 1L, "targetUserId", target))
                    .data().get("id")).longValue();
            controller.send(userAuth, Map.of("sessionId", sessionId, "senderId", 1L, "content", marker + " 开场 " + target));
            sessions.add(sessionId);
            Thread.sleep(5);
        }
        // New activity in the first conversation brings it back to the top.
        controller.send(userAuth, Map.of("sessionId", sessions.get(0), "senderId", 1L, "content", marker + " 追问"));

        Map<String, Object> first = controller.adminSessionsPage(adminAuth, marker, null, 2).data();
        assertEquals(List.of(sessions.get(0), sessions.get(2)), ids(first));
        assertEquals(true, first.get("hasMore"));
        assertEquals(3L, analyticsNumber(first.get("total")));
        assertEquals(2L, analyticsNumber(items(first).get(0).get("messageCount")));
        Map<String, Object> second = controller.adminSessionsPage(adminAuth, marker, (String) first.get("nextCursor"), 2).data();
        assertEquals(List.of(sessions.get(1)), ids(second));
        assertEquals(null, second.get("total"));

        // Text from any message in the conversation finds it, not just the latest one.
        assertEquals(List.of(sessions.get(1)), ids(controller.adminSessionsPage(adminAuth, marker + " 开场 802", null, 20).data()));
        assertEquals(500, controller.adminSessionsPage(adminAuth, marker, "garbage", 20).code());
        assertEquals(500, controller.adminSessionsPage(userAuth, marker, null, 20).code());
    }

    @Test
    void governanceMessagePageReadsBackwardsInChronologicalPages() {
        String adminAuth = "Bearer " + LocalAuth.issueToken("admin", 2L, "ADMIN");
        Long sessionId = ((Number) controller.createSession(userAuth, Map.of("userId", 1L, "targetUserId", 810L))
                .data().get("id")).longValue();
        List<Long> sent = new java.util.ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            sent.add(((Number) controller.send(userAuth, Map.of("sessionId", sessionId, "senderId", 1L, "content", "第 " + i + " 条"))
                    .data().get("id")).longValue());
        }

        Map<String, Object> latest = controller.adminMessagesPage(adminAuth, sessionId, null, 2).data();
        assertEquals(List.of(sent.get(3), sent.get(4)), ids(latest));
        assertEquals(true, latest.get("hasMore"));
        assertEquals(sent.get(3), ((Number) latest.get("nextCursor")).longValue());
        Map<String, Object> earlier = controller.adminMessagesPage(adminAuth, sessionId, sent.get(3), 2).data();
        assertEquals(List.of(sent.get(1), sent.get(2)), ids(earlier));
        Map<String, Object> oldest = controller.adminMessagesPage(adminAuth, sessionId, sent.get(1), 2).data();
        assertEquals(List.of(sent.get(0)), ids(oldest));
        assertEquals(false, oldest.get("hasMore"));
        assertEquals(null, oldest.get("nextCursor"));

        assertEquals(500, controller.adminMessagesPage(adminAuth, 987654L, null, 2).code());
        assertEquals(500, controller.adminMessagesPage(userAuth, sessionId, null, 2).code());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private static List<Long> ids(Map<String, Object> page) {
        return items(page).stream().map(item -> ((Number) item.get("id")).longValue()).toList();
    }

}
