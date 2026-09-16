package com.aiknowledge.user.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.common.PlatformConfigClient;
import com.aiknowledge.user.store.InMemoryUserStore;
import com.aiknowledge.user.store.UserStore;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserControllerTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/user-" + System.nanoTime());
    }

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final UserController controller =
            new UserController(new InMemoryUserStore(passwordEncoder), passwordEncoder);

    @Test
    void registrationCanBeDisabledByPlatformConfiguration() {
        PlatformConfigClient config = mock(PlatformConfigClient.class);
        when(config.enabled("registration_enabled", true)).thenReturn(false);
        UserController disabled = new UserController(
                new InMemoryUserStore(passwordEncoder), passwordEncoder,
                new com.aiknowledge.user.storage.UserAvatarStorageService("local", "target/test-user-avatars",
                        "http://127.0.0.1:9000", "test", "test", "test"),
                new com.aiknowledge.user.security.CaptchaService(), config
        );

        assertEquals(500, disabled.register(Map.of("username", "new-user", "password", "password123")).code());
    }

    @Test
    void demoUserCanLogin() {
        ApiResponse<Map<String, Object>> response =
                controller.login(credentials("demo", "demo"));

        assertEquals(0, response.code());
        assertNotNull(response.data().get("token"));
        String token = String.valueOf(response.data().get("token"));
        assertEquals(3, token.split("\\.").length);
        assertEquals(true, LocalAuth.isAuthenticated("Bearer " + token));
        assertEquals(false, LocalAuth.isAdmin("Bearer " + token));
        assertEquals(false, LocalAuth.isAuthenticated("Bearer " + token + "tampered"));

        ApiResponse<Map<String, Object>> session = controller.session("Bearer " + token);
        assertEquals(0, session.code());
        assertEquals("demo", session.data().get("username"));
        assertEquals("USER", session.data().get("role"));
        assertEquals(1L, session.data().get("userId"));
    }

    @Test
    void adminTokenContainsAdminRole() {
        ApiResponse<Map<String, Object>> response =
                controller.login(credentials("admin", "admin123"));
        assertEquals(0, response.code());
        String token = String.valueOf(response.data().get("token"));
        assertEquals(true, LocalAuth.isAdmin("Bearer " + token));
    }

    @Test
    void captchaIsRequiredAndCanOnlyBeUsedOnce() {
        assertEquals(500, controller.login(Map.of("username", "demo", "password", "demo")).code());
        Map<String, String> request = credentials("demo", "demo");
        assertEquals(0, controller.login(request).code());
        assertEquals(500, controller.login(request).code());
    }

    @Test
    void registeredUserCanLogin() {
        Map<String, String> registrationRequest = credentials("alice", "secret123");
        registrationRequest.put("nickname", "Alice");
        ApiResponse<Map<String, Object>> registration = controller.register(registrationRequest);
        assertEquals(0, registration.code());
        assertNotNull(registration.data().get("token"));
        assertEquals("USER", registration.data().get("role"));

        ApiResponse<Map<String, Object>> login =
                controller.login(credentials("alice", "secret123"));
        assertEquals(0, login.code());
    }

    @Test
    void invalidRegistrationFieldsDoNotConsumeTheCaptcha() {
        Map<String, String> registrationRequest = credentials("field-check-user", "onlyletters");

        assertEquals(500, controller.register(registrationRequest).code());
        registrationRequest.put("password", "letters123");
        assertEquals(0, controller.register(registrationRequest).code());
    }

    @Test
    void registrationRejectsUnsafeOrOverlongFields() {
        assertEquals(500, controller.register(credentials("same123", "same123")).code());
        assertEquals(500, controller.register(credentials("space-user", "secret 123")).code());

        Map<String, String> longNickname = credentials("nickname-user", "secret123");
        longNickname.put("nickname", "名".repeat(65));
        assertEquals(500, controller.register(longNickname).code());
    }

    @Test
    void authenticatedUserCanChangePassword() {
        String auth = "Bearer " + LocalAuth.issueToken("demo");
        var changed = controller.changePassword(auth, Map.of("currentPassword", "demo", "newPassword", "new-demo-pass"));
        assertEquals(0, changed.code());
        assertEquals(0, controller.login(credentials("demo", "new-demo-pass")).code());
    }

    @Test
    void reservedAdminNameCannotBeRegisteredWithDifferentCase() {
        Map<String, String> registrationRequest = credentials("Admin", "secret123");
        registrationRequest.put("nickname", "Not Admin");
        ApiResponse<Map<String, Object>> registration = controller.register(registrationRequest);
        assertEquals(500, registration.code());
        assertEquals("USER", LocalAuth.roleForUsername("Admin"));
    }

    @Test
    void disabledUserCannotLogin() {
        String adminAuth = "Bearer " + LocalAuth.issueToken("admin");
        assertEquals(0, controller.updateUserStatus(adminAuth, Map.of("userId", 1L, "status", "DISABLED")).code());
        assertEquals(500, controller.login(credentials("demo", "demo")).code());
        controller.updateUserStatus(adminAuth, Map.of("userId", 1L, "status", "ACTIVE"));
    }

    @Test
    void adminCanUpdateUserGovernanceAndThePolicyIsExposedInternally() {
        String adminAuth = "Bearer " + LocalAuth.issueToken("admin", 2L, "ADMIN");
        var updated = controller.updateUserGovernance(adminAuth, Map.of(
                "userId", 1L,
                "role", "USER",
                "status", "ACTIVE",
                "publishPolicy", "PRE_REVIEW",
                "messagingEnabled", false,
                "nickname", "审核中用户"
        ));

        assertEquals(0, updated.code());
        assertEquals("PRE_REVIEW", updated.data().get("publishPolicy"));
        assertEquals(false, updated.data().get("messagingEnabled"));
        var policy = controller.internalRelation("ai-knowledge-local-internal", 1L, 2L);
        assertEquals(0, policy.code());
        assertEquals(true, policy.data().get("publishAllowed"));
        assertEquals(true, policy.data().get("preAuditRequired"));
        assertEquals(false, policy.data().get("messagingAllowed"));

        controller.updateUserGovernance(adminAuth, Map.of(
                "userId", 1L,
                "publishPolicy", "STANDARD",
                "messagingEnabled", true
        ));
    }

    @Test
    void normalUserCannotUpdateGovernance() {
        String userAuth = "Bearer " + LocalAuth.issueToken("demo", 1L, "USER");
        assertEquals(500, controller.updateUserGovernance(userAuth, Map.of(
                "userId", 2L,
                "role", "USER"
        )).code());
    }

    @Test
    void blockedUsersCannotFollowEachOther() {
        String userAuth = "Bearer " + LocalAuth.issueToken("demo");
        assertEquals(0, controller.block(userAuth, Map.of("targetUserId", 2L)).code());
        assertEquals(500, controller.follow(userAuth, Map.of("targetUserId", 2L)).code());
        assertEquals(0, controller.unblock(userAuth, Map.of("targetUserId", 2L)).code());
    }

    @Test
    void directoryRequiresAnExactUsernameAndNeverReturnsAFullList() {
        Map<String, String> registrationRequest = credentials("directory-user", "secret123");
        registrationRequest.put("nickname", "Directory User");
        controller.register(registrationRequest);

        String auth = "Bearer " + LocalAuth.issueToken("demo");
        var blankResponse = controller.directory(auth, "");
        assertEquals(0, blankResponse.code());
        assertTrue(blankResponse.data().isEmpty());

        var response = controller.directory(auth, "directory-user");

        assertEquals(0, response.code());
        assertFalse(response.data().isEmpty());
        Map<String, Object> user = response.data().get(0);
        assertNotNull(user.get("id"));
        assertEquals("directory-user", user.get("username"));
        assertEquals("Directory User", user.get("nickname"));
        assertTrue(user.containsKey("avatarUrl"));
        assertFalse(user.containsKey("password"));
        assertFalse(user.containsKey("passwordHash"));
    }

    @Test
    void authenticatedClientsCanResolveOnlyRequestedUserSummaries() {
        String auth = "Bearer " + LocalAuth.issueToken("demo");
        var response = controller.summaries(auth, "2,1,2,999999");

        assertEquals(0, response.code());
        assertEquals(2, response.data().size());
        assertEquals(2L, response.data().get(0).get("id"));
        assertEquals(1L, response.data().get(1).get("id"));
        assertTrue(response.data().stream().allMatch(user -> user.containsKey("username") && user.containsKey("avatarUrl")));
        assertTrue(response.data().stream().noneMatch(user -> user.containsKey("passwordHash") || user.containsKey("signature")));
        assertEquals(500, controller.summaries(null, "1").code());
        assertEquals(500, controller.summaries(auth, "not-a-number").code());
    }

    private Map<String, String> credentials(String username, String password) {
        var challenge = controller.captcha();
        String question = String.valueOf(challenge.data().get("question"));
        String[] values = question.replace("= ?", "").split("\\+");
        int answer = Integer.parseInt(values[0].trim()) + Integer.parseInt(values[1].trim());
        Map<String, String> request = new HashMap<>();
        request.put("username", username);
        request.put("password", password);
        request.put("captchaId", String.valueOf(challenge.data().get("captchaId")));
        request.put("captchaAnswer", String.valueOf(answer));
        return request;
    }

    @Test
    void followStateCanBeSavedAndListed() {
        String auth = "Bearer " + LocalAuth.issueToken("demo");
        ApiResponse<Map<String, Object>> followed = controller.follow(auth, Map.of("userId", 999L, "targetUserId", 2L));
        assertEquals(0, followed.code());

        ApiResponse<Map<String, Object>> follows = controller.follows(auth, 1L);
        assertEquals(0, follows.code());
        assertEquals(java.util.List.of(2L), follows.data().get("followedUserIds"));
        String adminAuth = "Bearer " + LocalAuth.issueToken("admin");
        assertEquals(java.util.List.of(1L), controller.follows(adminAuth, 2L).data().get("followerUserIds"));
        assertEquals(500, controller.follows(auth, 2L).code());
    }

    @Test
    void blockReportAndBehaviorArePersisted() {
        String auth = "Bearer " + LocalAuth.issueToken("demo");
        assertEquals(0, controller.block(auth, Map.of("userId", 999L, "targetUserId", 2L)).code());
        assertEquals(java.util.List.of(2L), controller.blocks(auth, 1L).data().get("blockedUserIds"));

        var report = controller.reportUser(auth, Map.of("reporterId", 999L, "targetUserId", 2L, "reason", "spam"));
        assertEquals("PENDING", report.data().status());
        assertEquals(1L, report.data().reporterId());

        var behavior = controller.recordBehavior(auth, Map.of(
                "userId", 999L, "action", "VIEW", "targetType", "KNOWLEDGE", "targetId", 9L));
        assertEquals("VIEW", behavior.data().action());
        assertEquals(1, controller.behaviors(auth, 1L).data().size());
    }

    @Test
    void adminOverviewCountsUsersAndReportsWithoutReadingThemAll() {
        String adminAuth = "Bearer " + LocalAuth.issueToken("admin", 2L, "ADMIN");
        Map<String, Object> before = controller.adminOverview(adminAuth).data();
        long baseTotal = overviewNumber(before.get("totalUsers"));
        long baseActive = overviewNumber(before.get("activeUsers"));
        long baseRisk = overviewNumber(before.get("riskUsers"));
        long baseReports = overviewNumber(before.get("reports"));

        String username = "overview-" + System.nanoTime();
        Long userId = ((Number) ((Map<?, ?>) controller.register(credentials(username, "password123"))
                .data().get("user")).get("id")).longValue();
        controller.reportUser("Bearer " + LocalAuth.issueToken(username, userId, "USER"),
                Map.of("targetUserId", 1L, "reason", "概览举报"));

        Map<String, Object> afterRegister = controller.adminOverview(adminAuth).data();
        assertEquals(baseTotal + 1, overviewNumber(afterRegister.get("totalUsers")));
        assertEquals(baseActive + 1, overviewNumber(afterRegister.get("activeUsers")));
        assertEquals(baseRisk, overviewNumber(afterRegister.get("riskUsers")));
        assertEquals(baseReports + 1, overviewNumber(afterRegister.get("reports")));

        controller.updateUserStatus(adminAuth, Map.of("userId", userId, "status", "DISABLED"));
        Map<String, Object> afterDisable = controller.adminOverview(adminAuth).data();
        assertEquals(baseTotal + 1, overviewNumber(afterDisable.get("totalUsers")));
        assertEquals(baseActive, overviewNumber(afterDisable.get("activeUsers")));
        assertEquals(baseRisk + 1, overviewNumber(afterDisable.get("riskUsers")));
    }

    private static long overviewNumber(Object value) {
        return value instanceof Number found ? found.longValue() : 0L;
    }


    @Test
    void adminUserPageFiltersAndPagesServerSide() {
        String adminAuth = "Bearer " + LocalAuth.issueToken("admin", 2L, "ADMIN");
        String marker = "pagesample" + System.nanoTime();
        java.util.List<Long> created = new java.util.ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            created.add(((Number) ((Map<?, ?>) controller.register(credentials(marker + "-" + i, "password123"))
                    .data().get("user")).get("id")).longValue());
        }
        controller.updateUserStatus(adminAuth, Map.of("userId", created.get(2), "status", "DISABLED"));

        // The keyword narrows the table to this test's accounts, so the cursor walk is stable.
        Map<String, Object> first = controller.adminUsersPage(adminAuth, marker, null, null, null, null, 2).data();
        assertEquals(List.of(created.get(0), created.get(1)), pagedIds(first));
        assertEquals(true, first.get("hasMore"));
        assertEquals(3L, ((Number) first.get("total")).longValue());

        Map<String, Object> second = controller.adminUsersPage(
                adminAuth, marker, null, null, null, ((Number) first.get("nextCursor")).longValue(), 2).data();
        assertEquals(List.of(created.get(2)), pagedIds(second));
        assertEquals(false, second.get("hasMore"));
        assertEquals(null, second.get("total"));

        Map<String, Object> disabled = controller.adminUsersPage(adminAuth, marker, "DISABLED", null, null, null, 20).data();
        assertEquals(List.of(created.get(2)), pagedIds(disabled));
        assertEquals(1L, ((Number) disabled.get("total")).longValue());
        assertEquals(2L, ((Number) controller.adminUsersPage(adminAuth, marker, "ACTIVE", null, null, null, 20)
                .data().get("total")).longValue());
        assertTrue(pagedIds(controller.adminUsersPage(adminAuth, marker, null, "ADMIN", null, null, 20).data()).isEmpty());

        // One account by id, the lookup the moderation view uses to open a reported user.
        Map<String, Object> single = controller.adminUsersPage(adminAuth, null, null, null, created.get(1), null, 1).data();
        assertEquals(List.of(created.get(1)), pagedIds(single));

        assertEquals(500, controller.adminUsersPage(userAuth(), marker, null, null, null, null, 20).code());
        assertEquals(500, controller.adminUsersPage(adminAuth, null, null, null, null, -1L, 20).code());
    }

    private String userAuth() {
        return "Bearer " + LocalAuth.issueToken("demo", 1L, "USER");
    }

    @SuppressWarnings("unchecked")
    private static List<Long> pagedIds(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("items")).stream()
                .map(item -> ((Number) item.get("id")).longValue()).toList();
    }


    @Test
    void adminOverviewCountsOpenUserReports() {
        String adminAuth = "Bearer " + LocalAuth.issueToken("admin", 2L, "ADMIN");
        long openBefore = overviewNumber(controller.adminOverview(adminAuth).data().get("openReports"));
        long allBefore = overviewNumber(controller.adminOverview(adminAuth).data().get("reports"));

        String username = "openreport-" + System.nanoTime();
        Long userId = ((Number) ((Map<?, ?>) controller.register(credentials(username, "password123"))
                .data().get("user")).get("id")).longValue();
        Long reportId = controller.reportUser("Bearer " + LocalAuth.issueToken(username, userId, "USER"),
                Map.of("targetUserId", 1L, "reason", "待处理举报")).data().id();
        assertEquals(openBefore + 1, overviewNumber(controller.adminOverview(adminAuth).data().get("openReports")));

        controller.resolveUserReport(adminAuth, Map.of("reportId", reportId, "status", "RESOLVED", "result", "已处理"));
        Map<String, Object> after = controller.adminOverview(adminAuth).data();
        assertEquals(openBefore, overviewNumber(after.get("openReports")));
        assertEquals(allBefore + 1, overviewNumber(after.get("reports")));
    }


    @Test
    void userReportQueuePagesNewestFirstWithStatusAndKeyword() {
        String adminAuth = "Bearer " + LocalAuth.issueToken("admin", 2L, "ADMIN");
        String marker = "用户举报队列" + System.nanoTime();
        String username = "reporter-" + System.nanoTime();
        Long reporterId = ((Number) ((Map<?, ?>) controller.register(credentials(username, "password123"))
                .data().get("user")).get("id")).longValue();
        String reporterAuth = "Bearer " + LocalAuth.issueToken(username, reporterId, "USER");
        List<Long> created = new java.util.ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            created.add(controller.reportUser(reporterAuth, Map.of("targetUserId", 1L, "reason", marker + " " + i)).data().id());
        }

        Map<String, Object> first = controller.adminReportsPage(adminAuth, marker, null, null, 2).data();
        assertEquals(List.of(created.get(2), created.get(1)), reportIds(first));
        assertEquals(true, first.get("hasMore"));
        assertEquals(3L, overviewNumber(first.get("total")));
        Map<String, Object> second = controller.adminReportsPage(
                adminAuth, marker, null, ((Number) first.get("nextCursor")).longValue(), 2).data();
        assertEquals(List.of(created.get(0)), reportIds(second));
        assertEquals(null, second.get("total"));

        controller.resolveUserReport(adminAuth, Map.of("reportId", created.get(1), "status", "RESOLVED", "result", "已处理"));
        assertEquals(List.of(created.get(1)), reportIds(controller.adminReportsPage(adminAuth, marker, "RESOLVED", null, 20).data()));
        assertEquals(List.of(created.get(2)), reportIds(controller.adminReportsPage(adminAuth, marker + " 3", null, null, 20).data()));

        assertEquals(500, controller.adminReportsPage(reporterAuth, marker, null, null, 20).code());
        assertEquals(500, controller.adminReportsPage(adminAuth, marker, null, -1L, 20).code());
    }

    @SuppressWarnings("unchecked")
    private static List<Long> reportIds(Map<String, Object> page) {
        return ((List<UserStore.UserReport>) page.get("items")).stream().map(UserStore.UserReport::id).toList();
    }

}
