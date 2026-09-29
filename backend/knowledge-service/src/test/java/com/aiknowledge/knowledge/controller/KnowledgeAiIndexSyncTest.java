package com.aiknowledge.knowledge.controller;

import com.aiknowledge.common.AiContentReview;
import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.common.PlatformConfigClient;
import com.aiknowledge.knowledge.analysis.AiKnowledgeIndex;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What AI 问答 draws on follows the library from the knowledge service itself: files approved by AI review reach
 * the index, and files deleted, hidden or rejected leave it — whoever made the change.
 */
class KnowledgeAiIndexSyncTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/knowledge-index-sync-" + System.nanoTime());
    }

    private static final String MEMBER = "Bearer " + LocalAuth.issueToken("demo", 1L, "USER");
    private static final String ADMIN = "Bearer " + LocalAuth.issueToken("admin", 2L, "ADMIN");
    private static final String TEXT = "一段足够长的正文，讲的是检索增强生成的做法。";

    private final InMemoryKnowledgeStore store = new InMemoryKnowledgeStore();
    private final PlatformConfigClient config = mock(PlatformConfigClient.class);
    private final List<String> sent = new ArrayList<>();

    /** Records what would be sent to the AI service. */
    private final AiKnowledgeIndex index = new AiKnowledgeIndex("http://localhost:0/unused", "t", null) {
        @Override
        public void publish(Long fileId, String title, String text) {
            sent.add("publish " + fileId + " " + title + (text == null || text.isBlank() ? " (empty)" : ""));
        }

        @Override
        public void withdraw(Long fileId) {
            sent.add("withdraw " + fileId);
        }
    };

    private static final class FixedReview extends AiContentReview {
        private final Verdict verdict;

        FixedReview(Verdict verdict) {
            super("http://localhost:0/unused", "t", Duration.ofSeconds(1));
            this.verdict = verdict;
        }

        @Override
        public Verdict review(String kind, String title, String text) {
            return verdict;
        }
    }

    private KnowledgeController controller(AiContentReview.Verdict verdict) {
        when(config.enabled("knowledge_upload_enabled", true)).thenReturn(true);
        when(config.enabled("ai_audit_enabled", false)).thenReturn(verdict != null);
        when(config.maxUploadMb()).thenReturn(25);
        when(config.pdfMaxUploadMb()).thenReturn(200);
        KnowledgeController controller = new KnowledgeController(store,
                new LocalFileStorageService("target/test-uploads-index-sync", "local", "http://127.0.0.1:9000", "ai-knowledge"),
                new LocalFullTextSearchService("local", "http://127.0.0.1:9200", "ai-knowledge"),
                new DocumentTextExtractor(), config);
        if (verdict != null) controller.setContentReview(new FixedReview(verdict));
        controller.setAiIndex(index);
        return controller;
    }

    private Long upload(KnowledgeController controller, String title) {
        ApiResponse<Map<String, Object>> uploaded = controller.upload(MEMBER, Map.of("title", title, "fileType", "txt", "content", TEXT));
        assertEquals(0, uploaded.code(), uploaded.message());
        return ((Number) uploaded.data().get("id")).longValue();
    }

    @Test
    void aFileAiReviewApprovesGoesStraightIntoTheIndex() {
        Long approved = upload(controller(new AiContentReview.Verdict(AiContentReview.APPROVE, 0.97, "正常")), "自动通过");
        upload(controller(null), "等待人工");

        assertEquals(List.of("publish " + approved + " 自动通过"), sent);
    }

    /** The same through the file upload the page uses, not only the text upload. */
    @Test
    void anUploadedFileAiReviewApprovesGoesStraightIntoTheIndexToo() {
        KnowledgeController controller = controller(new AiContentReview.Verdict(AiContentReview.APPROVE, 0.97, "正常"));
        var file = new org.springframework.mock.web.MockMultipartFile("file", "notes.txt", "text/plain",
                TEXT.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        ApiResponse<Map<String, Object>> uploaded = controller.uploadFile(MEMBER, file, "上传的文件", null, null);
        assertEquals(0, uploaded.code(), uploaded.message());

        Long fileId = ((Number) uploaded.data().get("id")).longValue();
        assertEquals(List.of("publish " + fileId + " 上传的文件"), sent);
    }

    @Test
    void approvingIndexesAndRejectingDoesNot() {
        KnowledgeController controller = controller(null);
        Long approved = upload(controller, "通过的");
        Long rejected = upload(controller, "驳回的");

        controller.audit(ADMIN, Map.of("fileId", approved, "auditStatus", "APPROVED"));
        controller.audit(ADMIN, Map.of("fileId", rejected, "auditStatus", "REJECTED", "reason", "不合适"));

        assertEquals(List.of("publish " + approved + " 通过的", "withdraw " + rejected), sent);
    }

    @Test
    void hidingRenamingAndDeletingKeepTheIndexInStep() {
        KnowledgeController controller = controller(null);
        Long fileId = upload(controller, "原标题");
        controller.audit(ADMIN, Map.of("fileId", fileId, "auditStatus", "APPROVED"));
        sent.clear();

        controller.updateFileMetadata(ADMIN, Map.of("fileId", fileId, "title", "新标题"));
        controller.updateFileMetadata(ADMIN, Map.of("fileId", fileId, "auditStatus", "HIDDEN"));
        controller.updateFileMetadata(ADMIN, Map.of("fileId", fileId, "title", "下架后改名"));
        ApiResponse<Map<String, Object>> deleted = controller.deleteFile(MEMBER, Map.of("fileId", fileId));
        assertEquals(0, deleted.code(), deleted.message());

        assertEquals(List.of("publish " + fileId + " 新标题", "withdraw " + fileId, "withdraw " + fileId), sent,
                "renamed while approved, taken out when hidden, not touched by an edit while hidden, taken out on delete");
    }

    /** Its chunks are headed with the title and nothing else, so no other edit re-embeds them. */
    @Test
    void anEditThatLeavesTheTitleAndStatusAloneSendsNothing() {
        KnowledgeController controller = controller(null);
        Long fileId = upload(controller, "原标题");
        controller.audit(ADMIN, Map.of("fileId", fileId, "auditStatus", "APPROVED"));
        Long category = store.listCategories().get(0).getId();
        sent.clear();

        assertEquals(0, controller.updateFileMetadata(ADMIN, Map.of("fileId", fileId, "categoryId", category)).code());
        assertEquals(0, controller.updateFileMetadata(ADMIN, Map.of("fileId", fileId, "title", "原标题")).code());
        assertEquals(0, controller.updateFileMetadata(ADMIN, Map.of("fileId", fileId, "auditStatus", "APPROVED")).code());
        assertEquals(category, store.find(fileId).orElseThrow().getCategoryId(), "the edit itself still happened");
        assertEquals(List.of(), sent);
    }

    @Test
    void showingAHiddenFileAgainPutsItBack() {
        KnowledgeController controller = controller(null);
        Long fileId = upload(controller, "隐藏过的");
        controller.audit(ADMIN, Map.of("fileId", fileId, "auditStatus", "APPROVED"));
        controller.updateFileMetadata(ADMIN, Map.of("fileId", fileId, "auditStatus", "HIDDEN"));
        sent.clear();

        controller.updateFileMetadata(ADMIN, Map.of("fileId", fileId, "auditStatus", "APPROVED"));
        assertEquals(List.of("publish " + fileId + " 隐藏过的"), sent);
    }

    @Test
    void anAuthorDeletingTheirOwnApprovedFileTakesItOutOfTheIndex() {
        KnowledgeController controller = controller(null);
        Long fileId = upload(controller, "作者自己的");
        controller.audit(ADMIN, Map.of("fileId", fileId, "auditStatus", "APPROVED"));
        sent.clear();

        assertEquals(0, controller.deleteFile(MEMBER, Map.of("fileId", fileId)).code());
        assertEquals(List.of("withdraw " + fileId), sent);
    }
}
