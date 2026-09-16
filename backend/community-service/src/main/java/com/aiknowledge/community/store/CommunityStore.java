package com.aiknowledge.community.store;

import com.aiknowledge.community.entity.CommentEntity;
import com.aiknowledge.community.entity.PostDraftEntity;
import com.aiknowledge.community.entity.PostEntity;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;

public interface CommunityStore {
    PostEntity savePost(PostEntity post);

    PostEntity updatePost(PostEntity post);

    Optional<PostEntity> findPost(Long id);

    List<PostEntity> feed(Long authorUserId);

    /** Newest-first page of posts (by id) that the viewer may see. */
    List<PostEntity> pagePosts(PostPageQuery query);

    long countVisiblePosts(Long viewerUserId, boolean includeAll);

    CommentEntity saveComment(CommentEntity comment);

    List<CommentEntity> listComments(Long postId);

    Optional<CommentEntity> findComment(Long commentId);

    /** Thread roots (root_id = id) of a post with id greater than afterId, ascending by id. */
    List<CommentEntity> listRootComments(Long postId, Long afterId, int limit);

    /** Every comment (roots and replies) belonging to the given thread roots, ascending by id. */
    List<CommentEntity> listThreadComments(Long postId, Collection<Long> rootIds);

    long countComments(Long postId, boolean visibleOnly);

    PostDraftEntity saveDraft(PostDraftEntity draft);

    PostDraftEntity updateDraft(PostDraftEntity draft);

    Optional<PostDraftEntity> findDraft(Long id);

    List<PostDraftEntity> listDrafts(Long userId);

    boolean togglePostCollect(Long userId, Long postId);

    boolean hasPostCollect(Long userId, Long postId);

    List<PostEntity> listCollectedPosts(Long userId);

    Optional<PostEntity> auditPost(Long postId, String status, String reason);

    void savePostImages(Long postId, List<String> imageUrls);

    List<String> listPostImages(Long postId);

    Map<Long, List<String>> listPostImages(Collection<Long> postIds);

    boolean togglePostLike(Long userId, Long postId);

    boolean hasPostLike(Long userId, Long postId);

    long countPostLikes(Long postId);

    Map<Long, Long> countPostLikes(Collection<Long> postIds);

    Set<Long> likedPostIds(Long userId, Collection<Long> postIds);

    Set<Long> collectedPostIds(Long userId, Collection<Long> postIds);

    boolean removePost(Long postId);

    boolean removeComment(Long commentId);

    Optional<CommentEntity> updateCommentStatus(Long commentId, String status);

    boolean removeDraft(Long draftId);

    int removeDraftsBefore(LocalDateTime cutoff);

    /**
     * @param authorUserId  only posts by this author (optional)
     * @param authorUserIds only posts by one of these authors (optional)
     * @param includeAll    true for admins: skip the published-or-own visibility rule
     * @param beforeId      cursor: only posts with a smaller id (optional)
     */
    record PostPageQuery(Long authorUserId, Collection<Long> authorUserIds, Long viewerUserId, boolean includeAll, Long beforeId, int limit) { }
    record PostImageRecord(Long id, Long postId, String imageUrl, LocalDateTime createdAt) { }
    record PostLikeRecord(Long id, Long userId, Long postId, LocalDateTime createdAt) { }

    /** Totals behind the admin analytics page: published posts created since the cutoff. */
    record PostAnalytics(long posts, long likes) { }

    /** One day of a trend series, keyed by ISO date. */
    record DailyCount(String date, long count) { }

    /**
     * Aggregates published posts created since {@code since}. MySQL answers this with grouped
     * queries; the in-memory profile folds the same numbers over the feed.
     */
    default PostAnalytics postAnalytics(LocalDateTime since) {
        List<PostEntity> posts = analyticsPosts(since);
        Map<Long, Long> likes = countPostLikes(posts.stream().map(PostEntity::getId).toList());
        return new PostAnalytics(posts.size(), likes.values().stream().mapToLong(Long::longValue).sum());
    }

    /** New published posts per day; days without posts are left out and filled in by the caller. */
    default List<DailyCount> dailyPostCounts(LocalDateTime since) {
        Map<String, Long> perDay = new LinkedHashMap<>();
        for (PostEntity post : analyticsPosts(since)) {
            if (post.getCreatedAt() == null) continue;
            perDay.merge(post.getCreatedAt().toLocalDate().toString(), 1L, Long::sum);
        }
        return perDay.entrySet().stream().map(entry -> new DailyCount(entry.getKey(), entry.getValue())).toList();
    }

    /** The most liked published posts, in the order the analytics ranking shows them. */
    default List<PostEntity> topPosts(int limit) {
        if (limit <= 0) return List.of();
        List<PostEntity> published = feed(null).stream()
                .filter(post -> "PUBLISHED".equals(post.getStatus()))
                .toList();
        Map<Long, Long> likes = countPostLikes(published.stream().map(PostEntity::getId).toList());
        return published.stream()
                .sorted(Comparator.comparingLong((PostEntity post) -> likes.getOrDefault(post.getId(), 0L)).reversed())
                .limit(limit)
                .toList();
    }

    private List<PostEntity> analyticsPosts(LocalDateTime since) {
        return feed(null).stream()
                .filter(post -> "PUBLISHED".equals(post.getStatus()))
                .filter(post -> post.getCreatedAt() == null || !post.getCreatedAt().isBefore(since))
                .toList();
    }

}
