package com.aiknowledge.user.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.user.store.InMemoryUserStore;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

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
                controller.login(Map.of("username", "demo", "password", "demo"));

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
    }

    @Test
    void adminTokenContainsAdminRole() {
        ApiResponse<Map<String, Object>> response =
                controller.login(Map.of("username", "admin", "password", "admin123"));
        assertEquals(0, response.code());
        String token = String.valueOf(response.data().get("token"));
        assertEquals(true, LocalAuth.isAdmin("Bearer " + token));
    }

    @Test
    void registeredUserCanLogin() {
        ApiResponse<Map<String, Object>> registration = controller.register(Map.of(
                "username", "alice",
                "password", "secret123",
                "nickname", "Alice"
        ));
        assertEquals(0, registration.code());

        ApiResponse<Map<String, Object>> login =
                controller.login(Map.of("username", "alice", "password", "secret123"));
        assertEquals(0, login.code());
    }

    @Test
    void directoryReturnsActivePublicProfilesWithoutCredentials() {
        controller.register(Map.of(
                "username", "directory-user",
                "password", "secret123",
                "nickname", "Directory User"
        ));

        var response = controller.directory("directory");

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
    void followStateCanBeSavedAndListed() {
        ApiResponse<Map<String, Object>> followed = controller.follow(Map.of("userId", 1L, "targetUserId", 2L));
        assertEquals(0, followed.code());

        ApiResponse<Map<String, Object>> follows = controller.follows(1L);
        assertEquals(0, follows.code());
        assertEquals(java.util.List.of(2L), follows.data().get("followedUserIds"));
        assertEquals(java.util.List.of(1L), controller.follows(2L).data().get("followerUserIds"));
    }

    @Test
    void blockReportAndBehaviorArePersisted() {
        assertEquals(0, controller.block(Map.of("userId", 1L, "targetUserId", 2L)).code());
        assertEquals(java.util.List.of(2L), controller.blocks(1L).data().get("blockedUserIds"));

        var report = controller.reportUser(Map.of("reporterId", 1L, "targetUserId", 2L, "reason", "spam"));
        assertEquals("PENDING", report.data().status());

        var behavior = controller.recordBehavior(Map.of(
                "userId", 1L, "action", "VIEW", "targetType", "KNOWLEDGE", "targetId", 9L));
        assertEquals("VIEW", behavior.data().action());
        assertEquals(1, controller.behaviors(1L).data().size());
    }
}
