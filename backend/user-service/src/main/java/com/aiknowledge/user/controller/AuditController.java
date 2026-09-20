package com.aiknowledge.user.controller;

import com.aiknowledge.common.AdminAudit;
import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.AppTime;
import com.aiknowledge.common.AuditEntry;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.user.security.InternalAuth;
import com.aiknowledge.user.store.AuditLogStore;
import com.aiknowledge.user.store.AuditLogStore.StoredEntry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The administrator action log: other services add entries here, administrators read them. */
@RestController
@RequestMapping("/user")
public class AuditController {
    static final int PAGE_MAX = 100;
    private final AuditLogStore store;

    public AuditController(AuditLogStore store) {
        this.store = store;
    }

    @PostMapping("/internal/audit")
    public ApiResponse<Map<String, Object>> add(
            @RequestHeader(name = "X-Internal-Token", required = false) String token,
            @RequestBody AuditEntry entry
    ) {
        if (!InternalAuth.accepts(token)) return ApiResponse.fail("internal authorization is required");
        String problem = problem(entry);
        if (problem != null) return ApiResponse.fail(problem);
        StoredEntry stored = store.save(entry);
        return ApiResponse.ok(Map.of("id", stored.id()));
    }

    @GetMapping("/admin/audit")
    public ApiResponse<Map<String, Object>> page(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "actor", required = false) String actor,
            @RequestParam(name = "subjectUserId", required = false) Long subjectUserId,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "cursor", required = false) Long cursor,
            @RequestParam(name = "limit", defaultValue = "30") int limit
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) return denied;
        if (category != null && !category.isBlank() && !AdminAudit.CATEGORIES.contains(category)) {
            return ApiResponse.fail("invalid audit category");
        }
        if (cursor != null && cursor <= 0) return ApiResponse.fail("cursor must be positive");
        LocalDate fromDay;
        LocalDate toDay;
        try {
            fromDay = day(from);
            toDay = day(to);
        } catch (DateTimeParseException invalid) {
            return ApiResponse.fail("dates must look like 2026-09-17");
        }
        if (fromDay != null && toDay != null && toDay.isBefore(fromDay)) return ApiResponse.fail("the end date is before the start date");
        int size = Math.min(Math.max(limit, 1), PAGE_MAX);
        // Days are the administrator's calendar days (APP_TIME_ZONE); the end day is included.
        LocalDateTime start = fromDay == null ? null : AppTime.startOfDay(fromDay);
        LocalDateTime end = toDay == null ? null : AppTime.startOfDay(toDay.plusDays(1));
        List<StoredEntry> found = store.page(new AuditLogStore.Query(category, action, clip(actor), subjectUserId,
                clip(keyword), start, end, cursor, size + 1));
        boolean hasMore = found.size() > size;
        List<StoredEntry> page = hasMore ? found.subList(0, size) : found;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", page.stream().map(AuditController::view).toList());
        result.put("hasMore", hasMore);
        result.put("nextCursor", hasMore ? page.get(page.size() - 1).id() : null);
        return ApiResponse.ok(result);
    }

    static Map<String, Object> view(StoredEntry stored) {
        AuditEntry entry = stored.entry();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", stored.id());
        view.put("createdAt", stored.createdAt());
        view.put("actorId", entry.actorId());
        view.put("actorName", entry.actorName());
        view.put("action", entry.action());
        view.put("category", entry.category());
        view.put("targetType", entry.targetType());
        view.put("targetId", entry.targetId());
        view.put("targetLabel", entry.targetLabel());
        view.put("subjectUserId", entry.subjectUserId());
        view.put("summary", entry.summary());
        view.put("detail", entry.detail());
        view.put("source", entry.source());
        view.put("clientIp", entry.clientIp());
        return view;
    }

    private static String problem(AuditEntry entry) {
        if (entry == null) return "audit entry is required";
        if (entry.occurredAt() == null) return "audit time is required";
        if (entry.actorId() == null || entry.actorId() <= 0) return "audit actor is required";
        if (entry.action() == null || !entry.action().matches("[A-Z][A-Z_]{1,63}")) return "invalid audit action";
        if (!AdminAudit.CATEGORIES.contains(entry.category())) return "invalid audit category";
        return null;
    }

    private static LocalDate day(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value.trim());
    }

    private static String clip(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
    }
}
