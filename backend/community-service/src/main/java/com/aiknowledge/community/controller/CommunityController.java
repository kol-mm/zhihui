package com.aiknowledge.community.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.common.PlatformConfigClient;
import com.aiknowledge.common.UserRelationClient;
import com.aiknowledge.community.entity.CommentEntity;
import com.aiknowledge.community.entity.PostDraftEntity;
import com.aiknowledge.community.entity.PostEntity;
import com.aiknowledge.community.store.CommunityStore;
import com.aiknowledge.community.storage.CommunityMediaStorageService;
import com.aiknowledge.community.notification.CommunityNotificationClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.aiknowledge.common.DailySeries;
import java.time.LocalDate;
import com.aiknowledge.common.TimeCursor;

@RestController
public class CommunityController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int COMMENT_THREAD_PAGE_MAX = 50;
    private static final int COMMENT_THREAD_FILL_ROUNDS = 5;
    private static final int FEED_PAGE_MAX = 50;
    private final CommunityStore communityStore;
    private final CommunityMediaStorageService mediaStorage;
    private final CommunityNotificationClient notificationClient;
    private final PlatformConfigClient platformConfig;
    private final UserRelationClient userRelationClient;

    @Autowired
    public CommunityController(CommunityStore communityStore, CommunityMediaStorageService mediaStorage,
                               CommunityNotificationClient notificationClient, PlatformConfigClient platformConfig,
                               UserRelationClient userRelationClient) {
        this.communityStore = communityStore;
        this.mediaStorage = mediaStorage;
        this.notificationClient = notificationClient;
        this.platformConfig = platformConfig;
        this.userRelationClient = userRelationClient;
    }

    public CommunityController(CommunityStore communityStore, CommunityMediaStorageService mediaStorage,
                               CommunityNotificationClient notificationClient, PlatformConfigClient platformConfig) {
        this(communityStore, mediaStorage, notificationClient, platformConfig, null);
    }

    public CommunityController(CommunityStore communityStore, CommunityMediaStorageService mediaStorage,
                               CommunityNotificationClient notificationClient) {
        this(communityStore, mediaStorage, notificationClient, null);
    }

    public CommunityController(CommunityStore communityStore, CommunityMediaStorageService mediaStorage) {
        this(communityStore, mediaStorage, new CommunityNotificationClient(false, "", ""));
    }

    @PostMapping(value = "/post/media/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> uploadImages(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam("files") List<MultipartFile> files
    ) {
        if (!LocalAuth.isAuthenticated(authorization)) return ApiResponse.fail("valid user authorization is required");
        if (!communityEnabled()) return ApiResponse.fail("community feature is disabled");
        int imageLimit = maxPostImages();
        if (imageLimit == 0) return ApiResponse.fail("平台当前未开放帖子配图");
        if (files.isEmpty() || files.size() > imageLimit) return ApiResponse.fail("帖子配图数量不能超过 " + imageLimit + " 张");
        try {
            List<byte[]> contents = new java.util.ArrayList<>();
            for (MultipartFile file : files) {
                byte[] bytes = file.getBytes();
                mediaStorage.validate(bytes);
                contents.add(bytes);
            }
            List<String> imageUrls = new java.util.ArrayList<>();
            for (int index = 0; index < files.size(); index++) {
                imageUrls.add(mediaStorage.save(files.get(index).getOriginalFilename(), contents.get(index)));
            }
            return ApiResponse.ok(Map.of("imageUrls", imageUrls, "count", imageUrls.size()));
        } catch (IllegalArgumentException error) {
            return ApiResponse.fail(error.getMessage());
        } catch (Exception error) {
            return ApiResponse.fail("image upload failed: " + error.getMessage());
        }
    }

    @GetMapping("/post/media/{token}")
    public ResponseEntity<byte[]> media(@PathVariable String token) {
        CommunityMediaStorageService.StoredMedia media = mediaStorage.read(token);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(media.contentType()))
                .contentLength(media.bytes().length).body(media.bytes());
    }

    @GetMapping("/post/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
                "service", "community-service",
                "time", Instant.now().toString(),
                "dataMode", storeMode(communityStore),
                "mediaStorageMode", mediaStorage.mode()
        ));
    }

    @PostMapping("/post/create")
    public ApiResponse<Map<String, Object>> createPost(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        if (!communityEnabled()) return ApiResponse.fail("community feature is disabled");
        if (!publishingAllowed(userId)) return ApiResponse.fail("posting is disabled for this account");
        List<String> imageUrls = stringList(request.get("imageUrls"));
        if (imageUrls.size() > maxPostImages()) return ApiResponse.fail("帖子配图数量不能超过 " + maxPostImages() + " 张");
        PostEntity post = new PostEntity();
        post.setUserId(userId);
        post.setTitle(String.valueOf(request.getOrDefault("title", "未命名帖子")));
        post.setContent(String.valueOf(request.getOrDefault("content", "")));
        post.setStatus(initialPostStatus());
        PostEntity saved = communityStore.savePost(post);
        communityStore.savePostImages(saved.getId(), imageUrls);
        return ApiResponse.ok(toPostView(saved));
    }

    @PutMapping("/post/update")
    public ApiResponse<Map<String, Object>> updatePost(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        if (!communityEnabled() && !LocalAuth.isAdmin(authorization)) return ApiResponse.fail("community feature is disabled");
        Long postId = number(request.get("id"), 0L);
        PostEntity existing = communityStore.findPost(postId).orElse(null);
        if (existing == null) return ApiResponse.fail("post not found");
        if (!LocalAuth.canAccessUser(authorization, existing.getUserId())) return ApiResponse.fail("access to this post is denied");
        List<String> imageUrls = request.containsKey("imageUrls") ? stringList(request.get("imageUrls")) : List.of();
        if (imageUrls.size() > maxPostImages()) return ApiResponse.fail("帖子配图数量不能超过 " + maxPostImages() + " 张");
        PostEntity post = new PostEntity();
        post.setId(postId);
        post.setTitle(String.valueOf(request.getOrDefault("title", "未命名帖子")));
        post.setContent(String.valueOf(request.getOrDefault("content", "")));
        post.setStatus(initialPostStatus());
        PostEntity updated = communityStore.updatePost(post);
        if (request.containsKey("imageUrls")) communityStore.savePostImages(updated.getId(), imageUrls);
        return ApiResponse.ok(toPostView(updated));
    }

    @GetMapping("/post/detail")
    public ApiResponse<Map<String, Object>> detail(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "id", defaultValue = "1") Long id
    ) {
        if (!communityEnabled()) return ApiResponse.fail("community feature is disabled");
        PostEntity post = communityStore.findPost(id).orElse(null);
        if (post == null || !canViewPost(post, authorization)) return ApiResponse.fail("post not found");
        Long userId = LocalAuth.userId(authorization);
        Map<String, Object> detail = toPostView(post, userId);
        detail.put("commentCount", communityStore.countComments(id, !LocalAuth.isAdmin(authorization)));
        return ApiResponse.ok(detail);
    }

    @PostMapping("/comment/create")
    public ApiResponse<Map<String, Object>> createComment(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        if (!communityEnabled()) return ApiResponse.fail("community feature is disabled");
        if (!commentsEnabled()) return ApiResponse.fail("平台当前未开放评论功能");
        CommentEntity comment = buildComment(request, "POST", userId);
        if (comment.getPostId() <= 0 || comment.getContent().isBlank()) return ApiResponse.fail("post and comment content are required");
        if (comment.getContent().trim().length() > maxCommentLength()) return ApiResponse.fail("评论内容不能超过 " + maxCommentLength() + " 个字符");
        var post = communityStore.findPost(comment.getPostId());
        if (post.isEmpty() || !canViewPost(post.get(), authorization)) return ApiResponse.fail("post not found");
        if (!interactionAllowed(userId, post.get().getUserId())) return ApiResponse.fail("interaction with this user is blocked");
        CommentEntity parent = null;
        if (comment.getParentId() != null && comment.getParentId() > 0) {
            parent = communityStore.findComment(comment.getParentId())
                    .filter(item -> comment.getPostId().equals(item.getPostId()))
                    .orElse(null);
            if (parent == null) return ApiResponse.fail("parent comment does not belong to this post");
            if (!"VISIBLE".equals(parent.getStatus()) && !LocalAuth.isAdmin(authorization)) {
                return ApiResponse.fail("parent comment is not available");
            }
            if (!interactionAllowed(userId, parent.getUserId())) return ApiResponse.fail("interaction with this user is blocked");
        }
        comment.setContent(comment.getContent().trim());
        CommentEntity saved = communityStore.saveComment(comment);
        Long notificationTarget = parent == null ? post.get().getUserId() : parent.getUserId();
        if (!userId.equals(notificationTarget)) {
            notificationClient.commentCreated(notificationTarget, userId, post.get().getId(), saved.getContent());
        }
        return ApiResponse.ok(toCommentView(saved));
    }

    @GetMapping("/comment/list")
    public ApiResponse<List<Map<String, Object>>> comments(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "postId", required = false) Long postId
    ) {
        if (!communityEnabled()) return ApiResponse.ok(List.of());
        if (!commentsEnabled() && !LocalAuth.isAdmin(authorization)) return ApiResponse.ok(List.of());
        if (postId == null && !LocalAuth.isAdmin(authorization)) return ApiResponse.fail("admin authorization is required");
        if (postId != null) {
            PostEntity post = communityStore.findPost(postId).orElse(null);
            if (post == null || !canViewPost(post, authorization)) return ApiResponse.fail("post not found");
        }
        return ApiResponse.ok(visibleComments(postId, authorization));
    }

    /**
     * Pages a post's discussion by thread: each page holds up to {@code limit} root comments together with
     * every reply beneath them, so replies never arrive detached from their thread.
     */
    @GetMapping("/comment/threads")
    public ApiResponse<Map<String, Object>> commentThreads(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "postId") Long postId,
            @RequestParam(name = "cursor", required = false) Long cursor,
            @RequestParam(name = "limit", defaultValue = "10") int limit
    ) {
        boolean admin = LocalAuth.isAdmin(authorization);
        if (!communityEnabled() || (!commentsEnabled() && !admin)) return ApiResponse.ok(commentThreadPage(List.of(), null, false, 0));
        if (cursor != null && cursor < 0) return ApiResponse.fail("invalid comment cursor");
        PostEntity post = communityStore.findPost(postId).orElse(null);
        if (post == null || !canViewPost(post, authorization)) return ApiResponse.fail("post not found");
        int pageSize = Math.max(1, Math.min(COMMENT_THREAD_PAGE_MAX, limit));
        List<Map<String, Object>> items = new ArrayList<>();
        Long lastRootId = cursor;
        boolean hasMore = false;
        int threads = 0;
        // Hidden roots without visible replies are skipped, so keep reading until the page is full.
        for (int round = 0; round < COMMENT_THREAD_FILL_ROUNDS && threads < pageSize; round++) {
            int wanted = pageSize - threads;
            List<CommentEntity> roots = communityStore.listRootComments(postId, lastRootId, wanted + 1);
            hasMore = roots.size() > wanted;
            if (hasMore) roots = roots.subList(0, wanted);
            if (roots.isEmpty()) break;
            Map<Long, List<CommentEntity>> byRoot = new LinkedHashMap<>();
            roots.forEach(root -> byRoot.put(root.getId(), new ArrayList<>()));
            communityStore.listThreadComments(postId, byRoot.keySet())
                    .forEach(comment -> byRoot.get(comment.getRootId()).add(comment));
            for (List<CommentEntity> thread : byRoot.values()) {
                List<Map<String, Object>> views = threadViews(thread, admin);
                if (!views.isEmpty()) {
                    items.addAll(views);
                    threads++;
                }
            }
            lastRootId = roots.get(roots.size() - 1).getId();
            if (!hasMore) break;
        }
        return ApiResponse.ok(commentThreadPage(items, hasMore ? lastRootId : null, hasMore,
                communityStore.countComments(postId, !admin)));
    }

    @PostMapping("/post/draft")
    public ApiResponse<Map<String, Object>> saveDraft(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        if (!communityEnabled()) return ApiResponse.fail("community feature is disabled");
        PostDraftEntity draft = new PostDraftEntity();
        draft.setUserId(userId);
        draft.setTitle(String.valueOf(request.getOrDefault("title", "未命名草稿")));
        draft.setContent(String.valueOf(request.getOrDefault("content", "")));
        draft.setImageUrlsJson(encodeImageUrls(request.get("imageUrls")));
        return ApiResponse.ok(toDraftView(communityStore.saveDraft(draft)));
    }

    @PutMapping("/post/draft")
    public ApiResponse<Map<String, Object>> updateDraft(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long draftId = number(request.get("id"), 0L);
        PostDraftEntity existing = communityStore.findDraft(draftId).orElse(null);
        if (existing == null) return ApiResponse.fail("draft not found");
        if (!LocalAuth.canAccessUser(authorization, existing.getUserId())) return ApiResponse.fail("access to this draft is denied");
        existing.setTitle(String.valueOf(request.getOrDefault("title", existing.getTitle())));
        existing.setContent(String.valueOf(request.getOrDefault("content", existing.getContent())));
        if (request.containsKey("imageUrls")) existing.setImageUrlsJson(encodeImageUrls(request.get("imageUrls")));
        return ApiResponse.ok(toDraftView(communityStore.updateDraft(existing)));
    }

    @PostMapping("/post/draft/publish")
    public ApiResponse<Map<String, Object>> publishDraft(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long draftId = number(request.get("id"), 0L);
        PostDraftEntity draft = communityStore.findDraft(draftId).orElse(null);
        if (draft == null) return ApiResponse.fail("draft not found");
        if (!LocalAuth.canAccessUser(authorization, draft.getUserId())) return ApiResponse.fail("access to this draft is denied");
        if (!publishingAllowed(draft.getUserId())) return ApiResponse.fail("posting is disabled for this account");
        PostEntity post = new PostEntity();
        post.setUserId(draft.getUserId());
        post.setTitle(String.valueOf(request.getOrDefault("title", draft.getTitle())));
        post.setContent(String.valueOf(request.getOrDefault("content", draft.getContent())));
        post.setStatus(initialPostStatus());
        PostEntity saved = communityStore.savePost(post);
        List<String> imageUrls = request.containsKey("imageUrls")
                ? stringList(request.get("imageUrls")) : decodeImageUrls(draft.getImageUrlsJson());
        communityStore.savePostImages(saved.getId(), imageUrls);
        communityStore.removeDraft(draftId);
        return ApiResponse.ok(toPostView(saved));
    }

    @DeleteMapping("/post/draft")
    public ApiResponse<Map<String, Object>> removeOwnDraft(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long draftId = number(request.get("draftId"), 0L);
        PostDraftEntity draft = communityStore.findDraft(draftId).orElse(null);
        if (draft == null) return ApiResponse.fail("draft not found");
        if (!LocalAuth.canAccessUser(authorization, draft.getUserId())) return ApiResponse.fail("access to this draft is denied");
        return ApiResponse.ok(Map.of("draftId", draftId, "removed", communityStore.removeDraft(draftId)));
    }

    @GetMapping("/post/drafts")
    public ApiResponse<List<Map<String, Object>>> drafts(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "userId", required = false) Long userId
    ) {
        if (!(LocalAuth.isAdmin(authorization) && userId == null) && !LocalAuth.canAccessUser(authorization, userId)) {
            return ApiResponse.fail("access to this user is denied");
        }
        return ApiResponse.ok(communityStore.listDrafts(userId).stream().map(this::toDraftView).toList());
    }

    @GetMapping("/square/feed")
    public ApiResponse<List<Map<String, Object>>> feed(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "authorUserId", required = false) Long authorUserId
    ) {
        if (!communityEnabled()) return ApiResponse.ok(List.of());
        Long userId = LocalAuth.userId(authorization);
        return ApiResponse.ok(toPostViews(communityStore.feed(authorUserId).stream()
                .filter(post -> canViewPost(post, authorization)).toList(), userId));
    }

    /**
     * Newest-first, cursor-paged feed for the client forum and following square. {@code scope} is
     * {@code all}, {@code author} (requires authorUserId) or {@code following} (uses followedUserIds).
     */
    @GetMapping("/square/feed/page")
    public ApiResponse<Map<String, Object>> feedPage(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "scope", defaultValue = "all") String scope,
            @RequestParam(name = "authorUserId", required = false) Long authorUserId,
            @RequestParam(name = "followedUserIds", defaultValue = "") String followedUserIds,
            @RequestParam(name = "cursor", required = false) Long cursor,
            @RequestParam(name = "limit", defaultValue = "10") int limit
    ) {
        if (!communityEnabled()) return ApiResponse.ok(feedPageView(List.of(), null, false));
        if (cursor != null && cursor < 0) return ApiResponse.fail("invalid feed cursor");
        Long author = null;
        List<Long> authors = null;
        switch (scope) {
            case "all" -> { }
            case "author" -> {
                if (authorUserId == null || authorUserId <= 0) return ApiResponse.fail("authorUserId is required");
                author = authorUserId;
            }
            case "following" -> {
                authors = parseUserIds(followedUserIds);
                if (authors == null) return ApiResponse.fail("invalid followedUserIds");
                if (authors.isEmpty()) return ApiResponse.ok(feedPageView(List.of(), null, false));
            }
            default -> {
                return ApiResponse.fail("invalid feed scope");
            }
        }
        Long viewerUserId = LocalAuth.userId(authorization);
        int pageSize = Math.max(1, Math.min(FEED_PAGE_MAX, limit));
        List<PostEntity> posts = communityStore.pagePosts(new CommunityStore.PostPageQuery(
                author, authors, viewerUserId, LocalAuth.isAdmin(authorization), cursor, pageSize + 1));
        boolean hasMore = posts.size() > pageSize;
        if (hasMore) posts = posts.subList(0, pageSize);
        Long nextCursor = hasMore ? posts.get(posts.size() - 1).getId() : null;
        return ApiResponse.ok(feedPageView(toPostViews(posts.stream().filter(post -> canViewPost(post, authorization)).toList(), viewerUserId), nextCursor, hasMore));
    }

    @GetMapping("/square/feed/count")
    public ApiResponse<Map<String, Object>> feedCount(
            @RequestHeader(name = "Authorization", required = false) String authorization
    ) {
        long total = communityEnabled()
                ? communityStore.countVisiblePosts(LocalAuth.userId(authorization), LocalAuth.isAdmin(authorization))
                : 0;
        return ApiResponse.ok(Map.of("total", total));
    }

    @GetMapping("/square/following-feed")
    public ApiResponse<List<Map<String, Object>>> followingFeed(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "followedUserIds", defaultValue = "") String followedUserIds
    ) {
        if (!communityEnabled()) return ApiResponse.ok(List.of());
        Long userId = LocalAuth.userId(authorization);
        List<Long> ids = java.util.Arrays.stream(followedUserIds.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).map(Long::valueOf).toList();
        return ApiResponse.ok(toPostViews(communityStore.feed(null).stream().filter(post -> ids.contains(post.getUserId()))
                .filter(post -> canViewPost(post, authorization)).toList(), userId));
    }

    @PostMapping("/square/quick-comment")
    public ApiResponse<Map<String, Object>> quickComment(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        if (!communityEnabled()) return ApiResponse.fail("community feature is disabled");
        if (!commentsEnabled()) return ApiResponse.fail("平台当前未开放评论功能");
        CommentEntity comment = buildComment(request, "SQUARE", userId);
        PostEntity post = communityStore.findPost(comment.getPostId()).orElse(null);
        if (post == null || !canViewPost(post, authorization)) return ApiResponse.fail("post not found");
        if (!interactionAllowed(userId, post.getUserId())) return ApiResponse.fail("interaction with this user is blocked");
        if (comment.getContent().isBlank()) return ApiResponse.fail("comment content is required");
        if (comment.getContent().trim().length() > maxCommentLength()) return ApiResponse.fail("评论内容不能超过 " + maxCommentLength() + " 个字符");
        CommentEntity saved = communityStore.saveComment(comment);
        if (!userId.equals(post.getUserId())) {
            notificationClient.commentCreated(post.getUserId(), userId, post.getId(), saved.getContent());
        }
        return ApiResponse.ok(toCommentView(saved));
    }

    @PostMapping("/square/collect")
    public ApiResponse<Map<String, Object>> squareCollect(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        if (!communityEnabled()) return ApiResponse.fail("community feature is disabled");
        Long postId = number(request.get("postId"), 0L);
        PostEntity post = communityStore.findPost(postId).orElse(null);
        if (postId <= 0 || post == null || !canViewPost(post, authorization)) return ApiResponse.fail("post not found");
        if (!interactionAllowed(userId, post.getUserId())) return ApiResponse.fail("interaction with this user is blocked");
        boolean collected = communityStore.togglePostCollect(userId, postId);
        return ApiResponse.ok(Map.of("postId", postId, "collected", collected));
    }

    @GetMapping("/square/collections")
    public ApiResponse<List<Map<String, Object>>> squareCollections(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "userId", required = false) Long requestedUserId
    ) {
        Long viewerUserId = LocalAuth.userId(authorization);
        if (viewerUserId == null) return ApiResponse.fail("valid user authorization is required");
        Long userId = requestedUserId == null ? viewerUserId : requestedUserId;
        if (!LocalAuth.canAccessUser(authorization, userId)) return ApiResponse.fail("access to this user is denied");
        if (!communityEnabled()) return ApiResponse.ok(List.of());
        return ApiResponse.ok(toPostViews(communityStore.listCollectedPosts(userId), userId));
    }

    @PostMapping("/post/like")
    public ApiResponse<Map<String, Object>> likePost(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        if (!communityEnabled()) return ApiResponse.fail("community feature is disabled");
        Long postId = number(request.get("postId"), 0L);
        PostEntity post = communityStore.findPost(postId).orElse(null);
        if (post == null || !canViewPost(post, authorization)) return ApiResponse.fail("post not found");
        if (!interactionAllowed(userId, post.getUserId())) return ApiResponse.fail("interaction with this user is blocked");
        boolean liked = communityStore.togglePostLike(userId, postId);
        return ApiResponse.ok(Map.of(
                "postId", postId,
                "liked", liked,
                "created", liked,
                "likes", communityStore.countPostLikes(postId)
        ));
    }

    @GetMapping("/post/admin/overview")
    public ApiResponse<Map<String, Object>> adminOverview(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "authorUserId", required = false) Long authorUserId
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) {
            return denied;
        }
        CommunityStore.PostTotals totals = communityStore.postTotals(authorUserId);
        return ApiResponse.ok(Map.of(
                "module", "论坛管理",
                "publishedPosts", totals.published(),
                "pendingAudit", totals.pendingAudit(),
                "hiddenPosts", totals.hidden(),
                "draftsTracked", totals.drafts(),
                "comments", communityStore.countAdminComments(new CommunityStore.AdminCommentQuery(null, null, 0)),
                "squareMode", "仅展示已关注用户动态",
                "capabilities", List.of("帖子审核", "评论管理", "草稿追踪", "广场互动管理")
        ));
    }

    @PostMapping("/post/admin/audit")
    public ApiResponse<Map<String, Object>> auditPost(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) {
            return denied;
        }
        Long postId = number(request.get("postId"), 0L);
        String status = String.valueOf(request.getOrDefault("status", "PUBLISHED"));
        String reason = String.valueOf(request.getOrDefault("reason", ""));
        if (!List.of("PUBLISHED", "HIDDEN").contains(status)) return ApiResponse.fail("审核结果无效");
        PostEntity existing = communityStore.findPost(postId).orElse(null);
        if (existing == null) return ApiResponse.fail("帖子不存在");
        if (status.equals(existing.getStatus())) {
            return ApiResponse.fail("PUBLISHED".equals(status) ? "该帖子已经发布" : "该帖子已经隐藏");
        }
        boolean validTransition = ("PENDING".equals(existing.getStatus()) && List.of("PUBLISHED", "HIDDEN").contains(status))
                || ("PUBLISHED".equals(existing.getStatus()) && "HIDDEN".equals(status))
                || ("HIDDEN".equals(existing.getStatus()) && "PUBLISHED".equals(status));
        if (!validTransition) return ApiResponse.fail("当前状态不支持此审核操作");
        return communityStore.auditPost(postId, status, reason)
                .map(post -> ApiResponse.ok(Map.of(
                        "post", toPostView(post),
                        "reason", reason,
                        "updated", true
                )))
                .orElseGet(() -> ApiResponse.fail("帖子状态已发生变化，请刷新后重试"));
    }

    @DeleteMapping("/post")
    public ApiResponse<Map<String, Object>> removePost(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        Long postId = number(request.get("postId"), 0L);
        PostEntity post = communityStore.findPost(postId).orElse(null);
        if (post == null) return ApiResponse.fail("post not found");
        if (!LocalAuth.isAdmin(authorization) && !userId.equals(post.getUserId())) {
            return ApiResponse.fail("access to this post is denied");
        }
        return ApiResponse.ok(Map.of("postId", postId, "removed", communityStore.removePost(postId)));
    }

    @DeleteMapping("/comment")
    public ApiResponse<Map<String, Object>> removeOwnComment(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        Long commentId = number(request.get("commentId"), 0L);
        CommentEntity comment = communityStore.findComment(commentId).orElse(null);
        if (comment == null) return ApiResponse.fail("comment not found");
        if (!LocalAuth.isAdmin(authorization) && !userId.equals(comment.getUserId())) {
            return ApiResponse.fail("access to this comment is denied");
        }
        return ApiResponse.ok(Map.of("commentId", commentId, "removed", communityStore.removeComment(commentId)));
    }

    @DeleteMapping("/comment/admin")
    public ApiResponse<Map<String, Object>> removeComment(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        if (!LocalAuth.isAdmin(authorization)) return ApiResponse.fail("admin authorization is required");
        Long commentId = number(request.get("commentId"), 0L);
        return ApiResponse.ok(Map.of("commentId", commentId, "removed", communityStore.removeComment(commentId)));
    }

    @PostMapping("/comment/admin/status")
    public ApiResponse<Map<String, Object>> updateCommentStatus(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        if (!LocalAuth.isAdmin(authorization)) return ApiResponse.fail("admin authorization is required");
        Long commentId = number(request.get("commentId"), 0L);
        String status = String.valueOf(request.getOrDefault("status", "HIDDEN"));
        if (!List.of("VISIBLE", "HIDDEN").contains(status)) return ApiResponse.fail("invalid comment status");
        return communityStore.updateCommentStatus(commentId, status)
                .map(comment -> ApiResponse.ok(toCommentView(comment)))
                .orElseGet(() -> ApiResponse.fail("comment not found"));
    }

    @DeleteMapping("/post/admin/draft")
    public ApiResponse<Map<String, Object>> removeDraft(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        if (!LocalAuth.isAdmin(authorization)) return ApiResponse.fail("admin authorization is required");
        Long draftId = number(request.get("draftId"), 0L);
        return ApiResponse.ok(Map.of("draftId", draftId, "removed", communityStore.removeDraft(draftId)));
    }

    @DeleteMapping("/post/admin/drafts/expired")
    public ApiResponse<Map<String, Object>> removeExpiredDrafts(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        if (!LocalAuth.isAdmin(authorization)) return ApiResponse.fail("admin authorization is required");
        long retentionDays = number(request.get("retentionDays"), 30L);
        if (retentionDays < 1 || retentionDays > 3650) return ApiResponse.fail("retention days must be between 1 and 3650");
        int removed = communityStore.removeDraftsBefore(LocalDateTime.now().minusDays(retentionDays));
        return ApiResponse.ok(Map.of("retentionDays", retentionDays, "removed", removed));
    }

    private CommentEntity buildComment(Map<String, Object> request, String source, Long userId) {
        CommentEntity comment = new CommentEntity();
        comment.setPostId(number(request.get("postId"), 0L));
        comment.setUserId(userId);
        comment.setParentId(number(request.get("parentId"), 0L));
        comment.setContent(String.valueOf(request.getOrDefault("content", "")));
        comment.setSource(source);
        comment.setStatus("VISIBLE");
        return comment;
    }

    private Map<String, Object> toPostView(PostEntity post) {
        return toPostView(post, null);
    }

    private Map<String, Object> toPostView(PostEntity post, Long viewerUserId) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", post.getId());
        view.put("userId", post.getUserId());
        view.put("title", post.getTitle());
        view.put("content", post.getContent());
        view.put("status", post.getStatus());
        view.put("imageUrls", communityStore.listPostImages(post.getId()));
        view.put("likes", communityStore.countPostLikes(post.getId()));
        view.put("liked", communityStore.hasPostLike(viewerUserId, post.getId()));
        view.put("collected", communityStore.hasPostCollect(viewerUserId, post.getId()));
        view.put("createdAt", post.getCreatedAt());
        view.put("updatedAt", post.getUpdatedAt());
        return view;
    }

    /** Builds post views for a list with a fixed number of queries instead of four per post. */
    private List<Map<String, Object>> toPostViews(List<PostEntity> posts, Long viewerUserId) {
        if (posts.isEmpty()) return List.of();
        List<Long> postIds = posts.stream().map(PostEntity::getId).toList();
        Map<Long, List<String>> images = communityStore.listPostImages(postIds);
        Map<Long, Long> likes = communityStore.countPostLikes(postIds);
        Set<Long> liked = communityStore.likedPostIds(viewerUserId, postIds);
        Set<Long> collected = communityStore.collectedPostIds(viewerUserId, postIds);
        return posts.stream().map(post -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", post.getId());
            view.put("userId", post.getUserId());
            view.put("title", post.getTitle());
            view.put("content", post.getContent());
            view.put("status", post.getStatus());
            view.put("imageUrls", images.getOrDefault(post.getId(), List.of()));
            view.put("likes", likes.getOrDefault(post.getId(), 0L));
            view.put("liked", liked.contains(post.getId()));
            view.put("collected", collected.contains(post.getId()));
            view.put("createdAt", post.getCreatedAt());
            view.put("updatedAt", post.getUpdatedAt());
            return view;
        }).toList();
    }

    private Map<String, Object> feedPageView(List<Map<String, Object>> items, Long nextCursor, boolean hasMore) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("items", items);
        page.put("nextCursor", nextCursor);
        page.put("hasMore", hasMore);
        return page;
    }

    /** Parses a comma-separated id list; returns null when any entry is not a positive number. */
    private static List<Long> parseUserIds(String value) {
        List<Long> ids = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            try {
                long id = Long.parseLong(trimmed);
                if (id <= 0) return null;
                ids.add(id);
            } catch (NumberFormatException error) {
                return null;
            }
        }
        return ids;
    }

    private Map<String, Object> toCommentView(CommentEntity comment) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", comment.getId());
        view.put("postId", comment.getPostId());
        view.put("userId", comment.getUserId());
        view.put("parentId", comment.getParentId());
        view.put("rootId", comment.getRootId());
        view.put("content", comment.getContent());
        view.put("source", comment.getSource());
        view.put("status", comment.getStatus());
        view.put("createdAt", comment.getCreatedAt());
        return view;
    }

    private Map<String, Object> toDraftView(PostDraftEntity draft) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", draft.getId());
        view.put("userId", draft.getUserId());
        view.put("title", draft.getTitle());
        view.put("content", draft.getContent());
        view.put("imageUrls", decodeImageUrls(draft.getImageUrlsJson()));
        view.put("updatedAt", draft.getUpdatedAt());
        return view;
    }

    private Long number(Object value, Long fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(value.toString());
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> values) return values.stream().map(String::valueOf).toList();
        if (value == null || value.toString().isBlank()) return List.of();
        return java.util.Arrays.stream(value.toString().split(",")).map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private String encodeImageUrls(Object value) {
        List<String> imageUrls = stringList(value);
        if (imageUrls.size() > maxPostImages()) throw new IllegalArgumentException("草稿配图数量不能超过 " + maxPostImages() + " 张");
        try {
            return JSON.writeValueAsString(imageUrls);
        } catch (Exception error) {
            throw new IllegalArgumentException("invalid draft images");
        }
    }

    private List<String> decodeImageUrls(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return JSON.readValue(value, new TypeReference<>() { });
        } catch (Exception error) {
            return List.of();
        }
    }

    private boolean communityEnabled() {
        return platformConfig == null || platformConfig.enabled("community_enabled", true);
    }

    private boolean commentsEnabled() {
        return platformConfig == null || platformConfig.enabled("comments_enabled", true);
    }

    private boolean postAuditRequired() {
        return platformConfig == null || platformConfig.enabled("post_audit_required", true);
    }

    private String initialPostStatus() {
        return postAuditRequired() ? "PENDING" : "PUBLISHED";
    }

    private int maxPostImages() {
        return platformConfig == null ? 9 : Math.max(0, Math.min(9, platformConfig.integer("max_post_images", 9)));
    }

    private int maxCommentLength() {
        return platformConfig == null ? 2000 : Math.max(100, Math.min(5000, platformConfig.integer("max_comment_length", 2000)));
    }

    private String storeMode(Object store) {
        return store.getClass().getSimpleName().startsWith("MySql") ? "mysql" : "local";
    }

    private boolean interactionAllowed(Long userId, Long targetUserId) {
        return userId != null && targetUserId != null
                && (userId.equals(targetUserId) || userRelationClient == null
                || userRelationClient.interactionAllowed(userId, targetUserId));
    }

    private boolean publishingAllowed(Long userId) {
        return userId != null && (userRelationClient == null || userRelationClient.publishingAllowed(userId));
    }

    /**
     * Non-admins only see visible comments. A hidden comment that still has visible replies is returned as a
     * content-free placeholder so those replies stay attached to the thread.
     */
    private List<Map<String, Object>> threadViews(List<CommentEntity> thread, boolean admin) {
        if (admin) return thread.stream().map(this::toCommentView).toList();
        Map<Long, CommentEntity> byId = new LinkedHashMap<>();
        thread.forEach(comment -> byId.put(comment.getId(), comment));
        Set<Long> required = new HashSet<>();
        for (CommentEntity comment : thread) {
            if (!"VISIBLE".equals(comment.getStatus())) continue;
            CommentEntity current = comment;
            while (current != null && required.add(current.getId())) {
                current = current.getParentId() == null ? null : byId.get(current.getParentId());
            }
        }
        List<Map<String, Object>> views = new ArrayList<>();
        for (CommentEntity comment : thread) {
            if (!required.contains(comment.getId())) continue;
            views.add("VISIBLE".equals(comment.getStatus()) ? toCommentView(comment) : toCommentPlaceholder(comment));
        }
        return views;
    }

    private Map<String, Object> toCommentPlaceholder(CommentEntity comment) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", comment.getId());
        view.put("postId", comment.getPostId());
        view.put("userId", 0L);
        view.put("parentId", comment.getParentId());
        view.put("rootId", comment.getRootId());
        view.put("content", "");
        view.put("status", "HIDDEN");
        view.put("placeholder", true);
        view.put("createdAt", comment.getCreatedAt());
        return view;
    }

    private Map<String, Object> commentThreadPage(List<Map<String, Object>> items, Long nextCursor, boolean hasMore, long total) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("items", items);
        page.put("nextCursor", nextCursor);
        page.put("hasMore", hasMore);
        page.put("total", total);
        return page;
    }

    private List<Map<String, Object>> visibleComments(Long postId, String authorization) {
        return communityStore.listComments(postId).stream()
                .filter(comment -> LocalAuth.isAdmin(authorization) || "VISIBLE".equals(comment.getStatus()))
                .map(this::toCommentView)
                .toList();
    }

    private boolean canViewPost(PostEntity post, String authorization) {
        Long viewerUserId = LocalAuth.userId(authorization);
        return "PUBLISHED".equals(post.getStatus())
                || LocalAuth.isAdmin(authorization)
                || (viewerUserId != null && viewerUserId.equals(post.getUserId()));
    }

    private static final int ANALYTICS_MAX_DAYS = 365;
    private static final int ANALYTICS_TREND_DAYS = 14;
    private static final int ANALYTICS_TOP_LIMIT = 5;

    /**
     * Aggregated numbers for the admin analytics page. The page used to download the whole feed
     * and count in the browser; the database groups the same rows here instead.
     */
    @GetMapping("/post/admin/analytics")
    public ApiResponse<Map<String, Object>> adminAnalytics(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "days", defaultValue = "30") int days
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) {
            return denied;
        }
        int window = Math.min(Math.max(days, 1), ANALYTICS_MAX_DAYS);
        int trendDays = Math.min(window, ANALYTICS_TREND_DAYS);
        CommunityStore.PostAnalytics totals = communityStore.postAnalytics(LocalDateTime.now().minusDays(window));
        Map<String, Long> perDay = new LinkedHashMap<>();
        communityStore.dailyPostCounts(LocalDate.now().minusDays(trendDays - 1L).atStartOfDay())
                .forEach(entry -> perDay.merge(entry.date(), entry.count(), Long::sum));
        List<PostEntity> top = communityStore.topPosts(ANALYTICS_TOP_LIMIT);
        Map<Long, Long> topLikes = communityStore.countPostLikes(top.stream().map(PostEntity::getId).toList());
        List<Map<String, Object>> ranking = top.stream().map(post -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", post.getId());
            view.put("userId", post.getUserId());
            view.put("title", post.getTitle());
            view.put("likes", topLikes.getOrDefault(post.getId(), 0L));
            return view;
        }).toList();
        Map<String, Object> analytics = new LinkedHashMap<>();
        analytics.put("days", window);
        analytics.put("trendDays", trendDays);
        analytics.put("posts", totals.posts());
        analytics.put("likes", totals.likes());
        analytics.put("trend", DailySeries.fill(LocalDate.now(), trendDays, perDay));
        analytics.put("top", ranking);
        return ApiResponse.ok(analytics);
    }


    private static final int ADMIN_POST_PAGE_MAX = 100;
    private static final Set<String> ADMIN_POST_STATUSES = Set.of("PENDING", "PUBLISHED", "HIDDEN");

    /**
     * One page of a moderation post table. The queue used to download the whole feed and split it
     * in the browser; {@code status} takes one state or a comma list, such as PUBLISHED,HIDDEN.
     */
    @GetMapping("/post/admin/posts/page")
    public ApiResponse<Map<String, Object>> adminPostsPage(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "status", defaultValue = "") String status,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "cursor", required = false) Long cursor,
            @RequestParam(name = "limit", defaultValue = "20") int limit
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) {
            return denied;
        }
        if (cursor != null && cursor < 0) return ApiResponse.fail("cursor must not be negative");
        List<String> statuses = new ArrayList<>();
        for (String part : status.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) continue;
            if (!ADMIN_POST_STATUSES.contains(value)) return ApiResponse.fail("invalid post status");
            if (!statuses.contains(value)) statuses.add(value);
        }
        int size = Math.min(Math.max(limit, 1), ADMIN_POST_PAGE_MAX);
        List<PostEntity> found = communityStore.pageAdminPosts(
                new CommunityStore.AdminPostQuery(statuses, keyword, cursor, size + 1));
        boolean hasMore = found.size() > size;
        List<PostEntity> page = hasMore ? found.subList(0, size) : found;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", toPostViews(page, LocalAuth.userId(authorization)));
        result.put("nextCursor", page.isEmpty() ? null : page.get(page.size() - 1).getId());
        result.put("hasMore", hasMore);
        // Counting can touch most of the table, so only a first page that is full pays for it: a short first
        // page already is the whole result, and later pages send null.
        Long total = null;
        if (cursor == null) {
            total = hasMore ? communityStore.countAdminPosts(new CommunityStore.AdminPostQuery(statuses, keyword, null, size))
                    : page.size();
        }
        result.put("total", total);
        return ApiResponse.ok(result);
    }


    private static final int GOVERNANCE_PAGE_MAX = 100;

    /**
     * One page of the governance comment table. The dialog used to download every comment on the site;
     * the keyword and cursor are applied by the database here.
     */
    @GetMapping("/comment/admin/page")
    public ApiResponse<Map<String, Object>> adminCommentsPage(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "cursor", required = false) Long cursor,
            @RequestParam(name = "limit", defaultValue = "20") int limit
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) return denied;
        if (cursor != null && cursor < 0) return ApiResponse.fail("cursor must not be negative");
        int size = Math.min(Math.max(limit, 1), GOVERNANCE_PAGE_MAX);
        List<CommentEntity> found = communityStore.pageAdminComments(new CommunityStore.AdminCommentQuery(keyword, cursor, size + 1));
        boolean hasMore = found.size() > size;
        List<CommentEntity> page = hasMore ? found.subList(0, size) : found;
        Long total = null;
        if (cursor == null) {
            total = hasMore ? communityStore.countAdminComments(new CommunityStore.AdminCommentQuery(keyword, null, size)) : page.size();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", page.stream().map(this::toCommentView).toList());
        result.put("nextCursor", page.isEmpty() ? null : page.get(page.size() - 1).getId());
        result.put("hasMore", hasMore);
        result.put("total", total);
        return ApiResponse.ok(result);
    }

    /**
     * One page of the governance draft table, most recently edited first. The cursor is an opaque
     * {@code time|id} token taken from {@code nextCursor}.
     */
    @GetMapping("/post/admin/drafts/page")
    public ApiResponse<Map<String, Object>> adminDraftsPage(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "20") int limit
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) return denied;
        TimeCursor before;
        try {
            before = TimeCursor.parse(cursor);
        } catch (IllegalArgumentException error) {
            return ApiResponse.fail("invalid draft cursor");
        }
        int size = Math.min(Math.max(limit, 1), GOVERNANCE_PAGE_MAX);
        List<PostDraftEntity> found = communityStore.pageAdminDrafts(new CommunityStore.AdminDraftQuery(keyword, before, size + 1));
        boolean hasMore = found.size() > size;
        List<PostDraftEntity> page = hasMore ? found.subList(0, size) : found;
        Long total = null;
        if (before == null) {
            total = hasMore ? communityStore.countAdminDrafts(new CommunityStore.AdminDraftQuery(keyword, null, size)) : page.size();
        }
        PostDraftEntity last = page.isEmpty() ? null : page.get(page.size() - 1);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", page.stream().map(this::toDraftView).toList());
        result.put("nextCursor", last == null ? null : TimeCursor.format(last.getUpdatedAt(), last.getId()));
        result.put("hasMore", hasMore);
        result.put("total", total);
        return ApiResponse.ok(result);
    }

}
