package com.aiknowledge.user.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** The shared token other services present on /user/internal/* calls. */
public final class InternalAuth {
    private InternalAuth() {
    }

    public static boolean accepts(String presented) {
        if (presented == null || presented.isEmpty()) return false;
        return MessageDigest.isEqual(token().getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    static String token() {
        String configured = System.getenv("INTERNAL_USER_TOKEN");
        return configured == null || configured.isBlank() ? "ai-knowledge-local-internal" : configured;
    }
}
