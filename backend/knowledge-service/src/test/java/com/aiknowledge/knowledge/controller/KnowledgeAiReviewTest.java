package com.aiknowledge.knowledge.controller;

import com.aiknowledge.common.AiContentReview;
import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.common.PlatformConfigClient;
import com.aiknowledge.knowledge.search.LocalFullTextSearchService;
import com.aiknowledge.knowledge.storage.DocumentTextExtractor;
import com.aiknowledge.knowledge.storage.LocalFileStorageService;
import com.aiknowledge.knowledge.store.InMemoryKnowledgeStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI review is the first pass over an upload. Whatever it cannot decide — including an outage — stays PENDING
 * for a person, which is how the platform behaved before there was any AI review at all.
 */
class KnowledgeAiReviewTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/knowledge-review-" + System.nanoTime());
    }

    private static final String MEMBER = "Bearer " + LocalAuth.issueToken("demo", 1L, "USER");

    private final InMemoryKnowledgeStore store = new InMemoryKnowledgeStore();
    private final PlatformConfigClient config = mock(PlatformConfigClient.class);

    /** Stands in for the AI service; the real client is exercised by its own tests. */
    private static final class FixedReview extends AiContentReview {
        private final Verdict verdict;

        private FixedReview(Verdict verdict) {
            super("http://localhost:0/unused", "token", Duration.ofSeconds(1));
            this.verdict = verdict;
        }

        @Override
        public Verdict review(String kind, String title, String text) {
            return verdict;
        }
    }

    private KnowledgeController controllerThat(AiContentReview.Verdict verdict, boolean reviewEnabled) {
        when(config.enabled("knowledge_upload_enabled", true)).thenReturn(true);
        when(config.enabled("ai_audit_enabled", false)).thenReturn(reviewEnabled);
        KnowledgeController controller = new KnowledgeController(
                store,
                new LocalFileStorageService("target/test-uploads-review", "local", "http://127.0.0.1:9000", "ai-knowledge"),
                new LocalFullTextSearchService("local", "http://127.0.0.1:9200", "ai-knowledge"),
                new DocumentTextExtractor(), config);
        controller.setContentReview(new FixedReview(verdict));
        return controller;
    }

    private Map<String, Object> upload(KnowledgeController controller, String title) {
        ApiResponse<Map<String, Object>> uploaded = controller.upload(MEMBER, Map.of(
                "title", title, "fileType", "txt", "content", "一段足够长的正文，用于走完整的审核流程判断。"));
        assertEquals(0, uploaded.code(), uploaded.message());
        return uploaded.data();
    }

    @Test
    void contentTheReviewerClearsIsPublishedWithoutWaitingForAPerson() {
        var controller = controllerThat(new AiContentReview.Verdict("APPROVE", 0.9, "内容正常"), true);

        Map<String, Object> file = upload(controller, "自动通过样本");

        assertEquals("APPROVED", file.get("auditStatus"));
        assertEquals("AI", file.get("auditSource"));
        assertEquals("内容正常", file.get("auditReason"));
    }

    @Test
    void contentTheReviewerRefusesIsRejectedWithItsReason() {
        var controller = controllerThat(new AiContentReview.Verdict("REJECT", 0.95, "含有广告推广"), true);

        Map<String, Object> file = upload(controller, "自动驳回样本");

        assertEquals("REJECTED", file.get("auditStatus"));
        assertEquals("AI", file.get("auditSource"));
        assertEquals("含有广告推广", file.get("auditReason"));
    }

    @Test
    void contentTheReviewerCannotJudgeWaitsForAPerson() {
        var controller = controllerThat(AiContentReview.Verdict.escalate("模型把握不足"), true);

        Map<String, Object> file = upload(controller, "转人工样本");

        assertEquals("PENDING", file.get("auditStatus"));
        assertNull(file.get("auditSource"), "nobody has decided yet");
    }

    @Test
    void anUnavailableReviewerNeverRejectsOrPublishesByItself() {
        var controller = controllerThat(AiContentReview.Verdict.escalate("AI 审核暂不可用"), true);

        Map<String, Object> file = upload(controller, "服务不可用样本");

        assertEquals("PENDING", file.get("auditStatus"));
    }

    @Test
    void withReviewTurnedOffNothingIsAskedAndEverythingWaits() {
        var controller = controllerThat(new AiContentReview.Verdict("APPROVE", 1.0, "不应被采用"), false);

        Map<String, Object> file = upload(controller, "未开启样本");

        assertEquals("PENDING", file.get("auditStatus"), "the switch is off, so the verdict is never asked for");
        assertNull(file.get("auditSource"));
    }

    @Test
    void aPersonsDecisionIsRecordedWithItsReasonToo() {
        var controller = controllerThat(AiContentReview.Verdict.escalate("转人工"), true);
        Map<String, Object> file = upload(controller, "人工审核样本");
        long fileId = ((Number) file.get("id")).longValue();
        String adminAuth = "Bearer " + LocalAuth.issueToken("admin", 2L, "ADMIN");

        assertEquals(0, controller.audit(adminAuth, Map.of(
                "fileId", fileId, "auditStatus", "REJECTED", "reason", "与知识库主题无关")).code());

        var stored = store.find(fileId).orElseThrow();
        assertEquals("REJECTED", stored.getAuditStatus());
        assertEquals("MANUAL", stored.getAuditSource());
        // The reason used to be accepted and thrown away.
        assertEquals("与知识库主题无关", stored.getAuditReason());
    }
}
