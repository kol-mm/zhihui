package com.aiknowledge.user.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.user.store.InMemoryUserStore;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserControllerTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/user-" + System.nanoTime());
    }

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final UserController controller =
            new UserController(new InMemoryUserStore(passwordEncoder), passwordEncoder);

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
}
