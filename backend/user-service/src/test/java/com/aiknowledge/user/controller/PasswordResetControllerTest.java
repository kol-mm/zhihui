package com.aiknowledge.user.controller;

import com.aiknowledge.user.security.CaptchaTestSupport;
import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.user.entity.UserEntity;
import com.aiknowledge.user.security.CaptchaService;
import com.aiknowledge.user.security.LoginAttemptGuard;
import com.aiknowledge.user.security.TokenRevocations;
import com.aiknowledge.user.store.InMemoryPasswordResetStore;
import com.aiknowledge.user.store.InMemoryUserStore;
import com.aiknowledge.user.store.PasswordResetStore;
import com.aiknowledge.user.store.UserStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordResetControllerTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/reset-" + System.nanoTime());
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-17T08:00:00Z");

        void advance(Duration step) { now = now.plus(step); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class RecordingRevocations implements TokenRevocations {
        private final List<Long> users = new ArrayList<>();

        @Override public void revokeToken(String tokenId, long expiresAtEpochSecond) { }
        @Override public void revokeUser(long userId) { users.add(userId); }
        @Override public boolean isRevoked(LocalAuth.TokenInfo token) { return false; }
    }

    private static final String INVALID = "重置码无效或已过期，请联系管理员重新签发";
    private static final String ADMIN = "Bearer " + LocalAuth.issueToken("admin", 2L, "ADMIN");

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final UserStore users = new InMemoryUserStore(encoder);
    private final PasswordResetStore resets = new InMemoryPasswordResetStore();
    private final CaptchaService captcha = CaptchaTestSupport.predictable();
    private final LoginAttemptGuard guard = new LoginAttemptGuard();
    private final RecordingRevocations revocations = new RecordingRevocations();
    private final MutableClock clock = new MutableClock();
    private final PasswordResetController controller =
            new PasswordResetController(users, resets, encoder, captcha, guard, revocations, clock);

    @Test
    void askingAnswersTheSameWhetherOrNotTheAccountExists() {
        UserEntity member = member();
        ApiResponse<Map<String, Object>> unknown = controller.requestReset(form("username", "nobody-" + System.nanoTime()), CaptchaTestSupport.CLIENT);
        ApiResponse<Map<String, Object>> known = controller.requestReset(form("username", member.getUsername(), "contact", "微信 abc"), CaptchaTestSupport.CLIENT);
        assertEquals(unknown, known);
        assertEquals(1, resets.count(PasswordResetStore.PENDING));

        // Asking again keeps a single request and shows the newest contact note.
        controller.requestReset(form("username", member.getUsername(), "contact", "QQ 123"), CaptchaTestSupport.CLIENT);
        assertEquals(1, resets.count(null));
        assertEquals("QQ 123", resets.findOpen(member.getId()).orElseThrow().contact());

        assertEquals("请输入用户名", controller.requestReset(form("username", " "), CaptchaTestSupport.CLIENT).message());
        assertEquals("联系方式不能超过 100 个字符",
                controller.requestReset(form("username", member.getUsername(), "contact", "x".repeat(101)), CaptchaTestSupport.CLIENT).message());
        Map<String, String> noCaptcha = new HashMap<>(Map.of("username", member.getUsername()));
        assertEquals(500, controller.requestReset(noCaptcha, CaptchaTestSupport.CLIENT).code());
    }

    @Test
    void anIssuedCodeResetsThePasswordExactlyOnce() {
        UserEntity member = member();
        controller.requestReset(form("username", member.getUsername()), CaptchaTestSupport.CLIENT);
        Map<String, Object> page = controller.adminResetPage(ADMIN, null, null, 20).data();
        assertEquals(1L, page.get("pending"));
        Map<?, ?> item = (Map<?, ?>) ((List<?>) page.get("items")).get(0);
        assertEquals(member.getUsername(), item.get("username"));
        assertFalse(item.containsKey("codeHash"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        Map<String, Object> issued = controller.issueCode(ADMIN, Map.of("requestId", item.get("id")), response).data();
        String code = String.valueOf(issued.get("code"));
        assertTrue(code.matches("[A-HJKMNP-Z2-9]{4}-[A-HJKMNP-Z2-9]{4}-[A-HJKMNP-Z2-9]{4}"), code);
        assertEquals("no-store", response.getHeader("Cache-Control"));

        // Typed in lower case without dashes still counts.
        String typed = code.replace("-", "").toLowerCase();
        assertEquals(0, controller.completeReset(form("username", member.getUsername(), "code", typed, "newPassword", "fresh-pass9"), CaptchaTestSupport.CLIENT).code());
        assertTrue(encoder.matches("fresh-pass9", users.findById(member.getId()).orElseThrow().getPasswordHash()));
        assertEquals(List.of(member.getId()), revocations.users);
        assertEquals(PasswordResetStore.COMPLETED, resets.find(((Number) item.get("id")).longValue()).orElseThrow().status());

        assertEquals(INVALID, controller.completeReset(form("username", member.getUsername(), "code", code, "newPassword", "other-pass9"), CaptchaTestSupport.CLIENT).message());
        assertEquals("该重置申请已处理完毕", controller.issueCode(ADMIN, Map.of("requestId", item.get("id")), null).message());
    }

    /** Guesses at the old password stop mattering once it is replaced — from the guesser's address as well. */
    @Test
    void aCompletedResetLiftsEveryLockOnTheAccount() {
        UserEntity member = member();
        for (int i = 0; i < 20; i++) guard.recordFailure(member.getUsername(), "203.0.113." + (i / 5));
        assertTrue(guard.lockedForSeconds(member.getUsername(), "198.51.100.20") > 0);

        controller.requestReset(form("username", member.getUsername()), CaptchaTestSupport.CLIENT);
        Map<?, ?> item = (Map<?, ?>) ((List<?>) controller.adminResetPage(ADMIN, null, null, 20).data().get("items")).get(0);
        String code = String.valueOf(controller.issueCode(ADMIN, Map.of("requestId", item.get("id")), new MockHttpServletResponse())
                .data().get("code"));
        assertEquals(0, controller.completeReset(form("username", member.getUsername(), "code", code, "newPassword", "fresh-pass9"),
                CaptchaTestSupport.CLIENT).code());

        assertEquals(0, guard.lockedForSeconds(member.getUsername(), "198.51.100.20"));
        assertEquals(0, guard.lockedForSeconds(member.getUsername(), "203.0.113.0"));
    }

    @Test
    void fiveWrongCodesEndTheRequestUntilAnAdminIssuesANewOne() {
        UserEntity member = member();
        controller.requestReset(form("username", member.getUsername()), CaptchaTestSupport.CLIENT);
        long requestId = resets.findOpen(member.getId()).orElseThrow().id();
        String code = String.valueOf(controller.issueCode(ADMIN, Map.of("requestId", requestId), null).data().get("code"));

        for (int attempt = 0; attempt < PasswordResetController.MAX_CODE_ATTEMPTS; attempt++) {
            assertEquals(INVALID, controller.completeReset(form("username", member.getUsername(), "code", "AAAA-AAAA-AAAA", "newPassword", "fresh-pass9"), CaptchaTestSupport.CLIENT).message());
        }
        assertEquals(PasswordResetStore.EXPIRED, resets.find(requestId).orElseThrow().status());
        assertEquals(INVALID, controller.completeReset(form("username", member.getUsername(), "code", code, "newPassword", "fresh-pass9"), CaptchaTestSupport.CLIENT).message());

        String second = String.valueOf(controller.issueCode(ADMIN, Map.of("requestId", requestId), null).data().get("code"));
        assertEquals(INVALID, controller.completeReset(form("username", member.getUsername(), "code", code, "newPassword", "fresh-pass9"), CaptchaTestSupport.CLIENT).message());
        assertEquals(0, controller.completeReset(form("username", member.getUsername(), "code", second, "newPassword", "fresh-pass9"), CaptchaTestSupport.CLIENT).code());
    }

    @Test
    void codesStopWorkingAfterThirtyMinutes() {
        UserEntity member = member();
        controller.requestReset(form("username", member.getUsername()), CaptchaTestSupport.CLIENT);
        long requestId = resets.findOpen(member.getId()).orElseThrow().id();
        String code = String.valueOf(controller.issueCode(ADMIN, Map.of("requestId", requestId), null).data().get("code"));

        clock.advance(PasswordResetController.CODE_LIFETIME);
        assertEquals(INVALID, controller.completeReset(form("username", member.getUsername(), "code", code, "newPassword", "fresh-pass9"), CaptchaTestSupport.CLIENT).message());
        Map<?, ?> item = (Map<?, ?>) ((List<?>) controller.adminResetPage(ADMIN, "ISSUED", null, 20).data().get("items")).get(0);
        assertEquals(true, item.get("codeExpired"));
        assertTrue(revocations.users.isEmpty());
    }

    @Test
    void theNewPasswordFollowsTheUsualRulesAndKeepsTheCode() {
        UserEntity member = member();
        controller.requestReset(form("username", member.getUsername()), CaptchaTestSupport.CLIENT);
        long requestId = resets.findOpen(member.getId()).orElseThrow().id();
        String code = String.valueOf(controller.issueCode(ADMIN, Map.of("requestId", requestId), null).data().get("code"));

        assertEquals("密码须同时包含字母和数字",
                controller.completeReset(form("username", member.getUsername(), "code", code, "newPassword", "onlyletters"), CaptchaTestSupport.CLIENT).message());
        assertEquals("请输入重置码和新密码",
                controller.completeReset(form("username", member.getUsername(), "code", "", "newPassword", "fresh-pass9"), CaptchaTestSupport.CLIENT).message());
        assertEquals(0, resets.find(requestId).orElseThrow().failedAttempts());
        assertEquals(0, controller.completeReset(form("username", member.getUsername(), "code", code, "newPassword", "fresh-pass9"), CaptchaTestSupport.CLIENT).code());
    }

    @Test
    void onlyAdminsHandleRequestsAndSuspendedAccountsGetNoCode() {
        UserEntity member = member();
        String memberAuth = "Bearer " + LocalAuth.issueToken(member.getUsername(), member.getId(), "USER");
        controller.requestReset(form("username", member.getUsername()), CaptchaTestSupport.CLIENT);
        long requestId = resets.findOpen(member.getId()).orElseThrow().id();

        assertEquals(500, controller.adminResetPage(memberAuth, null, null, 20).code());
        assertEquals(500, controller.issueCode(memberAuth, Map.of("requestId", requestId), null).code());
        assertEquals(500, controller.closeRequest(memberAuth, Map.of("requestId", requestId)).code());
        assertEquals("重置申请不存在", controller.issueCode(ADMIN, Map.of("requestId", 999_999), null).message());

        users.updateStatus(member.getId(), "DISABLED");
        assertEquals("该账号已停用，无法重置密码", controller.issueCode(ADMIN, Map.of("requestId", requestId), null).message());
        users.updateStatus(member.getId(), "ACTIVE");

        Map<String, Object> closed = controller.closeRequest(ADMIN, Map.of("requestId", requestId, "note", "本人已找回")).data();
        assertEquals("CLOSED", closed.get("status"));
        assertEquals("本人已找回", closed.get("note"));
        assertEquals("该重置申请已处理完毕", controller.closeRequest(ADMIN, Map.of("requestId", requestId)).message());
        assertEquals("该重置申请已处理完毕", controller.issueCode(ADMIN, Map.of("requestId", requestId), null).message());
    }

    private UserEntity member() {
        UserEntity user = new UserEntity();
        user.setUsername("reset-" + System.nanoTime());
        user.setPasswordHash(encoder.encode("old-pass1"));
        user.setNickname("找回测试");
        user.setStatus("ACTIVE");
        user.setRole("USER");
        return users.save(user);
    }

    /** Form fields plus a solved captcha. */
    private Map<String, String> form(String... fields) {
        Map<String, String> request = new HashMap<>();
        for (int index = 0; index < fields.length; index += 2) request.put(fields[index], fields[index + 1]);
        Map<String, Object> challenge = captcha.issue(CaptchaTestSupport.CLIENT);
        request.put("captchaId", String.valueOf(challenge.get("captchaId")));
        request.put("captchaAnswer", CaptchaTestSupport.ANSWER);
        return request;
    }
}
