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
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * Image arithmetic challenges for login, registration and password reset.
 *
 * <p>Every challenge belongs to the browser that asked for it: the page sends a random key of its own in
 * {@link #CLIENT_HEADER} both when it fetches a challenge and when it answers one, and an answer arriving under any
 * other key — or none — is refused. A challenge works once, expires after three minutes, and is thrown away by the
 * first answer, right or wrong. Nothing here ever hands out the answer or the question in words; the only way to
 * learn the sum is to read the picture.
 */
@Service
public class CaptchaService {
    /** The request header carrying the browser's own key. */
    public static final String CLIENT_HEADER = "X-Captcha-Client";
    /**
     * What the page generates: crypto.randomUUID(), or "captcha-<time>-<random>" where that is unavailable. Anything
     * else is refused rather than trimmed or cut short, so two different keys can never be taken for one.
     */
    private static final Pattern CLIENT_KEY = Pattern.compile("[A-Za-z0-9-]{16,128}");
    private static final long TTL_SECONDS = 180;
    private static final long ISSUE_INTERVAL_MILLIS = 10_000L;
    private static final int MAX_ACTIVE_CHALLENGES = 10_000;
    private final RandomGenerator random;
    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();
    private final Map<String, Long> lastIssuedAt = new ConcurrentHashMap<>();
    private final Map<String, String> activeChallengeIds = new ConcurrentHashMap<>();

    public CaptchaService() {
        this(new SecureRandom());
    }

    /**
     * For tests in this package, which need to know the sum without the service ever saying it. Challenge ids still
     * come from UUID.randomUUID(), so a predictable source here never makes an id guessable.
     */
    CaptchaService(RandomGenerator random) {
        this.random = random;
    }

    public static boolean isValidClientKey(String clientKey) {
        return clientKey != null && CLIENT_KEY.matcher(clientKey).matches();
    }

    /** A new challenge for this browser, or — within ten seconds of the last — the one it already has. */
    public synchronized Map<String, Object> issue(String clientKey) {
        if (!isValidClientKey(clientKey)) throw new IllegalArgumentException("invalid captcha client key");
        cleanup();
        String key = clientKey;
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
                response.put("expiresInSeconds", Math.max(1, (active.expiresAt() - now + 999) / 1000));
            }
            return response;
        }
        String previousChallengeId = activeChallengeIds.remove(key);
        if (previousChallengeId != null) challenges.remove(previousChallengeId);
        lastIssuedAt.put(key, now);
        ensureCapacity();
        Map<String, Object> response = issueNow(key);
        activeChallengeIds.put(key, String.valueOf(response.get("captchaId")));
        return response;
    }

    private Map<String, Object> issueNow(String clientKey) {
        cleanup();
        int left = 10 + random.nextInt(90);
        int right = 1 + random.nextInt(9);
        String id = UUID.randomUUID().toString();
        String question = left + " + " + right + " = ?";
        long expiresAt = Instant.now().plusSeconds(TTL_SECONDS).toEpochMilli();
        String image = renderImage(question.replace(" ", ""));
        challenges.put(id, new Challenge(id, left + right, image, expiresAt, clientKey));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("captchaId", id);
        response.put("image", image);
        response.put("expiresAt", expiresAt);
        response.put("expiresInSeconds", TTL_SECONDS);
        response.put("refreshAfterSeconds", ISSUE_INTERVAL_MILLIS / 1000);
        return response;
    }

    private String renderImage(String question) {
        BufferedImage image = new BufferedImage(180, 52, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(242, 246, 243));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());

            for (int index = 0; index < 5; index++) {
                graphics.setColor(index % 2 == 0 ? new Color(123, 153, 139) : new Color(188, 139, 96));
                graphics.drawLine(random.nextInt(image.getWidth()), random.nextInt(image.getHeight()),
                        random.nextInt(image.getWidth()), random.nextInt(image.getHeight()));
            }
            for (int index = 0; index < 55; index++) {
                graphics.setColor(new Color(90 + random.nextInt(100), 90 + random.nextInt(100), 90 + random.nextInt(100)));
                graphics.fillRect(random.nextInt(image.getWidth()), random.nextInt(image.getHeight()),
                        1 + random.nextInt(2), 1 + random.nextInt(2));
            }

            Font font = new Font(Font.SANS_SERIF, Font.BOLD, 24);
            graphics.setFont(font);
            FontMetrics metrics = graphics.getFontMetrics(font);
            int x = Math.max(8, (image.getWidth() - metrics.stringWidth(question)) / 2);
            int y = (image.getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            for (int index = 0; index < question.length(); index++) {
                String character = String.valueOf(question.charAt(index));
                int width = metrics.stringWidth(character);
                AffineTransform original = graphics.getTransform();
                graphics.rotate(Math.toRadians(random.nextInt(17) - 8), x + width / 2.0, y - 8);
                graphics.setColor(index % 2 == 0 ? new Color(31, 70, 53) : new Color(91, 63, 42));
                graphics.drawString(character, x, y + random.nextInt(5) - 2);
                graphics.setTransform(original);
                x += width + 1;
            }
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

    /**
     * Whether this browser answered this challenge correctly. The challenge is used up by the answer, right or
     * wrong, but only when the answer comes from the browser it belongs to: a stranger quoting its id can neither
     * answer it nor spend it, and learns nothing either way.
     */
    public boolean verify(String captchaId, String answer, String clientKey) {
        if (!isValidClientKey(clientKey)) return false;
        if (captchaId == null || captchaId.isBlank() || answer == null || answer.isBlank()) return false;
        Challenge challenge = challenges.get(captchaId);
        if (challenge == null) return false;
        if (!challenge.clientKey().equals(clientKey)) return false;
        if (!challenges.remove(captchaId, challenge)) return false;
        releaseClient(challenge.clientKey(), captchaId);
        if (challenge.expiresAt() < System.currentTimeMillis()) return false;
        if (!answer.trim().matches("[0-9]{1,3}")) return false;
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

    private void ensureCapacity() {
        while (challenges.size() >= MAX_ACTIVE_CHALLENGES) {
            challenges.entrySet().stream()
                    .min((left, right) -> Long.compare(left.getValue().expiresAt(), right.getValue().expiresAt()))
                    .ifPresent(entry -> {
                        challenges.remove(entry.getKey(), entry.getValue());
                        releaseClient(entry.getValue().clientKey(), entry.getKey());
                    });
        }
    }

    private void releaseClient(String clientKey, String captchaId) {
        if (activeChallengeIds.remove(clientKey, captchaId)) {
            lastIssuedAt.remove(clientKey);
        }
    }

    private record Challenge(String id, int answer, String image, long expiresAt, String clientKey) {
    }
}
