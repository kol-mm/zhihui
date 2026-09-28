package com.aiknowledge.user.security;

import java.util.random.RandomGenerator;

/**
 * Captchas a test can answer. Lives with the tests, so nothing in the service itself can say the sum: the
 * predictable source makes every challenge 10 + 1, drawn as a picture like any other.
 */
public final class CaptchaTestSupport {
    /** A key of the kind the page sends. */
    public static final String CLIENT = "test-browser-0000-0001";
    /** The sum of every challenge from {@link #predictable()}. */
    public static final String ANSWER = "11";

    private CaptchaTestSupport() {
    }

    public static CaptchaService predictable() {
        return new CaptchaService(new ZeroSource());
    }

    /** Always the lowest value: 10 + 1 for the sum, and a quiet picture. */
    private static final class ZeroSource implements RandomGenerator {
        @Override
        public long nextLong() {
            return 0L;
        }
    }
}
