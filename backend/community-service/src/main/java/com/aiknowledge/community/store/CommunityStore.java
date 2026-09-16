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
import com.aiknowledge.common.TimeCursor;

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


    /** Counters behind the forum admin overview, optionally narrowed to one author. */
    record PostTotals(long published, long pendingAudit, long hidden, long drafts) { }

    /** MySQL answers this with one grouped query; the in-memory profile folds the feed. */
    default PostTotals postTotals(Long authorUserId) {
        List<PostEntity> posts = feed(authorUserId);
        return new PostTotals(
                posts.stream().filter(post -> "PUBLISHED".equals(post.getStatus())).count(),
                posts.stream().filter(post -> "PENDING".equals(post.getStatus())).count(),
                posts.stream().filter(post -> "HIDDEN".equals(post.getStatus())).count(),
                listDrafts(authorUserId).size());
    }


    /**
     * One page of a moderation post table, newest first.
     *
     * @param statuses only posts in one of these states; empty means every state
     * @param keyword  matches the post id, author id or title (optional)
     * @param beforeId cursor: only posts with a smaller id (optional)
     */
    record AdminPostQuery(Collection<String> statuses, String keyword, Long beforeId, int limit) { }

    /** MySQL pages this by keyset; the in-memory profile filters the feed the same way. */
    default List<PostEntity> pageAdminPosts(AdminPostQuery query) {
        return matchingAdminPosts(query)
                .filter(post -> query.beforeId() == null || post.getId() < query.beforeId())
                .limit(Math.max(query.limit(), 0))
                .toList();
    }

    /** How many posts match the moderation filters, ignoring the cursor. */
    default long countAdminPosts(AdminPostQuery query) {
        return matchingAdminPosts(query).count();
    }

    private java.util.stream.Stream<PostEntity> matchingAdminPosts(AdminPostQuery query) {
        String keyword = query.keyword() == null ? "" : query.keyword().trim().toLowerCase();
        return feed(null).stream()
                .sorted(Comparator.comparing(PostEntity::getId).reversed())
                .filter(post -> query.statuses() == null || query.statuses().isEmpty() || query.statuses().contains(post.getStatus()))
                .filter(post -> keyword.isEmpty()
                        || String.valueOf(post.getId()).contains(keyword)
                        || String.valueOf(post.getUserId()).contains(keyword)
                        || (post.getTitle() != null && post.getTitle().toLowerCase().contains(keyword)));
    }


    /**
     * One page of the governance comment table, newest first, across every post and status.
     *
     * @param keyword  matches the comment, post or author id, or the text (optional)
     * @param beforeId cursor: only comments with a smaller id (optional)
     */
    record AdminCommentQuery(String keyword, Long beforeId, int limit) { }

    default List<CommentEntity> pageAdminComments(AdminCommentQuery query) {
        return matchingAdminComments(query)
                .filter(comment -> query.beforeId() == null || comment.getId() < query.beforeId())
                .limit(Math.max(query.limit(), 0))
                .toList();
    }

    default long countAdminComments(AdminCommentQuery query) {
        return matchingAdminComments(query).count();
    }

    private java.util.stream.Stream<CommentEntity> matchingAdminComments(AdminCommentQuery query) {
        String keyword = query.keyword() == null ? "" : query.keyword().trim().toLowerCase();
        return listComments(null).stream()
                .sorted(Comparator.comparing(CommentEntity::getId).reversed())
                .filter(comment -> keyword.isEmpty()
                        || String.valueOf(comment.getId()).contains(keyword)
                        || String.valueOf(comment.getPostId()).contains(keyword)
                        || String.valueOf(comment.getUserId()).contains(keyword)
                        || (comment.getContent() != null && comment.getContent().toLowerCase().contains(keyword)));
    }

    /**
     * One page of the governance draft table, most recently edited first.
     *
     * @param keyword matches the draft or author id, the title or the text (optional)
     * @param before  cursor: only drafts after this (updated_at, id) position (optional)
     */
    record AdminDraftQuery(String keyword, TimeCursor before, int limit) { }

    default List<PostDraftEntity> pageAdminDrafts(AdminDraftQuery query) {
        return matchingAdminDrafts(query)
                .filter(draft -> query.before() == null || query.before().isAfter(draft.getUpdatedAt(), draft.getId()))
                .limit(Math.max(query.limit(), 0))
                .toList();
    }

    default long countAdminDrafts(AdminDraftQuery query) {
        return matchingAdminDrafts(query).count();
    }

    private java.util.stream.Stream<PostDraftEntity> matchingAdminDrafts(AdminDraftQuery query) {
        String keyword = query.keyword() == null ? "" : query.keyword().trim().toLowerCase();
        return listDrafts(null).stream()
                .sorted(Comparator.comparing(PostDraftEntity::getUpdatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(PostDraftEntity::getId)
                        .reversed())
                .filter(draft -> keyword.isEmpty()
                        || String.valueOf(draft.getId()).contains(keyword)
                        || String.valueOf(draft.getUserId()).contains(keyword)
                        || (draft.getTitle() != null && draft.getTitle().toLowerCase().contains(keyword))
                        || (draft.getContent() != null && draft.getContent().toLowerCase().contains(keyword)));
    }

}
