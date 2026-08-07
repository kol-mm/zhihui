package com.aiknowledge.user.security;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CaptchaService {
    private static final long TTL_SECONDS = 300;
    private static final long ISSUE_INTERVAL_MILLIS = 60_000L;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();
    private final Map<String, Long> lastIssuedAt = new ConcurrentHashMap<>();

    public Map<String, Object> issue() {
        return issueNow();
    }

    public synchronized Map<String, Object> issue(String clientKey) {
        cleanup();
        String key = clientKey == null || clientKey.isBlank() ? "unknown" : clientKey.trim();
        long now = System.currentTimeMillis();
        Long previous = lastIssuedAt.get(key);
        if (previous != null && now - previous < ISSUE_INTERVAL_MILLIS) {
            long retryAfterSeconds = Math.max(1, (ISSUE_INTERVAL_MILLIS - (now - previous) + 999) / 1000);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("cooldown", true);
            response.put("retryAfterSeconds", retryAfterSeconds);
            return response;
        }
        lastIssuedAt.put(key, now);
        return issueNow();
    }

    private Map<String, Object> issueNow() {
        cleanup();
        int left = 10 + random.nextInt(90);
        int right = 1 + random.nextInt(9);
        String id = UUID.randomUUID().toString();
        long expiresAt = Instant.now().plusSeconds(TTL_SECONDS).toEpochMilli();
        challenges.put(id, new Challenge(left + right, expiresAt));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("captchaId", id);
        response.put("question", left + " + " + right + " = ?");
        response.put("expiresAt", expiresAt);
        return response;
    }

    public boolean verify(String captchaId, String answer) {
        if (captchaId == null || captchaId.isBlank() || answer == null || answer.isBlank()) return false;
        Challenge challenge = challenges.remove(captchaId);
        if (challenge == null || challenge.expiresAt() < System.currentTimeMillis()) return false;
        try {
            return challenge.answer() == Integer.parseInt(answer.trim());
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        challenges.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        lastIssuedAt.entrySet().removeIf(entry -> now - entry.getValue() >= ISSUE_INTERVAL_MILLIS);
    }

    private record Challenge(int answer, long expiresAt) {
    }
}
