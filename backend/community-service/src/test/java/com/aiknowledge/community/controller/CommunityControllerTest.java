package com.aiknowledge.community.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.common.PlatformConfigClient;
import com.aiknowledge.common.UserRelationClient;
import com.aiknowledge.community.store.InMemoryCommunityStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    void platformConfigurationControlsCommentsImagesAndPostAudit() {
        PlatformConfigClient config = mock(PlatformConfigClient.class);
        when(config.enabled("community_enabled", true)).thenReturn(true);
        when(config.enabled("comments_enabled", true)).thenReturn(false);
        when(config.enabled("post_audit_required", true)).thenReturn(false);
        when(config.integer("max_post_images", 9)).thenReturn(1);
        CommunityController configured = new CommunityController(
                new InMemoryCommunityStore(),
                new com.aiknowledge.community.storage.CommunityMediaStorageService(
                        "local", "target/test-community-media", "http://127.0.0.1:9000",
                        "ai-community", "test", "test-password"),
                new com.aiknowledge.community.notification.CommunityNotificationClient(false, "", ""), config
        );

        var created = configured.createPost(userAuth, Map.of("title", "Direct publish", "content", "body"));
        assertEquals("PUBLISHED", created.data().get("status"));
        assertEquals(500, configured.createPost(userAuth, Map.of(
                "title", "Too many images", "content", "body", "imageUrls", List.of("one", "two"))).code());
        assertEquals(500, configured.createComment(userAuth, Map.of(
                "postId", created.data().get("id"), "content", "disabled comment")).code());
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
        var repeated = controller.auditPost(adminAuth, Map.of("postId", postId, "status", "PUBLISHED"));
        assertEquals(500, repeated.code());
        assertEquals("该帖子已经发布", repeated.message());
    }

    @Test
    void storeRejectsRepeatedPostStatusWrite() {
        InMemoryCommunityStore store = new InMemoryCommunityStore();
        CommunityController isolated = new CommunityController(
                store,
                new com.aiknowledge.community.storage.CommunityMediaStorageService(
                        "local", "target/test-community-media", "http://127.0.0.1:9000",
                        "ai-community", "test", "test-password")
        );
        var created = isolated.createPost(secondUserAuth, Map.of(
                "title", "Atomic audit " + System.nanoTime(),
                "content", "only one status update is allowed"
        ));
        Long postId = ((Number) created.data().get("id")).longValue();

        try {
            assertTrue(store.auditPost(postId, "PUBLISHED", "first").isPresent());
            assertTrue(store.auditPost(postId, "PUBLISHED", "repeated").isEmpty());
        } finally {
            store.removePost(postId);
        }
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
                "content", "draft content",
                "imageUrls", List.of("/post/media/draft-image")
        ));
        assertEquals(0, draft.code());

        ApiResponse<List<Map<String, Object>>> drafts = controller.drafts(userAuth, 1L);
        assertEquals(0, drafts.code());
        assertFalse(drafts.data().isEmpty());
        assertEquals("Draft title", drafts.data().get(0).get("title"));
        assertEquals(List.of("/post/media/draft-image"), drafts.data().get(0).get("imageUrls"));
    }

    @Test
    void ownerCanEditPublishAndDeleteDrafts() {
        var editable = controller.saveDraft(userAuth, Map.of("title", "First", "content", "Draft body"));
        Long editableId = ((Number) editable.data().get("id")).longValue();
        var updated = controller.updateDraft(userAuth, Map.of("id", editableId, "title", "Updated", "content", "Ready",
                "imageUrls", List.of("/post/media/first", "/post/media/second")));
        assertEquals(0, updated.code());
        assertEquals("Updated", updated.data().get("title"));
        assertEquals(2, ((List<?>) updated.data().get("imageUrls")).size());

        var published = controller.publishDraft(userAuth, Map.of("id", editableId, "title", "Published"));
        assertEquals(0, published.code());
        assertEquals("Published", published.data().get("title"));
        assertEquals(List.of("/post/media/first", "/post/media/second"), published.data().get("imageUrls"));
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
        assertFalse(detail.data().containsKey("comments"));
        assertEquals((long) initialCommentCount + 2, ((Number) detail.data().get("commentCount")).longValue());
    }

    @Test
    void commentThreadsPageByRootAndKeepNestedRepliesAttached() {
        Long postId = publishedPost("Threaded discussion");
        Long first = idOf(controller.createComment(userAuth, Map.of("postId", postId, "content", "root 1")));
        Long reply = idOf(controller.createComment(secondUserAuth, Map.of("postId", postId, "parentId", first, "content", "reply 1")));
        Long second = idOf(controller.createComment(secondUserAuth, Map.of("postId", postId, "content", "root 2")));
        Long nested = idOf(controller.createComment(userAuth, Map.of("postId", postId, "parentId", reply, "content", "nested reply")));
        Long third = idOf(controller.createComment(userAuth, Map.of("postId", postId, "content", "root 3")));

        Map<String, Object> page = controller.commentThreads(userAuth, postId, null, 2).data();
        assertEquals(List.of(first, reply, nested, second), ids(page));
        assertEquals(first, ((Number) threadItems(page).get(2).get("rootId")).longValue());
        assertEquals(true, page.get("hasMore"));
        assertEquals(second, ((Number) page.get("nextCursor")).longValue());
        assertEquals(5L, ((Number) page.get("total")).longValue());

        Map<String, Object> next = controller.commentThreads(userAuth, postId, second, 2).data();
        assertEquals(List.of(third), ids(next));
        assertEquals(false, next.get("hasMore"));
        assertEquals(null, next.get("nextCursor"));
    }

    @Test
    void hiddenCommentsBecomePlaceholdersOnlyWhileTheyHaveVisibleReplies() {
        Long postId = publishedPost("Moderated discussion");
        Long hiddenRoot = idOf(controller.createComment(userAuth, Map.of("postId", postId, "content", "secret root")));
        Long visibleReply = idOf(controller.createComment(secondUserAuth, Map.of("postId", postId, "parentId", hiddenRoot, "content", "still visible")));
        Long hiddenAlone = idOf(controller.createComment(userAuth, Map.of("postId", postId, "content", "hidden alone")));
        Long visibleRoot = idOf(controller.createComment(userAuth, Map.of("postId", postId, "content", "visible root")));
        controller.updateCommentStatus(adminAuth, Map.of("commentId", hiddenRoot, "status", "HIDDEN"));
        controller.updateCommentStatus(adminAuth, Map.of("commentId", hiddenAlone, "status", "HIDDEN"));

        Map<String, Object> page = controller.commentThreads(userAuth, postId, null, 2).data();
        assertEquals(List.of(hiddenRoot, visibleReply, visibleRoot), ids(page));
        Map<String, Object> placeholder = threadItems(page).get(0);
        assertEquals(true, placeholder.get("placeholder"));
        assertEquals("", placeholder.get("content"));
        assertEquals(0L, placeholder.get("userId"));
        assertEquals(false, page.get("hasMore"));
        assertEquals(2L, ((Number) page.get("total")).longValue());

        assertEquals(500, controller.createComment(secondUserAuth, Map.of("postId", postId, "parentId", hiddenRoot, "content", "reply to hidden")).code());
        Map<String, Object> adminPage = controller.commentThreads(adminAuth, postId, null, 10).data();
        assertEquals(List.of(hiddenRoot, visibleReply, hiddenAlone, visibleRoot), ids(adminPage));
        assertEquals("secret root", threadItems(adminPage).get(0).get("content"));
    }

    @Test
    void feedPagesNewestFirstWithoutDuplicatesAndRespectsVisibility() {
        String authorAuth = "Bearer " + LocalAuth.issueToken("feed-author", 777L, "USER");
        List<Long> published = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Long postId = idOf(controller.createPost(authorAuth, Map.of("title", "Feed " + i + " " + System.nanoTime(), "content", "body")));
            controller.auditPost(adminAuth, Map.of("postId", postId, "status", "PUBLISHED"));
            published.add(postId);
        }
        Long pending = idOf(controller.createPost(authorAuth, Map.of("title", "Pending " + System.nanoTime(), "content", "body")));

        Map<String, Object> first = controller.feedPage(userAuth, "author", 777L, "", null, 2).data();
        assertEquals(List.of(published.get(2), published.get(1)), ids(first));
        assertEquals(true, first.get("hasMore"));
        Map<String, Object> second = controller.feedPage(userAuth, "author", 777L, "", ((Number) first.get("nextCursor")).longValue(), 2).data();
        assertEquals(List.of(published.get(0)), ids(second));
        assertEquals(false, second.get("hasMore"));

        assertEquals(List.of(pending, published.get(2), published.get(1), published.get(0)),
                ids(controller.feedPage(authorAuth, "author", 777L, "", null, 10).data()));
        assertEquals(List.of(published.get(2), published.get(1), published.get(0)),
                ids(controller.feedPage(userAuth, "following", null, "777, 778", null, 10).data()));
        assertTrue(ids(controller.feedPage(userAuth, "following", null, "", null, 10).data()).isEmpty());
        assertEquals(500, controller.feedPage(userAuth, "following", null, "777,abc", null, 10).code());
        assertEquals(500, controller.feedPage(userAuth, "everything", null, "", null, 10).code());

        List<Long> seen = new java.util.ArrayList<>();
        Long cursor = null;
        do {
            Map<String, Object> page = controller.feedPage(userAuth, "all", null, "", cursor, 2).data();
            seen.addAll(ids(page));
            cursor = page.get("nextCursor") == null ? null : ((Number) page.get("nextCursor")).longValue();
        } while (cursor != null);
        assertEquals(seen.size(), new java.util.HashSet<>(seen).size());
        assertEquals(seen.stream().sorted(java.util.Comparator.reverseOrder()).toList(), seen);
        assertTrue(seen.containsAll(published));
        assertFalse(seen.contains(pending));
        long userVisible = ((Number) controller.feedCount(userAuth).data().get("total")).longValue();
        assertEquals((long) seen.size(), userVisible);
        assertTrue(((Number) controller.feedCount(adminAuth).data().get("total")).longValue() > userVisible);
        assertTrue(ids(controller.feedPage(adminAuth, "author", 777L, "", null, 10).data()).contains(pending));
    }

    @Test
    void feedViewsCarryBatchedLikeAndCollectState() {
        Long postId = publishedPost("Batched interactions");
        String readerAuth = "Bearer " + LocalAuth.issueToken("batch-reader", 888L, "USER");
        controller.likePost(readerAuth, Map.of("postId", postId));
        controller.squareCollect(readerAuth, Map.of("postId", postId));

        Map<String, Object> mine = threadItems(controller.feedPage(readerAuth, "author", 1L, "", null, 50).data()).stream()
                .filter(item -> postId.equals(((Number) item.get("id")).longValue())).findFirst().orElseThrow();
        assertEquals(1L, mine.get("likes"));
        assertEquals(true, mine.get("liked"));
        assertEquals(true, mine.get("collected"));
        assertEquals(List.of(), mine.get("imageUrls"));

        Map<String, Object> other = controller.feed(secondUserAuth, 1L).data().stream()
                .filter(item -> postId.equals(((Number) item.get("id")).longValue())).findFirst().orElseThrow();
        assertEquals(1L, other.get("likes"));
        assertEquals(false, other.get("liked"));
        assertEquals(false, other.get("collected"));
        assertTrue(controller.squareCollections(readerAuth, null).data().stream()
                .anyMatch(item -> postId.equals(((Number) item.get("id")).longValue()) && Boolean.TRUE.equals(item.get("collected"))));
    }

    private Long publishedPost(String title) {
        Long postId = idOf(controller.createPost(userAuth, Map.of("title", title + " " + System.nanoTime(), "content", "body")));
        controller.auditPost(adminAuth, Map.of("postId", postId, "status", "PUBLISHED"));
        return postId;
    }

    private static Long idOf(ApiResponse<Map<String, Object>> response) {
        assertEquals(0, response.code(), response.message());
        return ((Number) response.data().get("id")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> threadItems(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private static List<Long> ids(Map<String, Object> page) {
        return threadItems(page).stream().map(item -> ((Number) item.get("id")).longValue()).toList();
    }

    @Test
    void commentingOnOwnPostOrReplyingToOwnCommentDoesNotNotifySelf() {
        var notifications = mock(com.aiknowledge.community.notification.CommunityNotificationClient.class);
        var isolated = new CommunityController(
                new InMemoryCommunityStore(),
                new com.aiknowledge.community.storage.CommunityMediaStorageService(
                        "local", "target/test-community-media", "http://127.0.0.1:9000",
                        "ai-community", "test", "test-password"),
                notifications
        );
        var created = isolated.createPost(userAuth, Map.of("title", "Self notification", "content", "body"));
        Long postId = ((Number) created.data().get("id")).longValue();
        isolated.auditPost(adminAuth, Map.of("postId", postId, "status", "PUBLISHED"));

        var parent = isolated.createComment(userAuth, Map.of("postId", postId, "content", "own comment"));
        Long parentId = ((Number) parent.data().get("id")).longValue();
        isolated.createComment(userAuth, Map.of(
                "postId", postId, "parentId", parentId, "content", "reply to myself"));

        verifyNoInteractions(notifications);

        isolated.createComment(secondUserAuth, Map.of(
                "postId", postId, "parentId", parentId, "content", "reply from another user"));
        verify(notifications).commentCreated(1L, 2L, postId, "reply from another user");
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
    void adminCanHideAndRestoreComments() {
        var comment = controller.createComment(userAuth, Map.of("postId", 1L, "content", "moderated"));
        Long commentId = ((Number) comment.data().get("id")).longValue();

        assertEquals(0, controller.updateCommentStatus(adminAuth, Map.of(
                "commentId", commentId, "status", "HIDDEN")).code());
        assertFalse(controller.comments(userAuth, 1L).data().stream().anyMatch(item -> commentId.equals(item.get("id"))));
        assertTrue(controller.comments(adminAuth, 1L).data().stream().anyMatch(item -> commentId.equals(item.get("id"))));

        assertEquals(0, controller.updateCommentStatus(adminAuth, Map.of(
                "commentId", commentId, "status", "VISIBLE")).code());
        assertTrue(controller.comments(userAuth, 1L).data().stream().anyMatch(item -> commentId.equals(item.get("id"))));
    }

    @Test
    void accountWithBlockedPublishingPolicyCannotCreateOrPublishPosts() {
        UserRelationClient relations = mock(UserRelationClient.class);
        when(relations.publishingAllowed(1L)).thenReturn(false);
        CommunityController restricted = new CommunityController(
                new InMemoryCommunityStore(),
                new com.aiknowledge.community.storage.CommunityMediaStorageService(
                        "local", "target/test-community-media", "http://127.0.0.1:9000",
                        "ai-community", "test", "test-password"),
                new com.aiknowledge.community.notification.CommunityNotificationClient(false, "", ""),
                null,
                relations
        );
        assertEquals(500, restricted.createPost(userAuth, Map.of("title", "blocked", "content", "blocked")).code());
        var draft = restricted.saveDraft(userAuth, Map.of("title", "draft", "content", "draft"));
        assertEquals(500, restricted.publishDraft(userAuth, Map.of("id", draft.data().get("id"))).code());
    }

    @Test
    void adminCanRunExpiredDraftCleanup() {
        controller.saveDraft(userAuth, Map.of("title", "recent", "content", "keep"));
        var result = controller.removeExpiredDrafts(adminAuth, Map.of("retentionDays", 30));
        assertEquals(0, result.code());
        assertEquals(0, result.data().get("removed"));
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

    @Test
    void adminAnalyticsAggregatesPublishedPostsAndFillsTheTrend() {
        // The in-memory store carries data from the other tests, so every total is asserted as a delta.
        Map<String, Object> before = controller.adminAnalytics(adminAuth, 30).data();
        long basePosts = analyticsNumber(before.get("posts"));
        long baseLikes = analyticsNumber(before.get("likes"));

        // A dedicated author keeps this post out of the following feed the other tests assert on.
        String analyticsAuthor = "Bearer " + LocalAuth.issueToken("analytics-author", 4242L, "USER");
        String title = "统计帖子 " + System.nanoTime();
        Long postId = ((Number) controller.createPost(analyticsAuthor, Map.of(
                "title", title, "content", "统计内容")).data().get("id")).longValue();
        controller.auditPost(adminAuth, Map.of("postId", postId, "status", "PUBLISHED"));
        controller.likePost(userAuth, Map.of("postId", postId));

        Map<String, Object> after = controller.adminAnalytics(adminAuth, 30).data();
        assertEquals(basePosts + 1, analyticsNumber(after.get("posts")));
        assertEquals(baseLikes + 1, analyticsNumber(after.get("likes")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trend = (List<Map<String, Object>>) after.get("trend");
        assertEquals(14, trend.size());
        assertEquals(java.time.LocalDate.now().toString(), trend.get(13).get("date"));
        assertTrue(analyticsNumber(trend.get(13).get("count")) >= 1);
        assertEquals(7, ((List<?>) controller.adminAnalytics(adminAuth, 7).data().get("trend")).size());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> top = (List<Map<String, Object>>) after.get("top");
        assertTrue(top.size() <= 5);
        assertTrue(top.stream().anyMatch(item -> title.equals(item.get("title"))));
        long previous = Long.MAX_VALUE;
        for (Map<String, Object> item : top) {
            long likes = analyticsNumber(item.get("likes"));
            assertTrue(likes <= previous, "ranking is not sorted by likes");
            previous = likes;
        }

        assertEquals(500, controller.adminAnalytics(userAuth, 30).code());
    }

    private static long analyticsNumber(Object value) {
        return value instanceof Number found ? found.longValue() : 0L;
    }

}
