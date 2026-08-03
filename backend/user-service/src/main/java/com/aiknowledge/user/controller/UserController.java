package com.aiknowledge.user.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.user.entity.UserEntity;
import com.aiknowledge.user.store.UserStore;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/user")
public class UserController {
    private final UserStore userStore;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserStore userStore, PasswordEncoder passwordEncoder) {
        this.userStore = userStore;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of("service", "user-service", "time", Instant.now().toString()));
    }

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody Map<String, String> request) {
        String username = request.getOrDefault("username", "").trim();
        if (username.isEmpty()) {
            return ApiResponse.fail("username is required");
        }
        String password = request.getOrDefault("password", "");
        if (password.length() < 6) {
            return ApiResponse.fail("password must contain at least 6 characters");
        }
        if (userStore.findByUsername(username).isPresent()) {
            return ApiResponse.fail("username already exists");
        }

        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setNickname(request.getOrDefault("nickname", username));
        user.setStatus("ACTIVE");
        return ApiResponse.ok(toView(userStore.save(user)));
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, String> request) {
        String username = request.getOrDefault("username", "").trim();
        String password = request.getOrDefault("password", "");
        UserEntity user = userStore.findByUsername(username).orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            return ApiResponse.fail("invalid username or password");
        }
        return ApiResponse.ok(Map.of(
                "token", "local-dev-token-" + username,
                "user", toView(user)
        ));
    }

    @GetMapping("/info")
    public ApiResponse<Map<String, Object>> info() {
        return userStore.findByUsername("demo")
                .map(user -> ApiResponse.ok(toView(user)))
                .orElseGet(() -> ApiResponse.fail("demo user not found"));
    }

    @PostMapping("/follow")
    public ApiResponse<Map<String, Object>> follow(@RequestBody Map<String, Object> request) {
        return ApiResponse.ok(Map.of("followedUserId", request.getOrDefault("targetUserId", 0), "followed", true));
    }

    @DeleteMapping("/follow")
    public ApiResponse<Map<String, Object>> unfollow(@RequestBody Map<String, Object> request) {
        return ApiResponse.ok(Map.of("followedUserId", request.getOrDefault("targetUserId", 0), "followed", false));
    }

    @GetMapping("/admin/overview")
    public ApiResponse<Map<String, Object>> adminOverview() {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("module", "用户账号管理");
        overview.put("totalUsers", 1);
        overview.put("activeUsers", 1);
        overview.put("pendingAudits", 0);
        overview.put("riskUsers", 0);
        overview.put("capabilities", java.util.List.of("资料审核", "账号状态管理", "关注关系查看", "个人内容追踪"));
        return ApiResponse.ok(overview);
    }

    @PostMapping("/admin/status")
    public ApiResponse<Map<String, Object>> updateUserStatus(@RequestBody Map<String, Object> request) {
        return ApiResponse.ok(Map.of(
                "userId", request.getOrDefault("userId", 0),
                "status", request.getOrDefault("status", "ACTIVE"),
                "updated", true
        ));
    }

    private Map<String, Object> toView(UserEntity user) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", user.getId());
        view.put("username", user.getUsername());
        view.put("nickname", user.getNickname());
        view.put("avatarUrl", user.getAvatarUrl());
        view.put("signature", user.getSignature());
        view.put("status", user.getStatus());
        return view;
    }
}
