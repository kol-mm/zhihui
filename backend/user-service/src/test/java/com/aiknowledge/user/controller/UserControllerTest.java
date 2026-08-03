package com.aiknowledge.user.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.user.store.InMemoryUserStore;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
    void followStateCanBeSavedAndListed() {
        ApiResponse<Map<String, Object>> followed = controller.follow(Map.of("userId", 1L, "targetUserId", 2L));
        assertEquals(0, followed.code());

        ApiResponse<Map<String, Object>> follows = controller.follows(1L);
        assertEquals(0, follows.code());
        assertEquals(java.util.List.of(2L), follows.data().get("followedUserIds"));
    }
}
