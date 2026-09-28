package com.aiknowledge.user.security;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptchaServiceTest {
    private static final String BROWSER = "browser-aaaa-0001";
    private static final String OTHER_BROWSER = "browser-bbbb-0002";
    private static final String RIGHT = CaptchaTestSupport.ANSWER;

    private final CaptchaService service = CaptchaTestSupport.predictable();

    private String issue(String clientKey) {
        return String.valueOf(service.issue(clientKey).get("captchaId"));
    }

    @Test
    void aChallengeIsAPictureAndNeverTheQuestionOrTheAnswerInWords() {
        Map<String, Object> issued = new CaptchaService().issue(BROWSER);

        assertNotNull(issued.get("captchaId"));
        assertTrue(String.valueOf(issued.get("image")).startsWith("data:image/png;base64,"));
        assertEquals(Set.of("captchaId", "image", "expiresAt", "expiresInSeconds", "refreshAfterSeconds"), issued.keySet());
        assertEquals(10L, issued.get("refreshAfterSeconds"));
    }

    @Test
    void onlyAKeyOfTheKindThePageMakesIsAccepted() {
        for (String key : new String[]{null, "", " ", "short-key", " " + BROWSER, BROWSER + " ", "browser key with spaces",
                "browser/aaaa/0001", "浏览器浏览器浏览器浏览器浏览器浏览器", "a".repeat(129)}) {
            assertFalse(CaptchaService.isValidClientKey(key), String.valueOf(key));
            assertThrows(IllegalArgumentException.class, () -> service.issue(key), String.valueOf(key));
        }
        assertTrue(CaptchaService.isValidClientKey("a".repeat(16)));
        assertTrue(CaptchaService.isValidClientKey("a".repeat(128)));
        assertTrue(CaptchaService.isValidClientKey("0f8c2b7e-5d1a-4c3b-9e7f-2a6d4b8c1e0f"));
        assertTrue(CaptchaService.isValidClientKey("captcha-1727480000000-k3j9x2m1q8"));
    }

    @Test
    void theRightAnswerFromTheRightBrowserPassesOnce() {
        String id = issue(BROWSER);

        assertTrue(service.verify(id, RIGHT, BROWSER));
        assertFalse(service.verify(id, RIGHT, BROWSER));
    }

    @Test
    void aWrongAnswerUsesTheChallengeUp() {
        String wrong = issue(BROWSER);
        assertFalse(service.verify(wrong, "0", BROWSER));
        assertFalse(service.verify(wrong, RIGHT, BROWSER));

        String notANumber = issue(BROWSER);
        assertFalse(service.verify(notANumber, "eleven", BROWSER));
        assertFalse(service.verify(notANumber, RIGHT, BROWSER));
    }

    /**
     * An answer without the browser's key, or under another's, is refused whatever it says — and does not spend
     * the challenge, so a stranger who learns an id can neither use it nor take it from its owner.
     */
    @Test
    void anAnswerFromAnyOtherBrowserIsRefusedAndLeavesTheChallengeToItsOwner() {
        String id = issue(BROWSER);

        assertFalse(service.verify(id, RIGHT, null));
        assertFalse(service.verify(id, RIGHT, ""));
        assertFalse(service.verify(id, RIGHT, OTHER_BROWSER));
        assertFalse(service.verify(id, RIGHT, BROWSER.toUpperCase()));
        assertFalse(service.verify(id, RIGHT, BROWSER + " "));

        assertTrue(service.verify(id, RIGHT, BROWSER));
    }

    /** Keys used to be cut to 128 characters, so a longer key that began with the owner's passed as the owner. */
    @Test
    void aKeyIsComparedWholeNeverCutShort() {
        String owner = "k".repeat(128);
        String id = issue(owner);

        assertFalse(service.verify(id, RIGHT, owner + "x"));
        assertTrue(service.verify(id, RIGHT, owner));
    }

    @Test
    void withinTheCooldownTheSameBrowserGetsTheSameChallengeAndAnotherBrowserItsOwn() {
        Map<String, Object> first = service.issue(BROWSER);
        Map<String, Object> again = service.issue(BROWSER);
        assertEquals(true, again.get("cooldown"));
        assertEquals(first.get("captchaId"), again.get("captchaId"));
        assertEquals(first.get("image"), again.get("image"));

        Map<String, Object> other = service.issue(OTHER_BROWSER);
        assertFalse(other.containsKey("cooldown"));
        assertNotEquals(first.get("captchaId"), other.get("captchaId"));
    }

    @Test
    void anAnsweredChallengeCanBeReplacedAtOnce() {
        String id = issue(BROWSER);
        assertFalse(service.verify(id, "0", BROWSER));

        Map<String, Object> replacement = service.issue(BROWSER);
        assertFalse(replacement.containsKey("cooldown"));
        assertNotEquals(id, replacement.get("captchaId"));
    }

    /** The predictable source the tests use decides the sum only; ids still come from a secure generator. */
    @Test
    void idsStayUnpredictableEvenWithAPredictableSum() {
        String first = issue(BROWSER);
        String second = CaptchaTestSupport.predictable().issue(BROWSER).get("captchaId").toString();
        assertNotEquals(first, second);
    }
}
