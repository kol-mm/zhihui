package com.aiknowledge.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Reads the session tokens user-service issues (HS256, see common LocalAuth). The gateway does not depend on the
 * common module, which is built for servlet services, so the few lines it needs live here. Services still verify
 * every token themselves; the gateway only needs to know whose token it is to look up revocations.
 */
final class SessionTokens {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final int MAX_TOKEN_LENGTH = 8192;

    record Token(long userId, String tokenId, long issuedAt, long expiresAt) {
    }

    private final byte[] secret;

    SessionTokens(String secret) {
        String value = secret == null || secret.isBlank() ? "local-dev-secret-change-before-production" : secret;
        this.secret = value.getBytes(StandardCharsets.UTF_8);
    }

    static SessionTokens fromEnvironment() {
        return new SessionTokens(System.getenv("AI_KNOWLEDGE_JWT_SECRET"));
    }

    /** The token's identity when the signature matches and it has not expired; null otherwise. */
    Token read(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) return null;
        String[] parts = token.split("\\.");
        if (parts.length != 3) return null;
        try {
            Map<String, Object> header = JSON.readValue(DECODER.decode(parts[0]), new TypeReference<>() {});
            if (!"HS256".equals(header.get("alg"))) return null;
            byte[] expected = sign(parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
            if (!MessageDigest.isEqual(expected, parts[2].getBytes(StandardCharsets.US_ASCII))) return null;
            Map<String, Object> payload = JSON.readValue(DECODER.decode(parts[1]), new TypeReference<>() {});
            long userId = number(payload.get("uid"));
            long issuedAt = number(payload.get("iat"));
            long expiresAt = number(payload.get("exp"));
            Object tokenId = payload.get("jti");
            if (userId <= 0 || expiresAt <= Instant.now().getEpochSecond()) return null;
            return new Token(userId, tokenId == null ? "" : tokenId.toString(), issuedAt, expiresAt);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Bearer value of an Authorization header, or null. */
    static String bearer(String authorization) {
        if (authorization == null) return null;
        String value = authorization.trim();
        return value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7).trim() : null;
    }

    private String sign(String signingInput) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return ENCODER.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }
}
