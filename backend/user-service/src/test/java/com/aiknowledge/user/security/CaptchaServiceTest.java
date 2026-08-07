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

        Map<String, Object> recovered = service.issue("browser-client");
        assertEquals(true, recovered.get("cooldown"));
        assertEquals(issued.get("captchaId"), recovered.get("captchaId"));
        assertEquals(issued.get("image"), recovered.get("image"));
    }
}
