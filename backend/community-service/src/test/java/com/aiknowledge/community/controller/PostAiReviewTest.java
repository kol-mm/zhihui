package com.aiknowledge.community.controller;

import com.aiknowledge.common.AiContentReview;
import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.common.PlatformConfigClient;
import com.aiknowledge.community.storage.CommunityMediaStorageService;
import com.aiknowledge.community.store.InMemoryCommunityStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Posts get the same first pass as knowledge files: the AI clears or refuses what it is sure about, and
 * everything else waits for a person exactly as it did before.
 */
class PostAiReviewTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/post-review-" + System.nanoTime());
    }

    private static final String MEMBER = "Bearer " + LocalAuth.issueToken("demo", 1L, "USER");

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

    private CommunityController controllerThat(AiContentReview.Verdict verdict, boolean reviewEnabled) {
        PlatformConfigClient config = mock(PlatformConfigClient.class);
        when(config.enabled("community_enabled", true)).thenReturn(true);
        when(config.enabled("post_audit_required", true)).thenReturn(true);
        when(config.enabled("ai_audit_enabled", false)).thenReturn(reviewEnabled);
        CommunityController controller = new CommunityController(
                new InMemoryCommunityStore(),
                new CommunityMediaStorageService("local", "target/test-community-media",
                        "http://127.0.0.1:9000", "ai-community", "test", "test-password"),
                new com.aiknowledge.community.notification.CommunityNotificationClient(false, "", ""),
                config);
        controller.setContentReview(new FixedReview(verdict));
        return controller;
    }

    private Map<String, Object> post(CommunityController controller, String title) {
        ApiResponse<Map<String, Object>> created = controller.createPost(MEMBER, Map.of(
                "title", title, "content", "一段足够长的帖子正文，用来走完整的审核判断流程。"));
        assertEquals(0, created.code(), created.message());
        return created.data();
    }

    @Test
    void aPostTheReviewerClearsIsPublishedStraightAway() {
        var controller = controllerThat(new AiContentReview.Verdict("APPROVE", 0.92, "正常讨论"), true);

        Map<String, Object> created = post(controller, "自动通过帖子");

        assertEquals("PUBLISHED", created.get("status"));
        assertEquals("AI", created.get("auditSource"));
        assertEquals("正常讨论", created.get("auditReason"));
    }

    @Test
    void aPostTheReviewerRefusesIsHiddenWithItsReason() {
        var controller = controllerThat(new AiContentReview.Verdict("REJECT", 0.97, "人身攻击"), true);

        Map<String, Object> created = post(controller, "自动驳回帖子");

        assertEquals("HIDDEN", created.get("status"));
        assertEquals("AI", created.get("auditSource"));
        assertEquals("人身攻击", created.get("auditReason"));
    }

    @Test
    void aPostTheReviewerCannotJudgeWaitsForAPerson() {
        var controller = controllerThat(AiContentReview.Verdict.escalate("模型把握不足"), true);

        Map<String, Object> created = post(controller, "转人工帖子");

        assertEquals("PENDING", created.get("status"));
        assertNull(created.get("auditSource"));
    }

    @Test
    void anUnavailableReviewerLeavesThePostWaitingRatherThanHidingIt() {
        var controller = controllerThat(AiContentReview.Verdict.escalate("AI 审核暂不可用"), true);

        assertEquals("PENDING", post(controller, "服务不可用帖子").get("status"));
    }

    @Test
    void withReviewOffThePostQueuesAsItAlwaysDid() {
        var controller = controllerThat(new AiContentReview.Verdict("APPROVE", 1.0, "不应被采用"), false);

        Map<String, Object> created = post(controller, "未开启帖子");

        assertEquals("PENDING", created.get("status"));
        assertNull(created.get("auditSource"));
    }

    @Test
    void whenNothingNeedsReviewingTheReviewerIsNotConsulted() {
        PlatformConfigClient config = mock(PlatformConfigClient.class);
        when(config.enabled("community_enabled", true)).thenReturn(true);
        when(config.enabled("post_audit_required", true)).thenReturn(false);
        when(config.enabled("ai_audit_enabled", false)).thenReturn(true);
        CommunityController controller = new CommunityController(
                new InMemoryCommunityStore(),
                new CommunityMediaStorageService("local", "target/test-community-media",
                        "http://127.0.0.1:9000", "ai-community", "test", "test-password"),
                new com.aiknowledge.community.notification.CommunityNotificationClient(false, "", ""),
                config);
        controller.setContentReview(new FixedReview(new AiContentReview.Verdict("REJECT", 1.0, "不应被采用")));

        Map<String, Object> created = post(controller, "无需审核帖子");

        assertEquals("PUBLISHED", created.get("status"), "the platform is not holding posts, so none are reviewed");
        assertNull(created.get("auditSource"));
    }
}
