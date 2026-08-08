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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class CommunityController {
    private static final ObjectMapper JSON = new ObjectMapper();
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
        if (files.isEmpty() || files.size() > 9) return ApiResponse.fail("select between 1 and 9 images");
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
        return ApiResponse.ok(Map.of("service", "community-service", "time", Instant.now().toString()));
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
        PostEntity post = new PostEntity();
        post.setUserId(userId);
        post.setTitle(String.valueOf(request.getOrDefault("title", "未命名帖子")));
        post.setContent(String.valueOf(request.getOrDefault("content", "")));
        post.setStatus("PENDING");
        PostEntity saved = communityStore.savePost(post);
        communityStore.savePostImages(saved.getId(), stringList(request.get("imageUrls")));
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
        PostEntity post = new PostEntity();
        post.setId(postId);
        post.setTitle(String.valueOf(request.getOrDefault("title", "未命名帖子")));
        post.setContent(String.valueOf(request.getOrDefault("content", "")));
        post.setStatus("PENDING");
        PostEntity updated = communityStore.updatePost(post);
        if (request.containsKey("imageUrls")) communityStore.savePostImages(updated.getId(), stringList(request.get("imageUrls")));
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
        detail.put("comments", visibleComments(id, authorization));
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
        CommentEntity comment = buildComment(request, "POST", userId);
        if (comment.getPostId() <= 0 || comment.getContent().isBlank()) return ApiResponse.fail("post and comment content are required");
        var post = communityStore.findPost(comment.getPostId());
        if (post.isEmpty() || !canViewPost(post.get(), authorization)) return ApiResponse.fail("post not found");
        if (!interactionAllowed(userId, post.get().getUserId())) return ApiResponse.fail("interaction with this user is blocked");
        CommentEntity parent = null;
        if (comment.getParentId() != null && comment.getParentId() > 0) {
            parent = communityStore.listComments(comment.getPostId()).stream()
                    .filter(item -> comment.getParentId().equals(item.getId()))
                    .findFirst().orElse(null);
            if (parent == null) return ApiResponse.fail("parent comment does not belong to this post");
            if (!interactionAllowed(userId, parent.getUserId())) return ApiResponse.fail("interaction with this user is blocked");
        }
        comment.setContent(comment.getContent().trim());
        CommentEntity saved = communityStore.saveComment(comment);
        Long notificationTarget = parent == null ? post.get().getUserId() : parent.getUserId();
        notificationClient.commentCreated(notificationTarget, userId, post.get().getId(), saved.getContent());
        return ApiResponse.ok(toCommentView(saved));
    }

    @GetMapping("/comment/list")
    public ApiResponse<List<Map<String, Object>>> comments(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "postId", required = false) Long postId
    ) {
        if (!communityEnabled()) return ApiResponse.ok(List.of());
        if (postId == null && !LocalAuth.isAdmin(authorization)) return ApiResponse.fail("admin authorization is required");
        if (postId != null) {
            PostEntity post = communityStore.findPost(postId).orElse(null);
            if (post == null || !canViewPost(post, authorization)) return ApiResponse.fail("post not found");
        }
        return ApiResponse.ok(visibleComments(postId, authorization));
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
        post.setStatus("PENDING");
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
        return ApiResponse.ok(communityStore.feed(authorUserId).stream()
                .filter(post -> canViewPost(post, authorization))
                .map(post -> toPostView(post, userId)).toList());
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
        return ApiResponse.ok(communityStore.feed(null).stream().filter(post -> ids.contains(post.getUserId()))
                .filter(post -> canViewPost(post, authorization))
                .map(post -> toPostView(post, userId)).toList());
    }

    @PostMapping("/square/quick-comment")
    public ApiResponse<Map<String, Object>> quickComment(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        if (!communityEnabled()) return ApiResponse.fail("community feature is disabled");
        CommentEntity comment = buildComment(request, "SQUARE", userId);
        PostEntity post = communityStore.findPost(comment.getPostId()).orElse(null);
        if (post == null || !canViewPost(post, authorization)) return ApiResponse.fail("post not found");
        if (!interactionAllowed(userId, post.getUserId())) return ApiResponse.fail("interaction with this user is blocked");
        if (comment.getContent().isBlank()) return ApiResponse.fail("comment content is required");
        CommentEntity saved = communityStore.saveComment(comment);
        notificationClient.commentCreated(post.getUserId(), userId, post.getId(), saved.getContent());
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
        return ApiResponse.ok(communityStore.listCollectedPosts(userId).stream()
                .map(post -> toPostView(post, userId)).toList());
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
        List<PostEntity> feed = communityStore.feed(authorUserId);
        return ApiResponse.ok(Map.of(
                "module", "论坛管理",
                "publishedPosts", feed.stream().filter(post -> "PUBLISHED".equals(post.getStatus())).count(),
                "pendingAudit", feed.stream().filter(post -> "PENDING".equals(post.getStatus())).count(),
                "draftsTracked", communityStore.listDrafts(authorUserId).size(),
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
        if (!List.of("PUBLISHED", "HIDDEN").contains(status)) return ApiResponse.fail("invalid post status");
        return communityStore.auditPost(postId, status, reason)
                .map(post -> ApiResponse.ok(Map.of(
                        "post", toPostView(post),
                        "reason", reason,
                        "updated", true
                )))
                .orElseGet(() -> ApiResponse.fail("post not found"));
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
        CommentEntity comment = communityStore.listComments(null).stream()
                .filter(item -> commentId.equals(item.getId())).findFirst().orElse(null);
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

    private Map<String, Object> toCommentView(CommentEntity comment) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", comment.getId());
        view.put("postId", comment.getPostId());
        view.put("userId", comment.getUserId());
        view.put("parentId", comment.getParentId());
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
        if (imageUrls.size() > 9) throw new IllegalArgumentException("a draft can contain at most 9 images");
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

    private boolean interactionAllowed(Long userId, Long targetUserId) {
        return userId != null && targetUserId != null
                && (userId.equals(targetUserId) || userRelationClient == null
                || userRelationClient.interactionAllowed(userId, targetUserId));
    }

    private boolean publishingAllowed(Long userId) {
        return userId != null && (userRelationClient == null || userRelationClient.publishingAllowed(userId));
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
}
