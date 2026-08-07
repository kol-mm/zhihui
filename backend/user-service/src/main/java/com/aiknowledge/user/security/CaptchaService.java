package com.aiknowledge.user.security;

import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
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
    private final Map<String, String> activeChallengeIds = new ConcurrentHashMap<>();

    public Map<String, Object> issue() {
        return issueNow(true);
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
            Challenge active = challenges.get(activeChallengeIds.get(key));
            if (active != null && active.expiresAt() >= now) {
                response.put("captchaId", active.id());
                response.put("image", active.image());
                response.put("expiresAt", active.expiresAt());
            }
            return response;
        }
        lastIssuedAt.put(key, now);
        Map<String, Object> response = issueNow(false);
        activeChallengeIds.put(key, String.valueOf(response.get("captchaId")));
        return response;
    }

    private Map<String, Object> issueNow(boolean includeQuestion) {
        cleanup();
        int left = 10 + random.nextInt(90);
        int right = 1 + random.nextInt(9);
        String id = UUID.randomUUID().toString();
        String question = left + " + " + right + " = ?";
        long expiresAt = Instant.now().plusSeconds(TTL_SECONDS).toEpochMilli();
        String image = renderImage(question);
        challenges.put(id, new Challenge(id, left + right, image, expiresAt));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("captchaId", id);
        response.put("image", image);
        if (includeQuestion) response.put("question", question);
        response.put("expiresAt", expiresAt);
        return response;
    }

    private String renderImage(String question) {
        BufferedImage image = new BufferedImage(170, 48, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(242, 246, 243));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());

            for (int index = 0; index < 8; index++) {
                graphics.setColor(index % 2 == 0 ? new Color(123, 153, 139) : new Color(188, 139, 96));
                graphics.drawLine(random.nextInt(170), random.nextInt(48), random.nextInt(170), random.nextInt(48));
            }
            for (int index = 0; index < 45; index++) {
                graphics.setColor(new Color(90 + random.nextInt(100), 90 + random.nextInt(100), 90 + random.nextInt(100)));
                graphics.fillRect(random.nextInt(170), random.nextInt(48), 1 + random.nextInt(2), 1 + random.nextInt(2));
            }

            Font font = new Font(Font.SANS_SERIF, Font.BOLD, 22);
            graphics.setFont(font);
            graphics.setColor(new Color(37, 60, 50));
            FontMetrics metrics = graphics.getFontMetrics(font);
            int x = (image.getWidth() - metrics.stringWidth(question)) / 2;
            int y = (image.getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            AffineTransform original = graphics.getTransform();
            graphics.rotate(Math.toRadians(random.nextInt(9) - 4), image.getWidth() / 2.0, image.getHeight() / 2.0);
            graphics.drawString(question, x, y);
            graphics.setTransform(original);
        } finally {
            graphics.dispose();
        }

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException error) {
            throw new IllegalStateException("验证码图片生成失败", error);
        }
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
        activeChallengeIds.entrySet().removeIf(entry -> !challenges.containsKey(entry.getValue()));
    }

    private record Challenge(String id, int answer, String image, long expiresAt) {
    }
}
