package com.aiknowledge.user.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.AuditEntry;
import com.aiknowledge.user.security.CaptchaService;
import com.aiknowledge.user.security.CaptchaTestSupport;
import com.aiknowledge.user.security.LoginAttemptGuard;
import com.aiknowledge.user.storage.UserAvatarStorageService;
import com.aiknowledge.user.store.InMemoryUserStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sign-in through its endpoint, from the addresses the gateway vouches for. A stranger's wrong guesses lock the
 * stranger out, not the owner; many strangers together still close the account.
 */
class LoginLockoutTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/login-lockout-" + System.nanoTime());
    }

    private static final String WRONG = "用户名或密码错误";
    private static final String LOCKED = "登录失败次数过多";

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final CaptchaService captcha = CaptchaTestSupport.predictable();
    private final UserController controller = new UserController(new InMemoryUserStore(encoder), encoder,
            new UserAvatarStorageService("local", "target/test-user-avatars", "http://127.0.0.1:9000", "test", "test", "test"),
            captcha, null, new LoginAttemptGuard());

    /** A sign-in from this address, as the gateway forwards it: the page's captcha key and the vouched address. */
    private ApiResponse<Map<String, Object>> signIn(String address, String username, String password) {
        Map<String, Object> challenge = captcha.issue(CaptchaTestSupport.CLIENT);
        Map<String, String> form = new HashMap<>();
        form.put("username", username);
        form.put("password", password);
        form.put("captchaId", String.valueOf(challenge.get("captchaId")));
        form.put("captchaAnswer", CaptchaTestSupport.ANSWER);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.18.0.5");
        request.addHeader(CaptchaService.CLIENT_HEADER, CaptchaTestSupport.CLIENT);
        if (address != null) request.addHeader(AuditEntry.CLIENT_IP_HEADER, address);
        return controller.loginRequest(form, request, new MockHttpServletResponse());
    }

    @Test
    void aStrangersWrongGuessesLockTheStrangerOutButNotTheOwner() {
        for (int i = 0; i < 5; i++) assertEquals(WRONG, signIn("203.0.113.7", "demo", "guess-" + i).message());

        // Even the right password is refused from the stranger's address now...
        assertTrue(signIn("203.0.113.7", "demo", "demo").message().contains(LOCKED));
        // ...while the owner, elsewhere, signs in as usual. Before, the five guesses locked the account for all.
        assertEquals(0, signIn("198.51.100.20", "demo", "demo").code());
    }

    @Test
    void guessesFromManyAddressesCloseTheAccountForTheOwnerToo() {
        for (int address = 1; address <= 4; address++) {
            for (int i = 0; i < 5; i++) assertEquals(WRONG, signIn("203.0.113." + address, "demo", "guess-" + i).message());
        }
        assertTrue(signIn("198.51.100.20", "demo", "demo").message().contains(LOCKED));
        assertEquals(0, signIn("198.51.100.20", "admin", "admin123").code());
    }

    /**
     * The gateway replaces whatever the browser put in X-Client-Ip, so a guesser cannot pick a new address per
     * attempt. Without the gateway, as here with no header at all, the connection's own address is what counts.
     */
    @Test
    void withoutTheGatewaysHeaderTheConnectionAddressCounts() {
        for (int i = 0; i < 5; i++) assertEquals(WRONG, signIn(null, "demo", "guess-" + i).message());
        assertTrue(signIn(null, "demo", "demo").message().contains(LOCKED));
        assertEquals(0, signIn("198.51.100.20", "demo", "demo").code());
    }
}
