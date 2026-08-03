package com.aiknowledge.common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

public final class LocalAuth {
    private static final String PREFIX = "local-dev-token.";

    private LocalAuth() {
    }

    public static String roleForUsername(String username) {
        return "admin".equalsIgnoreCase(username) ? "ADMIN" : "USER";
    }

    public static String issueToken(String username) {
        String role = roleForUsername(username);
        String payload = username + ":" + role;
        return PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean isAdmin(String authorization) {
        return "ADMIN".equals(parseRole(authorization));
    }

    public static ApiResponse<Map<String, Object>> requireAdmin(String authorization) {
        if (isAdmin(authorization)) {
            return null;
        }
        return ApiResponse.fail("admin authorization is required");
    }

    private static String parseRole(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return "";
        }
        String token = authorization.trim();
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = token.substring(7).trim();
        }
        if (!token.startsWith(PREFIX)) {
            return "";
        }
        try {
            String encoded = token.substring(PREFIX.length());
            String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", 2);
            return parts.length == 2 ? parts[1] : "";
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }
}
