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
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    private final String adminAuth = "Bearer " + LocalAuth.issueToken("admin", 99L, "ADMIN");

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
        assertEquals(0, disabled.feed(userAuth, null).data().size());
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

        ApiResponse<List<Map<String, Object>>> feed = controller.feed(userAuth, null);
        assertEquals(0, feed.code());
        assertFalse(feed.data().isEmpty());
        assertEquals("Community Persistence", feed.data().get(0).get("title"));
        assertEquals("PENDING", created.data().get("status"));
    }

    @Test
    void pendingPostIsVisibleOnlyToOwnerAndAdminUntilApproved() {
        var created = controller.createPost(secondUserAuth, Map.of(
                "title", "Pending visibility " + System.nanoTime(),
                "content", "review before publishing"
        ));
        Long postId = ((Number) created.data().get("id")).longValue();

        assertEquals(true, controller.feed(secondUserAuth, 2L).data().stream()
                .anyMatch(post -> postId.equals(post.get("id"))));
        assertEquals(false, controller.feed(userAuth, null).data().stream()
                .anyMatch(post -> postId.equals(post.get("id"))));
        assertEquals(false, controller.followingFeed(userAuth, "2").data().stream()
                .anyMatch(post -> postId.equals(post.get("id"))));
        assertEquals(500, controller.detail(userAuth, postId).code());
        assertEquals(500, controller.likePost(userAuth, Map.of("postId", postId)).code());
        assertEquals(500, controller.squareCollect(userAuth, Map.of("postId", postId)).code());
        assertEquals(500, controller.createComment(userAuth, Map.of("postId", postId, "content", "hidden reply")).code());
        assertEquals(true, controller.feed(adminAuth, null).data().stream()
                .anyMatch(post -> postId.equals(post.get("id"))));

        assertEquals(0, controller.auditPost(adminAuth, Map.of("postId", postId, "status", "PUBLISHED")).code());
        assertEquals(0, controller.detail(userAuth, postId).code());
        assertEquals(true, controller.feed(userAuth, null).data().stream()
                .anyMatch(post -> postId.equals(post.get("id"))));
    }

    @Test
    void editingPublishedPostReturnsItToPendingReview() {
        var created = controller.createPost(secondUserAuth, Map.of("title", "Before edit", "content", "approved body"));
        Long postId = ((Number) created.data().get("id")).longValue();
        controller.auditPost(adminAuth, Map.of("postId", postId, "status", "PUBLISHED"));

        var updated = controller.updatePost(secondUserAuth, Map.of("id", postId, "title", "After edit", "content", "changed body"));

        assertEquals("PENDING", updated.data().get("status"));
        assertEquals(500, controller.detail(userAuth, postId).code());
        assertEquals(0, controller.detail(secondUserAuth, postId).code());
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
        var created = controller.createPost(secondUserAuth, Map.of("userId", 999L, "title", "followed", "content", "visible"));
        Long createdPostId = ((Number) created.data().get("id")).longValue();
        controller.auditPost(adminAuth, Map.of("postId", createdPostId, "status", "PUBLISHED"));
        var filtered = controller.followingFeed(userAuth, "2");
        assertEquals(1, filtered.data().size());
        Long postId = ((Number) filtered.data().get(0).get("id")).longValue();
        var firstLike = controller.likePost(userAuth, Map.of("userId", 999L, "postId", postId));
        var unlike = controller.likePost(userAuth, Map.of("userId", 999L, "postId", postId));
        assertEquals(true, firstLike.data().get("created"));
        assertEquals(false, unlike.data().get("liked"));
        assertEquals(0L, unlike.data().get("likes"));
    }

    @Test
    void postCollectionCanBeToggledAndAppearsInPostViews() {
        var collected = controller.squareCollect(userAuth, Map.of("postId", 1L));
        assertEquals(0, collected.code());
        assertEquals(true, collected.data().get("collected"));
        assertEquals(true, controller.detail(userAuth, 1L).data().get("collected"));
        assertEquals(1, controller.squareCollections(userAuth, null).data().size());
        assertEquals(true, controller.squareCollections(userAuth, null).data().get(0).get("collected"));

        var removed = controller.squareCollect(userAuth, Map.of("postId", 1L));
        assertEquals(0, removed.code());
        assertEquals(false, removed.data().get("collected"));
        assertEquals(false, controller.feed(userAuth, null).data().stream()
                .filter(post -> Long.valueOf(1L).equals(post.get("id")))
                .findFirst().orElseThrow().get("collected"));
        assertEquals(0, controller.squareCollections(userAuth, null).data().size());
        assertEquals(500, controller.squareCollections(secondUserAuth, 1L).code());
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
        int initialCommentCount = controller.comments(userAuth, 1L).data().size();
        ApiResponse<Map<String, Object>> comment = controller.createComment(userAuth, Map.of(
                "postId", 1L,
                "userId", 1L,
                "content", "detail comment"
        ));
        assertEquals(0, comment.code());
        Long parentId = ((Number) comment.data().get("id")).longValue();

        ApiResponse<Map<String, Object>> reply = controller.createComment(secondUserAuth, Map.of(
                "postId", 1L,
                "parentId", parentId,
                "content", "reply to comment"
        ));
        assertEquals(0, reply.code());
        assertEquals(parentId, reply.data().get("parentId"));

        ApiResponse<Map<String, Object>> invalidReply = controller.createComment(secondUserAuth, Map.of(
                "postId", 1L,
                "parentId", 999999L,
                "content", "invalid reply"
        ));
        assertEquals(500, invalidReply.code());

        ApiResponse<List<Map<String, Object>>> comments = controller.comments(userAuth, 1L);
        assertEquals(0, comments.code());
        assertEquals(initialCommentCount + 2, comments.data().size());

        ApiResponse<Map<String, Object>> detail = controller.detail(userAuth, 1L);
        assertEquals(0, detail.code());
        assertFalse(((List<?>) detail.data().get("comments")).isEmpty());
    }

    @Test
    void deletingCommentAlsoDeletesItsReplies() {
        var parent = controller.createComment(userAuth, Map.of("postId", 1L, "content", "parent to remove"));
        Long parentId = ((Number) parent.data().get("id")).longValue();
        var reply = controller.createComment(secondUserAuth, Map.of(
                "postId", 1L, "parentId", parentId, "content", "child to remove"));
        Long replyId = ((Number) reply.data().get("id")).longValue();

        assertEquals(500, controller.removeOwnComment(secondUserAuth, Map.of("commentId", parentId)).code());
        assertEquals(true, controller.removeOwnComment(userAuth, Map.of("commentId", parentId)).data().get("removed"));
        List<Map<String, Object>> remaining = controller.comments(userAuth, 1L).data();
        assertFalse(remaining.stream().anyMatch(item -> parentId.equals(item.get("id"))));
        assertFalse(remaining.stream().anyMatch(item -> replyId.equals(item.get("id"))));
    }

    @Test
    void ownerCanDeletePostAndAssociatedInteractions() {
        var created = controller.createPost(userAuth, Map.of(
                "title", "Post to delete", "content", "removable post", "imageUrls", List.of("local-file://one.png")));
        Long postId = ((Number) created.data().get("id")).longValue();
        assertEquals(0, controller.auditPost(adminAuth, Map.of("postId", postId, "status", "PUBLISHED")).code());
        assertEquals(0, controller.createComment(secondUserAuth, Map.of("postId", postId, "content", "temporary comment")).code());
        assertEquals(0, controller.likePost(secondUserAuth, Map.of("postId", postId)).code());
        assertEquals(0, controller.squareCollect(secondUserAuth, Map.of("postId", postId)).code());

        assertEquals(500, controller.removePost(secondUserAuth, Map.of("postId", postId)).code());
        assertEquals(true, controller.removePost(userAuth, Map.of("postId", postId)).data().get("removed"));
        assertFalse(controller.feed(adminAuth, null).data().stream().anyMatch(item -> postId.equals(item.get("id"))));
        assertTrue(controller.squareCollections(secondUserAuth, null).data().stream().noneMatch(item -> postId.equals(item.get("id"))));
        assertEquals(500, controller.detail(userAuth, postId).code());
    }
}
