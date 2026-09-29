package com.aiknowledge.knowledge.analysis;

import com.aiknowledge.knowledge.entity.KnowledgeCategoryEntity;
import com.aiknowledge.knowledge.entity.KnowledgeFileEntity;
import com.aiknowledge.knowledge.store.InMemoryKnowledgeStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A summary and a category for an upload, asked of the AI service in the background and never trusted blindly. */
class KnowledgeAnalysisTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/knowledge-analysis-" + System.nanoTime());
    }

    private final InMemoryKnowledgeStore store = new InMemoryKnowledgeStore();

    /** An AI service stand-in answering with a fixed body; records what it was sent. */
    private static final class StubAiService implements AutoCloseable {
        final List<Map<String, Object>> received = new ArrayList<>();
        final List<String> tokens = new ArrayList<>();
        private final HttpServer server;
        volatile int status = 200;
        volatile String body = "{\"code\":0,\"data\":{\"available\":false}}";

        StubAiService() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/analyze", exchange -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> request = new ObjectMapper().readValue(exchange.getRequestBody(), Map.class);
                received.add(request);
                tokens.add(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
                byte[] answer = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, answer.length);
                exchange.getResponseBody().write(answer);
                exchange.close();
            });
            server.start();
        }

        AiDocumentAnalysis client() {
            return new AiDocumentAnalysis("http://127.0.0.1:" + server.getAddress().getPort() + "/analyze", "internal-token",
                    Duration.ofSeconds(10));
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final List<AiDocumentAnalysis.Category> OFFERED = List.of(
            new AiDocumentAnalysis.Category(3, "人工智能"), new AiDocumentAnalysis.Category(7, "数据库"));

    // ---- the client ------------------------------------------------------------------------------------------------
    @Test
    void theClientSendsTheDocumentAndTheCategoriesWithTheInternalToken() throws Exception {
        try (StubAiService ai = new StubAiService()) {
            ai.body = "{\"code\":0,\"data\":{\"available\":true,\"summary\":\" 介绍检索增强生成 \",\"categoryId\":3}}";

            AiDocumentAnalysis.Result result = ai.client().analyze("RAG", "字".repeat(30_000), OFFERED);

            assertEquals(new AiDocumentAnalysis.Result(true, "介绍检索增强生成", 3L), result);
            Map<String, Object> sent = ai.received.get(0);
            assertEquals("RAG", sent.get("title"));
            assertEquals(AiDocumentAnalysis.MAX_TEXT_CHARS, String.valueOf(sent.get("text")).length());
            assertEquals(List.of(Map.of("id", 3, "name", "人工智能"), Map.of("id", 7, "name", "数据库")), sent.get("categories"));
            assertEquals("internal-token", ai.tokens.get(0));
        }
    }

    @Test
    void aCategoryThatWasNotOfferedIsDropped() throws Exception {
        try (StubAiService ai = new StubAiService()) {
            ai.body = "{\"code\":0,\"data\":{\"available\":true,\"summary\":\"摘要\",\"categoryId\":99}}";
            assertEquals(new AiDocumentAnalysis.Result(true, "摘要", null), ai.client().analyze("t", "正文", OFFERED));
        }
    }

    @Test
    void anythingButAnAvailableAnswerIsNothing() throws Exception {
        try (StubAiService ai = new StubAiService()) {
            assertEquals(AiDocumentAnalysis.Result.NONE, ai.client().analyze("t", "正文", OFFERED));
            ai.status = 503;
            ai.body = "{\"code\":0,\"data\":{\"available\":true,\"summary\":\"摘要\"}}";
            assertEquals(AiDocumentAnalysis.Result.NONE, ai.client().analyze("t", "正文", OFFERED));
            ai.status = 200;
            ai.body = "not json";
            assertEquals(AiDocumentAnalysis.Result.NONE, ai.client().analyze("t", "正文", OFFERED));
        }
        assertEquals(AiDocumentAnalysis.Result.NONE,
                new AiDocumentAnalysis("http://127.0.0.1:9/analyze", "t", Duration.ofSeconds(2)).analyze("t", "正文", OFFERED));
    }

    // ---- the runner and the store -----------------------------------------------------------------------------------
    /** Answers with a fixed result, recording the categories it was offered. */
    private static final class FixedAnalysis extends AiDocumentAnalysis {
        private final Result result;
        List<Category> offered;

        FixedAnalysis(Result result) {
            super("http://localhost:0/unused", "t", Duration.ofSeconds(1));
            this.result = result;
        }

        @Override
        public Result analyze(String title, String text, List<Category> categories) {
            offered = categories;
            return result;
        }
    }

    private KnowledgeFileEntity file(Long categoryId) {
        KnowledgeFileEntity file = new KnowledgeFileEntity();
        file.setUserId(1L);
        file.setTitle("资料");
        file.setCategoryId(categoryId);
        file.setAuditStatus("PENDING");
        return store.saveFile(file);
    }

    private Long firstCategoryId() {
        List<KnowledgeCategoryEntity> categories = store.listCategories();
        assertFalse(categories.isEmpty(), "the store starts with categories");
        return categories.get(0).getId();
    }

    @Test
    void aSuggestedCategoryIsAppliedToAFileThatHasNone() {
        Long suggested = firstCategoryId();
        KnowledgeFileEntity file = file(null);
        FixedAnalysis analysis = new FixedAnalysis(new AiDocumentAnalysis.Result(true, "摘要", suggested));

        new KnowledgeAnalysisRunner(store, analysis).run(file.getId(), file.getTitle(), "正文");

        KnowledgeFileEntity saved = store.find(file.getId()).orElseThrow();
        assertEquals("摘要", saved.getSummary());
        assertEquals(suggested, saved.getCategoryId());
        assertEquals(suggested, saved.getSuggestedCategoryId());
        assertEquals(store.listCategories().size(), analysis.offered.size(), "every category is offered");
    }

    @Test
    void aCategoryAlreadyChosenIsNeverReplaced() {
        List<KnowledgeCategoryEntity> categories = store.listCategories();
        Long chosen = categories.get(0).getId();
        Long suggested = categories.get(1).getId();
        KnowledgeFileEntity file = file(chosen);

        new KnowledgeAnalysisRunner(store, new FixedAnalysis(new AiDocumentAnalysis.Result(true, "摘要", suggested)))
                .run(file.getId(), file.getTitle(), "正文");

        KnowledgeFileEntity saved = store.find(file.getId()).orElseThrow();
        assertEquals(chosen, saved.getCategoryId());
        assertEquals(suggested, saved.getSuggestedCategoryId(), "kept as a suggestion for the reviewer");
    }

    /** Asking again while the model is down must not wipe what an earlier analysis wrote. */
    @Test
    void noAnswerLeavesTheFileAsItWas() {
        Long suggested = firstCategoryId();
        KnowledgeFileEntity file = file(null);
        store.saveAnalysis(file.getId(), "早先的摘要", suggested);

        new KnowledgeAnalysisRunner(store, new FixedAnalysis(AiDocumentAnalysis.Result.NONE)).run(file.getId(), "t", "正文");

        KnowledgeFileEntity saved = store.find(file.getId()).orElseThrow();
        assertEquals("早先的摘要", saved.getSummary());
        assertEquals(suggested, saved.getSuggestedCategoryId());
    }

    /** Runs submitted work at once, or refuses it like a full queue. */
    private static final class ImmediateExecutor extends AbstractExecutorService {
        boolean full;

        @Override
        public void execute(Runnable command) {
            if (full) throw new RejectedExecutionException("full");
            command.run();
        }

        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    @Test
    void submittingQueuesRealTextAndSkipsWhenTheQueueIsFull() {
        KnowledgeFileEntity file = file(null);
        ImmediateExecutor executor = new ImmediateExecutor();
        KnowledgeAnalysisRunner runner = new KnowledgeAnalysisRunner(store,
                new FixedAnalysis(new AiDocumentAnalysis.Result(true, "摘要", null)), executor);

        assertFalse(runner.submit(file.getId(), "t", "   "));
        assertFalse(runner.submit(null, "t", "正文"));
        assertNull(store.find(file.getId()).orElseThrow().getSummary());

        assertTrue(runner.submit(file.getId(), "t", "正文"));
        assertEquals("摘要", store.find(file.getId()).orElseThrow().getSummary());

        executor.full = true;
        assertFalse(runner.submit(file.getId(), "t", "正文"));
    }
}
