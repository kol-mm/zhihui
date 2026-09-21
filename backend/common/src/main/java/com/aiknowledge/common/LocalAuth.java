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
        return issueToken(username, userId, role, false);
    }

    /**
     * The super administrator claim rides alongside the ordinary role, so a token still says ADMIN and every
     * existing administrator check keeps working; only the few places that guard account creation and the AI
     * upstream settings look for the extra claim.
     */
    public static String issueToken(String username, Long userId, String role, boolean superAdmin) {
        long issuedAt = Instant.now().getEpochSecond();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", username);
        payload.put("uid", userId);
        payload.put("role", role);
        if (superAdmin) payload.put("sa", true);
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

    /** Only ever true for an administrator: the claim alone grants nothing. */
    public static boolean isSuperAdmin(String authorization) {
        Claims claims = claims(authorization);
        return claims != null && "ADMIN".equals(claims.role()) && claims.superAdmin();
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

    public static ApiResponse<Map<String, Object>> requireSuperAdmin(String authorization) {
        if (isSuperAdmin(authorization)) {
            return null;
        }
        return ApiResponse.fail("需要超级管理员权限");
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
                "superAdmin", claims.superAdmin(),
                "issuedAt", claims.issuedAt(),
                "expiresAt", claims.expiresAt()
        );
    }

    /** What session revocation needs from a valid token; null when the token is missing or not valid. */
    public static TokenInfo tokenInfo(String authorization) {
        Claims claims = claims(authorization);
        return claims == null ? null : new TokenInfo(claims.userId(), claims.tokenId(), claims.issuedAt(), claims.expiresAt());
    }

    public static long tokenLifetimeSeconds() {
        return expirySeconds();
    }

    public record TokenInfo(long userId, String tokenId, long issuedAt, long expiresAt) {
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
            boolean superAdmin = Boolean.TRUE.equals(payload.get("sa"));
            long issuedAt = number(payload.get("iat"));
            long expiresAt = number(payload.get("exp"));
            long now = Instant.now().getEpochSecond();
            if (username.isBlank() || userId <= 0 || !("USER".equals(role) || "ADMIN".equals(role))
                    || issuedAt > now + 60 || expiresAt <= now || expiresAt <= issuedAt
                    || expiresAt - issuedAt > expirySeconds() + 60) {
                return null;
            }
            return new Claims(username, userId, role, superAdmin, issuedAt, expiresAt, String.valueOf(payload.getOrDefault("jti", "")));
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

    private record Claims(String username, long userId, String role, boolean superAdmin, long issuedAt, long expiresAt, String tokenId) {
    }
}
