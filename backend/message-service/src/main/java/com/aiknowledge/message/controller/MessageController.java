package com.aiknowledge.message.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.common.PlatformConfigClient;
import com.aiknowledge.common.UserRelationClient;
import com.aiknowledge.message.entity.ChatMessageEntity;
import com.aiknowledge.message.entity.ChatSessionEntity;
import com.aiknowledge.message.entity.FaqEntity;
import com.aiknowledge.message.entity.FeedbackTicketEntity;
import com.aiknowledge.message.entity.NotificationEntity;
import com.aiknowledge.message.event.LocalEventBusService;
import com.aiknowledge.message.store.MessageStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.aiknowledge.common.DailySeries;
import java.time.LocalDate;
import java.time.LocalDateTime;
import com.aiknowledge.common.TimeCursor;

@RestController
public class MessageController {
    private static final int NOTIFICATION_PAGE_MAX = 50;
    private final MessageStore messageStore;
    private final LocalEventBusService eventBus;
    private final PlatformConfigClient platformConfig;
    private final UserRelationClient userRelationClient;

    @Autowired
    public MessageController(MessageStore messageStore, LocalEventBusService eventBus,
                             PlatformConfigClient platformConfig, UserRelationClient userRelationClient) {
        this.messageStore = messageStore;
        this.eventBus = eventBus;
        this.platformConfig = platformConfig;
        this.userRelationClient = userRelationClient;
    }

    public MessageController(MessageStore messageStore, LocalEventBusService eventBus, PlatformConfigClient platformConfig) {
        this(messageStore, eventBus, platformConfig, null);
    }

    public MessageController(MessageStore messageStore, LocalEventBusService eventBus) {
        this(messageStore, eventBus, null);
    }

    @GetMapping("/message/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
                "service", "message-service",
                "time", Instant.now().toString(),
                "dataMode", storeMode(messageStore),
                "eventMode", eventBus.status().get("mode")
        ));
    }

    @PostMapping("/message/send")
    public ApiResponse<Map<String, Object>> send(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long sessionId = number(request.get("sessionId"), 0L);
        Long senderId = LocalAuth.userId(authorization);
        if (senderId == null) return ApiResponse.fail("valid user authorization is required");
        String content = String.valueOf(request.getOrDefault("content", "")).trim();
        ChatSessionEntity session = messageStore.findSession(sessionId).orElse(null);
        if (session == null) return ApiResponse.fail("chat session not found");
        if (!isParticipant(session, senderId)) return ApiResponse.fail("user is not a participant of this session");
        if (!"ACTIVE".equals(session.getStatus())) return ApiResponse.fail("this conversation is not available for sending messages");
        Long recipientId = session.getUserAId().equals(senderId) ? session.getUserBId() : session.getUserAId();
        if (!messagingAllowed(senderId, recipientId)) return ApiResponse.fail("private messaging is unavailable for this conversation");
        if (content.isBlank()) return ApiResponse.fail("message content is required");
        if (content.length() > maxMessageLength()) return ApiResponse.fail("私信内容不能超过 " + maxMessageLength() + " 个字符");
        ChatMessageEntity message = new ChatMessageEntity();
        message.setSessionId(sessionId);
        message.setSenderId(senderId);
        message.setContent(content);
        message.setStatus("NORMAL");
        ChatMessageEntity saved = messageStore.sendMessage(message);
        if (notificationsEnabled()) {
        NotificationEntity notification = new NotificationEntity();
        notification.setUserId(recipientId);
        notification.setType("MESSAGE");
        notification.setTitle("收到新的私信");
        notification.setContent(content);
        messageStore.saveNotification(notification);
        }
        eventBus.publish("MESSAGE_SENT", String.valueOf(saved.getId()), Map.of(
                "sessionId", saved.getSessionId(),
                "senderId", saved.getSenderId(),
                "status", saved.getStatus()
        ));
        return ApiResponse.ok(toMessageView(saved));
    }

    @PostMapping("/message/session")
    public ApiResponse<Map<String, Object>> createSession(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        Long targetUserId = number(request.get("targetUserId"), 0L);
        if (userId <= 0 || targetUserId <= 0) return ApiResponse.fail("both users are required");
        if (userId.equals(targetUserId)) return ApiResponse.fail("cannot create a private chat with yourself");
        if (!messagingAllowed(userId, targetUserId)) return ApiResponse.fail("private messaging is unavailable for these users");
        return ApiResponse.ok(toSessionView(messageStore.getOrCreateSession(userId, targetUserId), userId));
    }

    @GetMapping("/message/list")
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "sessionId", defaultValue = "1") Long sessionId,
            @RequestParam(name = "userId", required = false) Long requestedUserId
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        if (requestedUserId != null && !LocalAuth.canAccessUser(authorization, requestedUserId)) return ApiResponse.fail("access to this user is denied");
        ChatSessionEntity session = messageStore.findSession(sessionId).orElse(null);
        if (session == null) return ApiResponse.ok(List.of());
        if (!isParticipant(session, userId)) return ApiResponse.fail("user is not a participant of this session");
        return ApiResponse.ok(messageStore.listMessages(sessionId).stream().map(this::toMessageView).toList());
    }

    @GetMapping("/message/page")
    public ApiResponse<List<Map<String, Object>>> page(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "sessionId") Long sessionId,
            @RequestParam(name = "beforeId", required = false) Long beforeId,
            @RequestParam(name = "afterId", required = false) Long afterId,
            @RequestParam(name = "limit", defaultValue = "30") int limit
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        if (beforeId != null && afterId != null) return ApiResponse.fail("choose beforeId or afterId");
        if (beforeId != null && beforeId <= 0 || afterId != null && afterId < 0) return ApiResponse.fail("invalid message cursor");
        ChatSessionEntity session = messageStore.findSession(sessionId).orElse(null);
        if (session == null) return ApiResponse.ok(List.of());
        if (!isParticipant(session, userId)) return ApiResponse.fail("user is not a participant of this session");
        int pageSize = Math.max(1, Math.min(100, limit));
        return ApiResponse.ok(messageStore.pageMessages(sessionId, beforeId, afterId, pageSize)
                .stream().map(this::toMessageView).toList());
    }

    @PostMapping("/message/clear")
    public ApiResponse<Map<String, Object>> clear(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long sessionId = request.containsKey("sessionId") ? number(request.get("sessionId"), null) : null;
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        if (sessionId == null && !LocalAuth.isAdmin(authorization)) {
            return ApiResponse.fail("admin authorization is required to clear all messages");
        }
        if (sessionId != null) {
            ChatSessionEntity session = messageStore.findSession(sessionId).orElse(null);
            if (session == null) return ApiResponse.fail("chat session not found");
            if (!isParticipant(session, userId) && !LocalAuth.isAdmin(authorization)) {
                return ApiResponse.fail("user is not a participant of this session");
            }
        }
        int removed = messageStore.clearMessages(sessionId);
        eventBus.publish("MESSAGE_CLEARED", sessionId == null ? "ALL" : String.valueOf(sessionId), Map.of("removed", removed));
        return ApiResponse.ok(Map.of("sessionId", sessionId == null ? "ALL" : sessionId, "removed", removed));
    }

    @PostMapping("/message/clear-all")
    public ApiResponse<Map<String, Object>> clearAllForUser(
            @RequestHeader(name = "Authorization", required = false) String authorization
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        int removed = messageStore.clearUserMessages(userId);
        eventBus.publish("USER_MESSAGES_CLEARED", String.valueOf(userId), Map.of("removed", removed));
        return ApiResponse.ok(Map.of("userId", userId, "removed", removed));
    }

    @DeleteMapping("/message/session")
    public ApiResponse<Map<String, Object>> deleteSession(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        Long sessionId = number(request.get("sessionId"), 0L);
        ChatSessionEntity session = messageStore.findSession(sessionId).orElse(null);
        if (session == null) return ApiResponse.ok(Map.of("sessionId", sessionId, "removed", false));
        if (!isParticipant(session, userId) && !LocalAuth.isAdmin(authorization)) {
            return ApiResponse.fail("user is not a participant of this session");
        }
        boolean removed = messageStore.deleteSession(sessionId);
        if (removed) eventBus.publish("MESSAGE_SESSION_DELETED", String.valueOf(sessionId), Map.of("removed", true));
        return ApiResponse.ok(Map.of("sessionId", sessionId, "removed", removed));
    }

    @DeleteMapping("/message")
    public ApiResponse<Map<String, Object>> deleteMessage(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long messageId = number(request.get("messageId"), 0L);
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        ChatMessageEntity message = messageStore.findMessage(messageId).orElse(null);
        if (message == null) return ApiResponse.ok(Map.of("messageId", messageId, "removed", false));
        ChatSessionEntity session = messageStore.findSession(message.getSessionId()).orElse(null);
        if (session == null || !isParticipant(session, userId)) {
            return ApiResponse.fail("user is not a participant of this session");
        }
        if (!userId.equals(message.getSenderId()) && !LocalAuth.isAdmin(authorization)) {
            return ApiResponse.fail("only the sender can delete this message");
        }
        boolean removed = messageStore.deleteMessage(messageId);
        if (removed) eventBus.publish("MESSAGE_DELETED", String.valueOf(messageId), Map.of("removed", true));
        return ApiResponse.ok(Map.of("messageId", messageId, "removed", removed));
    }

    @GetMapping("/message/sessions")
    public ApiResponse<List<Map<String, Object>>> sessions(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "userId", required = false) Long requestedUserId
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        if (requestedUserId != null && !LocalAuth.canAccessUser(authorization, requestedUserId)) return ApiResponse.fail("access to this user is denied");
        return ApiResponse.ok(toSessionViews(messageStore.listSessions(userId), session -> userId));
    }

    @GetMapping("/event/status")
    public ApiResponse<Map<String, Object>> eventStatus() {
        return ApiResponse.ok(eventBus.status());
    }

    @GetMapping("/event/list")
    public ApiResponse<List<Map<String, Object>>> events(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "limit", defaultValue = "50") Integer limit
    ) {
        if (!LocalAuth.isAdmin(authorization)) return ApiResponse.fail("admin authorization is required");
        return ApiResponse.ok(eventBus.list(limit).stream().map(this::toEventView).toList());
    }

    @GetMapping("/notification/list")
    public ApiResponse<List<Map<String, Object>>> notifications(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "userId", required = false) Long requestedUserId
    ) {
        Long authenticatedUserId = LocalAuth.userId(authorization);
        if (authenticatedUserId == null) return ApiResponse.fail("valid user authorization is required");
        if (!notificationsEnabled() && !LocalAuth.isAdmin(authorization)) return ApiResponse.fail("notifications feature is disabled");
        if (requestedUserId != null && !LocalAuth.canAccessUser(authorization, requestedUserId)) return ApiResponse.fail("access to this user is denied");
        Long userId = LocalAuth.isAdmin(authorization) ? requestedUserId : authenticatedUserId;
        return ApiResponse.ok(messageStore.listNotifications(userId).stream().map(this::toNotificationView).toList());
    }

    /** Newest-first page of notifications for the signed-in user (admins may read another user's). */
    @GetMapping("/notification/page")
    public ApiResponse<Map<String, Object>> notificationPage(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "userId", required = false) Long requestedUserId,
            @RequestParam(name = "cursor", required = false) Long cursor,
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            @RequestParam(name = "unreadOnly", defaultValue = "false") boolean unreadOnly
    ) {
        Long authenticatedUserId = LocalAuth.userId(authorization);
        if (authenticatedUserId == null) return ApiResponse.fail("valid user authorization is required");
        if (!notificationsEnabled() && !LocalAuth.isAdmin(authorization)) return ApiResponse.fail("notifications feature is disabled");
        if (requestedUserId != null && !LocalAuth.canAccessUser(authorization, requestedUserId)) return ApiResponse.fail("access to this user is denied");
        if (cursor != null && cursor < 0) return ApiResponse.fail("invalid notification cursor");
        Long userId = LocalAuth.isAdmin(authorization) && requestedUserId != null ? requestedUserId : authenticatedUserId;
        int pageSize = Math.max(1, Math.min(NOTIFICATION_PAGE_MAX, limit));
        List<NotificationEntity> notifications = messageStore.pageNotifications(userId, cursor, pageSize + 1, unreadOnly);
        boolean hasMore = notifications.size() > pageSize;
        if (hasMore) notifications = notifications.subList(0, pageSize);
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("items", notifications.stream().map(this::toNotificationView).toList());
        page.put("nextCursor", hasMore ? notifications.get(notifications.size() - 1).getId() : null);
        page.put("hasMore", hasMore);
        page.put("unread", messageStore.countUnreadNotifications(userId));
        return ApiResponse.ok(page);
    }

    @GetMapping("/notification/unread-count")
    public ApiResponse<Map<String, Object>> unreadNotificationCount(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "userId", required = false) Long requestedUserId
    ) {
        Long authenticatedUserId = LocalAuth.userId(authorization);
        if (authenticatedUserId == null) return ApiResponse.fail("valid user authorization is required");
        if (requestedUserId != null && !LocalAuth.canAccessUser(authorization, requestedUserId)) return ApiResponse.fail("access to this user is denied");
        if (!notificationsEnabled() && !LocalAuth.isAdmin(authorization)) return ApiResponse.ok(Map.of("unread", 0L));
        Long userId = LocalAuth.isAdmin(authorization) && requestedUserId != null ? requestedUserId : authenticatedUserId;
        return ApiResponse.ok(Map.of("unread", messageStore.countUnreadNotifications(userId)));
    }

    @PostMapping("/notification/read")
    public ApiResponse<Map<String, Object>> markNotificationRead(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        if (!notificationsEnabled()) return ApiResponse.fail("notifications feature is disabled");
        Long notificationId = number(request.get("notificationId"), 0L);
        return messageStore.markNotificationRead(userId, notificationId)
                .map(item -> ApiResponse.ok(Map.of(
                        "notification", toNotificationView(item),
                        "updated", true,
                        "unread", messageStore.countUnreadNotifications(userId))))
                .orElseGet(() -> ApiResponse.fail("notification not found"));
    }

    @PostMapping("/notification/read-all")
    public ApiResponse<Map<String, Object>> markAllNotificationsRead(
            @RequestHeader(name = "Authorization", required = false) String authorization
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        if (!notificationsEnabled()) return ApiResponse.fail("notifications feature is disabled");
        int updated = messageStore.markAllNotificationsRead(userId);
        return ApiResponse.ok(Map.of("updated", updated, "unread", messageStore.countUnreadNotifications(userId)));
    }

    @GetMapping("/feedback/faqs")
    public ApiResponse<List<Map<String, Object>>> faqs() {
        return ApiResponse.ok(messageStore.listFaqs().stream().map(this::toFaqView).toList());
    }

    @PostMapping("/feedback/admin/faq")
    public ApiResponse<Map<String, Object>> saveFaq(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        if (!LocalAuth.isAdmin(authorization)) return ApiResponse.fail("admin authorization is required");
        FaqEntity faq = new FaqEntity();
        if (request.get("id") != null) faq.setId(number(request.get("id"), null));
        faq.setQuestion(String.valueOf(request.getOrDefault("question", "")));
        faq.setAnswer(String.valueOf(request.getOrDefault("answer", "")));
        faq.setSortNo(number(request.get("sortNo"), 0L).intValue());
        faq.setEnabled(number(request.get("enabled"), 1L).intValue());
        return ApiResponse.ok(toFaqView(messageStore.saveFaq(faq)));
    }

    @DeleteMapping("/feedback/admin/faq")
    public ApiResponse<Map<String, Object>> deleteFaq(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        if (!LocalAuth.isAdmin(authorization)) return ApiResponse.fail("admin authorization is required");
        Long faqId = number(request.get("faqId"), 0L);
        return ApiResponse.ok(Map.of("faqId", faqId, "removed", messageStore.deleteFaq(faqId)));
    }

    @PostMapping("/feedback/ticket")
    public ApiResponse<Map<String, Object>> createTicket(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        if (!feedbackEnabled()) return ApiResponse.fail("平台当前未开放反馈提交");
        FeedbackTicketEntity ticket = new FeedbackTicketEntity();
        ticket.setUserId(userId);
        ticket.setType(String.valueOf(request.getOrDefault("type", "BUG")));
        ticket.setContent(String.valueOf(request.getOrDefault("content", "")));
        ticket.setStatus("PENDING");
        ticket.setOfficialReply("");
        FeedbackTicketEntity saved = messageStore.createTicket(ticket);
        eventBus.publish("FEEDBACK_TICKET_CREATED", String.valueOf(saved.getId()), Map.of(
                "userId", saved.getUserId(),
                "type", saved.getType(),
                "status", saved.getStatus()
        ));
        return ApiResponse.ok(toTicketView(saved));
    }

    @GetMapping("/feedback/tickets")
    public ApiResponse<List<Map<String, Object>>> tickets(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "userId", required = false) Long requestedUserId
    ) {
        Long authenticatedUserId = LocalAuth.userId(authorization);
        if (authenticatedUserId == null) return ApiResponse.fail("valid user authorization is required");
        if (requestedUserId != null && !LocalAuth.canAccessUser(authorization, requestedUserId)) return ApiResponse.fail("access to this user is denied");
        Long userId = LocalAuth.isAdmin(authorization) ? requestedUserId : authenticatedUserId;
        return ApiResponse.ok(messageStore.listTickets(userId).stream().map(this::toTicketView).toList());
    }

    @GetMapping("/message/admin/overview")
    public ApiResponse<Map<String, Object>> messageAdminOverview(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "userId", required = false) Long userId
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) {
            return denied;
        }
        return ApiResponse.ok(Map.of(
                "module", "消息互动管理",
                "notifications", messageStore.countNotifications(userId),
                "sessionOneMessages", messageStore.countMessages(List.of(1L)).getOrDefault(1L, 0L),
                "storedEvents", eventBus.list(200).size(),
                "capabilities", List.of("私信监管", "互动提醒", "聊天归档", "消息清理")
        ));
    }

    @GetMapping("/message/admin/sessions")
    public ApiResponse<List<Map<String, Object>>> adminSessions(
            @RequestHeader(name = "Authorization", required = false) String authorization
    ) {
        if (!LocalAuth.isAdmin(authorization)) return ApiResponse.fail("admin authorization is required");
        List<ChatSessionEntity> sessions = messageStore.listSessions(null);
        Map<Long, Long> counts = messageStore.countMessages(sessions.stream().map(ChatSessionEntity::getId).toList());
        List<Map<String, Object>> views = toSessionViews(sessions, ChatSessionEntity::getUserAId);
        views.forEach(view -> view.put("messageCount", counts.getOrDefault((Long) view.get("id"), 0L)));
        return ApiResponse.ok(views);
    }

    @GetMapping("/message/admin/list")
    public ApiResponse<List<Map<String, Object>>> adminMessages(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam Long sessionId
    ) {
        if (!LocalAuth.isAdmin(authorization)) return ApiResponse.fail("admin authorization is required");
        if (messageStore.findSession(sessionId).isEmpty()) return ApiResponse.fail("chat session not found");
        return ApiResponse.ok(messageStore.listMessages(sessionId).stream().map(this::toMessageView).toList());
    }

    @PostMapping("/message/admin/session/status")
    public ApiResponse<Map<String, Object>> updateSessionStatus(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        if (!LocalAuth.isAdmin(authorization)) return ApiResponse.fail("admin authorization is required");
        Long sessionId = number(request.get("sessionId"), 0L);
        String status = String.valueOf(request.getOrDefault("status", "RESTRICTED"));
        if (!List.of("ACTIVE", "RESTRICTED", "ARCHIVED").contains(status)) {
            return ApiResponse.fail("invalid conversation status");
        }
        return messageStore.updateSessionStatus(sessionId, status)
                .map(session -> ApiResponse.ok(toSessionView(session, session.getUserAId())))
                .orElseGet(() -> ApiResponse.fail("chat session not found"));
    }

    @GetMapping("/feedback/admin/overview")
    public ApiResponse<Map<String, Object>> feedbackAdminOverview(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "userId", required = false) Long userId
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) {
            return denied;
        }
        MessageStore.TicketTotals totals = messageStore.ticketTotals(userId);
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("module", "工单反馈管理");
        overview.put("tickets", totals.total());
        overview.put("faqs", messageStore.countFaqs());
        overview.put("pendingTickets", totals.pending());
        overview.put("processingTickets", totals.processing());
        overview.put("resolvedTickets", totals.resolved());
        overview.put("bugTickets", totals.bug());
        overview.put("suggestionTickets", totals.suggestion());
        overview.put("supportTickets", totals.support());
        overview.put("supportWorkload", messageStore.ticketWorkload(userId));
        overview.put("capabilities", List.of("客服分配", "工单处理", "进度跟踪", "接待统计", "FAQ维护"));
        return ApiResponse.ok(overview);
    }

    @PostMapping("/feedback/admin/assign")
    public ApiResponse<Map<String, Object>> assignTicket(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) return denied;
        Long ticketId = number(request.get("ticketId"), 0L);
        Long rawAssignee = number(request.get("assigneeUserId"), 0L);
        Long assigneeUserId = rawAssignee != null && rawAssignee > 0 ? rawAssignee : null;
        return messageStore.assignTicket(ticketId, assigneeUserId)
                .map(ticket -> {
                    eventBus.publish("FEEDBACK_TICKET_ASSIGNED", String.valueOf(ticket.getId()), Map.of(
                            "assigned", assigneeUserId != null,
                            "assigneeUserId", assigneeUserId == null ? 0L : assigneeUserId
                    ));
                    return ApiResponse.ok(toTicketView(ticket));
                })
                .orElseGet(() -> ApiResponse.fail("ticket not found"));
    }

    @PostMapping("/feedback/admin/reply")
    public ApiResponse<Map<String, Object>> replyTicket(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) {
            return denied;
        }
        Long ticketId = number(request.get("ticketId"), 0L);
        String status = String.valueOf(request.getOrDefault("status", "PROCESSING"));
        String reply = String.valueOf(request.getOrDefault("reply", ""));
        if (!List.of("PENDING", "PROCESSING", "RESOLVED").contains(status)) return ApiResponse.fail("invalid ticket status");
        if (reply.length() > 5000) return ApiResponse.fail("ticket reply is too long");
        return messageStore.replyTicket(ticketId, status, reply)
                .map(ticket -> {
                    NotificationEntity notification = new NotificationEntity();
                    notification.setUserId(ticket.getUserId());
                    notification.setType("FEEDBACK");
                    notification.setTitle("工单有新的处理结果");
                    notification.setContent(ticket.getOfficialReply() == null ? "你的工单状态已更新。" : ticket.getOfficialReply());
                    messageStore.saveNotification(notification);
                    eventBus.publish("FEEDBACK_TICKET_REPLIED", String.valueOf(ticket.getId()), Map.of(
                            "status", ticket.getStatus(),
                            "hasReply", ticket.getOfficialReply() != null && !ticket.getOfficialReply().isBlank()
                    ));
                    return ApiResponse.ok(Map.of("ticket", toTicketView(ticket), "updated", true));
                })
                .orElseGet(() -> ApiResponse.fail("ticket not found"));
    }

    private Map<String, Object> toEventView(LocalEventBusService.EventRecord event) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", event.getId());
        view.put("type", event.getType());
        view.put("aggregateId", event.getAggregateId());
        view.put("payload", event.getPayload());
        view.put("status", event.getStatus());
        view.put("createdAt", event.getCreatedAt());
        return view;
    }

    private String storeMode(Object store) {
        return store.getClass().getSimpleName().startsWith("MySql") ? "mysql" : "local";
    }

    private boolean interactionAllowed(Long userId, Long targetUserId) {
        return userRelationClient == null || userRelationClient.interactionAllowed(userId, targetUserId);
    }

    private boolean messagingAllowed(Long userId, Long targetUserId) {
        return privateMessagesEnabled() && interactionAllowed(userId, targetUserId)
                && (userRelationClient == null || userRelationClient.messagingAllowed(userId, targetUserId));
    }

    private Map<String, Object> toMessageView(ChatMessageEntity message) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", message.getId());
        view.put("sessionId", message.getSessionId());
        view.put("senderId", message.getSenderId());
        view.put("content", message.getContent());
        view.put("status", message.getStatus());
        view.put("createdAt", message.getCreatedAt());
        return view;
    }

    /** Session views for a list, loading every session's latest message in one batch. */
    private List<Map<String, Object>> toSessionViews(List<ChatSessionEntity> sessions, java.util.function.Function<ChatSessionEntity, Long> viewer) {
        Map<Long, ChatMessageEntity> latest = messageStore.latestMessages(sessions.stream().map(ChatSessionEntity::getId).toList());
        return sessions.stream().map(session -> toSessionView(session, viewer.apply(session), latest.get(session.getId()))).toList();
    }

    private Map<String, Object> toSessionView(ChatSessionEntity session, Long userId) {
        return toSessionView(session, userId, messageStore.latestMessage(session.getId()).orElse(null));
    }

    private Map<String, Object> toSessionView(ChatSessionEntity session, Long userId, ChatMessageEntity latest) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", session.getId());
        view.put("userAId", session.getUserAId());
        view.put("userBId", session.getUserBId());
        view.put("otherUserId", session.getUserAId().equals(userId) ? session.getUserBId() : session.getUserAId());
        view.put("status", session.getStatus());
        view.put("updatedAt", session.getUpdatedAt());
        view.put("lastMessage", latest == null ? "" : latest.getContent());
        return view;
    }

    private boolean isParticipant(ChatSessionEntity session, Long userId) {
        return userId != null && (userId.equals(session.getUserAId()) || userId.equals(session.getUserBId()));
    }

    private Map<String, Object> toNotificationView(NotificationEntity notification) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", notification.getId());
        view.put("userId", notification.getUserId());
        view.put("type", notification.getType());
        view.put("title", localizeNotificationTitle(notification));
        view.put("content", localizeNotificationContent(notification));
        view.put("read", notification.getIsRead() != null && notification.getIsRead() == 1);
        view.put("createdAt", notification.getCreatedAt());
        return view;
    }

    private String localizeNotificationTitle(NotificationEntity notification) {
        String title = notification.getTitle() == null ? "" : notification.getTitle();
        return switch (title) {
            case "Your post received a new comment" -> "你的帖子收到新评论";
            case "Local environment is ready" -> "本地环境已就绪";
            default -> title;
        };
    }

    private String localizeNotificationContent(NotificationEntity notification) {
        String content = notification.getContent() == null ? "" : notification.getContent();
        if ("COMMENT".equals(notification.getType()) && content.matches("^Post #\\d+:.*")) {
            int separator = content.indexOf(':');
            return "帖子 #" + content.substring("Post #".length(), separator) + "：" + content.substring(separator + 1).stripLeading();
        }
        if ("SYSTEM".equals(notification.getType())
                && "Message service is running in local memory mode.".equals(content)) {
            return "消息服务正在以本地存储模式运行。";
        }
        return content;
    }

    private Map<String, Object> toFaqView(FaqEntity faq) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", faq.getId());
        view.put("question", faq.getQuestion());
        view.put("answer", faq.getAnswer());
        view.put("sortNo", faq.getSortNo());
        return view;
    }

    private Map<String, Object> toTicketView(FeedbackTicketEntity ticket) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", ticket.getId());
        view.put("userId", ticket.getUserId());
        view.put("type", ticket.getType());
        view.put("content", ticket.getContent());
        view.put("status", ticket.getStatus());
        view.put("reply", ticket.getOfficialReply());
        view.put("assigneeUserId", ticket.getAssigneeUserId());
        view.put("assignedAt", ticket.getAssignedAt());
        view.put("closedAt", ticket.getClosedAt());
        view.put("createdAt", ticket.getCreatedAt());
        view.put("updatedAt", ticket.getUpdatedAt());
        return view;
    }

    private boolean notificationsEnabled() {
        return platformConfig == null || platformConfig.enabled("notifications_enabled", true);
    }

    private boolean privateMessagesEnabled() {
        return platformConfig == null || platformConfig.enabled("private_messages_enabled", true);
    }

    private boolean feedbackEnabled() {
        return platformConfig == null || platformConfig.enabled("feedback_enabled", true);
    }

    private int maxMessageLength() {
        return platformConfig == null ? 2000 : Math.max(100, Math.min(5000, platformConfig.integer("max_message_length", 2000)));
    }

    private Long number(Object value, Long fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(value.toString());
    }

    private static final int ANALYTICS_MAX_DAYS = 365;
    private static final int ANALYTICS_TREND_DAYS = 14;

    /**
     * Aggregated ticket numbers for the admin analytics page, replacing the full ticket download
     * the page used to count in the browser.
     */
    @GetMapping("/feedback/admin/analytics")
    public ApiResponse<Map<String, Object>> feedbackAdminAnalytics(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "days", defaultValue = "30") int days
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) {
            return denied;
        }
        int window = Math.min(Math.max(days, 1), ANALYTICS_MAX_DAYS);
        int trendDays = Math.min(window, ANALYTICS_TREND_DAYS);
        MessageStore.TicketAnalytics totals = messageStore.ticketAnalytics(LocalDateTime.now().minusDays(window));
        Map<String, Long> perDay = new LinkedHashMap<>();
        messageStore.dailyTicketCounts(LocalDate.now().minusDays(trendDays - 1L).atStartOfDay())
                .forEach(entry -> perDay.merge(entry.date(), entry.count(), Long::sum));
        Map<String, Object> analytics = new LinkedHashMap<>();
        analytics.put("days", window);
        analytics.put("trendDays", trendDays);
        analytics.put("total", totals.total());
        analytics.put("bug", totals.bug());
        analytics.put("suggestion", totals.suggestion());
        analytics.put("support", totals.support());
        analytics.put("resolved", totals.resolved());
        analytics.put("trend", DailySeries.fill(LocalDate.now(), trendDays, perDay));
        return ApiResponse.ok(analytics);
    }


    private static final int TICKET_PAGE_MAX = 100;

    /**
     * One page of the ticket table. Admins see every reporter unless they ask for one; members
     * only ever see their own tickets.
     */
    @GetMapping("/feedback/tickets/page")
    public ApiResponse<Map<String, Object>> ticketsPage(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "userId", required = false) Long requestedUserId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "cursor", required = false) Long cursor,
            @RequestParam(name = "limit", defaultValue = "20") int limit
    ) {
        Long authenticatedUserId = LocalAuth.userId(authorization);
        if (authenticatedUserId == null) return ApiResponse.fail("valid user authorization is required");
        if (requestedUserId != null && !LocalAuth.canAccessUser(authorization, requestedUserId)) {
            return ApiResponse.fail("access to this user is denied");
        }
        if (cursor != null && cursor < 0) return ApiResponse.fail("cursor must not be negative");
        Long userId = LocalAuth.isAdmin(authorization) ? requestedUserId : authenticatedUserId;
        int size = Math.min(Math.max(limit, 1), TICKET_PAGE_MAX);
        MessageStore.TicketPageQuery query = new MessageStore.TicketPageQuery(userId, status, keyword, cursor, size + 1);
        List<FeedbackTicketEntity> found = messageStore.pageTickets(query);
        boolean hasMore = found.size() > size;
        List<FeedbackTicketEntity> page = hasMore ? found.subList(0, size) : found;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", page.stream().map(this::toTicketView).toList());
        result.put("nextCursor", page.isEmpty() ? null : page.get(page.size() - 1).getId());
        result.put("hasMore", hasMore);
        // Counting can touch most of the table, so only a first page that is full pays for it: a short first
        // page already is the whole result, and later pages send null.
        Long total = null;
        if (cursor == null) {
            total = hasMore ? messageStore.countTickets(new MessageStore.TicketPageQuery(userId, status, keyword, null, size))
                    : page.size();
        }
        result.put("total", total);
        return ApiResponse.ok(result);
    }


    private static final int GOVERNANCE_PAGE_MAX = 100;
    private static final int ADMIN_MESSAGE_PAGE_MAX = 100;

    /**
     * One page of the governance conversation table, most recently active first. The cursor is an opaque
     * {@code time|id} token taken from {@code nextCursor}.
     */
    @GetMapping("/message/admin/sessions/page")
    public ApiResponse<Map<String, Object>> adminSessionsPage(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "20") int limit
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) return denied;
        TimeCursor before;
        try {
            before = TimeCursor.parse(cursor);
        } catch (IllegalArgumentException error) {
            return ApiResponse.fail("invalid conversation cursor");
        }
        int size = Math.min(Math.max(limit, 1), GOVERNANCE_PAGE_MAX);
        List<ChatSessionEntity> found = messageStore.pageAdminSessions(new MessageStore.AdminSessionQuery(keyword, before, size + 1));
        boolean hasMore = found.size() > size;
        List<ChatSessionEntity> page = hasMore ? found.subList(0, size) : found;
        Long total = null;
        if (before == null) {
            total = hasMore ? messageStore.countAdminSessions(new MessageStore.AdminSessionQuery(keyword, null, size)) : page.size();
        }
        Map<Long, Long> counts = messageStore.countMessages(page.stream().map(ChatSessionEntity::getId).toList());
        List<Map<String, Object>> views = toSessionViews(page, ChatSessionEntity::getUserAId);
        views.forEach(view -> view.put("messageCount", counts.getOrDefault((Long) view.get("id"), 0L)));
        ChatSessionEntity last = page.isEmpty() ? null : page.get(page.size() - 1);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", views);
        result.put("nextCursor", last == null ? null : TimeCursor.format(last.getUpdatedAt(), last.getId()));
        result.put("hasMore", hasMore);
        result.put("total", total);
        return ApiResponse.ok(result);
    }

    /**
     * The newest messages of one conversation for the moderation preview, oldest first within the page.
     * {@code nextCursor} is the oldest message returned; pass it back to read further into the past.
     */
    @GetMapping("/message/admin/messages/page")
    public ApiResponse<Map<String, Object>> adminMessagesPage(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam Long sessionId,
            @RequestParam(name = "cursor", required = false) Long cursor,
            @RequestParam(name = "limit", defaultValue = "30") int limit
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) return denied;
        if (cursor != null && cursor < 0) return ApiResponse.fail("cursor must not be negative");
        if (messageStore.findSession(sessionId).isEmpty()) return ApiResponse.fail("chat session not found");
        int size = Math.min(Math.max(limit, 1), ADMIN_MESSAGE_PAGE_MAX);
        // One extra row tells whether older messages exist; the page is chronological, so it sits at the front.
        List<ChatMessageEntity> found = messageStore.pageMessages(sessionId, cursor, null, size + 1);
        boolean hasMore = found.size() > size;
        List<ChatMessageEntity> page = hasMore ? found.subList(1, found.size()) : found;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", page.stream().map(this::toMessageView).toList());
        result.put("nextCursor", hasMore && !page.isEmpty() ? page.get(0).getId() : null);
        result.put("hasMore", hasMore);
        result.put("total", null);
        return ApiResponse.ok(result);
    }

}
