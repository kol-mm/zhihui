package com.aiknowledge.user.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.user.entity.UserEntity;
import com.aiknowledge.user.store.UserStore;
import com.aiknowledge.user.storage.UserAvatarStorageService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/user")
public class UserController {
    private final UserStore userStore;
    private final PasswordEncoder passwordEncoder;
    private final UserAvatarStorageService avatarStorage;

    @Autowired
    public UserController(UserStore userStore, PasswordEncoder passwordEncoder, UserAvatarStorageService avatarStorage) {
        this.userStore = userStore;
        this.passwordEncoder = passwordEncoder;
        this.avatarStorage = avatarStorage;
    }

    public UserController(UserStore userStore, PasswordEncoder passwordEncoder) {
        this(userStore, passwordEncoder, new UserAvatarStorageService("local", "../data/user-avatars",
                "http://127.0.0.1:9000", "ai-user-avatar", "aiknowledge", "ai-knowledge-local-change-me"));
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of("service", "user-service", "time", Instant.now().toString()));
    }

    @PostMapping(value = "/avatar/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> uploadAvatar(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam("file") MultipartFile file
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        try {
            String avatarUrl = avatarStorage.save(file.getBytes());
            return userStore.findById(userId)
                    .flatMap(user -> userStore.updateProfile(userId, user.getNickname(), avatarUrl, user.getSignature()))
                    .map(user -> ApiResponse.ok(toView(user)))
                    .orElseGet(() -> ApiResponse.fail("user not found"));
        } catch (IllegalArgumentException error) {
            return ApiResponse.fail(error.getMessage());
        } catch (Exception error) {
            return ApiResponse.fail("avatar upload failed: " + error.getMessage());
        }
    }

    @GetMapping("/avatar/{token}")
    public ResponseEntity<byte[]> avatar(@PathVariable String token) {
        UserAvatarStorageService.StoredAvatar avatar = avatarStorage.read(token);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(avatar.contentType()))
                .contentLength(avatar.bytes().length).body(avatar.bytes());
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
                "token", LocalAuth.issueToken(username, user.getId(), LocalAuth.roleForUsername(username)),
                "role", LocalAuth.roleForUsername(username),
                "user", toView(user)
        ));
    }

    @GetMapping("/session")
    public ApiResponse<Map<String, Object>> session(
            @RequestHeader(name = "Authorization", required = false) String authorization
    ) {
        if (!LocalAuth.isAuthenticated(authorization)) {
            return ApiResponse.fail("valid user authorization is required");
        }
        return ApiResponse.ok(LocalAuth.session(authorization));
    }

    @GetMapping("/info")
    public ApiResponse<Map<String, Object>> info(@RequestParam(name = "username", defaultValue = "demo") String username) {
        return userStore.findByUsername(username)
                .map(user -> ApiResponse.ok(toView(user)))
                .orElseGet(() -> ApiResponse.fail("user not found"));
    }

    @GetMapping("/directory")
    public ApiResponse<java.util.List<Map<String, Object>>> directory(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "keyword", defaultValue = "") String keyword
    ) {
        if (!LocalAuth.isAuthenticated(authorization)) return ApiResponse.fail("valid user authorization is required");
        String query = keyword.trim().toLowerCase();
        return ApiResponse.ok(userStore.listUsers().stream()
                .filter(user -> "ACTIVE".equals(user.getStatus()))
                .filter(user -> query.isBlank()
                        || user.getUsername().toLowerCase().contains(query)
                        || user.getNickname().toLowerCase().contains(query))
                .limit(20)
                .map(user -> Map.<String, Object>of(
                        "id", user.getId(),
                        "username", user.getUsername(),
                        "nickname", user.getNickname(),
                        "avatarUrl", user.getAvatarUrl() == null ? "" : user.getAvatarUrl()))
                .toList());
    }

    @PostMapping("/profile")
    public ApiResponse<Map<String, Object>> updateProfile(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        String nickname = String.valueOf(request.getOrDefault("nickname", ""));
        String avatarUrl = String.valueOf(request.getOrDefault("avatarUrl", ""));
        String signature = String.valueOf(request.getOrDefault("signature", ""));
        return userStore.updateProfile(userId, nickname, avatarUrl, signature)
                .map(user -> ApiResponse.ok(toView(user)))
                .orElseGet(() -> ApiResponse.fail("user not found"));
    }

    @PostMapping("/follow")
    public ApiResponse<Map<String, Object>> follow(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        Long targetUserId = number(request.get("targetUserId"), 0L);
        userStore.follow(userId, targetUserId);
        return ApiResponse.ok(Map.of("userId", userId, "followedUserId", targetUserId, "followed", true));
    }

    @DeleteMapping("/follow")
    public ApiResponse<Map<String, Object>> unfollow(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        Long targetUserId = number(request.get("targetUserId"), 0L);
        userStore.unfollow(userId, targetUserId);
        return ApiResponse.ok(Map.of("userId", userId, "followedUserId", targetUserId, "followed", false));
    }

    @GetMapping("/follows")
    public ApiResponse<Map<String, Object>> follows(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "userId", defaultValue = "1") Long userId
    ) {
        if (!LocalAuth.canAccessUser(authorization, userId)) return ApiResponse.fail("access to this user is denied");
        return ApiResponse.ok(Map.of(
                "userId", userId,
                "followedUserIds", userStore.listFollowTargets(userId),
                "followerUserIds", userStore.listFollowerIds(userId)
        ));
    }

    @PostMapping("/block")
    public ApiResponse<Map<String, Object>> block(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        Long targetUserId = number(request.get("targetUserId"), 0L);
        userStore.block(userId, targetUserId);
        return ApiResponse.ok(Map.of("userId", userId, "blockedUserId", targetUserId, "blocked", true));
    }

    @DeleteMapping("/block")
    public ApiResponse<Map<String, Object>> unblock(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        Long targetUserId = number(request.get("targetUserId"), 0L);
        userStore.unblock(userId, targetUserId);
        return ApiResponse.ok(Map.of("userId", userId, "blockedUserId", targetUserId, "blocked", false));
    }

    @GetMapping("/blocks")
    public ApiResponse<Map<String, Object>> blocks(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "userId", defaultValue = "1") Long userId
    ) {
        if (!LocalAuth.canAccessUser(authorization, userId)) return ApiResponse.fail("access to this user is denied");
        return ApiResponse.ok(Map.of("userId", userId, "blockedUserIds", userStore.listBlockedIds(userId)));
    }

    @PostMapping("/report")
    public ApiResponse<UserStore.UserReport> reportUser(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long reporterId = LocalAuth.userId(authorization);
        if (reporterId == null) return ApiResponse.fail("valid user authorization is required");
        return ApiResponse.ok(userStore.reportUser(
                reporterId,
                number(request.get("targetUserId"), 0L),
                String.valueOf(request.getOrDefault("reason", "未填写原因"))
        ));
    }

    @PostMapping("/behavior")
    public ApiResponse<UserStore.BehaviorRecord> recordBehavior(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        return ApiResponse.ok(userStore.recordBehavior(
                userId,
                String.valueOf(request.getOrDefault("action", "VIEW")),
                String.valueOf(request.getOrDefault("targetType", "UNKNOWN")),
                number(request.get("targetId"), 0L)
        ));
    }

    @GetMapping("/behaviors")
    public ApiResponse<java.util.List<UserStore.BehaviorRecord>> behaviors(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "userId", defaultValue = "1") Long userId
    ) {
        if (!LocalAuth.canAccessUser(authorization, userId)) return ApiResponse.fail("access to this user is denied");
        return ApiResponse.ok(userStore.listBehaviors(userId));
    }

    @GetMapping("/admin/overview")
    public ApiResponse<Map<String, Object>> adminOverview(@RequestHeader(name = "Authorization", required = false) String authorization) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) {
            return denied;
        }
        java.util.List<UserEntity> users = userStore.listUsers();
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("module", "用户账号管理");
        overview.put("totalUsers", users.size());
        overview.put("activeUsers", users.stream().filter(user -> "ACTIVE".equals(user.getStatus())).count());
        overview.put("pendingAudits", 0);
        overview.put("riskUsers", users.stream().filter(user -> !"ACTIVE".equals(user.getStatus())).count());
        overview.put("capabilities", java.util.List.of("资料审核", "账号状态管理", "关注关系查看", "个人内容追踪"));
        return ApiResponse.ok(overview);
    }

    @GetMapping("/admin/users")
    public ApiResponse<java.util.List<Map<String, Object>>> adminUsers(
            @RequestHeader(name = "Authorization", required = false) String authorization
    ) {
        if (!LocalAuth.isAdmin(authorization)) {
            return ApiResponse.fail("admin authorization is required");
        }
        return ApiResponse.ok(userStore.listUsers().stream().map(this::toView).toList());
    }

    @GetMapping("/admin/reports")
    public ApiResponse<java.util.List<UserStore.UserReport>> adminReports(
            @RequestHeader(name = "Authorization", required = false) String authorization
    ) {
        if (!LocalAuth.isAdmin(authorization)) return ApiResponse.fail("admin authorization is required");
        return ApiResponse.ok(userStore.listUserReports());
    }

    @PostMapping("/admin/report/resolve")
    public ApiResponse<UserStore.UserReport> resolveUserReport(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        if (!LocalAuth.isAdmin(authorization)) return ApiResponse.fail("admin authorization is required");
        return userStore.resolveUserReport(
                        number(request.get("reportId"), 0L),
                        String.valueOf(request.getOrDefault("status", "RESOLVED")),
                        String.valueOf(request.getOrDefault("result", "已处理")))
                .map(ApiResponse::ok).orElseGet(() -> ApiResponse.fail("report not found"));
    }

    @PostMapping("/admin/status")
    public ApiResponse<Map<String, Object>> updateUserStatus(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) {
            return denied;
        }
        Long userId = number(request.get("userId"), 0L);
        String status = String.valueOf(request.getOrDefault("status", "ACTIVE"));
        return userStore.updateStatus(userId, status)
                .map(user -> ApiResponse.ok(Map.of(
                        "user", toView(user),
                        "updated", true
                )))
                .orElseGet(() -> ApiResponse.fail("user not found"));
    }

    private Long number(Object value, Long fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(value.toString());
    }

    private Map<String, Object> toView(UserEntity user) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", user.getId());
        view.put("username", user.getUsername());
        view.put("nickname", user.getNickname());
        view.put("avatarUrl", user.getAvatarUrl());
        view.put("signature", user.getSignature());
        view.put("status", user.getStatus());
        view.put("role", LocalAuth.roleForUsername(user.getUsername()));
        return view;
    }
}
