package com.aiknowledge.user.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.user.security.CaptchaService;
import com.aiknowledge.user.security.CaptchaTestSupport;
import com.aiknowledge.user.security.LoginAttemptGuard;
import com.aiknowledge.user.security.TokenRevocations;
import com.aiknowledge.user.storage.UserAvatarStorageService;
import com.aiknowledge.user.store.InMemoryPasswordResetStore;
import com.aiknowledge.user.store.InMemoryUserStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The captcha endpoints as the page reaches them. Every challenge is tied to the key the page sent for it; the
 * old path for pages that sent none — challenges keyed by the connection's address and answers that skipped the
 * check — is gone.
 */
class CaptchaBindingTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/captcha-binding-" + System.nanoTime());
    }

    private static final String OTHER_BROWSER = "another-browser-0002";
    private static final String CAPTCHA_REFUSED = "验证码错误或已失效，请重新获取";

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final InMemoryUserStore users = new InMemoryUserStore(encoder);
    private final CaptchaService captcha = CaptchaTestSupport.predictable();
    private final UserController controller = new UserController(users, encoder,
            new UserAvatarStorageService("local", "target/test-user-avatars", "http://127.0.0.1:9000", "test", "test", "test"),
            captcha, null, new LoginAttemptGuard());
    private final PasswordResetController resets = new PasswordResetController(users, new InMemoryPasswordResetStore(),
            encoder, captcha, new LoginAttemptGuard(), TokenRevocations.inMemory());

    private static MockHttpServletRequest from(String clientKey) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.18.0.5");   // the gateway: every visitor arrives from here
        if (clientKey != null) request.addHeader(CaptchaService.CLIENT_HEADER, clientKey);
        return request;
    }

    private ApiResponse<Map<String, Object>> challenge(MockHttpServletRequest request) {
        return controller.captcha(request, new MockHttpServletResponse());
    }

    private Map<String, String> signIn(String captchaId, String... fields) {
        Map<String, String> form = new HashMap<>(Map.of("username", "demo", "password", "demo"));
        for (int index = 0; index < fields.length; index += 2) form.put(fields[index], fields[index + 1]);
        form.put("captchaId", captchaId);
        form.put("captchaAnswer", CaptchaTestSupport.ANSWER);
        return form;
    }

    private String issuedId(String clientKey) {
        ApiResponse<Map<String, Object>> issued = challenge(from(clientKey));
        assertEquals(0, issued.code(), issued.message());
        return String.valueOf(issued.data().get("captchaId"));
    }

    @Test
    void aPageThatSendsNoKeyOrAMalformedOneGetsNoChallenge() {
        for (String key : new String[]{null, "", "   ", "short", "has spaces in it!!", "x".repeat(129)}) {
            ApiResponse<Map<String, Object>> refused = challenge(from(key));
            assertEquals(500, refused.code(), String.valueOf(key));
            assertEquals(UserController.CAPTCHA_CLIENT_INVALID, refused.message());
            assertNull(refused.data());
        }
    }

    /** Behind the gateway every visitor has the same address, so it must never stand in for the page's key. */
    @Test
    void twoVisitorsBehindTheGatewayNeverShareAChallenge() {
        String first = issuedId(CaptchaTestSupport.CLIENT);
        String second = issuedId(OTHER_BROWSER);
        assertNotEquals(first, second);
    }

    @Test
    void signingInNeedsTheKeyTheChallengeWasIssuedFor() {
        String id = issuedId(CaptchaTestSupport.CLIENT);

        MockHttpServletResponse noKey = new MockHttpServletResponse();
        assertEquals(CAPTCHA_REFUSED, controller.loginRequest(signIn(id), from(null), noKey).message());
        assertNull(noKey.getHeader("Set-Cookie"));
        assertEquals(CAPTCHA_REFUSED, controller.loginRequest(signIn(id), from(OTHER_BROWSER), new MockHttpServletResponse()).message());
        assertEquals(CAPTCHA_REFUSED, controller.loginRequest(signIn(id), from("bad key"), new MockHttpServletResponse()).message());

        // None of those spent it: its own page can still sign in with it, once.
        MockHttpServletResponse signedIn = new MockHttpServletResponse();
        assertEquals(0, controller.loginRequest(signIn(id), from(CaptchaTestSupport.CLIENT), signedIn).code());
        assertFalse(signedIn.getHeader("Set-Cookie").isEmpty());
        assertEquals(CAPTCHA_REFUSED,
                controller.loginRequest(signIn(id), from(CaptchaTestSupport.CLIENT), new MockHttpServletResponse()).message());
    }

    @Test
    void registeringNeedsItToo() {
        String id = issuedId(CaptchaTestSupport.CLIENT);
        String username = "bound-" + (System.nanoTime() % 1_000_000);
        Map<String, String> form = signIn(id, "username", username, "password", "secret123");

        MockHttpServletResponse refused = new MockHttpServletResponse();
        assertEquals(CAPTCHA_REFUSED, controller.registerRequest(form, from(null), refused).message());
        assertNull(refused.getHeader("Set-Cookie"));
        assertFalse(users.findByUsername(username).isPresent());

        assertEquals(0, controller.registerRequest(form, from(CaptchaTestSupport.CLIENT), new MockHttpServletResponse()).code());
    }

    @Test
    void askingForAndCompletingAPasswordResetNeedItToo() {
        String id = issuedId(CaptchaTestSupport.CLIENT);
        Map<String, String> ask = signIn(id, "username", "demo");
        assertEquals(CAPTCHA_REFUSED, resets.requestResetRequest(ask, from(null)).message());
        assertEquals(CAPTCHA_REFUSED, resets.requestResetRequest(ask, from(OTHER_BROWSER)).message());
        assertEquals(0, resets.requestResetRequest(ask, from(CaptchaTestSupport.CLIENT)).code());

        String second = issuedId(CaptchaTestSupport.CLIENT);
        Map<String, String> complete = signIn(second, "username", "demo", "code", "AAAA-AAAA-AAAA", "newPassword", "fresh-pass9");
        assertEquals(CAPTCHA_REFUSED, resets.completeResetRequest(complete, from(null)).message());
    }
}
