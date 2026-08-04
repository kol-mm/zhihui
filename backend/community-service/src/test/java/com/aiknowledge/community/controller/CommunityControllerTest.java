package com.aiknowledge.community.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.community.store.InMemoryCommunityStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CommunityControllerTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/community-" + System.nanoTime());
    }

    private final CommunityController controller =
            new CommunityController(new InMemoryCommunityStore());
    private final String userAuth = "Bearer " + LocalAuth.issueToken("demo");
    private final String secondUserAuth = "Bearer " + LocalAuth.issueToken("author", 2L, "USER");

    @Test
    void createdPostAppearsInFeed() {
        ApiResponse<Map<String, Object>> created = controller.createPost(userAuth, Map.of(
                "userId", 1L,
                "title", "Community Persistence",
                "content", "forum post",
                "imageUrls", List.of("local-file://one.png", "local-file://two.png")
        ));
        assertEquals(0, created.code());
        assertEquals(2, ((List<?>) created.data().get("imageUrls")).size());

        ApiResponse<List<Map<String, Object>>> feed = controller.feed(null);
        assertEquals(0, feed.code());
        assertFalse(feed.data().isEmpty());
        assertEquals("Community Persistence", feed.data().get(0).get("title"));
    }

    @Test
    void postCanBeLikedAndFollowingFeedCanBeFiltered() {
        controller.createPost(secondUserAuth, Map.of("userId", 999L, "title", "followed", "content", "visible"));
        var filtered = controller.followingFeed("2");
        assertEquals(1, filtered.data().size());
        Long postId = ((Number) filtered.data().get(0).get("id")).longValue();
        assertEquals(1L, controller.likePost(userAuth, Map.of("userId", 999L, "postId", postId)).data().get("likes"));
    }

    @Test
    void draftCanBeSavedAndListed() {
        ApiResponse<Map<String, Object>> draft = controller.saveDraft(userAuth, Map.of(
                "userId", 1L,
                "title", "Draft title",
                "content", "draft content"
        ));
        assertEquals(0, draft.code());

        ApiResponse<List<Map<String, Object>>> drafts = controller.drafts(userAuth, 1L);
        assertEquals(0, drafts.code());
        assertFalse(drafts.data().isEmpty());
        assertEquals("Draft title", drafts.data().get(0).get("title"));
    }

    @Test
    void squareQuickCommentUsesSquareSource() {
        ApiResponse<Map<String, Object>> comment = controller.quickComment(userAuth, Map.of(
                "postId", 1L,
                "userId", 1L,
                "content", "quick comment"
        ));
        assertEquals(0, comment.code());
        assertEquals("SQUARE", comment.data().get("source"));
    }

    @Test
    void commentsCanBeListedWithPostDetail() {
        ApiResponse<Map<String, Object>> comment = controller.createComment(userAuth, Map.of(
                "postId", 1L,
                "userId", 1L,
                "content", "detail comment"
        ));
        assertEquals(0, comment.code());

        ApiResponse<List<Map<String, Object>>> comments = controller.comments(1L);
        assertEquals(0, comments.code());
        assertFalse(comments.data().isEmpty());

        ApiResponse<Map<String, Object>> detail = controller.detail(1L);
        assertEquals(0, detail.code());
        assertFalse(((List<?>) detail.data().get("comments")).isEmpty());
    }
}
