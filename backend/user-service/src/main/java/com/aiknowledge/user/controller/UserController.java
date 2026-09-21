package com.aiknowledge.user.controller;

import com.aiknowledge.common.AdminAudit;
import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.common.PlatformConfigClient;
import com.aiknowledge.user.entity.UserEntity;
import com.aiknowledge.user.security.InternalAuth;
import com.aiknowledge.user.store.InMemoryProfileAuditStore;
import com.aiknowledge.user.store.ProfileAuditStore;
import com.aiknowledge.user.store.ProfileAuditStore.ProfileChange;
import com.aiknowledge.user.store.UserStore;
import com.aiknowledge.user.storage.UserAvatarStorageService;
import com.aiknowledge.user.security.CaptchaService;
import com.aiknowledge.user.security.LoginAttemptGuard;
import com.aiknowledge.user.security.NicknamePolicy;
import com.aiknowledge.user.security.PasswordRules;
import com.aiknowledge.user.security.SessionCookies;
import com.aiknowledge.user.security.TokenRevocations;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/user")
public class UserController {
    private final UserStore userStore;
    private final ProfileAuditStore profileAuditStore;
    private final PasswordEncoder passwordEncoder;
    private final UserAvatarStorageService avatarStorage;
    private final CaptchaService captchaService;
    private final PlatformConfigClient platformConfig;
    private final LoginAttemptGuard loginAttemptGuard;
    private final TokenRevocations tokenRevocations;
    private volatile String timingPaddingHash;
    private AdminAudit audit = AdminAudit.NONE;

    @Autowired(required = false)
    public void setAdminAudit(AdminAudit audit) {
        this.audit = audit == null ? AdminAudit.NONE : audit;
    }

    /** Names new accounts may not take, compared case-insensitively. Existing accounts are unaffected. */
    private static final java.util.Set<String> RESERVED_USERNAMES = java.util.Set.of(
            "admin", "administrator", "root", "system", "sysadmin", "superuser", "moderator", "support",
            "official", "service", "security", "null", "undefined");

    @Autowired
    public UserController(UserStore userStore, PasswordEncoder passwordEncoder, UserAvatarStorageService avatarStorage,
                          CaptchaService captchaService, PlatformConfigClient platformConfig, LoginAttemptGuard loginAttemptGuard,
                          TokenRevocations tokenRevocations, ProfileAuditStore profileAuditStore) {
        this.profileAuditStore = profileAuditStore;
        this.userStore = userStore;
        this.passwordEncoder = passwordEncoder;
        this.avatarStorage = avatarStorage;
        this.captchaService = captchaService;
        this.platformConfig = platformConfig;
        this.loginAttemptGuard = loginAttemptGuard;
        this.tokenRevocations = tokenRevocations;
    }

    public UserController(UserStore userStore, PasswordEncoder passwordEncoder, UserAvatarStorageService avatarStorage,
                          CaptchaService captchaService, PlatformConfigClient platformConfig, LoginAttemptGuard loginAttemptGuard) {
        this(userStore, passwordEncoder, avatarStorage, captchaService, platformConfig, loginAttemptGuard, TokenRevocations.inMemory());
    }

    public UserController(UserStore userStore, PasswordEncoder passwordEncoder, UserAvatarStorageService avatarStorage,
                          CaptchaService captchaService, PlatformConfigClient platformConfig, LoginAttemptGuard loginAttemptGuard,
                          TokenRevocations tokenRevocations) {
        this(userStore, passwordEncoder, avatarStorage, captchaService, platformConfig, loginAttemptGuard, tokenRevocations,
                new InMemoryProfileAuditStore());
    }

    public UserController(UserStore userStore, PasswordEncoder passwordEncoder, UserAvatarStorageService avatarStorage,
                          CaptchaService captchaService, PlatformConfigClient platformConfig) {
        this(userStore, passwordEncoder, avatarStorage, captchaService, platformConfig, new LoginAttemptGuard());
    }

    public UserController(UserStore userStore, PasswordEncoder passwordEncoder, UserAvatarStorageService avatarStorage) {
        this(userStore, passwordEncoder, avatarStorage, new CaptchaService(), null);
    }

    public UserController(UserStore userStore, PasswordEncoder passwordEncoder) {
        this(userStore, passwordEncoder, new UserAvatarStorageService("local", "../data/user-avatars",
                "http://127.0.0.1:9000", "ai-user-avatar", "aiknowledge", "ai-knowledge-local-change-me"));
    }

    public UserController(UserStore userStore, PasswordEncoder passwordEncoder, UserAvatarStorageService avatarStorage,
                          CaptchaService captchaService) {
        this(userStore, passwordEncoder, avatarStorage, captchaService, null);
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
                "service", "user-service",
                "time", Instant.now().toString(),
                "dataMode", storeMode(userStore),
                "avatarStorageMode", avatarStorage.mode()
        ));
    }

    @GetMapping("/captcha")
    public ApiResponse<Map<String, Object>> captcha(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        String clientKey = request.getHeader("X-Captcha-Client");
        if (clientKey == null || clientKey.isBlank()) clientKey = request.getRemoteAddr();
        return ApiResponse.ok(captchaService.issue(clientKey));
    }

    /** Backward-compatible helper for direct controller tests. */
    public ApiResponse<Map<String, Object>> captcha() {
        return ApiResponse.ok(captchaService.issue());
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
    public ApiResponse<Map<String, Object>> registerRequest(@RequestBody Map<String, String> request,
                                                             HttpServletRequest servletRequest,
                                                             HttpServletResponse servletResponse) {
        return startSession(register(request, captchaClientKey(servletRequest)), servletRequest, servletResponse);
    }

    public ApiResponse<Map<String, Object>> register(Map<String, String> request) {
        return register(request, null);
    }

    private ApiResponse<Map<String, Object>> register(Map<String, String> request, String captchaClientKey) {
        if (platformConfig != null && !platformConfig.enabled("registration_enabled", true)) {
            return ApiResponse.fail("平台当前未开放新用户注册");
        }
        String username = request.getOrDefault("username", "").trim();
        if (!username.matches("[A-Za-z0-9_-]{3,32}")) {
            return ApiResponse.fail("username must contain 3-32 letters, numbers, underscores or hyphens");
        }
        if (RESERVED_USERNAMES.contains(username.toLowerCase(java.util.Locale.ROOT))) {
            return ApiResponse.fail("this username is reserved");
        }
        String password = request.getOrDefault("password", "");
        String passwordProblem = PasswordRules.problem(password, username);
        if (passwordProblem != null) {
            return ApiResponse.fail(passwordProblem);
        }
        String nickname = request.getOrDefault("nickname", username).trim();
        if (nickname.length() > 64) {
            return ApiResponse.fail("nickname must not exceed 64 characters");
        }
        // A blank nickname shows the username instead, so that is what gets checked then.
        if (NicknamePolicy.impersonatesStaff(nickname.isBlank() ? username : nickname, platformName())) {
            return ApiResponse.fail(nickname.isBlank() || nickname.equals(username)
                    ? "this username is reserved" : "this nickname is reserved for platform staff");
        }
        if (!verifyCaptcha(request, captchaClientKey)) return ApiResponse.fail("captcha is required or invalid; please obtain a new captcha");
        if (userStore.findByUsername(username).isPresent()) {
            return ApiResponse.fail("username already exists");
        }

        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setNickname(nickname.isBlank() ? username : nickname);
        user.setStatus("ACTIVE");
        user.setRole("USER");
        String publishPolicy = platformConfig == null ? "STANDARD" : platformConfig.text("default_publish_policy", "STANDARD");
        user.setPublishPolicy(List.of("STANDARD", "PRE_REVIEW", "BLOCKED").contains(publishPolicy) ? publishPolicy : "STANDARD");
        user.setMessagingEnabled(true);
        UserEntity saved = userStore.save(user);
        return ApiResponse.ok(authResult(saved));
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> loginRequest(@RequestBody Map<String, String> request,
                                                          HttpServletRequest servletRequest,
                                                          HttpServletResponse servletResponse) {
        return startSession(login(request, captchaClientKey(servletRequest)), servletRequest, servletResponse);
    }

    public ApiResponse<Map<String, Object>> login(Map<String, String> request) {
        return login(request, null);
    }

    private ApiResponse<Map<String, Object>> login(Map<String, String> request, String captchaClientKey) {
        if (!verifyCaptcha(request, captchaClientKey)) return ApiResponse.fail("captcha is required or invalid; please obtain a new captcha");
        String username = request.getOrDefault("username", "").trim();
        String password = request.getOrDefault("password", "");
        if (username.isEmpty() || password.isEmpty()) return ApiResponse.fail("username and password are required");
        if (username.length() > 32 || password.length() > 128) return ApiResponse.fail("invalid username or password");
        long lockedSeconds = loginAttemptGuard.lockedForSeconds(username);
        if (lockedSeconds > 0) {
            return ApiResponse.fail("登录失败次数过多，请 " + Math.max(1, (lockedSeconds + 59) / 60) + " 分钟后再试");
        }
        UserEntity user = userStore.findByUsername(username).orElse(null);
        boolean matches;
        if (user == null) {
            // Unknown names still pay for one hash check, so response time does not reveal which accounts exist.
            passwordEncoder.matches(password, timingPaddingHash());
            matches = false;
        } else {
            matches = passwordEncoder.matches(password, user.getPasswordHash());
        }
        if (!matches) {
            loginAttemptGuard.recordFailure(username);
            return ApiResponse.fail("invalid username or password");
        }
        if (!"ACTIVE".equals(user.getStatus())) return ApiResponse.fail("user account is disabled");
        loginAttemptGuard.recordSuccess(username);
        return ApiResponse.ok(authResult(user));
    }

    @GetMapping("/internal/relation")
    public ApiResponse<Map<String, Object>> internalRelation(
            @RequestHeader(name = "X-Internal-Token", required = false) String token,
            @RequestParam Long userId,
            @RequestParam Long targetUserId
    ) {
        if (!InternalAuth.accepts(token)) return ApiResponse.fail("internal authorization is required");
        UserEntity source = userStore.findById(userId).orElse(null);
        UserEntity target = userStore.findById(targetUserId).orElse(null);
        boolean blocked = userStore.listBlockedIds(userId).contains(targetUserId)
                || userStore.listBlockedIds(targetUserId).contains(userId);
        boolean sourceActive = source != null && "ACTIVE".equals(source.getStatus());
        boolean targetActive = target != null && "ACTIVE".equals(target.getStatus());
        boolean messagingAllowed = sourceActive && targetActive && messagingEnabled(source) && messagingEnabled(target);
        String publishPolicy = publishPolicy(source);
        return ApiResponse.ok(Map.of(
                "blocked", blocked,
                "targetActive", targetActive,
                "allowed", messagingAllowed && !blocked,
                "publishAllowed", sourceActive && !"BLOCKED".equals(publishPolicy),
                "preAuditRequired", "PRE_REVIEW".equals(publishPolicy),
                "messagingAllowed", messagingAllowed
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

    /** Ends this browser session: the token is revoked at the gateway and the cookie is dropped. */
    @PostMapping("/logout")
    public ApiResponse<Map<String, Object>> logout(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        LocalAuth.TokenInfo token = LocalAuth.tokenInfo(authorization);
        if (token != null) tokenRevocations.revokeToken(token.tokenId(), token.expiresAt());
        SessionCookies.clear(servletRequest, servletResponse);
        return ApiResponse.ok(Map.of("loggedOut", true));
    }

    /**
     * Moves a session that an earlier version of the site kept in localStorage into the httpOnly cookie, so
     * members signed in before the switch stay signed in. The page deletes its copy afterwards.
     */
    @PostMapping("/session/adopt")
    public ApiResponse<Map<String, Object>> adoptSession(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        LocalAuth.TokenInfo token = LocalAuth.tokenInfo(authorization);
        if (token == null || tokenRevocations.isRevoked(token)) {
            return ApiResponse.fail("valid user authorization is required");
        }
        SessionCookies.write(servletRequest, servletResponse, authorization.trim().substring(7).trim(),
                java.time.Duration.ofSeconds(token.expiresAt() - Instant.now().getEpochSecond()));
        return ApiResponse.ok(LocalAuth.session(authorization));
    }

    /**
     * Looks a user up by username. Signed-in callers only: other members get the public card (and only for active
     * accounts), while the account itself and admins also see status, role and publishing settings.
     */
    @GetMapping("/info")
    public ApiResponse<Map<String, Object>> info(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "username", defaultValue = "") String username
    ) {
        if (!LocalAuth.isAuthenticated(authorization)) return ApiResponse.fail("valid user authorization is required");
        UserEntity user = userStore.findByUsername(username.trim()).orElse(null);
        if (user == null) return ApiResponse.fail("user not found");
        boolean fullView = LocalAuth.isAdmin(authorization) || user.getId().equals(LocalAuth.userId(authorization));
        if (fullView) return ApiResponse.ok(selfView(user));
        if (!"ACTIVE".equals(user.getStatus())) return ApiResponse.fail("user not found");
        Map<String, Object> card = toPublicSummary(user);
        card.remove("status");
        card.put("signature", user.getSignature());
        return ApiResponse.ok(card);
    }

    @GetMapping("/directory")
    public ApiResponse<java.util.List<Map<String, Object>>> directory(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "keyword", defaultValue = "") String keyword
    ) {
        if (!LocalAuth.isAuthenticated(authorization)) return ApiResponse.fail("valid user authorization is required");
        String query = keyword.trim().toLowerCase();
        if (query.isBlank()) return ApiResponse.ok(java.util.List.of());
        return ApiResponse.ok(userStore.findByUsername(query)
                .filter(user -> "ACTIVE".equals(user.getStatus()))
                .map(user -> java.util.List.of(Map.<String, Object>of(
                        "id", user.getId(),
                        "username", user.getUsername(),
                        "nickname", user.getNickname(),
                        "avatarUrl", user.getAvatarUrl() == null ? "" : user.getAvatarUrl())))
                .orElseGet(java.util.List::of));
    }

    @GetMapping("/summaries")
    public ApiResponse<java.util.List<Map<String, Object>>> summaries(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "ids", defaultValue = "") String ids
    ) {
        if (!LocalAuth.isAuthenticated(authorization)) return ApiResponse.fail("valid user authorization is required");
        try {
            java.util.List<Long> requestedIds = java.util.Arrays.stream(ids.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .map(Long::valueOf)
                    .filter(value -> value > 0)
                    .distinct()
                    .limit(100)
                    .toList();
            if (requestedIds.isEmpty()) return ApiResponse.ok(java.util.List.of());
            Map<Long, UserEntity> usersById = userStore.findByIds(requestedIds).stream()
                    .collect(java.util.stream.Collectors.toMap(UserEntity::getId, user -> user));
            return ApiResponse.ok(requestedIds.stream()
                    .map(usersById::get)
                    .filter(java.util.Objects::nonNull)
                    .map(this::toPublicSummary)
                    .toList());
        } catch (NumberFormatException error) {
            return ApiResponse.fail("user ids must be positive numbers");
        }
    }

    @PostMapping("/profile")
    public ApiResponse<Map<String, Object>> updateProfile(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        String nickname = String.valueOf(request.getOrDefault("nickname", "")).trim();
        String avatarUrl = String.valueOf(request.getOrDefault("avatarUrl", ""));
        String signature = String.valueOf(request.getOrDefault("signature", "")).trim();
        if (nickname.length() > 64 || signature.length() > 500 || avatarUrl.length() > 2000) {
            return ApiResponse.fail("profile fields exceed the allowed length");
        }
        UserEntity current = userStore.findById(userId).orElse(null);
        if (current == null) return ApiResponse.fail("user not found");
        // Only a changed nickname is checked, so members whose name predates the rule can still edit the rest.
        if (!LocalAuth.isAdmin(authorization) && !nickname.equals(current.getNickname())
                && NicknamePolicy.impersonatesStaff(nickname, platformName())) {
            return ApiResponse.fail("this nickname is reserved for platform staff");
        }
        if (!LocalAuth.isAdmin(authorization) && profileAuditRequired(current)) {
            return ApiResponse.ok(queueProfileChange(current, nickname, avatarUrl, signature));
        }
        return userStore.updateProfile(userId, nickname, avatarUrl, signature)
                .map(user -> ApiResponse.ok(selfView(user)))
                .orElseGet(() -> ApiResponse.fail("user not found"));
    }

    /**
     * Holds the nickname and signature for review. The avatar is not reviewed, so it is applied at once; a
     * member who only changed their picture never waits.
     */
    private Map<String, Object> queueProfileChange(UserEntity current, String nickname, String avatarUrl, String signature) {
        UserEntity member = current;
        if (!java.util.Objects.equals(avatarUrl, current.getAvatarUrl())) {
            member = userStore.updateProfile(current.getId(), current.getNickname(), avatarUrl, current.getSignature())
                    .orElse(current);
        }
        boolean unchanged = nickname.equals(member.getNickname())
                && java.util.Objects.equals(blankToNull(signature), blankToNull(member.getSignature()));
        if (!unchanged || profileAuditStore.findOpen(member.getId()).isPresent()) {
            profileAuditStore.submit(member.getId(), nickname, signature, member.getNickname(), member.getSignature());
        }
        return selfView(member);
    }

    /** Members edit their own profile behind review when the platform asks for it, or when they are pre-reviewed. */
    private boolean profileAuditRequired(UserEntity user) {
        if ("PRE_REVIEW".equals(publishPolicy(user))) return true;
        return platformConfig != null && platformConfig.enabled("profile_audit_required", false);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** The member's own view: what everyone sees, plus the state of a profile change they submitted. */
    private Map<String, Object> selfView(UserEntity user) {
        Map<String, Object> view = toView(user);
        view.put("profileAudit", profileAuditStore.findLatest(user.getId())
                .filter(change -> !ProfileAuditStore.APPROVED.equals(change.status()))
                .map(UserController::profileChangeView).orElse(null));
        return view;
    }

    private static Map<String, Object> profileChangeView(ProfileChange change) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", change.id());
        view.put("status", change.status());
        view.put("nickname", change.nickname());
        view.put("signature", change.signature());
        view.put("reason", change.reason());
        view.put("createdAt", change.createdAt());
        view.put("updatedAt", change.updatedAt());
        return view;
    }

    /** Changing the password signs out every other session; this one continues on a fresh cookie. */
    @PostMapping("/password")
    public ApiResponse<Map<String, Object>> changePasswordRequest(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, String> request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        return startSession(changePassword(authorization, request), servletRequest, servletResponse);
    }

    public ApiResponse<Map<String, Object>> changePassword(String authorization, Map<String, String> request) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        String current = request.getOrDefault("currentPassword", "");
        String next = request.getOrDefault("newPassword", "");
        UserEntity user = userStore.findById(userId).orElse(null);
        if (user == null || !passwordEncoder.matches(current, user.getPasswordHash())) {
            return ApiResponse.fail("current password is incorrect");
        }
        String passwordProblem = PasswordRules.problem(next, user.getUsername());
        if (passwordProblem != null) {
            return ApiResponse.fail(passwordProblem);
        }
        if (next.equals(current)) {
            return ApiResponse.fail("new password must differ from the current password");
        }
        userStore.updatePassword(userId, passwordEncoder.encode(next));
        tokenRevocations.revokeUser(userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated", true);
        // Issued after the revocation, so it stays valid (tokens issued earlier than that second are revoked).
        result.put("token", LocalAuth.issueToken(user.getUsername(), user.getId(), role(user)));
        return ApiResponse.ok(result);
    }

    @PostMapping("/follow")
    public ApiResponse<Map<String, Object>> follow(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        Long targetUserId = number(request.get("targetUserId"), 0L);
        if (userId.equals(targetUserId)) return ApiResponse.fail("cannot follow yourself");
        UserEntity target = userStore.findById(targetUserId).orElse(null);
        if (target == null || !"ACTIVE".equals(target.getStatus())) return ApiResponse.fail("user not found");
        if (isBlockedEitherDirection(userId, targetUserId)) return ApiResponse.fail("interaction with this user is blocked");
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
        if (userId.equals(targetUserId) || userStore.findById(targetUserId).isEmpty()) return ApiResponse.fail("user not found");
        userStore.block(userId, targetUserId);
        userStore.unfollow(targetUserId, userId);
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
        UserStore.UserTotals totals = userStore.userTotals();
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("module", "用户账号管理");
        overview.put("totalUsers", totals.total());
        overview.put("activeUsers", totals.active());
        overview.put("pendingAudits", profileAuditStore.countPending());
        overview.put("riskUsers", totals.risk());
        overview.put("admins", totals.admins());
        overview.put("reports", userStore.countUserReports());
        overview.put("openReports", userStore.countOpenUserReports());
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
        String status = String.valueOf(request.getOrDefault("status", "RESOLVED"));
        String result = String.valueOf(request.getOrDefault("result", "已处理"));
        return userStore.resolveUserReport(number(request.get("reportId"), 0L), status, result)
                .map(report -> {
                    UserEntity target = userStore.findById(report.targetUserId()).orElse(null);
                    audit.record(authorization, AdminAudit.Event.of("USER_REPORT_RESOLVE", AdminAudit.ACCOUNTS, "USER_REPORT",
                                    report.id(), userLabel(target, report.targetUserId()), report.targetUserId(),
                                    "处理用户举报：" + reportStatusLabel(report.status()))
                            .with("status", report.status()).with("result", report.result()));
                    return ApiResponse.ok(report);
                })
                .orElseGet(() -> ApiResponse.fail("report not found"));
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
        if (!ACCOUNT_STATUSES.contains(status)) return ApiResponse.fail("invalid account status");
        UserEntity before = userStore.findById(userId).orElse(null);
        if (before == null) return ApiResponse.fail("user not found");
        String selfProblem = selfChangeProblem(authorization, before, status, role(before));
        if (selfProblem != null) return ApiResponse.fail(selfProblem);
        String previousStatus = before.getStatus();
        return userStore.updateStatus(userId, status)
                .map(user -> {
                    // A suspended member is signed out everywhere at once, not when the token runs out.
                    if (!"ACTIVE".equals(user.getStatus())) tokenRevocations.revokeUser(userId);
                    if (!status.equals(previousStatus)) {
                        audit.record(authorization, AdminAudit.Event.of("USER_STATUS", AdminAudit.ACCOUNTS, "USER", userId,
                                        userLabel(user, userId), userId,
                                        "账号状态：" + statusLabel(previousStatus) + " → " + statusLabel(status))
                                .withChanges(new AdminAudit.Changes().add("status", "账号状态", previousStatus, status)));
                    }
                    return ApiResponse.ok(Map.<String, Object>of(
                            "user", toView(user),
                            "updated", true
                    ));
                })
                .orElseGet(() -> ApiResponse.fail("user not found"));
    }

    /** The review queue: newest first, filtered by state and by member or nickname. */
    @GetMapping("/admin/profile-changes/page")
    public ApiResponse<Map<String, Object>> adminProfileChangesPage(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) Long cursor,
            @RequestParam(name = "limit", defaultValue = "20") int limit
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) return denied;
        if (cursor != null && cursor < 0) return ApiResponse.fail("cursor must not be negative");
        if (status != null && !status.isBlank() && !PROFILE_CHANGE_STATUSES.contains(status)) {
            return ApiResponse.fail("invalid profile change status");
        }
        int size = Math.min(Math.max(limit, 1), ADMIN_PROFILE_CHANGE_PAGE_MAX);
        ProfileAuditStore.ChangeQuery query = new ProfileAuditStore.ChangeQuery(keyword, status, cursor, size + 1);
        List<ProfileChange> found = profileAuditStore.pageChanges(query);
        boolean hasMore = found.size() > size;
        List<ProfileChange> page = hasMore ? found.subList(0, size) : found;
        // One query for the members, so the queue can diff against what the account looks like right now.
        Map<Long, UserEntity> members = userStore.findByIds(page.stream().map(ProfileChange::userId).distinct().toList())
                .stream().collect(LinkedHashMap::new, (map, user) -> map.put(user.getId(), user), Map::putAll);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", page.stream().map(change -> profileChangeAdminView(change, members.get(change.userId()))).toList());
        result.put("nextCursor", hasMore ? page.get(page.size() - 1).id() : null);
        result.put("hasMore", hasMore);
        result.put("total", cursor != null ? null
                : hasMore ? profileAuditStore.countChanges(new ProfileAuditStore.ChangeQuery(keyword, status, null, Integer.MAX_VALUE))
                : (long) page.size());
        return ApiResponse.ok(result);
    }

    /** Approves or rejects one profile change; a rejection tells the member why. */
    @PostMapping("/admin/profile-change/audit")
    public ApiResponse<Map<String, Object>> auditProfileChange(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) return denied;
        Long changeId = number(request.get("changeId"), 0L);
        String status = String.valueOf(request.getOrDefault("status", ProfileAuditStore.APPROVED));
        if (!List.of(ProfileAuditStore.APPROVED, ProfileAuditStore.REJECTED).contains(status)) {
            return ApiResponse.fail("审核结果无效");
        }
        ProfileChange change = profileAuditStore.find(changeId).orElse(null);
        if (change == null) return ApiResponse.fail("资料修改申请不存在");
        if (status.equals(change.status())) {
            return ApiResponse.fail(ProfileAuditStore.APPROVED.equals(status) ? "该资料修改已经通过审核" : "该资料修改已经被驳回");
        }
        if (!ProfileAuditStore.PENDING.equals(change.status())) return ApiResponse.fail("当前状态不支持此审核操作");
        UserEntity member = userStore.findById(change.userId()).orElse(null);
        if (member == null) return ApiResponse.fail("user not found");
        String reason = String.valueOf(request.getOrDefault("reason", "")).trim();
        if (reason.length() > 200) reason = reason.substring(0, 200);
        if (ProfileAuditStore.REJECTED.equals(status) && reason.isBlank()) reason = "资料未通过审核";
        // The platform name can have changed since the member submitted, so the nickname rule runs again here.
        if (ProfileAuditStore.APPROVED.equals(status) && NicknamePolicy.impersonatesStaff(change.nickname(), platformName())) {
            return ApiResponse.fail("该昵称与平台工作人员重名，请驳回该申请");
        }
        if (ProfileAuditStore.APPROVED.equals(status)) {
            userStore.updateProfile(change.userId(), change.nickname(), member.getAvatarUrl(), change.signature());
        }
        ProfileChange decided = profileAuditStore.resolve(changeId, status, reason.isBlank() ? null : reason,
                LocalAuth.userId(authorization)).orElse(null);
        if (decided == null) return ApiResponse.fail("当前状态不支持此审核操作");
        UserEntity updated = userStore.findById(change.userId()).orElse(member);
        audit.record(authorization, AdminAudit.Event.of("USER_PROFILE_AUDIT", AdminAudit.ACCOUNTS, "USER_PROFILE_CHANGE",
                        changeId, userLabel(updated, change.userId()), change.userId(),
                        ProfileAuditStore.APPROVED.equals(status) ? "通过会员资料修改" : "驳回会员资料修改")
                .withChanges(new AdminAudit.Changes()
                        .add("nickname", "昵称", change.beforeNickname(), change.nickname())
                        .add("signature", "个人签名", change.beforeSignature(), change.signature()))
                .with("reason", decided.reason()));
        return ApiResponse.ok(profileChangeAdminView(decided, updated));
    }

    private static final List<String> PROFILE_CHANGE_STATUSES =
            List.of(ProfileAuditStore.PENDING, ProfileAuditStore.APPROVED, ProfileAuditStore.REJECTED);

    /**
     * One queue row. "before" is what the account looks like now rather than what it looked like at submission,
     * so an administrator decides against the current state; "stale" marks the two having drifted apart.
     */
    private static Map<String, Object> profileChangeAdminView(ProfileChange change, UserEntity member) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", change.id());
        view.put("userId", change.userId());
        view.put("username", member == null ? null : member.getUsername());
        view.put("status", change.status());
        view.put("reason", change.reason());
        view.put("createdAt", change.createdAt());
        view.put("updatedAt", change.updatedAt());
        String liveNickname = member == null ? change.beforeNickname() : member.getNickname();
        String liveSignature = member == null ? change.beforeSignature() : member.getSignature();
        view.put("before", fields(liveNickname, liveSignature));
        view.put("after", fields(change.nickname(), change.signature()));
        view.put("stale", !java.util.Objects.equals(liveNickname, change.beforeNickname())
                || !java.util.Objects.equals(liveSignature, change.beforeSignature()));
        return view;
    }

    private static Map<String, Object> fields(String nickname, String signature) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("nickname", nickname);
        values.put("signature", signature);
        return values;
    }

    @PostMapping("/admin/governance")
    public ApiResponse<Map<String, Object>> updateUserGovernance(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) return denied;
        Long userId = number(request.get("userId"), 0L);
        UserEntity user = userStore.findById(userId).orElse(null);
        if (user == null) return ApiResponse.fail("user not found");
        String role = String.valueOf(request.getOrDefault("role", role(user)));
        String status = String.valueOf(request.getOrDefault("status", user.getStatus()));
        String publishPolicy = String.valueOf(request.getOrDefault("publishPolicy", publishPolicy(user)));
        boolean messagingEnabled = !Boolean.FALSE.equals(request.getOrDefault("messagingEnabled", messagingEnabled(user)));
        if (!List.of("USER", "ADMIN").contains(role)) return ApiResponse.fail("invalid role");
        if (!ACCOUNT_STATUSES.contains(status)) return ApiResponse.fail("invalid account status");
        String selfProblem = selfChangeProblem(authorization, user, status, role);
        if (selfProblem != null) return ApiResponse.fail(selfProblem);
        if (!role.equals(role(user)) && !LocalAuth.isSuperAdmin(authorization)) {
            return ApiResponse.fail("只有超级管理员可以任命或撤销管理员");
        }
        // A super administrator is not an ordinary account: another administrator must not be able to demote,
        // disable or rename the one account that can appoint administrators.
        if (superAdmin(user) && !user.getId().equals(LocalAuth.userId(authorization))) {
            return ApiResponse.fail("超级管理员账号只能由本人修改");
        }
        if (!List.of("STANDARD", "PRE_REVIEW", "BLOCKED").contains(publishPolicy)) return ApiResponse.fail("invalid publish policy");
        String nickname = String.valueOf(request.getOrDefault("nickname", user.getNickname())).trim();
        String avatarUrl = String.valueOf(request.getOrDefault("avatarUrl", user.getAvatarUrl() == null ? "" : user.getAvatarUrl()));
        String signature = String.valueOf(request.getOrDefault("signature", user.getSignature() == null ? "" : user.getSignature())).trim();
        if (nickname.length() > 64 || signature.length() > 500 || avatarUrl.length() > 2000) return ApiResponse.fail("profile fields exceed the allowed length");
        String resetPassword = String.valueOf(request.getOrDefault("resetPassword", ""));
        // Checked before anything is written, so a rejected password does not leave half the changes applied.
        if (!resetPassword.isBlank()) {
            String passwordProblem = PasswordRules.problem(resetPassword, user.getUsername());
            if (passwordProblem != null) return ApiResponse.fail(passwordProblem);
        }
        boolean removeEmail = Boolean.TRUE.equals(request.get("removeEmail"));
        AdminAudit.Changes changes = new AdminAudit.Changes()
                .add("role", "角色", role(user), role)
                .add("status", "账号状态", user.getStatus(), status)
                .add("publishPolicy", "发帖策略", publishPolicy(user), publishPolicy)
                .add("messagingEnabled", "允许私信", messagingEnabled(user), messagingEnabled)
                .add("nickname", "昵称", user.getNickname(), nickname)
                .add("avatarUrl", "头像地址", user.getAvatarUrl(), avatarUrl)
                .add("signature", "个人签名", user.getSignature(), signature);
        if (!resetPassword.isBlank()) changes.addHidden("password", "登录密码");
        if (removeEmail && user.getEmail() != null) changes.add("email", "绑定邮箱", user.getEmail(), null);
        boolean signOut = !"ACTIVE".equals(status) || !role.equals(role(user)) || !resetPassword.isBlank();
        userStore.updateProfile(userId, nickname, avatarUrl, signature);
        userStore.updateStatus(userId, status);
        userStore.updateGovernance(userId, role, publishPolicy, messagingEnabled);
        if (!resetPassword.isBlank()) {
            userStore.updatePassword(userId, passwordEncoder.encode(resetPassword));
        }
        if (removeEmail && user.getEmail() != null) userStore.updateEmail(userId, null);
        // Tokens carry the role, and a suspension or new password should apply right away: end open sessions.
        if (signOut) tokenRevocations.revokeUser(userId);
        UserEntity updated = userStore.findById(userId).orElseThrow();
        if (!changes.isEmpty()) {
            audit.record(authorization, AdminAudit.Event.of("USER_GOVERNANCE", AdminAudit.ACCOUNTS, "USER", userId,
                    userLabel(updated, userId), userId, "修改了" + changes.labels()).withChanges(changes));
        }
        return ApiResponse.ok(toView(updated));
    }

    private static final List<String> ACCOUNT_STATUSES = List.of("ACTIVE", "DISABLED", "DELETED");

    /**
     * Administrators cannot suspend, delete or demote themselves: with a single administrator that would leave the
     * platform without one, and a mistaken click would sign them out for good.
     */
    private String selfChangeProblem(String authorization, UserEntity target, String status, String role) {
        if (!target.getId().equals(LocalAuth.userId(authorization))) return null;
        if (!"ACTIVE".equals(status)) return "administrators cannot disable their own account";
        if (!role.equals(role(target))) return "administrators cannot change their own role";
        return null;
    }

    static String userLabel(UserEntity user, Long fallbackId) {
        if (user == null) return "#" + fallbackId;
        return user.getNickname() + "（@" + user.getUsername() + "）";
    }

    private static String statusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "ACTIVE" -> "正常";
            case "DISABLED" -> "已停用";
            case "DELETED" -> "已删除";
            default -> String.valueOf(status);
        };
    }

    private static String reportStatusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "RESOLVED" -> "已处理";
            case "REJECTED" -> "已驳回";
            case "PENDING" -> "待处理";
            default -> String.valueOf(status);
        };
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
        view.put("role", role(user));
        view.put("superAdmin", superAdmin(user));
        view.put("publishPolicy", publishPolicy(user));
        view.put("messagingEnabled", messagingEnabled(user));
        // Only the member and administrators see this view; public cards never carry the address.
        view.put("email", user.getEmail());
        view.put("emailVerified", user.getEmailVerifiedAt() != null);
        view.put("emailVerifiedAt", user.getEmailVerifiedAt());
        return view;
    }

    private Map<String, Object> toPublicSummary(UserEntity user) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", user.getId());
        view.put("username", user.getUsername());
        view.put("nickname", user.getNickname());
        view.put("avatarUrl", user.getAvatarUrl() == null ? "" : user.getAvatarUrl());
        view.put("status", user.getStatus());
        return view;
    }

    /** Moves the token of a successful sign-in into the session cookie; the page never receives it. */
    private static ApiResponse<Map<String, Object>> startSession(ApiResponse<Map<String, Object>> result,
                                                                 HttpServletRequest servletRequest,
                                                                 HttpServletResponse servletResponse) {
        if (result.code() != 0 || result.data() == null) return result;
        Map<String, Object> body = new LinkedHashMap<>(result.data());
        Object token = body.remove("token");
        if (token != null) SessionCookies.write(servletRequest, servletResponse, token.toString());
        return ApiResponse.ok(body);
    }

    private String platformName() {
        return platformConfig == null ? "" : platformConfig.text("platform_name", "");
    }

    private String timingPaddingHash() {
        String hash = timingPaddingHash;
        if (hash == null) {
            hash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
            timingPaddingHash = hash;
        }
        return hash;
    }

    private boolean verifyCaptcha(Map<String, String> request, String clientKey) {
        return captchaService.verify(request.get("captchaId"), request.get("captchaAnswer"), clientKey);
    }

    private String captchaClientKey(HttpServletRequest request) {
        String clientKey = request.getHeader("X-Captcha-Client");
        // 兼容仍在浏览器缓存中的旧版前端：旧版登录请求没有此请求头。
        return clientKey == null || clientKey.isBlank() ? null : clientKey;
    }

    private boolean isBlockedEitherDirection(Long userId, Long targetUserId) {
        return userStore.listBlockedIds(userId).contains(targetUserId)
                || userStore.listBlockedIds(targetUserId).contains(userId);
    }

    private Map<String, Object> authResult(UserEntity user) {
        String role = role(user);
        return Map.of(
                "token", LocalAuth.issueToken(user.getUsername(), user.getId(), role, superAdmin(user)),
                "role", role,
                "user", toView(user)
        );
    }

    /** Only an administrator can be a super administrator; the flag alone grants nothing. */
    private boolean superAdmin(UserEntity user) {
        return user != null && Boolean.TRUE.equals(user.getSuperAdmin()) && "ADMIN".equals(role(user));
    }

    private String role(UserEntity user) {
        return user.getRole() == null || user.getRole().isBlank() ? LocalAuth.roleForUsername(user.getUsername()) : user.getRole();
    }

    private String storeMode(Object store) {
        return store.getClass().getSimpleName().startsWith("MySql") ? "mysql" : "local";
    }

    private String publishPolicy(UserEntity user) {
        return user == null || user.getPublishPolicy() == null || user.getPublishPolicy().isBlank() ? "STANDARD" : user.getPublishPolicy();
    }

    private boolean messagingEnabled(UserEntity user) {
        return user != null && !Boolean.FALSE.equals(user.getMessagingEnabled());
    }

    private static final int ADMIN_USER_PAGE_MAX = 100;
    private static final int ADMIN_PROFILE_CHANGE_PAGE_MAX = 100;

    /**
     * One page of the admin user table. The table used to download every account and filter in
     * the browser; the filters and the cursor are applied by the database here.
     */
    @GetMapping("/admin/users/page")
    public ApiResponse<Map<String, Object>> adminUsersPage(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "role", required = false) String role,
            @RequestParam(name = "userId", required = false) Long userId,
            @RequestParam(name = "cursor", required = false) Long cursor,
            @RequestParam(name = "limit", defaultValue = "20") int limit
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) {
            return denied;
        }
        if (cursor != null && cursor < 0) {
            return ApiResponse.fail("cursor must not be negative");
        }
        int size = Math.min(Math.max(limit, 1), ADMIN_USER_PAGE_MAX);
        UserStore.UserPageQuery query = new UserStore.UserPageQuery(keyword, status, role, userId, cursor, size + 1);
        java.util.List<UserEntity> found = userStore.pageUsers(query);
        boolean hasMore = found.size() > size;
        java.util.List<UserEntity> page = hasMore ? found.subList(0, size) : found;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", page.stream().map(this::toView).toList());
        result.put("nextCursor", page.isEmpty() ? null : page.get(page.size() - 1).getId());
        result.put("hasMore", hasMore);
        // Counting can touch most of the table, so only a first page that is full pays for it: a short first
        // page already is the whole result, and later pages send null.
        Long total = null;
        if (cursor == null) {
            total = hasMore ? userStore.countUsers(new UserStore.UserPageQuery(keyword, status, role, userId, null, size))
                    : page.size();
        }
        result.put("total", total);
        return ApiResponse.ok(result);
    }


    private static final int ADMIN_REPORT_PAGE_MAX = 100;

    /** One page of the report queue; replaces downloading every report for the moderation tab. */
    @GetMapping("/admin/reports/page")
    public ApiResponse<Map<String, Object>> adminReportsPage(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) Long cursor,
            @RequestParam(name = "limit", defaultValue = "20") int limit
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) return denied;
        if (cursor != null && cursor < 0) return ApiResponse.fail("cursor must not be negative");
        int size = Math.min(Math.max(limit, 1), ADMIN_REPORT_PAGE_MAX);
        java.util.List<UserStore.UserReport> found = userStore.pageUserReports(new UserStore.AdminReportQuery(keyword, status, cursor, size + 1));
        boolean hasMore = found.size() > size;
        java.util.List<UserStore.UserReport> page = hasMore ? found.subList(0, size) : found;
        Long total = null;
        if (cursor == null) {
            total = hasMore ? userStore.countUserReports(new UserStore.AdminReportQuery(keyword, status, null, size)) : page.size();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", page);
        result.put("nextCursor", page.isEmpty() ? null : page.get(page.size() - 1).id());
        result.put("hasMore", hasMore);
        result.put("total", total);
        return ApiResponse.ok(result);
    }

}
