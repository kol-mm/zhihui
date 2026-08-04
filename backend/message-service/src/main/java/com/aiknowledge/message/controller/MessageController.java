package com.aiknowledge.message.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.message.entity.ChatMessageEntity;
import com.aiknowledge.message.entity.FaqEntity;
import com.aiknowledge.message.entity.FeedbackTicketEntity;
import com.aiknowledge.message.entity.NotificationEntity;
import com.aiknowledge.message.event.LocalEventBusService;
import com.aiknowledge.message.store.MessageStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class MessageController {
    private final MessageStore messageStore;
    private final LocalEventBusService eventBus;

    public MessageController(MessageStore messageStore, LocalEventBusService eventBus) {
        this.messageStore = messageStore;
        this.eventBus = eventBus;
    }

    @GetMapping("/message/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of("service", "message-service", "time", Instant.now().toString()));
    }

    @PostMapping("/message/send")
    public ApiResponse<Map<String, Object>> send(@RequestBody Map<String, Object> request) {
        ChatMessageEntity message = new ChatMessageEntity();
        message.setSessionId(number(request.get("sessionId"), 1L));
        message.setSenderId(number(request.get("senderId"), 1L));
        message.setContent(String.valueOf(request.getOrDefault("content", "")));
        message.setStatus("NORMAL");
        ChatMessageEntity saved = messageStore.sendMessage(message);
        eventBus.publish("MESSAGE_SENT", String.valueOf(saved.getId()), Map.of(
                "sessionId", saved.getSessionId(),
                "senderId", saved.getSenderId(),
                "status", saved.getStatus()
        ));
        return ApiResponse.ok(toMessageView(saved));
    }

    @GetMapping("/message/list")
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(name = "sessionId", defaultValue = "1") Long sessionId) {
        return ApiResponse.ok(messageStore.listMessages(sessionId).stream().map(this::toMessageView).toList());
    }

    @PostMapping("/message/clear")
    public ApiResponse<Map<String, Object>> clear(@RequestBody Map<String, Object> request) {
        Long sessionId = request.containsKey("sessionId") ? number(request.get("sessionId"), null) : null;
        int removed = messageStore.clearMessages(sessionId);
        eventBus.publish("MESSAGE_CLEARED", sessionId == null ? "ALL" : String.valueOf(sessionId), Map.of("removed", removed));
        return ApiResponse.ok(Map.of("sessionId", sessionId == null ? "ALL" : sessionId, "removed", removed));
    }

    @GetMapping("/event/status")
    public ApiResponse<Map<String, Object>> eventStatus() {
        return ApiResponse.ok(eventBus.status());
    }

    @GetMapping("/event/list")
    public ApiResponse<List<Map<String, Object>>> events(@RequestParam(name = "limit", defaultValue = "50") Integer limit) {
        return ApiResponse.ok(eventBus.list(limit).stream().map(this::toEventView).toList());
    }

    @GetMapping("/notification/list")
    public ApiResponse<List<Map<String, Object>>> notifications(@RequestParam(name = "userId", required = false) Long userId) {
        return ApiResponse.ok(messageStore.listNotifications(userId).stream().map(this::toNotificationView).toList());
    }

    @GetMapping("/feedback/faqs")
    public ApiResponse<List<Map<String, Object>>> faqs() {
        return ApiResponse.ok(messageStore.listFaqs().stream().map(this::toFaqView).toList());
    }

    @PostMapping("/feedback/ticket")
    public ApiResponse<Map<String, Object>> createTicket(@RequestBody Map<String, Object> request) {
        FeedbackTicketEntity ticket = new FeedbackTicketEntity();
        ticket.setUserId(number(request.get("userId"), 1L));
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
    public ApiResponse<List<Map<String, Object>>> tickets(@RequestParam(name = "userId", required = false) Long userId) {
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
                "notifications", messageStore.listNotifications(userId).size(),
                "sessionOneMessages", messageStore.listMessages(1L).size(),
                "capabilities", List.of("私信监管", "互动提醒", "聊天归档", "消息清理")
        ));
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
        return ApiResponse.ok(Map.of(
                "module", "工单反馈管理",
                "tickets", messageStore.listTickets(userId).size(),
                "faqs", messageStore.listFaqs().size(),
                "pendingTickets", messageStore.listTickets(userId).stream().filter(ticket -> "PENDING".equals(ticket.getStatus())).count(),
                "capabilities", List.of("客服配置", "工单处理", "进度跟踪", "FAQ维护")
        ));
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
        return messageStore.replyTicket(ticketId, status, reply)
                .map(ticket -> {
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

    private Map<String, Object> toNotificationView(NotificationEntity notification) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", notification.getId());
        view.put("userId", notification.getUserId());
        view.put("type", notification.getType());
        view.put("title", notification.getTitle());
        view.put("content", notification.getContent());
        view.put("read", notification.getIsRead() != null && notification.getIsRead() == 1);
        view.put("createdAt", notification.getCreatedAt());
        return view;
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
        view.put("createdAt", ticket.getCreatedAt());
        view.put("updatedAt", ticket.getUpdatedAt());
        return view;
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
}
