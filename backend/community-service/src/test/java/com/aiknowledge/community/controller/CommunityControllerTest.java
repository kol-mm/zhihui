package com.aiknowledge.community.controller;

import com.aiknowledge.common.ApiResponse;
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

    @Test
    void createdPostAppearsInFeed() {
        ApiResponse<Map<String, Object>> created = controller.createPost(Map.of(
                "userId", 1L,
                "title", "Community Persistence",
                "content", "forum post"
        ));
        assertEquals(0, created.code());

        ApiResponse<List<Map<String, Object>>> feed = controller.feed(null);
        assertEquals(0, feed.code());
        assertFalse(feed.data().isEmpty());
        assertEquals("Community Persistence", feed.data().get(0).get("title"));
    }

    @Test
    void draftCanBeSavedAndListed() {
        ApiResponse<Map<String, Object>> draft = controller.saveDraft(Map.of(
                "userId", 1L,
                "title", "Draft title",
                "content", "draft content"
        ));
        assertEquals(0, draft.code());

        ApiResponse<List<Map<String, Object>>> drafts = controller.drafts(1L);
        assertEquals(0, drafts.code());
        assertFalse(drafts.data().isEmpty());
        assertEquals("Draft title", drafts.data().get(0).get("title"));
    }

    @Test
    void squareQuickCommentUsesSquareSource() {
        ApiResponse<Map<String, Object>> comment = controller.quickComment(Map.of(
                "postId", 1L,
                "userId", 1L,
                "content", "quick comment"
        ));
        assertEquals(0, comment.code());
        assertEquals("SQUARE", comment.data().get("source"));
    }
}
