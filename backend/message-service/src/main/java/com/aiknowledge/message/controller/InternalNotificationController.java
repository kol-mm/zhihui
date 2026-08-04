package com.aiknowledge.message.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.message.entity.NotificationEntity;
import com.aiknowledge.message.store.MessageStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class InternalNotificationController {
    private final MessageStore messageStore;
    private final String internalToken;

    public InternalNotificationController(
            MessageStore messageStore,
            @Value("${internal.notification-token:ai-knowledge-local-internal}") String internalToken
    ) {
        this.messageStore = messageStore;
        this.internalToken = internalToken;
    }

    @PostMapping("/internal/notification")
    public ApiResponse<Map<String, Object>> create(
            @RequestHeader(name = "X-Internal-Token", required = false) String token,
            @RequestBody Map<String, Object> request
    ) {
        if (!internalToken.equals(token)) return ApiResponse.fail("internal authorization is required");
        Long userId = number(request.get("userId"));
        String title = String.valueOf(request.getOrDefault("title", "New notification")).trim();
        String content = String.valueOf(request.getOrDefault("content", "")).trim();
        if (userId == null || userId <= 0 || title.isBlank()) return ApiResponse.fail("valid notification data is required");
        NotificationEntity notification = new NotificationEntity();
        notification.setUserId(userId);
        notification.setType(String.valueOf(request.getOrDefault("type", "SYSTEM")));
        notification.setTitle(title);
        notification.setContent(content);
        NotificationEntity saved = messageStore.saveNotification(notification);
        return ApiResponse.ok(Map.of("id", saved.getId(), "userId", saved.getUserId(), "created", true));
    }

    private Long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? null : Long.valueOf(value.toString()); }
        catch (NumberFormatException ignored) { return null; }
    }
}
