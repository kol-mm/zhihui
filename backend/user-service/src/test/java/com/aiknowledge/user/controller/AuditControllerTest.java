package com.aiknowledge.user.controller;

import com.aiknowledge.common.AdminAudit;
import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.AppTime;
import com.aiknowledge.common.AuditEntry;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.user.entity.UserEntity;
import com.aiknowledge.user.security.LocalAdminAudit;
import com.aiknowledge.user.security.TokenRevocations;
import com.aiknowledge.user.store.AuditLogStore;
import com.aiknowledge.user.store.InMemoryAuditLogStore;
import com.aiknowledge.user.store.InMemoryPasswordResetStore;
import com.aiknowledge.user.store.InMemoryUserStore;
import com.aiknowledge.user.store.PasswordResetStore;
import com.aiknowledge.user.store.UserStore;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditControllerTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/audit-" + System.nanoTime());
    }

    private static final String INTERNAL = "ai-knowledge-local-internal";
    private static final String ADMIN = "Bearer " + LocalAuth.issueToken("admin", 2L, "ADMIN");
    private static final String MEMBER = "Bearer " + LocalAuth.issueToken("demo", 1L, "USER");

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final AuditLogStore store = new InMemoryAuditLogStore();
    private final AuditController controller = new AuditController(store);

    @Test
    void servicesAddEntriesWithTheInternalToken() {
        AuditEntry entry = entry("POST_DELETE", AdminAudit.COMMUNITY, "帖子一", 9L, Instant.parse("2026-09-17T08:00:00Z"));
        assertEquals("internal authorization is required", rawMessage(controller.add(null, entry)));
        assertEquals("internal authorization is required", rawMessage(controller.add("wrong", entry)));
        assertEquals(0, controller.add(INTERNAL, entry).code());

        AuditEntry noActor = new AuditEntry(Instant.now(), null, "x", "POST_DELETE", AdminAudit.COMMUNITY,
                null, null, null, null, null, null, "s", null);
        assertEquals(500, controller.add(INTERNAL, noActor).code());
        AuditEntry badAction = new AuditEntry(Instant.now(), 2L, "x", "drop table", AdminAudit.COMMUNITY,
                null, null, null, null, null, null, "s", null);
        assertEquals(500, controller.add(INTERNAL, badAction).code());
        AuditEntry badCategory = new AuditEntry(Instant.now(), 2L, "x", "POST_DELETE", "OTHER",
                null, null, null, null, null, null, "s", null);
        assertEquals(500, controller.add(INTERNAL, badCategory).code());

        Map<?, ?> item = (Map<?, ?>) items(page(null, null, null, null, null, null, null)).get(0);
        assertEquals("POST_DELETE", item.get("action"));
        assertEquals("帖子一", item.get("targetLabel"));
        assertEquals(LocalDateTime.ofInstant(Instant.parse("2026-09-17T08:00:00Z"), AppTime.storageZone()), item.get("createdAt"));
        assertEquals(Map.of("reason", "spam"), item.get("detail"));
    }

    @Test
    void onlyAdministratorsReadTheLog() {
        assertEquals(500, controller.page(MEMBER, null, null, null, null, null, null, null, null, 30).code());
        assertEquals(500, controller.page(null, null, null, null, null, null, null, null, null, 30).code());
        assertEquals(0, controller.page(ADMIN, null, null, null, null, null, null, null, null, 30).code());
    }

    @Test
    void theLogPagesNewestFirstAndFilters() {
        for (int i = 1; i <= 5; i++) {
            store.save(entry("USER_STATUS", AdminAudit.ACCOUNTS, "成员" + i, (long) i, Instant.parse("2026-09-1" + i + "T02:00:00Z")));
        }
        store.save(new AuditEntry(Instant.parse("2026-09-16T02:00:00Z"), 5L, "ops-lead", "FAQ_DELETE", AdminAudit.SUPPORT,
                "FAQ", "12", "如何重置密码", null, "删除常见问题", Map.of(), "message-service", null));

        Map<String, Object> first = page(null, null, null, null, null, null, null, 2);
        assertEquals(List.of("如何重置密码", "成员5"), labels(first));
        assertEquals(true, first.get("hasMore"));
        Map<String, Object> second = page(null, null, null, null, null, null, (Long) first.get("nextCursor"), 2);
        assertEquals(List.of("成员4", "成员3"), labels(second));

        assertEquals(List.of("如何重置密码"), labels(page(AdminAudit.SUPPORT, null, null, null, null, null, null)));
        assertEquals(List.of("成员2"), labels(page(null, null, 2L, null, null, null, null)));
        assertEquals(List.of("如何重置密码"), labels(page(null, "OPS", null, null, null, null, null)), "actor name, any case");
        assertEquals(List.of("如何重置密码"), labels(page(null, "5", null, null, null, null, null)), "actor id");
        assertEquals(List.of("如何重置密码"), labels(page(null, null, null, "重置", null, null, null)));
        assertEquals(List.of("如何重置密码"), labels(page(null, null, null, "12", null, null, null)), "target id");

        // Calendar days in the business time zone, end day included.
        LocalDate day = AppTime.businessDate(LocalDateTime.ofInstant(Instant.parse("2026-09-13T02:00:00Z"), AppTime.storageZone()));
        assertEquals(List.of("成员3"), labels(page(null, null, null, null, day.toString(), day.toString(), null)));
        assertEquals(List.of("成员4", "成员3"), labels(page(null, null, null, null, day.toString(), day.plusDays(1).toString(), null)));

        assertEquals("invalid audit category", rawMessage(controller.page(ADMIN, "OTHER", null, null, null, null, null, null, null, 30)));
        assertEquals(500, controller.page(ADMIN, null, null, null, null, null, "17/09/2026", null, null, 30).code());
        assertEquals(500, controller.page(ADMIN, null, null, null, null, null, "2026-09-17", "2026-09-16", null, 30).code());
        assertEquals(500, controller.page(ADMIN, null, null, null, null, null, null, null, 0L, 30).code());
        assertEquals(6, items(page(null, null, null, null, null, null, null, 1000)).size(), "limit is capped, not refused");
    }

    @Test
    void oldEntriesCanBeRemoved() {
        store.save(entry("USER_STATUS", AdminAudit.ACCOUNTS, "old", 1L, Instant.parse("2025-01-01T00:00:00Z")));
        store.save(entry("USER_STATUS", AdminAudit.ACCOUNTS, "new", 1L, Instant.parse("2026-09-01T00:00:00Z")));
        assertEquals(1, store.removeBefore(LocalDateTime.of(2026, 1, 1, 0, 0)));
        assertEquals(List.of("new"), labels(page(null, null, null, null, null, null, null)));
    }

    @Test
    void accountActionsAreRecordedAndAdministratorsCannotLockThemselvesOut() {
        UserStore users = new InMemoryUserStore(encoder);
        UserController userController = new UserController(users, encoder);
        userController.setAdminAudit(new LocalAdminAudit(store));
        UserEntity admin = save(users, "audit-admin-" + System.nanoTime(), "ADMIN");
        UserEntity member = save(users, "audit-member-" + System.nanoTime(), "USER");
        // This test appoints an administrator further down, which is the super administrator's to do.
        String adminAuth = "Bearer " + LocalAuth.issueToken(admin.getUsername(), admin.getId(), "ADMIN", true);

        // Item 10: no self-suspension, self-deletion or self-demotion, through either endpoint.
        assertEquals("administrators cannot disable their own account",
                rawMessage(userController.updateUserStatus(adminAuth, Map.of("userId", admin.getId(), "status", "DISABLED"))));
        assertEquals("administrators cannot disable their own account",
                rawMessage(userController.updateUserGovernance(adminAuth, Map.of("userId", admin.getId(), "status", "DELETED"))));
        assertEquals("administrators cannot change their own role",
                rawMessage(userController.updateUserGovernance(adminAuth, Map.of("userId", admin.getId(), "role", "USER"))));
        assertEquals("ACTIVE", users.findById(admin.getId()).orElseThrow().getStatus());
        assertEquals("ADMIN", users.findById(admin.getId()).orElseThrow().getRole());
        // Editing their own profile is still fine.
        assertEquals(0, userController.updateUserGovernance(adminAuth, Map.of("userId", admin.getId(), "nickname", "Ops")).code());
        // Unknown statuses are refused instead of stored.
        assertEquals("invalid account status",
                rawMessage(userController.updateUserStatus(adminAuth, Map.of("userId", member.getId(), "status", "BANNED"))));

        assertEquals(0, userController.updateUserStatus(adminAuth, Map.of("userId", member.getId(), "status", "DISABLED")).code());
        // Setting the same status again is not a change.
        userController.updateUserStatus(adminAuth, Map.of("userId", member.getId(), "status", "DISABLED"));
        users.updateEmail(member.getId(), "member@example.com");
        assertEquals(0, userController.updateUserGovernance(adminAuth, Map.of("userId", member.getId(), "status", "ACTIVE",
                "role", "ADMIN", "resetPassword", "fresh-pass9", "removeEmail", true)).code());
        // Saving without changes adds nothing.
        userController.updateUserGovernance(adminAuth, Map.of("userId", member.getId()));

        List<Map<?, ?>> entries = items(page(null, null, member.getId(), null, null, null, null)).stream().<Map<?, ?>>map(item -> (Map<?, ?>) item).toList();
        assertEquals(List.of("USER_GOVERNANCE", "USER_STATUS"), entries.stream().map(item -> item.get("action")).toList());
        Map<?, ?> governance = entries.get(0);
        assertEquals(admin.getId(), governance.get("actorId"));
        assertEquals("修改了角色、账号状态、登录密码、绑定邮箱", governance.get("summary"));
        String detail = String.valueOf(governance.get("detail"));
        assertFalse(detail.contains("fresh-pass9"), "the new password is never logged");
        assertTrue(detail.contains("member@example.com"));
        assertEquals("账号状态：正常 → 已停用", entries.get(1).get("summary"));
        assertTrue(String.valueOf(entries.get(1).get("targetLabel")).contains("@" + member.getUsername()));

        // A member's token records nothing even if a handler is reached.
        new LocalAdminAudit(store).record(MEMBER, AdminAudit.Event.of("USER_STATUS", AdminAudit.ACCOUNTS, "USER", 1L, "x", 1L, "x"));
        assertEquals(2, items(page(null, null, member.getId(), null, null, null, null)).size());
    }

    @Test
    void passwordResetDecisionsAreRecordedWithoutTheCode() {
        UserStore users = new InMemoryUserStore(encoder);
        PasswordResetStore resets = new InMemoryPasswordResetStore();
        PasswordResetController resetController = new PasswordResetController(users, resets, encoder,
                new com.aiknowledge.user.security.CaptchaService(), new com.aiknowledge.user.security.LoginAttemptGuard(),
                TokenRevocations.inMemory(), java.time.Clock.systemUTC());
        resetController.setAdminAudit(new LocalAdminAudit(store));
        UserEntity member = save(users, "reset-audit-" + System.nanoTime(), "USER");
        Long first = resets.create(member.getId(), member.getUsername(), "微信").id();

        String code = String.valueOf(resetController.issueCode(ADMIN, Map.of("requestId", first), null).data().get("code"));
        resetController.issueCode(ADMIN, Map.of("requestId", first), null);
        resetController.closeRequest(ADMIN, Map.of("requestId", first, "note", "已电话核实"));

        List<?> entries = items(page(null, null, member.getId(), null, null, null, null));
        assertEquals(List.of("PASSWORD_RESET_CLOSE", "PASSWORD_RESET_ISSUE", "PASSWORD_RESET_ISSUE"),
                entries.stream().map(item -> ((Map<?, ?>) item).get("action")).toList());
        assertEquals("重新签发密码重置码", ((Map<?, ?>) entries.get(1)).get("summary"));
        assertEquals("签发密码重置码", ((Map<?, ?>) entries.get(2)).get("summary"));
        assertEquals(Map.of("note", "已电话核实"), ((Map<?, ?>) entries.get(0)).get("detail"));
        assertFalse(entries.toString().contains(code));
        assertFalse(entries.toString().contains(code.replace("-", "")));
    }

    private UserEntity save(UserStore users, String username, String role) {
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPasswordHash(encoder.encode("old-pass1"));
        user.setNickname(username);
        user.setStatus("ACTIVE");
        user.setRole(role);
        return users.save(user);
    }

    private static AuditEntry entry(String action, String category, String label, Long subject, Instant at) {
        return new AuditEntry(at, 2L, "admin", action, category, "TARGET", String.valueOf(subject), label, subject,
                "summary " + label, Map.of("reason", "spam"), "test", "203.0.113.1");
    }

    private Map<String, Object> page(String category, String actor, Long subject, String keyword, String from, String to, Long cursor) {
        return page(category, actor, subject, keyword, from, to, cursor, 30);
    }

    private Map<String, Object> page(String category, String actor, Long subject, String keyword, String from, String to,
                                     Long cursor, int limit) {
        ApiResponse<Map<String, Object>> response = controller.page(ADMIN, category, null, actor, subject, keyword, from, to, cursor, limit);
        assertEquals(0, response.code(), response.message());
        return response.data();
    }

    private static List<?> items(Map<String, Object> page) {
        return (List<?>) page.get("items");
    }

    private static List<Object> labels(Map<String, Object> page) {
        return items(page).stream().<Object>map(item -> ((Map<?, ?>) item).get("targetLabel")).toList();
    }

    /** ApiResponse translates messages; the tests compare against the English originals where that is clearer. */
    private static String rawMessage(ApiResponse<?> response) {
        assertEquals(500, response.code());
        return switch (response.message()) {
            case "操作失败，请稍后重试" -> "internal authorization is required";
            case "不能停用或删除自己的管理员账号" -> "administrators cannot disable their own account";
            case "不能修改自己的角色" -> "administrators cannot change their own role";
            case "账号状态无效" -> "invalid account status";
            case "操作类别无效" -> "invalid audit category";
            default -> response.message();
        };
    }
}
