package com.aiknowledge.user.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.common.PlatformConfigClient;
import com.aiknowledge.user.store.InMemoryUserStore;
import com.aiknowledge.user.store.UserStore;
import com.aiknowledge.user.security.CaptchaService;
import com.aiknowledge.user.security.LoginAttemptGuard;
import com.aiknowledge.user.security.TokenRevocations;
import com.aiknowledge.user.storage.UserAvatarStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        var changed = controller.changePassword(auth, Map.of("currentPassword", "demo", "newPassword", "new-demo-pass1"));
        assertEquals(0, changed.code());
        assertEquals(0, controller.login(credentials("demo", "new-demo-pass1")).code());
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


    @Test
    void repeatedFailedLoginsLockTheAccountForAWhile() {
        for (int i = 0; i < 5; i++) {
            assertEquals("用户名或密码错误", controller.login(credentials("demo", "wrong-pass" + i)).message());
        }
        ApiResponse<Map<String, Object>> locked = controller.login(credentials("demo", "demo"));
        assertEquals(500, locked.code());
        assertTrue(locked.message().contains("登录失败次数过多"), locked.message());

        // Other accounts keep working.
        assertEquals(0, controller.login(credentials("admin", "admin123")).code());

        // Unknown names lock the same way, so a lock says nothing about whether an account exists.
        String ghost = "ghost-" + System.nanoTime();
        for (int i = 0; i < 5; i++) {
            assertEquals("用户名或密码错误", controller.login(credentials(ghost, "wrong-pass" + i)).message());
        }
        assertTrue(controller.login(credentials(ghost, "wrong-pass")).message().contains("登录失败次数过多"));
    }

    @Test
    void loginAsksForMissingCredentials() {
        assertEquals("请输入用户名和密码", controller.login(credentials("", "")).message());
        assertEquals("请输入用户名和密码", controller.login(credentials("demo", "")).message());
    }

    @Test
    void profileLookupNeedsSignInAndHidesGovernanceFieldsFromOthers() {
        String adminAuth = "Bearer " + LocalAuth.issueToken("admin", 2L, "ADMIN");
        String demoAuth = "Bearer " + LocalAuth.issueToken("demo");
        String username = "lookup-" + System.nanoTime();
        Map<String, Object> registered = controller.register(credentials(username, "secret123")).data();
        String memberAuth = "Bearer " + registered.get("token");
        Long memberId = ((Number) ((Map<?, ?>) registered.get("user")).get("id")).longValue();

        assertEquals(500, controller.info(null, "demo").code());
        assertEquals(500, controller.info(memberAuth, "").code());

        Map<String, Object> card = controller.info(memberAuth, "demo").data();
        assertEquals("demo", card.get("username"));
        assertTrue(card.containsKey("nickname"));
        assertFalse(card.containsKey("role"));
        assertFalse(card.containsKey("status"));
        assertFalse(card.containsKey("publishPolicy"));

        assertTrue(controller.info(demoAuth, "demo").data().containsKey("role"));
        assertTrue(controller.info(adminAuth, "demo").data().containsKey("publishPolicy"));

        controller.updateUserStatus(adminAuth, Map.of("userId", memberId, "status", "DISABLED"));
        assertEquals(500, controller.info(demoAuth, username).code());
        assertEquals("DISABLED", controller.info(adminAuth, username).data().get("status"));
    }

    @Test
    void passwordChangeFollowsTheRegistrationRules() {
        String username = "pwchange-" + System.nanoTime();
        String auth = "Bearer " + controller.register(credentials(username, "secret123")).data().get("token");

        assertEquals("密码须同时包含字母和数字", controller.changePassword(auth, Map.of(
                "currentPassword", "secret123", "newPassword", "onlyletters")).message());
        assertEquals("密码须同时包含字母和数字", controller.changePassword(auth, Map.of(
                "currentPassword", "secret123", "newPassword", "12345678")).message());
        assertEquals("密码不能包含空格", controller.changePassword(auth, Map.of(
                "currentPassword", "secret123", "newPassword", "has space 1")).message());
        assertEquals("新密码不能与当前密码相同", controller.changePassword(auth, Map.of(
                "currentPassword", "secret123", "newPassword", "secret123")).message());
        assertEquals("当前密码不正确", controller.changePassword(auth, Map.of(
                "currentPassword", "wrong-pass1", "newPassword", "better456")).message());

        assertEquals(0, controller.changePassword(auth, Map.of("currentPassword", "secret123", "newPassword", "better456")).code());
        assertEquals(0, controller.login(credentials(username, "better456")).code());
    }

    @Test
    void registrationRejectsReservedNamesInAnyCase() {
        for (String reserved : new String[]{"Admin", "ADMINISTRATOR", "root", "System", "support"}) {
            assertEquals("该用户名为系统保留名称", controller.register(credentials(reserved, "secret123")).message(), reserved);
        }
    }


    @Test
    void signInPutsTheTokenInAnHttpOnlyCookieOnly() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Proto", "https,http");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ApiResponse<Map<String, Object>> result = controller.loginRequest(credentials("admin", "admin123"), request, response);

        assertEquals(0, result.code());
        assertFalse(result.data().containsKey("token"));
        assertEquals("ADMIN", result.data().get("role"));
        String cookie = response.getHeader("Set-Cookie");
        assertTrue(cookie.startsWith("zh_session="), cookie);
        assertTrue(cookie.contains("HttpOnly") && cookie.contains("Secure") && cookie.contains("SameSite=Lax"), cookie);
        assertEquals("no-store", response.getHeader("Cache-Control"));
        String token = cookie.substring("zh_session=".length(), cookie.indexOf(';'));
        assertTrue(LocalAuth.isAdmin("Bearer " + token));

        // Plain HTTP gets no Secure flag, and a failed sign-in sets nothing.
        MockHttpServletResponse local = new MockHttpServletResponse();
        controller.loginRequest(credentials("admin", "admin123"), new MockHttpServletRequest(), local);
        assertFalse(local.getHeader("Set-Cookie").contains("Secure"));
        MockHttpServletResponse failed = new MockHttpServletResponse();
        assertEquals(500, controller.loginRequest(credentials("demo", "wrong-pass1"), new MockHttpServletRequest(), failed).code());
        assertNull(failed.getHeader("Set-Cookie"));

        MockHttpServletResponse registered = new MockHttpServletResponse();
        var registration = controller.registerRequest(credentials("cookie-" + System.nanoTime(), "secret123"),
                new MockHttpServletRequest(), registered);
        assertFalse(registration.data().containsKey("token"));
        assertTrue(registered.getHeader("Set-Cookie").startsWith("zh_session="));
    }

    @Test
    void logoutEndsThatSessionOnly() {
        RecordingRevocations revocations = new RecordingRevocations();
        UserController sessions = controllerWith(revocations);
        String current = "Bearer " + LocalAuth.issueToken("demo", 1L, "USER");
        String otherDevice = "Bearer " + LocalAuth.issueToken("demo", 1L, "USER");

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertEquals(0, sessions.logout(current, new MockHttpServletRequest(), response).code());
        assertEquals(List.of(LocalAuth.tokenInfo(current).tokenId()), revocations.tokens);
        assertTrue(revocations.users.isEmpty());
        assertTrue(response.getHeader("Set-Cookie").contains("Max-Age=0"));
        // Signing out without a valid session still clears the cookie.
        MockHttpServletResponse anonymous = new MockHttpServletResponse();
        assertEquals(0, sessions.logout(null, new MockHttpServletRequest(), anonymous).code());
        assertTrue(anonymous.getHeader("Set-Cookie").contains("Max-Age=0"));

        // An older page's stored token moves into the cookie, unless it was revoked.
        assertEquals(500, sessions.adoptSession(current, new MockHttpServletRequest(), new MockHttpServletResponse()).code());
        assertEquals(500, sessions.adoptSession("Bearer forged", new MockHttpServletRequest(), new MockHttpServletResponse()).code());
        MockHttpServletResponse adopted = new MockHttpServletResponse();
        var adoption = sessions.adoptSession(otherDevice, new MockHttpServletRequest(), adopted);
        assertEquals(0, adoption.code());
        assertEquals("demo", adoption.data().get("username"));
        assertTrue(adopted.getHeader("Set-Cookie").startsWith("zh_session=" + otherDevice.substring(7) + ";"));
        long cookieSeconds = Long.parseLong(adopted.getHeader("Set-Cookie").replaceAll(".*Max-Age=(\\d+).*", "$1"));
        long tokenSeconds = LocalAuth.tokenInfo(otherDevice).expiresAt() - java.time.Instant.now().getEpochSecond();
        assertTrue(Math.abs(cookieSeconds - tokenSeconds) <= 2, cookieSeconds + " vs " + tokenSeconds);
    }

    @Test
    void passwordChangeSignsOutEverywhereAndRenewsThisSession() {
        RecordingRevocations revocations = new RecordingRevocations();
        UserController sessions = controllerWith(revocations);
        String username = "rotate-" + System.nanoTime();
        Map<String, Object> registered = sessions.register(credentials(sessions, username, "secret123")).data();
        long userId = ((Number) ((Map<?, ?>) registered.get("user")).get("id")).longValue();
        String auth = "Bearer " + registered.get("token");

        MockHttpServletResponse response = new MockHttpServletResponse();
        var changed = sessions.changePasswordRequest(auth, Map.of("currentPassword", "secret123", "newPassword", "better456"),
                new MockHttpServletRequest(), response);
        assertEquals(0, changed.code());
        assertEquals(Map.of("updated", true), changed.data());
        assertEquals(List.of(userId), revocations.users);
        String cookie = response.getHeader("Set-Cookie");
        String renewed = cookie.substring("zh_session=".length(), cookie.indexOf(';'));
        assertEquals(userId, LocalAuth.userId("Bearer " + renewed));

        // A rejected change revokes nothing.
        sessions.changePassword(auth, Map.of("currentPassword", "wrong-pass1", "newPassword", "better789"));
        assertEquals(1, revocations.users.size());
    }

    @Test
    void suspensionsAndRoleChangesEndOpenSessions() {
        RecordingRevocations revocations = new RecordingRevocations();
        UserController sessions = controllerWith(revocations);
        String adminAuth = "Bearer " + LocalAuth.issueToken("admin", 2L, "ADMIN");
        String username = "governed-" + System.nanoTime();
        long userId = ((Number) ((Map<?, ?>) sessions.register(credentials(sessions, username, "secret123"))
                .data().get("user")).get("id")).longValue();

        sessions.updateUserGovernance(adminAuth, Map.of("userId", userId, "publishPolicy", "PRE_REVIEW"));
        assertTrue(revocations.users.isEmpty());

        sessions.updateUserStatus(adminAuth, Map.of("userId", userId, "status", "DISABLED"));
        assertEquals(List.of(userId), revocations.users);
        sessions.updateUserStatus(adminAuth, Map.of("userId", userId, "status", "ACTIVE"));
        assertEquals(1, revocations.users.size());

        sessions.updateUserGovernance(adminAuth, Map.of("userId", userId, "role", "ADMIN"));
        assertEquals(2, revocations.users.size());

        // A weak reset password is refused before anything is written.
        assertEquals("密码须同时包含字母和数字", sessions.updateUserGovernance(adminAuth, Map.of(
                "userId", userId, "role", "USER", "publishPolicy", "BLOCKED", "resetPassword", "onlyletters")).message());
        assertEquals(2, revocations.users.size());
        var user = sessions.info(adminAuth, username).data();
        assertEquals("ADMIN", user.get("role"));
        assertEquals("PRE_REVIEW", user.get("publishPolicy"));

        assertEquals(0, sessions.updateUserGovernance(adminAuth, Map.of("userId", userId, "resetPassword", "handed-over1")).code());
        assertEquals(3, revocations.users.size());
        assertEquals(0, sessions.login(credentials(sessions, username, "handed-over1")).code());
    }

    @Test
    void membersCannotPassThemselvesOffAsStaff() {
        Map<String, String> request = credentials("impostor-" + System.nanoTime(), "secret123");
        request.put("nickname", "官方 客服");
        assertEquals("昵称不能冒充平台管理员、官方或客服，请更换", controller.register(request).message());
        // With no nickname the username is shown, so the username is what gets checked.
        assertEquals("该用户名为系统保留名称", controller.register(credentials("admin_zhang", "secret123")).message());

        String username = "renamer-" + System.nanoTime();
        Map<String, Object> registered = controller.register(credentials(username, "secret123")).data();
        String auth = "Bearer " + registered.get("token");
        assertEquals("昵称不能冒充平台管理员、官方或客服，请更换", controller.updateProfile(auth,
                Map.of("nickname", "社区管理员", "signature", "")).message());
        assertEquals(0, controller.updateProfile(auth, Map.of("nickname", "普通成员", "signature", "你好")).code());

        // Admins are staff; and a name that predates the rule does not block editing the rest of the profile.
        String adminAuth = "Bearer " + LocalAuth.issueToken("admin", 2L, "ADMIN");
        String adminName = String.valueOf(controller.info(adminAuth, "admin").data().get("nickname"));
        assertEquals(0, controller.updateProfile(adminAuth, Map.of("nickname", "平台管理员", "signature", "")).code());
        controller.updateProfile(adminAuth, Map.of("nickname", adminName, "signature", ""));
        long userId = ((Number) ((Map<?, ?>) registered.get("user")).get("id")).longValue();
        controller.updateUserGovernance(adminAuth, Map.of("userId", userId, "nickname", "官方助手"));
        assertEquals(0, controller.updateProfile(auth, Map.of("nickname", "官方助手", "signature", "只改签名")).code());
    }

    private static final class RecordingRevocations implements TokenRevocations {
        private final List<String> tokens = new java.util.ArrayList<>();
        private final List<Long> users = new java.util.ArrayList<>();

        @Override public void revokeToken(String tokenId, long expiresAtEpochSecond) { tokens.add(tokenId); }
        @Override public void revokeUser(long userId) { users.add(userId); }
        @Override public boolean isRevoked(LocalAuth.TokenInfo token) { return token != null && tokens.contains(token.tokenId()); }
    }

    private UserController controllerWith(TokenRevocations revocations) {
        return new UserController(new InMemoryUserStore(passwordEncoder), passwordEncoder,
                new UserAvatarStorageService("local", "target/test-user-avatars", "http://127.0.0.1:9000", "test", "test", "test"),
                new CaptchaService(), null, new LoginAttemptGuard(), revocations);
    }

    private Map<String, String> credentials(UserController target, String username, String password) {
        var challenge = target.captcha();
        String[] values = String.valueOf(challenge.data().get("question")).replace("= ?", "").split("\\+");
        Map<String, String> request = new HashMap<>();
        request.put("username", username);
        request.put("password", password);
        request.put("captchaId", String.valueOf(challenge.data().get("captchaId")));
        request.put("captchaAnswer", String.valueOf(Integer.parseInt(values[0].trim()) + Integer.parseInt(values[1].trim())));
        return request;
    }
}
