package com.aiknowledge.community.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.common.PlatformConfigClient;
import com.aiknowledge.community.store.InMemoryCommunityStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommunityControllerTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/community-" + System.nanoTime());
    }

    private final CommunityController controller =
            new CommunityController(
                    new InMemoryCommunityStore(),
                    new com.aiknowledge.community.storage.CommunityMediaStorageService(
                            "local", "target/test-community-media", "http://127.0.0.1:9000",
                            "ai-community", "test", "test-password")
            );
    private final String userAuth = "Bearer " + LocalAuth.issueToken("demo");
    private final String secondUserAuth = "Bearer " + LocalAuth.issueToken("author", 2L, "USER");

    @Test
    void disabledCommunityRejectsUserWrites() {
        PlatformConfigClient config = mock(PlatformConfigClient.class);
        when(config.enabled("community_enabled", true)).thenReturn(false);
        CommunityController disabled = new CommunityController(
                new InMemoryCommunityStore(),
                new com.aiknowledge.community.storage.CommunityMediaStorageService(
                        "local", "target/test-community-media", "http://127.0.0.1:9000",
                        "ai-community", "test", "test-password"),
                new com.aiknowledge.community.notification.CommunityNotificationClient(false, "", ""),
                config
        );
        assertEquals(500, disabled.createPost(userAuth, Map.of("title", "blocked", "content", "blocked")).code());
        assertEquals(0, disabled.feed(null).data().size());
    }

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
    void imageCanBeUploadedAndReadFromMediaEndpoint() {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0};
        var file = new org.springframework.mock.web.MockMultipartFile("files", "cover.png", "image/png", png);
        var uploaded = controller.uploadImages(userAuth, List.of(file));
        assertEquals(0, uploaded.code());
        String url = String.valueOf(((List<?>) uploaded.data().get("imageUrls")).get(0));
        var response = controller.media(url.substring(url.lastIndexOf('/') + 1));
        assertEquals(200, response.getStatusCode().value());
        assertEquals("image/png", response.getHeaders().getContentType().toString());
        assertEquals(png.length, response.getBody().length);
    }

    @Test
    void postCanBeLikedAndFollowingFeedCanBeFiltered() {
        controller.createPost(secondUserAuth, Map.of("userId", 999L, "title", "followed", "content", "visible"));
        var filtered = controller.followingFeed("2");
        assertEquals(1, filtered.data().size());
        Long postId = ((Number) filtered.data().get(0).get("id")).longValue();
        var firstLike = controller.likePost(userAuth, Map.of("userId", 999L, "postId", postId));
        var duplicateLike = controller.likePost(userAuth, Map.of("userId", 999L, "postId", postId));
        assertEquals(true, firstLike.data().get("created"));
        assertEquals(false, duplicateLike.data().get("created"));
        assertEquals(1L, duplicateLike.data().get("likes"));
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
    void ownerCanEditPublishAndDeleteDrafts() {
        var editable = controller.saveDraft(userAuth, Map.of("title", "First", "content", "Draft body"));
        Long editableId = ((Number) editable.data().get("id")).longValue();
        var updated = controller.updateDraft(userAuth, Map.of("id", editableId, "title", "Updated", "content", "Ready"));
        assertEquals(0, updated.code());
        assertEquals("Updated", updated.data().get("title"));

        var published = controller.publishDraft(userAuth, Map.of("id", editableId, "title", "Published"));
        assertEquals(0, published.code());
        assertEquals("Published", published.data().get("title"));
        assertFalse(controller.drafts(userAuth, 1L).data().stream().anyMatch(item -> editableId.equals(item.get("id"))));

        var removable = controller.saveDraft(userAuth, Map.of("title", "Remove", "content", "Later"));
        Long removableId = ((Number) removable.data().get("id")).longValue();
        assertEquals(0, controller.removeOwnDraft(userAuth, Map.of("draftId", removableId)).code());
    }

    @Test
    void anotherUserCannotManageDraft() {
        var draft = controller.saveDraft(userAuth, Map.of("title", "Private", "content", "Owner only"));
        Long draftId = ((Number) draft.data().get("id")).longValue();
        assertEquals(500, controller.updateDraft(secondUserAuth, Map.of("id", draftId, "title", "Denied")).code());
        assertEquals(500, controller.publishDraft(secondUserAuth, Map.of("id", draftId)).code());
        assertEquals(500, controller.removeOwnDraft(secondUserAuth, Map.of("draftId", draftId)).code());
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
