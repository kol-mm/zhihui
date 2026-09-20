package com.aiknowledge.common;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Map;

/** An audit event with who, when and where, as it travels to and is stored by the user service. */
public record AuditEntry(
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt,
        Long actorId,
        String actorName,
        String action,
        String category,
        String targetType,
        String targetId,
        String targetLabel,
        Long subjectUserId,
        String summary,
        Map<String, Object> detail,
        String source,
        String clientIp
) {
    /** Set by the gateway from the connection it trusts; anything a browser sends under this name is replaced. */
    public static final String CLIENT_IP_HEADER = "X-Client-Ip";

    public AuditEntry {
        action = clip(action, 64);
        category = clip(category, 32);
        targetType = clip(targetType, 32);
        targetId = clip(targetId, 64);
        targetLabel = clip(targetLabel, 200);
        actorName = clip(actorName, 64);
        summary = clip(summary, 500);
        source = clip(source, 32);
        clientIp = clip(clientIp, 64);
        detail = detail == null ? Map.of() : detail;
    }

    /** The entry for an administrator's event in the current request, or null when the caller is not an administrator. */
    public static AuditEntry forRequest(String authorization, AdminAudit.Event event, String source) {
        if (event == null || !LocalAuth.isAdmin(authorization)) return null;
        return new AuditEntry(Instant.now(), LocalAuth.userId(authorization), LocalAuth.username(authorization),
                event.action(), event.category(), event.targetType(),
                event.targetId() == null ? null : String.valueOf(event.targetId()), event.targetLabel(),
                event.subjectUserId(), event.summary(), event.detail(), source, currentClientIp());
    }

    static String currentClientIp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return null;
        HttpServletRequest request = attributes.getRequest();
        String forwarded = request.getHeader(CLIENT_IP_HEADER);
        return forwarded != null && !forwarded.isBlank() ? forwarded.trim() : request.getRemoteAddr();
    }

    private static String clip(String value, int max) {
        if (value == null) return null;
        String trimmed = value.strip();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max - 1) + "…";
    }
}
