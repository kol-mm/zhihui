package com.aiknowledge.community.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.community.entity.CommentEntity;
import com.aiknowledge.community.entity.PostCollectEntity;
import com.aiknowledge.community.entity.PostDraftEntity;
import com.aiknowledge.community.entity.PostEntity;
import com.aiknowledge.community.store.CommunityStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class CommunityController {
    private final CommunityStore communityStore;

    public CommunityController(CommunityStore communityStore) {
        this.communityStore = communityStore;
    }

    @GetMapping("/post/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of("service", "community-service", "time", Instant.now().toString()));
    }

    @PostMapping("/post/create")
    public ApiResponse<Map<String, Object>> createPost(@RequestBody Map<String, Object> request) {
        PostEntity post = new PostEntity();
        post.setUserId(number(request.get("userId"), 1L));
        post.setTitle(String.valueOf(request.getOrDefault("title", "Untitled post")));
        post.setContent(String.valueOf(request.getOrDefault("content", "")));
        post.setStatus("PUBLISHED");
        return ApiResponse.ok(toPostView(communityStore.savePost(post)));
    }

    @PutMapping("/post/update")
    public ApiResponse<Map<String, Object>> updatePost(@RequestBody Map<String, Object> request) {
        PostEntity post = new PostEntity();
        post.setId(number(request.get("id"), 0L));
        post.setTitle(String.valueOf(request.getOrDefault("title", "Updated post")));
        post.setContent(String.valueOf(request.getOrDefault("content", "")));
        post.setStatus("PUBLISHED");
        return ApiResponse.ok(toPostView(communityStore.updatePost(post)));
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
    public ApiResponse<Map<String, Object>> createComment(@RequestBody Map<String, Object> request) {
        CommentEntity comment = buildComment(request, "POST");
        return ApiResponse.ok(toCommentView(communityStore.saveComment(comment)));
    }

    @GetMapping("/comment/list")
    public ApiResponse<List<Map<String, Object>>> comments(@RequestParam(name = "postId", required = false) Long postId) {
        return ApiResponse.ok(communityStore.listComments(postId).stream().map(this::toCommentView).toList());
    }

    @PostMapping("/post/draft")
    public ApiResponse<Map<String, Object>> saveDraft(@RequestBody Map<String, Object> request) {
        PostDraftEntity draft = new PostDraftEntity();
        draft.setUserId(number(request.get("userId"), 1L));
        draft.setTitle(String.valueOf(request.getOrDefault("title", "Untitled draft")));
        draft.setContent(String.valueOf(request.getOrDefault("content", "")));
        return ApiResponse.ok(toDraftView(communityStore.saveDraft(draft)));
    }

    @GetMapping("/post/drafts")
    public ApiResponse<List<Map<String, Object>>> drafts(@RequestParam(name = "userId", required = false) Long userId) {
        return ApiResponse.ok(communityStore.listDrafts(userId).stream().map(this::toDraftView).toList());
    }

    @GetMapping("/square/feed")
    public ApiResponse<List<Map<String, Object>>> feed(@RequestParam(name = "authorUserId", required = false) Long authorUserId) {
        return ApiResponse.ok(communityStore.feed(authorUserId).stream().map(this::toPostView).toList());
    }

    @PostMapping("/square/quick-comment")
    public ApiResponse<Map<String, Object>> quickComment(@RequestBody Map<String, Object> request) {
        CommentEntity comment = buildComment(request, "SQUARE");
        return ApiResponse.ok(toCommentView(communityStore.saveComment(comment)));
    }

    @PostMapping("/square/collect")
    public ApiResponse<Map<String, Object>> squareCollect(@RequestBody Map<String, Object> request) {
        PostCollectEntity collect = new PostCollectEntity();
        collect.setUserId(number(request.get("userId"), 1L));
        collect.setPostId(number(request.get("postId"), 0L));
        collect.setSource("SQUARE");
        PostCollectEntity saved = communityStore.collectPost(collect);
        return ApiResponse.ok(Map.of("id", saved.getId(), "postId", saved.getPostId(), "collected", true, "source", saved.getSource()));
    }

    @GetMapping("/post/admin/overview")
    public ApiResponse<Map<String, Object>> adminOverview(@RequestParam(name = "authorUserId", required = false) Long authorUserId) {
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
    public ApiResponse<Map<String, Object>> auditPost(@RequestBody Map<String, Object> request) {
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

    private CommentEntity buildComment(Map<String, Object> request, String source) {
        CommentEntity comment = new CommentEntity();
        comment.setPostId(number(request.get("postId"), 0L));
        comment.setUserId(number(request.get("userId"), 1L));
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
}
