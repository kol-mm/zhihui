package com.aiknowledge.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Asks the AI service whether a piece of content may be published.
 *
 * <p>AI review is the platform's first pass; a person is the fallback. Every failure here — the service being
 * unreachable, slow, turned off or answering with something unexpected — comes back as ESCALATE, so content
 * waits for a human rather than being published or rejected by accident.
 */
public class AiContentReview {
    private static final Logger log = LoggerFactory.getLogger(AiContentReview.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String APPROVE = "APPROVE";
    public static final String REJECT = "REJECT";
    public static final String ESCALATE = "ESCALATE";

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final URI endpoint;
    private final String internalToken;
    private final Duration timeout;

    public AiContentReview() {
        this(System.getenv().getOrDefault("AI_REVIEW_URL", "http://ai:8200/ai/internal/review"),
                System.getenv().getOrDefault("PLATFORM_INTERNAL_USER_TOKEN", "ai-knowledge-local-internal"),
                waitFor(System.getenv("AI_REVIEW_TIMEOUT_SECONDS")));
    }

    /** ai-service's own default for AI_REVIEW_TIMEOUT_SECONDS. */
    static final double MODEL_SECONDS_DEFAULT = 8;
    /** Time for ai-service to notice its model has timed out and answer, on top of the model's allowance. */
    static final Duration ANSWER_MARGIN = Duration.ofSeconds(2);

    /**
     * How long to wait for ai-service, given AI_REVIEW_TIMEOUT_SECONDS — the time ai-service allows its model.
     *
     * <p>Both services read the same variable, and this side must always wait the longer, or it gives up just as
     * ai-service answers: the content still goes to a person, but with "AI 审核暂不可用" instead of the reason
     * ai-service had. The two used to be separate numbers, 8 and 10, that happened to be in the right order;
     * setting the variable made them equal. A value that is not a positive number falls back to the default
     * rather than stopping the service at start-up, which is what Long.parseLong("8.5") used to do.
     */
    static Duration waitFor(String modelSeconds) {
        double seconds = MODEL_SECONDS_DEFAULT;
        try {
            if (modelSeconds != null && !modelSeconds.isBlank()) seconds = Double.parseDouble(modelSeconds.trim());
        } catch (NumberFormatException ignored) {
            seconds = MODEL_SECONDS_DEFAULT;
        }
        if (!(seconds > 0) || Double.isInfinite(seconds)) seconds = MODEL_SECONDS_DEFAULT;
        return Duration.ofMillis(Math.round(seconds * 1000)).plus(ANSWER_MARGIN);
    }

    public AiContentReview(String endpoint, String internalToken, Duration timeout) {
        this.endpoint = URI.create(endpoint);
        this.internalToken = internalToken;
        this.timeout = timeout;
    }

    /** ai-service's limit on the text of one review (ReviewRequest.text); longer text is refused there outright. */
    static final int MAX_TEXT_CHARS = 2_000_000;
    /** ai-service's limit on the title (ReviewRequest.title). */
    static final int MAX_TITLE_CHARS = 500;

    /**
     * The verdict on one submission. Never throws: a review that cannot be made is a review for a person.
     *
     * <p>Text and title longer than ai-service accepts are cut to its limits rather than sent whole to be refused,
     * which used to reach a person as "AI 审核暂不可用". What was cut off was never reviewed, so a cut submission
     * is never approved here: an approval becomes a referral to a person, a rejection stands, and either way the
     * reason says how much was reviewed.
     */
    public Verdict review(String kind, String title, String text) {
        String fullTitle = title == null ? "" : title;
        String fullText = text == null ? "" : text;
        boolean cut = fullTitle.length() > MAX_TITLE_CHARS || fullText.length() > MAX_TEXT_CHARS;
        Verdict verdict = ask(kind, capped(fullTitle, MAX_TITLE_CHARS), capped(fullText, MAX_TEXT_CHARS));
        if (!cut) return verdict;
        String note = fullText.length() > MAX_TEXT_CHARS
                ? "原文共 " + fullText.length() + " 字，只有前 " + MAX_TEXT_CHARS + " 字经过审核"
                : "标题超过 " + MAX_TITLE_CHARS + " 字，只有前 " + MAX_TITLE_CHARS + " 字经过审核";
        String reason = verdict.reason() == null || verdict.reason().isBlank() ? note : verdict.reason() + "；" + note;
        return verdict.rejected() ? new Verdict(REJECT, verdict.confidence(), reason) : Verdict.escalate(reason);
    }

    /**
     * At most {@code limit} UTF-16 units, never ending on half a surrogate pair. ai-service counts code points,
     * which are never more than UTF-16 units, so the result is always within its limit.
     */
    static String capped(String value, int limit) {
        if (value.length() <= limit) return value;
        int end = Character.isHighSurrogate(value.charAt(limit - 1)) ? limit - 1 : limit;
        return value.substring(0, end);
    }

    private Verdict ask(String kind, String title, String text) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("kind", kind);
            payload.put("title", title);
            payload.put("text", text);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", internalToken)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Verdict.escalate("AI 审核暂不可用（HTTP " + response.statusCode() + "）");
            }
            Map<String, Object> body = JSON.readValue(response.body(), new TypeReference<>() {});
            Object data = body.get("data");
            if (!(data instanceof Map<?, ?> verdict)) return Verdict.escalate("AI 审核返回了无法识别的结果");
            Object decisionValue = verdict.get("decision");
            Object reasonValue = verdict.get("reason");
            String decision = decisionValue == null ? ESCALATE : String.valueOf(decisionValue);
            String reason = reasonValue == null ? "" : String.valueOf(reasonValue);
            if (!APPROVE.equals(decision) && !REJECT.equals(decision)) {
                return new Verdict(ESCALATE, 0, reason.isBlank() ? "AI 无法判断" : reason);
            }
            double confidence = verdict.get("confidence") instanceof Number number ? number.doubleValue() : 0;
            return new Verdict(decision, confidence, reason);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return Verdict.escalate("AI 审核被中断");
        } catch (Exception error) {
            log.warn("AI review unavailable, leaving the decision to a reviewer: {}", error.toString());
            return Verdict.escalate("AI 审核暂不可用");
        }
    }

    public record Verdict(String decision, double confidence, String reason) {
        public static Verdict escalate(String reason) {
            return new Verdict(ESCALATE, 0, reason);
        }

        public boolean approved() {
            return APPROVE.equals(decision);
        }

        public boolean rejected() {
            return REJECT.equals(decision);
        }

        /** What a reviewer sees on the row: the decision in plain Chinese, with the model's reason. */
        public String summary() {
            String label = approved() ? "AI 自动通过" : rejected() ? "AI 自动驳回" : "AI 转人工复核";
            return reason == null || reason.isBlank() ? label : label + "：" + reason;
        }
    }
}
