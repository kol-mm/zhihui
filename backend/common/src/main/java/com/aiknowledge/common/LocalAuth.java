package com.aiknowledge.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class LocalAuth {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String HEADER = encodeJson(Map.of("alg", "HS256", "typ", "JWT"));
    private static final long DEFAULT_EXPIRY_SECONDS = 8 * 60 * 60;

    private LocalAuth() {
    }

    public static String roleForUsername(String username) {
        return "admin".equals(username) ? "ADMIN" : "USER";
    }

    public static String issueToken(String username) {
        return issueToken(username, "admin".equals(username) ? 2L : 1L, roleForUsername(username));
    }

    public static String issueToken(String username, Long userId, String role) {
        long issuedAt = Instant.now().getEpochSecond();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", username);
        payload.put("uid", userId);
        payload.put("role", role);
        payload.put("iat", issuedAt);
        payload.put("exp", issuedAt + expirySeconds());
        payload.put("jti", UUID.randomUUID().toString());
        String encodedPayload = encodeJson(payload);
        String signingInput = HEADER + "." + encodedPayload;
        return signingInput + "." + sign(signingInput);
    }

    public static boolean isAuthenticated(String authorization) {
        return claims(authorization) != null;
    }

    public static boolean isAdmin(String authorization) {
        Claims claims = claims(authorization);
        return claims != null && "ADMIN".equals(claims.role());
    }

    public static String username(String authorization) {
        Claims claims = claims(authorization);
        return claims == null ? "" : claims.username();
    }

    public static Long userId(String authorization) {
        Claims claims = claims(authorization);
        return claims == null ? null : claims.userId();
    }

    public static String role(String authorization) {
        Claims claims = claims(authorization);
        return claims == null ? "" : claims.role();
    }

    public static boolean canAccessUser(String authorization, Long requestedUserId) {
        Long authenticatedUserId = userId(authorization);
        return authenticatedUserId != null && (authenticatedUserId.equals(requestedUserId) || isAdmin(authorization));
    }

    public static ApiResponse<Map<String, Object>> requireUser(String authorization) {
        if (isAuthenticated(authorization)) {
            return null;
        }
        return ApiResponse.fail("valid user authorization is required");
    }

    public static ApiResponse<Map<String, Object>> requireAdmin(String authorization) {
        if (isAdmin(authorization)) {
            return null;
        }
        return ApiResponse.fail("admin authorization is required");
    }

    public static Map<String, Object> session(String authorization) {
        Claims claims = claims(authorization);
        if (claims == null) {
            return Map.of("authenticated", false);
        }
        return Map.of(
                "authenticated", true,
                "userId", claims.userId(),
                "username", claims.username(),
                "role", claims.role(),
                "issuedAt", claims.issuedAt(),
                "expiresAt", claims.expiresAt()
        );
    }

    private static Claims claims(String authorization) {
        String token = bearerToken(authorization);
        String[] parts = token.split("\\.");
        if (parts.length != 3 || !"HS256".equals(headerAlgorithm(parts[0]))) {
            return null;
        }
        String signingInput = parts[0] + "." + parts[1];
        if (!MessageDigest.isEqual(sign(signingInput).getBytes(StandardCharsets.US_ASCII), parts[2].getBytes(StandardCharsets.US_ASCII))) {
            return null;
        }
        try {
            Map<String, Object> payload = JSON.readValue(DECODER.decode(parts[1]), new TypeReference<>() {});
            String username = String.valueOf(payload.getOrDefault("sub", ""));
            long userId = number(payload.get("uid"));
            String role = String.valueOf(payload.getOrDefault("role", ""));
            long issuedAt = number(payload.get("iat"));
            long expiresAt = number(payload.get("exp"));
            long now = Instant.now().getEpochSecond();
            if (username.isBlank() || userId <= 0 || !("USER".equals(role) || "ADMIN".equals(role))
                    || issuedAt > now + 60 || expiresAt <= now || expiresAt <= issuedAt
                    || expiresAt - issuedAt > expirySeconds() + 60) {
                return null;
            }
            return new Claims(username, userId, role, issuedAt, expiresAt);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String headerAlgorithm(String encodedHeader) {
        try {
            Map<String, Object> header = JSON.readValue(DECODER.decode(encodedHeader), new TypeReference<>() {});
            return String.valueOf(header.getOrDefault("alg", ""));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || authorization.isBlank()) { return ""; }
        String value = authorization.trim();
        if (!value.regionMatches(true, 0, "Bearer ", 0, 7)) { return ""; }
        String token = value.substring(7).trim();
        return token.length() <= 8192 ? token : "";
    }

    private static String encodeJson(Map<String, Object> value) {
        try {
            return ENCODER.encodeToString(JSON.writeValueAsBytes(value));
        } catch (Exception error) {
            throw new IllegalStateException("failed to encode jwt", error);
        }
    }

    private static String sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return ENCODER.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception error) {
            throw new IllegalStateException("failed to sign jwt", error);
        }
    }

    private static String secret() {
        String configured = System.getenv("AI_KNOWLEDGE_JWT_SECRET");
        return configured == null || configured.isBlank() ? "local-dev-secret-change-before-production" : configured;
    }

    private static long expirySeconds() {
        String configured = System.getenv("AI_KNOWLEDGE_JWT_EXPIRES_SECONDS");
        if (configured == null || configured.isBlank()) { return DEFAULT_EXPIRY_SECONDS; }
        try { return Math.max(60, Long.parseLong(configured)); } catch (NumberFormatException ignored) { return DEFAULT_EXPIRY_SECONDS; }
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private record Claims(String username, long userId, String role, long issuedAt, long expiresAt) {
    }
}
