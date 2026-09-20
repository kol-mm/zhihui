package com.aiknowledge.user.controller;

import com.aiknowledge.common.AdminAudit;
import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.user.entity.UserEntity;
import com.aiknowledge.user.security.CaptchaService;
import com.aiknowledge.user.security.LoginAttemptGuard;
import com.aiknowledge.user.security.PasswordRules;
import com.aiknowledge.user.security.TokenRevocations;
import com.aiknowledge.user.store.PasswordResetStore;
import com.aiknowledge.user.store.PasswordResetStore.ResetRequest;
import com.aiknowledge.user.store.UserStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Forgotten passwords. Accounts have no email or phone on file, so an admin hands out a one-time code: the member
 * asks from the login page, an admin issues a code in the console and passes it on outside the site, and the
 * member sets a new password with it. Using the code signs the account out everywhere.
 *
 * <p>The public endpoints answer the same way whether or not the account exists.
 */
@RestController
@RequestMapping("/user")
public class PasswordResetController {
    static final Duration CODE_LIFETIME = Duration.ofMinutes(30);
    static final int MAX_CODE_ATTEMPTS = 5;
    /** No 0/O, 1/I/L: the code is read out or typed by hand. */
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 12;
    private static final int PAGE_MAX = 100;
    private static final String INVALID_CODE = "reset code is invalid or expired";

    private final UserStore userStore;
    private final PasswordResetStore resetStore;
    private final PasswordEncoder passwordEncoder;
    private final CaptchaService captchaService;
    private final LoginAttemptGuard loginAttemptGuard;
    private final TokenRevocations tokenRevocations;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private volatile String timingPaddingHash;

    private AdminAudit audit = AdminAudit.NONE;

    @Autowired(required = false)
    public void setAdminAudit(AdminAudit audit) {
        this.audit = audit == null ? AdminAudit.NONE : audit;
    }

    @Autowired
    public PasswordResetController(UserStore userStore, PasswordResetStore resetStore, PasswordEncoder passwordEncoder,
                                   CaptchaService captchaService, LoginAttemptGuard loginAttemptGuard,
                                   TokenRevocations tokenRevocations) {
        this(userStore, resetStore, passwordEncoder, captchaService, loginAttemptGuard, tokenRevocations, Clock.systemDefaultZone());
    }

    PasswordResetController(UserStore userStore, PasswordResetStore resetStore, PasswordEncoder passwordEncoder,
                            CaptchaService captchaService, LoginAttemptGuard loginAttemptGuard,
                            TokenRevocations tokenRevocations, Clock clock) {
        this.userStore = userStore;
        this.resetStore = resetStore;
        this.passwordEncoder = passwordEncoder;
        this.captchaService = captchaService;
        this.loginAttemptGuard = loginAttemptGuard;
        this.tokenRevocations = tokenRevocations;
        this.clock = clock;
    }

    @PostMapping("/password-reset/request")
    public ApiResponse<Map<String, Object>> requestResetRequest(@RequestBody Map<String, String> request,
                                                                 HttpServletRequest servletRequest) {
        return requestReset(request, captchaClientKey(servletRequest));
    }

    public ApiResponse<Map<String, Object>> requestReset(Map<String, String> request) {
        return requestReset(request, null);
    }

    private ApiResponse<Map<String, Object>> requestReset(Map<String, String> request, String captchaClientKey) {
        String username = text(request, "username");
        String contact = text(request, "contact");
        if (username.isEmpty() || username.length() > 32) return ApiResponse.fail("username is required");
        if (contact.length() > 100) return ApiResponse.fail("contact must not exceed 100 characters");
        if (!verifyCaptcha(request, captchaClientKey)) return ApiResponse.fail("captcha is required or invalid; please obtain a new captcha");
        userStore.findByUsername(username)
                .filter(user -> "ACTIVE".equals(user.getStatus()))
                .ifPresent(user -> resetStore.findOpen(user.getId()).ifPresentOrElse(
                        // Asking again keeps one request per account; the admin sees the latest contact note.
                        open -> resetStore.refresh(open.id(), contact),
                        () -> resetStore.create(user.getId(), user.getUsername(), contact)));
        return ApiResponse.ok(Map.of("submitted", true));
    }

    @PostMapping("/password-reset/complete")
    public ApiResponse<Map<String, Object>> completeResetRequest(@RequestBody Map<String, String> request,
                                                                  HttpServletRequest servletRequest) {
        return completeReset(request, captchaClientKey(servletRequest));
    }

    public ApiResponse<Map<String, Object>> completeReset(Map<String, String> request) {
        return completeReset(request, null);
    }

    private ApiResponse<Map<String, Object>> completeReset(Map<String, String> request, String captchaClientKey) {
        String username = text(request, "username");
        String code = normalizeCode(text(request, "code"));
        String newPassword = request.getOrDefault("newPassword", "");
        if (username.isEmpty() || username.length() > 32) return ApiResponse.fail("username is required");
        if (code.isEmpty() || code.length() > 64 || newPassword.isEmpty()) return ApiResponse.fail("reset code and new password are required");
        if (!verifyCaptcha(request, captchaClientKey)) return ApiResponse.fail("captcha is required or invalid; please obtain a new captcha");
        String passwordProblem = PasswordRules.problem(newPassword, username);
        if (passwordProblem != null) return ApiResponse.fail(passwordProblem);

        UserEntity user = userStore.findByUsername(username).filter(found -> "ACTIVE".equals(found.getStatus())).orElse(null);
        ResetRequest open = user == null ? null : resetStore.findOpen(user.getId())
                .filter(found -> PasswordResetStore.ISSUED.equals(found.status()) && found.codeHash() != null)
                .orElse(null);
        if (open == null || codeExpired(open)) {
            // Same hash work as a real check, so timing does not tell which accounts have a live code.
            passwordEncoder.matches(code, timingPaddingHash());
            return ApiResponse.fail(INVALID_CODE);
        }
        if (!passwordEncoder.matches(code, open.codeHash())) {
            resetStore.recordFailure(open.id(), MAX_CODE_ATTEMPTS);
            return ApiResponse.fail(INVALID_CODE);
        }
        if (!resetStore.complete(open.id(), open.codeHash())) return ApiResponse.fail(INVALID_CODE);
        userStore.updatePassword(user.getId(), passwordEncoder.encode(newPassword));
        tokenRevocations.revokeUser(user.getId());
        loginAttemptGuard.recordSuccess(user.getUsername());
        return ApiResponse.ok(Map.of("reset", true));
    }

    /** One page of reset requests for the admin console, newest first, with the number still waiting. */
    @GetMapping("/admin/password-resets/page")
    public ApiResponse<Map<String, Object>> adminResetPage(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) Long cursor,
            @RequestParam(name = "limit", defaultValue = "20") int limit
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) return denied;
        if (cursor != null && cursor < 0) return ApiResponse.fail("cursor must not be negative");
        int size = Math.min(Math.max(limit, 1), PAGE_MAX);
        List<ResetRequest> found = resetStore.page(status, cursor, size + 1);
        boolean hasMore = found.size() > size;
        List<ResetRequest> page = hasMore ? found.subList(0, size) : found;
        Map<Long, UserEntity> users = userStore.findByIds(page.stream().map(ResetRequest::userId).distinct().toList())
                .stream().collect(Collectors.toMap(UserEntity::getId, Function.identity(), (left, right) -> left));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", page.stream().map(request -> view(request, users.get(request.userId()))).toList());
        result.put("nextCursor", page.isEmpty() ? null : page.get(page.size() - 1).id());
        result.put("hasMore", hasMore);
        result.put("total", cursor != null ? null : hasMore ? resetStore.count(status) : (long) page.size());
        result.put("pending", resetStore.count(PasswordResetStore.PENDING));
        return ApiResponse.ok(result);
    }

    /**
     * Issues a fresh code, replacing any earlier one. The code is only ever in this response; the store keeps a hash.
     */
    @PostMapping("/admin/password-reset/issue")
    public ApiResponse<Map<String, Object>> issueCode(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request,
            HttpServletResponse servletResponse
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) return denied;
        if (servletResponse != null) servletResponse.setHeader("Cache-Control", "no-store");
        ResetRequest existing = resetStore.find(number(request.get("requestId"))).orElse(null);
        if (existing == null) return ApiResponse.fail("reset request not found");
        if (PasswordResetStore.COMPLETED.equals(existing.status()) || PasswordResetStore.CLOSED.equals(existing.status())) {
            return ApiResponse.fail("reset request is already closed");
        }
        UserEntity user = userStore.findById(existing.userId()).orElse(null);
        if (user == null) return ApiResponse.fail("user not found");
        if (!"ACTIVE".equals(user.getStatus())) return ApiResponse.fail("cannot reset the password of a disabled account");
        String code = newCode();
        LocalDateTime expiresAt = LocalDateTime.now(clock).plus(CODE_LIFETIME);
        ResetRequest issued = resetStore.issue(existing.id(), passwordEncoder.encode(normalizeCode(code)), expiresAt,
                LocalAuth.userId(authorization)).orElse(null);
        if (issued == null) return ApiResponse.fail("reset request is already closed");
        // The code itself is never logged.
        audit.record(authorization, AdminAudit.Event.of("PASSWORD_RESET_ISSUE", AdminAudit.ACCOUNTS, "PASSWORD_RESET",
                issued.id(), UserController.userLabel(user, user.getId()), user.getId(),
                PasswordResetStore.ISSUED.equals(existing.status()) ? "重新签发密码重置码" : "签发密码重置码"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("expiresAt", expiresAt);
        // Server clocks run on UTC wall time; the page shows the deadline from this instead.
        result.put("expiresInSeconds", CODE_LIFETIME.toSeconds());
        result.put("request", view(issued, user));
        return ApiResponse.ok(result);
    }

    @PostMapping("/admin/password-reset/close")
    public ApiResponse<Map<String, Object>> closeRequest(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) return denied;
        Long requestId = number(request.get("requestId"));
        String note = String.valueOf(request.getOrDefault("note", "")).trim();
        if (note.length() > 255) note = note.substring(0, 255);
        ResetRequest existing = resetStore.find(requestId).orElse(null);
        if (existing == null) return ApiResponse.fail("reset request not found");
        String closeNote = note;
        return resetStore.close(requestId, LocalAuth.userId(authorization), note)
                .map(closed -> {
                    UserEntity owner = userStore.findById(closed.userId()).orElse(null);
                    audit.record(authorization, AdminAudit.Event.of("PASSWORD_RESET_CLOSE", AdminAudit.ACCOUNTS, "PASSWORD_RESET",
                                    closed.id(), owner == null ? "@" + closed.username() : UserController.userLabel(owner, closed.userId()),
                                    closed.userId(), "关闭密码重置申请")
                            .with("note", closeNote));
                    return ApiResponse.ok(view(closed, owner));
                })
                .orElseGet(() -> ApiResponse.fail("reset request is already closed"));
    }

    private Map<String, Object> view(ResetRequest request, UserEntity user) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", request.id());
        view.put("userId", request.userId());
        view.put("username", request.username());
        view.put("nickname", user == null ? request.username() : user.getNickname());
        view.put("accountStatus", user == null ? "DELETED" : user.getStatus());
        view.put("contact", request.contact() == null ? "" : request.contact());
        // The address bound to the account helps staff confirm the request came from its owner.
        view.put("email", user == null ? null : user.getEmail());
        view.put("emailVerified", user != null && user.getEmailVerifiedAt() != null);
        view.put("status", request.status());
        view.put("codeExpiresAt", request.codeExpiresAt());
        view.put("codeExpired", PasswordResetStore.ISSUED.equals(request.status()) && codeExpired(request));
        view.put("failedAttempts", request.failedAttempts());
        view.put("handledBy", request.handledBy());
        view.put("note", request.note() == null ? "" : request.note());
        view.put("createdAt", request.createdAt());
        view.put("updatedAt", request.updatedAt());
        return view;
    }

    private boolean codeExpired(ResetRequest request) {
        return request.codeExpiresAt() == null || !LocalDateTime.now(clock).isBefore(request.codeExpiresAt());
    }

    /** Twelve characters in groups of four, e.g. K7QX-M2PD-9RHT (about 59 bits). */
    private String newCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH + 2);
        for (int index = 0; index < CODE_LENGTH; index++) {
            if (index > 0 && index % 4 == 0) code.append('-');
            code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    /** Case, spaces and dashes do not matter when the member types the code. */
    static String normalizeCode(String code) {
        return code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    private String timingPaddingHash() {
        String hash = timingPaddingHash;
        if (hash == null) {
            hash = passwordEncoder.encode(UUID.randomUUID().toString());
            timingPaddingHash = hash;
        }
        return hash;
    }

    private boolean verifyCaptcha(Map<String, String> request, String clientKey) {
        return captchaService.verify(request.get("captchaId"), request.get("captchaAnswer"), clientKey);
    }

    private static String captchaClientKey(HttpServletRequest request) {
        String clientKey = request.getHeader("X-Captcha-Client");
        return clientKey == null || clientKey.isBlank() ? null : clientKey;
    }

    private static String text(Map<String, String> request, String key) {
        return Objects.toString(request.get(key), "").trim();
    }

    private static Long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? 0L : Long.valueOf(value.toString().trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
