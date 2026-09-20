package com.aiknowledge.community.controller;

import com.aiknowledge.common.AdminAudit;
import com.aiknowledge.common.AuditEntry;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.community.storage.CommunityMediaStorageService;
import com.aiknowledge.community.store.InMemoryCommunityStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which community actions end up in the administrator action log. */
class CommunityAuditTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/community-audit-" + System.nanoTime());
    }

    private static final String AUTHOR = "Bearer " + LocalAuth.issueToken("author", 52L, "USER");
    private static final String ADMIN = "Bearer " + LocalAuth.issueToken("admin", 99L, "ADMIN");

    private final List<AuditEntry> entries = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AdminAudit recorder = (authorization, event) -> {
        AuditEntry entry = AuditEntry.forRequest(authorization, event, "test");
        if (entry != null) entries.add(entry);
    };

    private List<String> actions() {
        return entries.stream().map(AuditEntry::action).toList();
    }

    private final CommunityController controller = new CommunityController(new InMemoryCommunityStore(),
            new CommunityMediaStorageService("local", "target/test-community-media", "http://127.0.0.1:9000",
                    "ai-community", "test", "test-password"));

    {
        controller.setAdminAudit(recorder);
    }

    private Long publishedPost(String title) {
        Long postId = ((Number) controller.createPost(AUTHOR, Map.of("title", title, "content", "正文")).data().get("id")).longValue();
        controller.auditPost(ADMIN, Map.of("postId", postId, "status", "PUBLISHED"));
        return postId;
    }

    @Test
    void moderationOfPostsAndCommentsIsRecorded() {
        String title = "审计帖子 " + System.nanoTime();
        Long postId = publishedPost(title);
        Long commentId = ((Number) controller.createComment(AUTHOR, Map.of("postId", postId, "content", "一条   需要 隐藏的评论")).data().get("id")).longValue();

        controller.updateCommentStatus(ADMIN, Map.of("commentId", commentId, "status", "HIDDEN"));
        controller.updateCommentStatus(ADMIN, Map.of("commentId", commentId, "status", "HIDDEN"));
        controller.removeComment(ADMIN, Map.of("commentId", commentId));
        controller.updatePost(ADMIN, Map.of("id", postId, "title", title + " 已编辑", "content", "新的正文"));
        controller.auditPost(ADMIN, Map.of("postId", postId, "status", "HIDDEN", "reason", "广告"));
        controller.removePost(ADMIN, Map.of("postId", postId));

        assertEquals(List.of("POST_AUDIT", "COMMENT_STATUS", "COMMENT_DELETE", "POST_EDIT", "POST_AUDIT", "POST_DELETE"), actions());
        assertEquals(52L, entries.get(0).subjectUserId());
        assertEquals("发布帖子", entries.get(0).summary());
        assertEquals("一条 需要 隐藏的评论", entries.get(1).targetLabel());
        assertEquals(postId, entries.get(2).detail().get("postId"));
        String edit = entries.get(3).detail().get("changes").toString();
        assertTrue(edit.contains("标题") && edit.contains("正文") && !edit.contains("新的正文"), edit);
        assertEquals("广告", entries.get(4).detail().get("reason"));
        assertEquals("删除他人帖子", entries.get(5).summary());
    }

    @Test
    void authorsManagingTheirOwnContentAreNotRecorded() {
        Long postId = ((Number) controller.createPost(ADMIN, Map.of("title", "管理员公告 " + System.nanoTime(), "content", "x")).data().get("id")).longValue();
        controller.updatePost(ADMIN, Map.of("id", postId, "title", "改标题", "content", "y"));
        controller.removePost(ADMIN, Map.of("postId", postId));
        Long own = ((Number) controller.createPost(AUTHOR, Map.of("title", "作者的帖子", "content", "x")).data().get("id")).longValue();
        controller.removePost(AUTHOR, Map.of("postId", own));
        assertEquals(List.of(), actions());
    }

    @Test
    void draftRemovalsAreRecorded() {
        Long draftId = ((Number) controller.saveDraft(AUTHOR, Map.of("title", "作者草稿", "content", "x")).data().get("id")).longValue();
        Long secondDraft = ((Number) controller.saveDraft(AUTHOR, Map.of("title", "", "content", "y")).data().get("id")).longValue();
        controller.removeDraft(ADMIN, Map.of("draftId", draftId));
        controller.removeOwnDraft(ADMIN, Map.of("draftId", secondDraft));
        controller.removeExpiredDrafts(ADMIN, Map.of("retentionDays", 30));
        assertEquals(List.of("DRAFT_DELETE", "DRAFT_DELETE", "DRAFTS_PURGE"), actions());
        assertEquals("作者草稿", entries.get(0).targetLabel());
        assertEquals("未命名草稿", entries.get(1).targetLabel());
        assertEquals(52L, entries.get(1).subjectUserId());
        assertEquals(30L, entries.get(2).detail().get("retentionDays"));
    }
}
