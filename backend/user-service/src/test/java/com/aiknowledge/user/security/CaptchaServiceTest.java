package com.aiknowledge.user.security;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptchaServiceTest {
    @Test
    void publicChallengeUsesAnImageAndCanBeRecoveredDuringCooldown() {
        CaptchaService service = new CaptchaService();

        Map<String, Object> issued = service.issue("browser-client");
        assertNotNull(issued.get("captchaId"));
        assertTrue(String.valueOf(issued.get("image")).startsWith("data:image/png;base64,"));
        assertFalse(issued.containsKey("question"));
        assertEquals(10L, issued.get("refreshAfterSeconds"));

        Map<String, Object> recovered = service.issue("browser-client");
        assertEquals(true, recovered.get("cooldown"));
        assertEquals(issued.get("captchaId"), recovered.get("captchaId"));
        assertEquals(issued.get("image"), recovered.get("image"));
    }

    @Test
    void consumedChallengeAllowsAnImmediateReplacement() {
        CaptchaService service = new CaptchaService();
        Map<String, Object> issued = service.issue("browser-client");

        assertFalse(service.verify(String.valueOf(issued.get("captchaId")), "not-a-number", "browser-client"));

        Map<String, Object> replacement = service.issue("browser-client");
        assertFalse(replacement.containsKey("cooldown"));
        assertFalse(issued.get("captchaId").equals(replacement.get("captchaId")));
    }

    @Test
    void challengeCannotBeUsedByAnotherBrowser() {
        CaptchaService service = new CaptchaService();
        Map<String, Object> issued = service.issue("browser-a");

        assertFalse(service.verify(String.valueOf(issued.get("captchaId")), "0", "browser-b"));
        assertFalse(service.verify(String.valueOf(issued.get("captchaId")), "0", "browser-a"));
    }
}
