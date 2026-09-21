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
                Duration.ofSeconds(Long.parseLong(System.getenv().getOrDefault("AI_REVIEW_TIMEOUT_SECONDS", "10"))));
    }

    public AiContentReview(String endpoint, String internalToken, Duration timeout) {
        this.endpoint = URI.create(endpoint);
        this.internalToken = internalToken;
        this.timeout = timeout;
    }

    /** The verdict on one submission. Never throws: a review that cannot be made is a review for a person. */
    public Verdict review(String kind, String title, String text) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("kind", kind);
            payload.put("title", title == null ? "" : title);
            payload.put("text", text == null ? "" : text);
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
