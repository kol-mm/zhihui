package com.aiknowledge.knowledge.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.common.PlatformConfigClient;
import com.aiknowledge.knowledge.analysis.AiDocumentAnalysis;
import com.aiknowledge.knowledge.analysis.KnowledgeAnalysisRunner;
import com.aiknowledge.knowledge.search.LocalFullTextSearchService;
import com.aiknowledge.knowledge.storage.DocumentTextExtractor;
import com.aiknowledge.knowledge.storage.LocalFileStorageService;
import com.aiknowledge.knowledge.store.InMemoryKnowledgeStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Uploads ask for a summary and a category only when AI 摘要与自动分类 is on; administrators can ask again. */
class KnowledgeAnalysisControllerTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/knowledge-analysis-controller-" + System.nanoTime());
    }

    private static final String MEMBER = "Bearer " + LocalAuth.issueToken("demo", 1L, "USER");
    private static final String ADMIN = "Bearer " + LocalAuth.issueToken("admin", 2L, "ADMIN");

    private final InMemoryKnowledgeStore store = new InMemoryKnowledgeStore();
    private final PlatformConfigClient config = mock(PlatformConfigClient.class);

    /** Records what it is asked to analyse instead of doing it. */
    private static final class RecordingRunner extends KnowledgeAnalysisRunner {
        final List<Long> submitted = new ArrayList<>();

        RecordingRunner(InMemoryKnowledgeStore store) {
            super(store, new AiDocumentAnalysis("http://localhost:0/unused", "t", Duration.ofSeconds(1)));
        }

        @Override
        public boolean submit(Long fileId, String title, String text) {
            if (text == null || text.isBlank()) return false;
            submitted.add(fileId);
            return true;
        }
    }

    private final RecordingRunner runner = new RecordingRunner(store);

    private KnowledgeController controller(boolean analysisOn) {
        when(config.enabled("knowledge_upload_enabled", true)).thenReturn(true);
        when(config.enabled("ai_audit_enabled", false)).thenReturn(false);
        when(config.enabled("ai_analysis_enabled", false)).thenReturn(analysisOn);
        KnowledgeController controller = new KnowledgeController(store,
                new LocalFileStorageService("target/test-uploads-analysis", "local", "http://127.0.0.1:9000", "ai-knowledge"),
                new LocalFullTextSearchService("local", "http://127.0.0.1:9200", "ai-knowledge"),
                new DocumentTextExtractor(), config);
        controller.setAnalysisRunner(runner);
        return controller;
    }

    private Long upload(KnowledgeController controller, String content) {
        ApiResponse<Map<String, Object>> uploaded = controller.upload(MEMBER, Map.of(
                "title", "检索增强生成", "fileType", "txt", "content", content));
        assertEquals(0, uploaded.code(), uploaded.message());
        return ((Number) uploaded.data().get("id")).longValue();
    }

    @Test
    void anUploadAsksForAnalysisOnlyWhileTheSwitchIsOn() {
        Long analysed = upload(controller(true), "一段关于检索增强生成的正文。");
        upload(controller(false), "另一段正文。");

        assertEquals(List.of(analysed), runner.submitted);
    }

    @Test
    void theViewCarriesTheSummaryAndTheSuggestion() {
        KnowledgeController controller = controller(true);
        Long fileId = upload(controller, "一段关于检索增强生成的正文。");
        Long category = store.listCategories().get(0).getId();
        store.saveAnalysis(fileId, "介绍检索增强生成。", category);

        Map<String, Object> view = controller.page(ADMIN, null, 12, null, null, null, true).data();
        @SuppressWarnings("unchecked")
        Map<String, Object> item = ((List<Map<String, Object>>) view.get("items")).stream()
                .filter(entry -> fileId.equals(((Number) entry.get("id")).longValue())).findFirst().orElseThrow();
        assertEquals("介绍检索增强生成。", item.get("summary"));
        assertEquals(category, item.get("suggestedCategoryId"));
    }

    @Test
    void anAdministratorCanAskAgainForAFileWithText() {
        KnowledgeController controller = controller(true);
        Long fileId = upload(controller, "一段关于检索增强生成的正文。");
        runner.submitted.clear();

        assertEquals("需要管理员权限", controller.analyzeFile(MEMBER, Map.of("fileId", fileId)).message());
        assertEquals(0, controller.analyzeFile(ADMIN, Map.of("fileId", fileId)).code());
        assertEquals(List.of(fileId), runner.submitted);
        assertTrue(controller.analyzeFile(ADMIN, Map.of("fileId", 987654)).code() != 0);
        assertEquals("AI 摘要与自动分类未开启", controller(false).analyzeFile(ADMIN, Map.of("fileId", fileId)).message());
    }
}
