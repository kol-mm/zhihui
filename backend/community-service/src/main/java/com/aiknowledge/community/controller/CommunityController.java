package com.aiknowledge.community.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.community.entity.CommentEntity;
import com.aiknowledge.community.entity.PostCollectEntity;
import com.aiknowledge.community.entity.PostDraftEntity;
import com.aiknowledge.community.entity.PostEntity;
import com.aiknowledge.community.store.CommunityStore;
import com.aiknowledge.community.storage.CommunityMediaStorageService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class CommunityController {
    private final CommunityStore communityStore;
    private final CommunityMediaStorageService mediaStorage;

    public CommunityController(CommunityStore communityStore, CommunityMediaStorageService mediaStorage) {
        this.communityStore = communityStore;
        this.mediaStorage = mediaStorage;
    }

    @PostMapping(value = "/post/media/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> uploadImages(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam("files") List<MultipartFile> files
    ) {
        if (!LocalAuth.isAuthenticated(authorization)) return ApiResponse.fail("valid user authorization is required");
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
        PostEntity post = new PostEntity();
        post.setUserId(userId);
        post.setTitle(String.valueOf(request.getOrDefault("title", "Untitled post")));
        post.setContent(String.valueOf(request.getOrDefault("content", "")));
        post.setStatus("PUBLISHED");
        PostEntity saved = communityStore.savePost(post);
        communityStore.savePostImages(saved.getId(), stringList(request.get("imageUrls")));
        return ApiResponse.ok(toPostView(saved));
    }

    @PutMapping("/post/update")
    public ApiResponse<Map<String, Object>> updatePost(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long postId = number(request.get("id"), 0L);
        PostEntity existing = communityStore.findPost(postId).orElse(null);
        if (existing == null) return ApiResponse.fail("post not found");
        if (!LocalAuth.canAccessUser(authorization, existing.getUserId())) return ApiResponse.fail("access to this post is denied");
        PostEntity post = new PostEntity();
        post.setId(postId);
        post.setTitle(String.valueOf(request.getOrDefault("title", "Updated post")));
        post.setContent(String.valueOf(request.getOrDefault("content", "")));
        post.setStatus("PUBLISHED");
        PostEntity updated = communityStore.updatePost(post);
        if (request.containsKey("imageUrls")) communityStore.savePostImages(updated.getId(), stringList(request.get("imageUrls")));
        return ApiResponse.ok(toPostView(updated));
    }

    @GetMapping("/post/detail")
    public ApiResponse<Map<String, Object>> detail(@RequestParam(name = "id", defaultValue = "1") Long id) {
        return communityStore.findPost(id)
                .map(post -> {
                    Map<String, Object> detail = toPostView(post);
                    detail.put("comments", communityStore.listComments(id).stream().map(this::toCommentView).toList());
                    return ApiResponse.ok(detail);
                })
                .orElseGet(() -> ApiResponse.fail("post not found"));
    }

    @PostMapping("/comment/create")
    public ApiResponse<Map<String, Object>> createComment(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        CommentEntity comment = buildComment(request, "POST", userId);
        return ApiResponse.ok(toCommentView(communityStore.saveComment(comment)));
    }

    @GetMapping("/comment/list")
    public ApiResponse<List<Map<String, Object>>> comments(@RequestParam(name = "postId", required = false) Long postId) {
        return ApiResponse.ok(communityStore.listComments(postId).stream().map(this::toCommentView).toList());
    }

    @PostMapping("/post/draft")
    public ApiResponse<Map<String, Object>> saveDraft(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        PostDraftEntity draft = new PostDraftEntity();
        draft.setUserId(userId);
        draft.setTitle(String.valueOf(request.getOrDefault("title", "Untitled draft")));
        draft.setContent(String.valueOf(request.getOrDefault("content", "")));
        return ApiResponse.ok(toDraftView(communityStore.saveDraft(draft)));
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
    public ApiResponse<List<Map<String, Object>>> feed(@RequestParam(name = "authorUserId", required = false) Long authorUserId) {
        return ApiResponse.ok(communityStore.feed(authorUserId).stream().map(this::toPostView).toList());
    }

    @GetMapping("/square/following-feed")
    public ApiResponse<List<Map<String, Object>>> followingFeed(
            @RequestParam(name = "followedUserIds", defaultValue = "") String followedUserIds
    ) {
        List<Long> ids = java.util.Arrays.stream(followedUserIds.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).map(Long::valueOf).toList();
        return ApiResponse.ok(communityStore.feed(null).stream().filter(post -> ids.contains(post.getUserId()))
                .map(this::toPostView).toList());
    }

    @PostMapping("/square/quick-comment")
    public ApiResponse<Map<String, Object>> quickComment(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        CommentEntity comment = buildComment(request, "SQUARE", userId);
        return ApiResponse.ok(toCommentView(communityStore.saveComment(comment)));
    }

    @PostMapping("/square/collect")
    public ApiResponse<Map<String, Object>> squareCollect(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        PostCollectEntity collect = new PostCollectEntity();
        collect.setUserId(userId);
        collect.setPostId(number(request.get("postId"), 0L));
        collect.setSource("SQUARE");
        PostCollectEntity saved = communityStore.collectPost(collect);
        return ApiResponse.ok(Map.of("id", saved.getId(), "postId", saved.getPostId(), "collected", true, "source", saved.getSource()));
    }

    @PostMapping("/post/like")
    public ApiResponse<Map<String, Object>> likePost(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        Long postId = number(request.get("postId"), 0L);
        communityStore.likePost(userId, postId);
        return ApiResponse.ok(Map.of("postId", postId, "liked", true, "likes", communityStore.countPostLikes(postId)));
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
                "publishedPosts", feed.size(),
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
        return communityStore.auditPost(postId, status, reason)
                .map(post -> ApiResponse.ok(Map.of(
                        "post", toPostView(post),
                        "reason", reason,
                        "updated", true
                )))
                .orElseGet(() -> ApiResponse.fail("post not found"));
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

    @DeleteMapping("/post/admin/draft")
    public ApiResponse<Map<String, Object>> removeDraft(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        if (!LocalAuth.isAdmin(authorization)) return ApiResponse.fail("admin authorization is required");
        Long draftId = number(request.get("draftId"), 0L);
        return ApiResponse.ok(Map.of("draftId", draftId, "removed", communityStore.removeDraft(draftId)));
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
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", post.getId());
        view.put("userId", post.getUserId());
        view.put("title", post.getTitle());
        view.put("content", post.getContent());
        view.put("status", post.getStatus());
        view.put("imageUrls", communityStore.listPostImages(post.getId()));
        view.put("likes", communityStore.countPostLikes(post.getId()));
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
}
