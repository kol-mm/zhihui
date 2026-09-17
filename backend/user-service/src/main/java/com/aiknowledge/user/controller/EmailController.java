package com.aiknowledge.user.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.common.PlatformConfigClient;
import com.aiknowledge.user.entity.UserEntity;
import com.aiknowledge.user.security.EmailSender;
import com.aiknowledge.user.store.EmailVerificationStore;
import com.aiknowledge.user.store.EmailVerificationStore.PendingCode;
import com.aiknowledge.user.store.UserStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A member's own email address. Binding is enough to use it; verification is optional and needs a configured mail
 * server: a six-digit code is mailed to the address and entered back here.
 */
@RestController
@RequestMapping("/user/email")
public class EmailController {
    private static final Logger log = LoggerFactory.getLogger(EmailController.class);
    static final Duration CODE_LIFETIME = Duration.ofMinutes(15);
    static final Duration RESEND_AFTER = Duration.ofSeconds(60);
    static final int MAX_CODE_ATTEMPTS = 5;
    static final int MAX_CODES_PER_DAY = 10;
    private static final int MAX_EMAIL_LENGTH = 254;
    // Deliberately plain: a local part, one @ and a dotted domain, no spaces.
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,64}@[^\\s@]+\\.[^\\s@.]{2,}$");

    private final UserStore userStore;
    private final EmailVerificationStore codes;
    private final EmailSender mail;
    private final PasswordEncoder passwordEncoder;
    private final PlatformConfigClient platformConfig;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public EmailController(UserStore userStore, EmailVerificationStore codes, EmailSender mail, PasswordEncoder passwordEncoder,
                           ObjectProvider<PlatformConfigClient> platformConfig) {
        this(userStore, codes, mail, passwordEncoder, platformConfig.getIfAvailable(), Clock.systemDefaultZone());
    }

    EmailController(UserStore userStore, EmailVerificationStore codes, EmailSender mail, PasswordEncoder passwordEncoder,
                    PlatformConfigClient platformConfig, Clock clock) {
        this.userStore = userStore;
        this.codes = codes;
        this.mail = mail;
        this.passwordEncoder = passwordEncoder;
        this.platformConfig = platformConfig;
        this.clock = clock;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> status(@RequestHeader(name = "Authorization", required = false) String authorization) {
        UserEntity user = currentUser(authorization);
        if (user == null) return ApiResponse.fail("valid user authorization is required");
        return ApiResponse.ok(view(user));
    }

    /** Binds or changes the address. A changed address starts unverified. */
    @PostMapping
    public ApiResponse<Map<String, Object>> bind(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        UserEntity user = currentUser(authorization);
        if (user == null) return ApiResponse.fail("valid user authorization is required");
        String email = normalize(request.get("email"));
        if (email.isEmpty()) return ApiResponse.fail("email is required");
        if (email.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(email).matches()) return ApiResponse.fail("email address is invalid");
        if (email.equals(user.getEmail())) return ApiResponse.ok(view(user));
        codes.find(user.getId()).ifPresent(code -> retire(code, code.failedAttempts()));
        return userStore.updateEmail(user.getId(), email)
                .map(updated -> ApiResponse.ok(view(updated)))
                .orElseGet(() -> ApiResponse.fail("user not found"));
    }

    @DeleteMapping
    public ApiResponse<Map<String, Object>> unbind(@RequestHeader(name = "Authorization", required = false) String authorization) {
        UserEntity user = currentUser(authorization);
        if (user == null) return ApiResponse.fail("valid user authorization is required");
        codes.find(user.getId()).ifPresent(code -> retire(code, code.failedAttempts()));
        return userStore.updateEmail(user.getId(), null)
                .map(updated -> ApiResponse.ok(view(updated)))
                .orElseGet(() -> ApiResponse.fail("user not found"));
    }

    /** Mails a verification code to the bound address. */
    @PostMapping("/code")
    public ApiResponse<Map<String, Object>> sendCode(@RequestHeader(name = "Authorization", required = false) String authorization) {
        UserEntity user = currentUser(authorization);
        if (user == null) return ApiResponse.fail("valid user authorization is required");
        if (user.getEmail() == null) return ApiResponse.fail("bind an email address first");
        if (user.getEmailVerifiedAt() != null) return ApiResponse.fail("email is already verified");
        if (!mail.available()) return ApiResponse.fail("email verification is not available");
        LocalDateTime now = LocalDateTime.now(clock);
        PendingCode previous = codes.find(user.getId()).orElse(null);
        if (previous != null) {
            long wait = Duration.between(now, previous.sentAt().plus(RESEND_AFTER)).toSeconds();
            if (wait > 0) return ApiResponse.fail("请 " + wait + " 秒后再获取验证码");
        }
        LocalDate today = now.toLocalDate();
        int sentToday = previous != null && today.equals(previous.sentDay()) ? previous.sentCount() : 0;
        if (sentToday >= MAX_CODES_PER_DAY) return ApiResponse.fail("too many verification emails today");

        String code = String.format("%06d", random.nextInt(1_000_000));
        String site = platformConfig == null ? "知汇" : platformConfig.text("platform_name", "知汇");
        try {
            mail.send(user.getEmail(), "【" + site + "】邮箱验证码",
                    user.getNickname() + "，你好：\n\n你的邮箱验证码是 " + code + "，" + CODE_LIFETIME.toMinutes()
                            + " 分钟内有效。\n\n如果这不是你本人的操作，请忽略这封邮件。\n\n" + site);
        } catch (RuntimeException failed) {
            log.warn("Could not send the verification email for user {}: {}", user.getId(), failed.toString());
            return ApiResponse.fail("verification email could not be sent");
        }
        codes.save(new PendingCode(user.getId(), user.getEmail(), passwordEncoder.encode(code), now.plus(CODE_LIFETIME),
                0, now, today, sentToday + 1));
        return ApiResponse.ok(view(userStore.findById(user.getId()).orElse(user)));
    }

    @PostMapping("/verify")
    public ApiResponse<Map<String, Object>> verify(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        UserEntity user = currentUser(authorization);
        if (user == null) return ApiResponse.fail("valid user authorization is required");
        String code = Objects.toString(request.get("code"), "").replaceAll("\\s", "");
        if (!code.matches("\\d{6}")) return ApiResponse.fail("enter the six-digit code");
        if (user.getEmailVerifiedAt() != null) return ApiResponse.ok(view(user));
        PendingCode pending = codes.find(user.getId()).orElse(null);
        // A code only counts for the address it was sent to, before it expires.
        if (!usable(pending, user.getEmail(), LocalDateTime.now(clock))) {
            return ApiResponse.fail("verification code is invalid or expired");
        }
        if (!passwordEncoder.matches(code, pending.codeHash())) {
            codes.recordFailure(user.getId());
            if (pending.failedAttempts() + 1 >= MAX_CODE_ATTEMPTS) retire(pending, pending.failedAttempts() + 1);
            return ApiResponse.fail("verification code is invalid or expired");
        }
        retire(pending, pending.failedAttempts());
        try {
            return userStore.markEmailVerified(user.getId(), pending.email())
                    .map(updated -> ApiResponse.ok(view(updated)))
                    .orElseGet(() -> ApiResponse.fail("verification code is invalid or expired"));
        } catch (UserStore.EmailTakenException taken) {
            return ApiResponse.fail("email is already verified by another account");
        }
    }

    /**
     * Makes a code unusable but keeps its row, so the resend cooldown and the daily count still apply after the
     * address changes.
     */
    private void retire(PendingCode code, int failedAttempts) {
        // A whole second in the past: MySQL DATETIME rounds to the second, so "now" could still lie ahead.
        LocalDateTime expired = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS).minusSeconds(1);
        if (expired.isAfter(code.expiresAt())) expired = code.expiresAt();
        codes.save(new PendingCode(code.userId(), code.email(), code.codeHash(), expired, failedAttempts,
                code.sentAt(), code.sentDay(), code.sentCount()));
    }

    /** A code counts only for the address it was sent to, before it expires and before too many mistakes. */
    private static boolean usable(PendingCode code, String email, LocalDateTime now) {
        return code != null && Objects.equals(code.email(), email) && now.isBefore(code.expiresAt())
                && code.failedAttempts() < MAX_CODE_ATTEMPTS;
    }

    private Map<String, Object> view(UserEntity user) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("email", user.getEmail());
        view.put("verified", user.getEmailVerifiedAt() != null);
        view.put("verifiedAt", user.getEmailVerifiedAt());
        view.put("verificationAvailable", mail.available());
        PendingCode pending = user.getEmail() == null ? null : codes.find(user.getId())
                .filter(code -> Objects.equals(code.email(), user.getEmail()))
                .orElse(null);
        LocalDateTime now = LocalDateTime.now(clock);
        view.put("codeSent", usable(pending, user.getEmail(), now));
        view.put("resendAfterSeconds", pending == null ? 0
                : Math.max(0, Duration.between(now, pending.sentAt().plus(RESEND_AFTER)).toSeconds()));
        return view;
    }

    private UserEntity currentUser(String authorization) {
        Long userId = LocalAuth.userId(authorization);
        return userId == null ? null : userStore.findById(userId).orElse(null);
    }

    static String normalize(Object value) {
        return Objects.toString(value, "").trim().toLowerCase(Locale.ROOT);
    }
}
