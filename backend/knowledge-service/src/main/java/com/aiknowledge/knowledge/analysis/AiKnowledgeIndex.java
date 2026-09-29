package com.aiknowledge.knowledge.analysis;

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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the AI service's index — what AI 问答 draws on — in step with the library: an approved file's text is sent
 * to it, and a file that is deleted, hidden or rejected is taken out.
 *
 * <p>This used to be left to the administrator's browser, which missed every change it did not make itself: files
 * approved by AI review never reached the index, and files deleted by their own authors stayed in it to be quoted.
 * Changes are sent one at a time, in order, in the background; a failure is logged, and 重建知识索引 restores the
 * index from the library.
 */
public class AiKnowledgeIndex {
    private static final Logger log = LoggerFactory.getLogger(AiKnowledgeIndex.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int QUEUE_LIMIT = 500;
    /** The AI service accepts at most this much text for one document. */
    static final int MAX_TEXT_CHARS = 2_000_000;

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String base;
    private final String internalToken;
    private final ExecutorService executor;

    public AiKnowledgeIndex() {
        this(System.getenv().getOrDefault("AI_INDEX_URL", "http://ai:8200/ai/internal/index"),
                System.getenv().getOrDefault("PLATFORM_INTERNAL_USER_TOKEN", "ai-knowledge-local-internal"),
                new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_LIMIT), runnable -> {
                    Thread thread = new Thread(runnable, "ai-knowledge-index");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    public AiKnowledgeIndex(String base, String internalToken, ExecutorService executor) {
        this.base = base.replaceAll("/+$", "");
        this.internalToken = internalToken;
        this.executor = executor;
    }

    /** The file's current text replaces whatever the index held for it; with no text, it is taken out. */
    public void publish(Long fileId, String title, String text) {
        if (fileId == null) return;
        if (text == null || text.isBlank()) {
            withdraw(fileId);
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("file_id", fileId);
        payload.put("title", title == null ? "" : title);
        payload.put("text", text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text);
        submit(fileId, "publish", () -> send(base, payload));
    }

    public void withdraw(Long fileId) {
        if (fileId == null) return;
        submit(fileId, "withdraw", () -> send(base + "/remove", Map.of("file_id", fileId)));
    }

    private void submit(Long fileId, String what, java.util.function.BooleanSupplier work) {
        try {
            executor.execute(() -> {
                if (!work.getAsBoolean()) log.warn("AI index {} failed for knowledge file {}; rebuild the index to recover", what, fileId);
            });
        } catch (RejectedExecutionException full) {
            log.warn("AI index queue is full; {} of knowledge file {} was skipped", what, fileId);
        }
    }

    /** One request to the AI service; true when it answered with success. */
    boolean send(String url, Map<String, Object> payload) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", internalToken)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception error) {
            log.warn("AI index unavailable: {}", error.toString());
            return false;
        }
    }
}
