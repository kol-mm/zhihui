package com.aiknowledge.user.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.user.entity.UserEntity;
import com.aiknowledge.user.security.EmailSender;
import com.aiknowledge.user.store.EmailVerificationStore;
import com.aiknowledge.user.store.InMemoryEmailVerificationStore;
import com.aiknowledge.user.store.InMemoryUserStore;
import com.aiknowledge.user.store.UserStore;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailControllerTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/email-" + System.nanoTime());
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-17T08:00:00Z");

        void advance(Duration step) { now = now.plus(step); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class Outbox implements EmailSender {
        record Mail(String to, String subject, String body) { }

        final List<Mail> sent = new ArrayList<>();
        boolean available = true;
        boolean failing;

        @Override public boolean available() { return available; }
        @Override public void send(String to, String subject, String body) {
            if (failing) throw new IllegalStateException("smtp down");
            sent.add(new Mail(to, subject, body));
        }

        String lastCode() {
            Matcher matcher = Pattern.compile("(\\d{6})").matcher(sent.get(sent.size() - 1).body());
            assertTrue(matcher.find());
            return matcher.group(1);
        }
    }

    private static final String INVALID_CODE = "验证码错误或已过期";

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final UserStore users = new InMemoryUserStore(encoder);
    private final EmailVerificationStore codes = new InMemoryEmailVerificationStore();
    private final Outbox outbox = new Outbox();
    private final MutableClock clock = new MutableClock();
    private final EmailController controller = new EmailController(users, codes, outbox, encoder, null, clock);

    @Test
    void anAddressCanBeBoundWithoutEverVerifyingIt() {
        String auth = auth(member());
        assertEquals(null, controller.status(auth).data().get("email"));

        Map<String, Object> bound = controller.bind(auth, Map.of("email", "  Reader@Example.COM ")).data();
        assertEquals("reader@example.com", bound.get("email"));
        assertEquals(false, bound.get("verified"));
        assertEquals(true, bound.get("verificationAvailable"));
        assertTrue(outbox.sent.isEmpty(), "binding alone sends nothing");

        assertEquals("邮箱地址格式不正确", controller.bind(auth, Map.of("email", "not-an-address")).message());
        assertEquals("邮箱地址格式不正确", controller.bind(auth, Map.of("email", "a b@example.com")).message());
        assertEquals("邮箱地址格式不正确", controller.bind(auth, Map.of("email", "x@" + "a".repeat(250) + ".com")).message());
        assertEquals("请输入邮箱地址", controller.bind(auth, Map.of("email", " ")).message());
        assertEquals("请先登录后再操作", controller.bind(null, Map.of("email", "x@example.com")).message());

        Map<String, Object> removed = controller.unbind(auth).data();
        assertNull(removed.get("email"));
        assertEquals(false, removed.get("verified"));
    }

    @Test
    void aMailedCodeVerifiesTheAddress() {
        UserEntity member = member();
        String auth = auth(member);
        assertEquals("请先绑定邮箱", controller.sendCode(auth).message());
        controller.bind(auth, Map.of("email", "reader@example.com"));

        Map<String, Object> sent = controller.sendCode(auth).data();
        assertEquals(true, sent.get("codeSent"));
        assertEquals(60L, ((Number) sent.get("resendAfterSeconds")).longValue());
        assertEquals("reader@example.com", outbox.sent.get(0).to());
        String code = outbox.lastCode();
        assertFalse(codes.find(member.getId()).orElseThrow().codeHash().contains(code), "only a hash is stored");

        assertEquals("请输入 6 位数字验证码", controller.verify(auth, Map.of("code", "12ab")).message());
        Map<String, Object> verified = controller.verify(auth, Map.of("code", code.substring(0, 3) + " " + code.substring(3))).data();
        assertEquals(true, verified.get("verified"));
        assertTrue(users.findById(member.getId()).orElseThrow().getEmailVerifiedAt() != null);
        assertEquals(false, verified.get("codeSent"), "a used code cannot be used again");
        assertEquals("邮箱已验证", controller.sendCode(auth).message());

        // Changing the address starts over as unverified.
        Map<String, Object> changed = controller.bind(auth, Map.of("email", "new@example.com")).data();
        assertEquals(false, changed.get("verified"));
        // Re-binding the same address changes nothing.
        controller.verify(auth, Map.of("code", "000000"));
        assertEquals("new@example.com", controller.bind(auth, Map.of("email", "NEW@example.com")).data().get("email"));
    }

    @Test
    void codesExpireAreThrottledAndStopAfterRepeatedMistakes() {
        UserEntity member = member();
        String auth = auth(member);
        controller.bind(auth, Map.of("email", "reader@example.com"));
        controller.sendCode(auth);
        assertTrue(controller.sendCode(auth).message().contains("秒后再获取验证码"));
        assertEquals(1, outbox.sent.size());

        clock.advance(Duration.ofMinutes(16));
        assertEquals(INVALID_CODE, controller.verify(auth, Map.of("code", outbox.lastCode())).message());

        controller.sendCode(auth);
        String code = outbox.lastCode();
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int attempt = 0; attempt < EmailController.MAX_CODE_ATTEMPTS; attempt++) {
            assertEquals(INVALID_CODE, controller.verify(auth, Map.of("code", wrong)).message());
        }
        // After five mistakes even the right code is refused until a new one is sent.
        assertEquals(false, controller.status(auth).data().get("codeSent"));
        assertEquals(INVALID_CODE, controller.verify(auth, Map.of("code", code)).message());

        // A code only counts for the address it was sent to.
        clock.advance(Duration.ofMinutes(2));
        controller.sendCode(auth);
        String forOld = outbox.lastCode();
        controller.bind(auth, Map.of("email", "other@example.com"));
        assertEquals(INVALID_CODE, controller.verify(auth, Map.of("code", forOld)).message());

        // At most ten mails a day.
        for (int i = 0; i < 12; i++) {
            clock.advance(Duration.ofMinutes(2));
            controller.sendCode(auth);
        }
        assertEquals("今天获取验证码的次数已达上限，请明天再试", controller.sendCode(auth).message());
        // Changing the address in between does not reset the count.
        assertEquals(EmailController.MAX_CODES_PER_DAY, outbox.sent.size());
        clock.advance(Duration.ofDays(1));
        assertEquals(0, controller.sendCode(auth).code());
    }

    @Test
    void withoutAMailServerVerificationIsUnavailableButBindingWorks() {
        String auth = auth(member());
        outbox.available = false;
        Map<String, Object> bound = controller.bind(auth, Map.of("email", "reader@example.com")).data();
        assertEquals(false, bound.get("verificationAvailable"));
        assertEquals("平台暂未开通邮件发送，邮箱暂时无法验证", controller.sendCode(auth).message());

        outbox.available = true;
        outbox.failing = true;
        assertEquals("验证邮件发送失败，请稍后重试", controller.sendCode(auth).message());
        outbox.failing = false;
        // A failed send does not start the cooldown.
        assertEquals(0, controller.sendCode(auth).code());
    }

    @Test
    void aVerifiedAddressBelongsToOneAccount() {
        String first = auth(member());
        UserEntity secondMember = member();
        String second = auth(secondMember);
        controller.bind(first, Map.of("email", "shared@example.com"));
        controller.bind(second, Map.of("email", "shared@example.com"));
        controller.sendCode(second);
        String secondCode = outbox.lastCode();
        controller.sendCode(first);
        assertEquals(0, controller.verify(first, Map.of("code", outbox.lastCode())).code());

        assertEquals("该邮箱已被其他账号验证，请更换邮箱", controller.verify(second, Map.of("code", secondCode)).message());
        assertFalse(users.findById(secondMember.getId()).orElseThrow().getEmailVerifiedAt() != null);
        // The address stays bound (unverified) and can still be used for the account.
        assertEquals("shared@example.com", controller.status(second).data().get("email"));
    }

    @Test
    void aCancelledCodeStaysCancelledWhenTheDatabaseRoundsTimes() {
        // MySQL DATETIME rounds to the nearest second, so a code cancelled at .700 would otherwise live until the next second.
        clock.advance(Duration.ofMillis(700));
        EmailVerificationStore rounding = new EmailVerificationStore() {
            @Override public java.util.Optional<PendingCode> find(Long userId) { return codes.find(userId); }
            @Override public void recordFailure(Long userId) { codes.recordFailure(userId); }
            @Override public void save(PendingCode code) {
                codes.save(new PendingCode(code.userId(), code.email(), code.codeHash(), round(code.expiresAt()),
                        code.failedAttempts(), round(code.sentAt()), code.sentDay(), code.sentCount()));
            }
            private java.time.LocalDateTime round(java.time.LocalDateTime time) {
                return time.plusNanos(500_000_000).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            }
        };
        EmailController roundingController = new EmailController(users, rounding, outbox, encoder, null, clock);
        String auth = auth(member());
        roundingController.bind(auth, Map.of("email", "rounding@example.com"));
        roundingController.sendCode(auth);
        String code = outbox.lastCode();
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int attempt = 0; attempt < EmailController.MAX_CODE_ATTEMPTS; attempt++) {
            roundingController.verify(auth, Map.of("code", wrong));
        }
        assertEquals(false, roundingController.status(auth).data().get("codeSent"));
        assertEquals(INVALID_CODE, roundingController.verify(auth, Map.of("code", code)).message());

        // The same holds for a code replaced by a new address.
        clock.advance(Duration.ofMinutes(2));
        roundingController.bind(auth, Map.of("email", "rounding@example.com"));
        roundingController.sendCode(auth);
        String fresh = outbox.lastCode();
        roundingController.bind(auth, Map.of("email", "elsewhere@example.com"));
        roundingController.bind(auth, Map.of("email", "rounding@example.com"));
        assertEquals(false, roundingController.status(auth).data().get("codeSent"));
        assertEquals(INVALID_CODE, roundingController.verify(auth, Map.of("code", fresh)).message());
    }

    private UserEntity member() {
        UserEntity user = new UserEntity();
        user.setUsername("email-" + System.nanoTime());
        user.setPasswordHash(encoder.encode("old-pass1"));
        user.setNickname("邮箱测试");
        user.setStatus("ACTIVE");
        user.setRole("USER");
        return users.save(user);
    }

    private static String auth(UserEntity user) {
        return "Bearer " + LocalAuth.issueToken(user.getUsername(), user.getId(), "USER");
    }
}
