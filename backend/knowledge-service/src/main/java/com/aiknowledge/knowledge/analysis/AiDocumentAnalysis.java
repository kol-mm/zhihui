package com.aiknowledge.knowledge.analysis;

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
import java.util.List;
import java.util.Map;

/**
 * Asks the AI service for a document's summary and category (POST /ai/internal/analyze). Never throws: without an
 * answer the document simply has no summary.
 */
public class AiDocumentAnalysis {
    private static final Logger log = LoggerFactory.getLogger(AiDocumentAnalysis.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** The AI service reads no more than this; sending more only costs time. */
    static final int MAX_TEXT_CHARS = 20_000;

    public record Category(long id, String name) { }

    /** What came back: nothing at all, or a summary and/or a category from the list that was sent. */
    public record Result(boolean available, String summary, Long categoryId) {
        public static final Result NONE = new Result(false, null, null);
    }

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final URI endpoint;
    private final String internalToken;
    private final Duration timeout;

    public AiDocumentAnalysis() {
        this(System.getenv().getOrDefault("AI_ANALYZE_URL", "http://ai:8200/ai/internal/analyze"),
                System.getenv().getOrDefault("PLATFORM_INTERNAL_USER_TOKEN", "ai-knowledge-local-internal"),
                Duration.ofSeconds(150));
    }

    public AiDocumentAnalysis(String endpoint, String internalToken, Duration timeout) {
        this.endpoint = URI.create(endpoint);
        this.internalToken = internalToken;
        this.timeout = timeout;
    }

    public Result analyze(String title, String text, List<Category> categories) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("title", title == null ? "" : title.length() > 500 ? title.substring(0, 500) : title);
            String body = text == null ? "" : text;
            payload.put("text", body.length() > MAX_TEXT_CHARS ? body.substring(0, MAX_TEXT_CHARS) : body);
            payload.put("categories", categories.stream().limit(200)
                    .map(category -> Map.of("id", category.id(), "name", category.name())).toList());
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", internalToken)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return Result.NONE;
            Map<String, Object> answer = JSON.readValue(response.body(), new TypeReference<>() {});
            if (!(answer.get("data") instanceof Map<?, ?> data) || !Boolean.TRUE.equals(data.get("available"))) {
                return Result.NONE;
            }
            String summary = data.get("summary") instanceof String written && !written.isBlank() ? written.trim() : null;
            Long offered = data.get("categoryId") instanceof Number number ? number.longValue() : null;
            // Only a category that was offered; the AI service checks this too.
            Long categoryId = offered != null && categories.stream().anyMatch(category -> category.id() == offered)
                    ? offered : null;
            if (summary != null && summary.length() > 500) summary = summary.substring(0, 500);
            return new Result(true, summary, categoryId);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return Result.NONE;
        } catch (Exception error) {
            log.warn("Document analysis unavailable: {}", error.toString());
            return Result.NONE;
        }
    }
}
