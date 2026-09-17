package com.aiknowledge.community.store;

import com.aiknowledge.common.AppTime;
import com.aiknowledge.community.entity.CommentEntity;
import com.aiknowledge.community.entity.PostCollectEntity;
import com.aiknowledge.community.entity.PostDraftEntity;
import com.aiknowledge.community.entity.PostEntity;
import com.aiknowledge.community.entity.PostImageEntity;
import com.aiknowledge.community.entity.PostLikeEntity;
import com.aiknowledge.community.mapper.CommentMapper;
import com.aiknowledge.community.mapper.PostCollectMapper;
import com.aiknowledge.community.mapper.PostDraftMapper;
import com.aiknowledge.community.mapper.PostMapper;
import com.aiknowledge.community.mapper.PostImageMapper;
import com.aiknowledge.community.mapper.PostLikeMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Objects;
import com.aiknowledge.common.TimeCursor;

@Repository
@Profile("mysql")
public class MySqlCommunityStore implements CommunityStore {
    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final PostDraftMapper draftMapper;
    private final PostCollectMapper collectMapper;
    private final PostImageMapper imageMapper;
    private final PostLikeMapper likeMapper;

    public MySqlCommunityStore(
            PostMapper postMapper,
            CommentMapper commentMapper,
            PostDraftMapper draftMapper,
            PostCollectMapper collectMapper,
            PostImageMapper imageMapper,
            PostLikeMapper likeMapper
    ) {
        this.postMapper = postMapper;
        this.commentMapper = commentMapper;
        this.draftMapper = draftMapper;
        this.collectMapper = collectMapper;
        this.imageMapper = imageMapper;
        this.likeMapper = likeMapper;
    }

    @Override
    public PostEntity savePost(PostEntity post) {
        postMapper.insert(post);
        return post;
    }

    @Override
    public PostEntity updatePost(PostEntity post) {
        postMapper.updateById(post);
        return postMapper.selectById(post.getId());
    }

    @Override
    public Optional<PostEntity> findPost(Long id) {
        return Optional.ofNullable(postMapper.selectById(id));
    }

    @Override
    public List<PostEntity> feed(Long authorUserId) {
        return postMapper.selectList(Wrappers.<PostEntity>lambdaQuery()
                .eq(authorUserId != null, PostEntity::getUserId, authorUserId)
                .orderByDesc(PostEntity::getCreatedAt));
    }

    @Override
    public List<PostEntity> pagePosts(PostPageQuery query) {
        LambdaQueryWrapper<PostEntity> wrapper = visiblePosts(query.viewerUserId(), query.includeAll())
                .eq(query.authorUserId() != null, PostEntity::getUserId, query.authorUserId())
                .in(query.authorUserIds() != null && !query.authorUserIds().isEmpty(), PostEntity::getUserId, query.authorUserIds())
                .lt(query.beforeId() != null && query.beforeId() > 0, PostEntity::getId, query.beforeId())
                .orderByDesc(PostEntity::getId)
                .last("LIMIT " + Math.max(1, query.limit()));
        return postMapper.selectList(wrapper);
    }

    @Override
    public long countVisiblePosts(Long viewerUserId, boolean includeAll) {
        Long count = postMapper.selectCount(visiblePosts(viewerUserId, includeAll));
        return count == null ? 0 : count;
    }

    private LambdaQueryWrapper<PostEntity> visiblePosts(Long viewerUserId, boolean includeAll) {
        LambdaQueryWrapper<PostEntity> wrapper = Wrappers.lambdaQuery();
        if (!includeAll) {
            wrapper.and(visible -> visible.eq(PostEntity::getStatus, "PUBLISHED")
                    .or(viewerUserId != null, own -> own.eq(PostEntity::getUserId, viewerUserId)));
        }
        return wrapper;
    }

    @Override
    @Transactional
    public CommentEntity saveComment(CommentEntity comment) {
        if (comment.getRootId() == null && comment.getParentId() != null && comment.getParentId() > 0) {
            CommentEntity parent = commentMapper.selectById(comment.getParentId());
            if (parent != null) comment.setRootId(parent.getRootId() != null ? parent.getRootId() : parent.getId());
        }
        commentMapper.insert(comment);
        if (comment.getRootId() == null) {
            comment.setRootId(comment.getId());
            commentMapper.update(null, Wrappers.<CommentEntity>lambdaUpdate()
                    .eq(CommentEntity::getId, comment.getId())
                    .set(CommentEntity::getRootId, comment.getId())
                    .setSql("is_root = 1"));
        }
        if (comment.getCreatedAt() == null) {
            CommentEntity stored = commentMapper.selectById(comment.getId());
            if (stored != null) comment.setCreatedAt(stored.getCreatedAt());
        }
        return comment;
    }

    @Override
    public List<CommentEntity> listComments(Long postId) {
        return commentMapper.selectList(Wrappers.<CommentEntity>lambdaQuery()
                .eq(postId != null, CommentEntity::getPostId, postId)
                .orderByAsc(CommentEntity::getCreatedAt));
    }

    @Override
    public Optional<CommentEntity> findComment(Long commentId) {
        return commentId == null ? Optional.empty() : Optional.ofNullable(commentMapper.selectById(commentId));
    }

    @Override
    public List<CommentEntity> listRootComments(Long postId, Long afterId, int limit) {
        return commentMapper.selectList(Wrappers.<CommentEntity>lambdaQuery()
                .eq(CommentEntity::getPostId, postId)
                .apply("is_root = 1")
                .gt(afterId != null && afterId > 0, CommentEntity::getId, afterId)
                .orderByAsc(CommentEntity::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    @Override
    public List<CommentEntity> listThreadComments(Long postId, Collection<Long> rootIds) {
        if (rootIds == null || rootIds.isEmpty()) return List.of();
        return commentMapper.selectList(Wrappers.<CommentEntity>lambdaQuery()
                .eq(CommentEntity::getPostId, postId)
                .in(CommentEntity::getRootId, rootIds)
                .orderByAsc(CommentEntity::getId));
    }

    @Override
    public long countComments(Long postId, boolean visibleOnly) {
        return commentMapper.selectCount(Wrappers.<CommentEntity>lambdaQuery()
                .eq(CommentEntity::getPostId, postId)
                .eq(visibleOnly, CommentEntity::getStatus, "VISIBLE"));
    }

    @Override
    public PostDraftEntity saveDraft(PostDraftEntity draft) {
        draftMapper.insert(draft);
        return draft;
    }

    @Override
    public PostDraftEntity updateDraft(PostDraftEntity draft) {
        draft.setUpdatedAt(LocalDateTime.now());
        draftMapper.updateById(draft);
        return draft;
    }

    @Override
    public Optional<PostDraftEntity> findDraft(Long id) {
        return Optional.ofNullable(draftMapper.selectById(id));
    }

    @Override
    public List<PostDraftEntity> listDrafts(Long userId) {
        return draftMapper.selectList(Wrappers.<PostDraftEntity>lambdaQuery()
                .eq(userId != null, PostDraftEntity::getUserId, userId)
                .orderByDesc(PostDraftEntity::getUpdatedAt));
    }

    @Override
    @Transactional
    public boolean togglePostCollect(Long userId, Long postId) {
        var match = Wrappers.<PostCollectEntity>lambdaQuery()
                .eq(PostCollectEntity::getUserId, userId).eq(PostCollectEntity::getPostId, postId);
        Long existing = collectMapper.selectCount(match);
        if (existing != null && existing > 0) {
            collectMapper.delete(match);
            return false;
        }
        PostCollectEntity collect = new PostCollectEntity();
        collect.setUserId(userId);
        collect.setPostId(postId);
        collect.setSource("SQUARE");
        collect.setCreatedAt(LocalDateTime.now());
        try {
            collectMapper.insert(collect);
            return true;
        } catch (DuplicateKeyException ignored) {
            collectMapper.delete(Wrappers.<PostCollectEntity>lambdaQuery()
                    .eq(PostCollectEntity::getUserId, userId).eq(PostCollectEntity::getPostId, postId));
            return false;
        }
    }

    @Override
    public boolean hasPostCollect(Long userId, Long postId) {
        if (userId == null) return false;
        Long count = collectMapper.selectCount(Wrappers.<PostCollectEntity>lambdaQuery()
                .eq(PostCollectEntity::getUserId, userId).eq(PostCollectEntity::getPostId, postId));
        return count != null && count > 0;
    }

    @Override
    public List<PostEntity> listCollectedPosts(Long userId) {
        List<Long> postIds = collectMapper.selectList(Wrappers.<PostCollectEntity>lambdaQuery()
                        .eq(PostCollectEntity::getUserId, userId)
                        .orderByDesc(PostCollectEntity::getCreatedAt))
                .stream().map(PostCollectEntity::getPostId).distinct().toList();
        if (postIds.isEmpty()) return List.of();
        Map<Long, PostEntity> posts = new HashMap<>();
        postMapper.selectBatchIds(postIds).forEach(post -> posts.put(post.getId(), post));
        return postIds.stream()
                .map(posts::get)
                .filter(post -> post != null && "PUBLISHED".equals(post.getStatus()))
                .toList();
    }

    @Override
    public Optional<PostEntity> auditPost(Long postId, String status, String reason) {
        PostEntity post = postMapper.selectById(postId);
        if (post == null || status.equals(post.getStatus())) {
            return Optional.empty();
        }
        int updated = postMapper.update(null, Wrappers.<PostEntity>lambdaUpdate()
                .eq(PostEntity::getId, postId)
                .eq(PostEntity::getStatus, post.getStatus())
                .set(PostEntity::getStatus, status)
                .set(PostEntity::getUpdatedAt, LocalDateTime.now()));
        return updated == 1 ? Optional.ofNullable(postMapper.selectById(postId)) : Optional.empty();
    }

    @Override
    @Transactional
    public void savePostImages(Long postId, List<String> imageUrls) {
        imageMapper.delete(Wrappers.<PostImageEntity>lambdaQuery().eq(PostImageEntity::getPostId, postId));
        for (int index = 0; index < imageUrls.size(); index++) {
            PostImageEntity image = new PostImageEntity();
            image.setPostId(postId);
            image.setImageUrl(imageUrls.get(index));
            image.setSortNo(index);
            imageMapper.insert(image);
        }
    }

    @Override
    public List<String> listPostImages(Long postId) {
        return imageMapper.selectList(Wrappers.<PostImageEntity>lambdaQuery()
                        .eq(PostImageEntity::getPostId, postId).orderByAsc(PostImageEntity::getSortNo))
                .stream().map(PostImageEntity::getImageUrl).toList();
    }

    @Override
    public Map<Long, List<String>> listPostImages(Collection<Long> postIds) {
        Map<Long, List<String>> images = new LinkedHashMap<>();
        if (postIds == null || postIds.isEmpty()) return images;
        imageMapper.selectList(Wrappers.<PostImageEntity>lambdaQuery()
                        .in(PostImageEntity::getPostId, postIds)
                        .orderByAsc(PostImageEntity::getPostId).orderByAsc(PostImageEntity::getSortNo))
                .forEach(image -> images.computeIfAbsent(image.getPostId(), key -> new java.util.ArrayList<>()).add(image.getImageUrl()));
        return images;
    }

    @Override
    @Transactional
    public boolean togglePostLike(Long userId, Long postId) {
        var match = Wrappers.<PostLikeEntity>lambdaQuery()
                .eq(PostLikeEntity::getUserId, userId).eq(PostLikeEntity::getPostId, postId);
        Long existing = likeMapper.selectCount(match);
        if (existing != null && existing > 0) {
            likeMapper.delete(match);
            return false;
        }
        PostLikeEntity like = new PostLikeEntity();
        like.setUserId(userId);
        like.setPostId(postId);
        like.setSource("POST");
        like.setCreatedAt(LocalDateTime.now());
        try {
            likeMapper.insert(like);
            return true;
        } catch (DuplicateKeyException ignored) {
            likeMapper.delete(Wrappers.<PostLikeEntity>lambdaQuery()
                    .eq(PostLikeEntity::getUserId, userId).eq(PostLikeEntity::getPostId, postId));
            return false;
        }
    }

    @Override
    public boolean hasPostLike(Long userId, Long postId) {
        if (userId == null) return false;
        Long count = likeMapper.selectCount(Wrappers.<PostLikeEntity>lambdaQuery()
                .eq(PostLikeEntity::getUserId, userId).eq(PostLikeEntity::getPostId, postId));
        return count != null && count > 0;
    }

    @Override
    public long countPostLikes(Long postId) {
        Long count = likeMapper.selectCount(Wrappers.<PostLikeEntity>lambdaQuery().eq(PostLikeEntity::getPostId, postId));
        return count == null ? 0 : count;
    }

    @Override
    public Map<Long, Long> countPostLikes(Collection<Long> postIds) {
        Map<Long, Long> counts = new HashMap<>();
        if (postIds == null || postIds.isEmpty()) return counts;
        likeMapper.selectMaps(new QueryWrapper<PostLikeEntity>()
                        .select("post_id AS post_id", "COUNT(*) AS like_count")
                        .in("post_id", postIds)
                        .groupBy("post_id"))
                .forEach(row -> counts.put(((Number) row.get("post_id")).longValue(), ((Number) row.get("like_count")).longValue()));
        return counts;
    }

    @Override
    public Set<Long> likedPostIds(Long userId, Collection<Long> postIds) {
        if (userId == null || postIds == null || postIds.isEmpty()) return Set.of();
        Set<Long> liked = new HashSet<>();
        likeMapper.selectList(Wrappers.<PostLikeEntity>lambdaQuery()
                        .select(PostLikeEntity::getPostId)
                        .eq(PostLikeEntity::getUserId, userId)
                        .in(PostLikeEntity::getPostId, postIds))
                .forEach(like -> liked.add(like.getPostId()));
        return liked;
    }

    @Override
    public Set<Long> collectedPostIds(Long userId, Collection<Long> postIds) {
        if (userId == null || postIds == null || postIds.isEmpty()) return Set.of();
        Set<Long> collected = new HashSet<>();
        collectMapper.selectList(Wrappers.<PostCollectEntity>lambdaQuery()
                        .select(PostCollectEntity::getPostId)
                        .eq(PostCollectEntity::getUserId, userId)
                        .in(PostCollectEntity::getPostId, postIds))
                .forEach(collect -> collected.add(collect.getPostId()));
        return collected;
    }

    @Override
    @Transactional
    public boolean removePost(Long postId) {
        if (postMapper.selectById(postId) == null) return false;
        commentMapper.delete(Wrappers.<CommentEntity>lambdaQuery().eq(CommentEntity::getPostId, postId));
        collectMapper.delete(Wrappers.<PostCollectEntity>lambdaQuery().eq(PostCollectEntity::getPostId, postId));
        imageMapper.delete(Wrappers.<PostImageEntity>lambdaQuery().eq(PostImageEntity::getPostId, postId));
        likeMapper.delete(Wrappers.<PostLikeEntity>lambdaQuery().eq(PostLikeEntity::getPostId, postId));
        return postMapper.deleteById(postId) > 0;
    }

    @Override
    @Transactional
    public boolean removeComment(Long commentId) {
        CommentEntity root = commentMapper.selectById(commentId);
        if (root == null) return false;
        List<CommentEntity> all = commentMapper.selectList(Wrappers.<CommentEntity>lambdaQuery()
                .eq(CommentEntity::getPostId, root.getPostId())
                .eq(root.getRootId() != null, CommentEntity::getRootId, root.getRootId()));
        java.util.Set<Long> removedIds = new java.util.LinkedHashSet<>();
        removedIds.add(commentId);
        boolean changed;
        do {
            changed = all.stream()
                    .filter(item -> item.getParentId() != null && removedIds.contains(item.getParentId()))
                    .map(CommentEntity::getId)
                    .filter(removedIds::add)
                    .count() > 0;
        } while (changed);
        return commentMapper.deleteByIds(removedIds) > 0;
    }

    @Override
    public Optional<CommentEntity> updateCommentStatus(Long commentId, String status) {
        CommentEntity comment = commentMapper.selectById(commentId);
        if (comment == null) return Optional.empty();
        comment.setStatus(status);
        commentMapper.updateById(comment);
        return Optional.of(comment);
    }

    @Override public boolean removeDraft(Long draftId) { return draftMapper.deleteById(draftId) > 0; }

    @Override
    public int removeDraftsBefore(LocalDateTime cutoff) {
        return draftMapper.delete(Wrappers.<PostDraftEntity>lambdaQuery().lt(PostDraftEntity::getUpdatedAt, cutoff));
    }

    /**
     * Optimizer hint for the analytics time windows. Left alone, MySQL reads every post with the status through
     * idx_post_status_id and filters the dates afterwards; on a 300k-post table that was 3 to 10 times slower for
     * 7 to 180 day windows and no faster for a full year. the Flyway baseline (V1) creates the index, and MySQL
     * ignores the hint with a warning if it is ever missing.
     */
    private static final String WINDOW_INDEX = "/*+ INDEX(post idx_post_status_created) */ ";

    @Override
    public PostAnalytics postAnalytics(LocalDateTime since) {
        List<Map<String, Object>> rows = postMapper.selectMaps(new QueryWrapper<PostEntity>()
                .select(WINDOW_INDEX + "COUNT(*) AS post_count")
                .eq("status", "PUBLISHED")
                .ge("created_at", since));
        long posts = rows.isEmpty() ? 0L : countValue(rows.get(0).get("post_count"));
        Long likes = likeMapper.selectCount(new QueryWrapper<PostLikeEntity>()
                .apply("post_id IN (SELECT " + WINDOW_INDEX + "id FROM post WHERE status = 'PUBLISHED' AND created_at >= {0})", since));
        return new PostAnalytics(posts, likes == null ? 0L : likes);
    }

    @Override
    public List<DailyCount> dailyPostCounts(LocalDateTime since) {
        String day = AppTime.sqlBusinessDate("created_at");
        return postMapper.selectMaps(new QueryWrapper<PostEntity>()
                        .select(WINDOW_INDEX + day + " AS day", "COUNT(*) AS post_count")
                        .eq("status", "PUBLISHED")
                        .ge("created_at", since)
                        .groupBy(day)
                        .orderByAsc(day))
                .stream()
                .map(row -> new DailyCount(String.valueOf(row.get("day")), countValue(row.get("post_count"))))
                .toList();
    }

    @Override
    public List<PostEntity> topPosts(int limit) {
        if (limit <= 0) return List.of();
        List<Long> ranked = likeMapper.selectMaps(new QueryWrapper<PostLikeEntity>()
                        .select("post_id AS post_id", "COUNT(*) AS like_count")
                        .groupBy("post_id")
                        .orderByDesc("COUNT(*)")
                        .last("LIMIT " + Math.max(limit * 4, 20)))
                .stream()
                .map(row -> ((Number) row.get("post_id")).longValue())
                .toList();
        List<PostEntity> ordered = new ArrayList<>();
        if (!ranked.isEmpty()) {
            Map<Long, PostEntity> byId = new LinkedHashMap<>();
            postMapper.selectBatchIds(ranked).stream()
                    .filter(post -> "PUBLISHED".equals(post.getStatus()))
                    .forEach(post -> byId.put(post.getId(), post));
            ranked.stream().map(byId::get).filter(Objects::nonNull).forEach(ordered::add);
        }
        if (ordered.size() < limit) {
            // Fewer liked posts than the ranking shows: pad with the newest published posts, the
            // same way sorting the whole feed by like count used to.
            Set<Long> taken = new HashSet<>();
            ordered.forEach(post -> taken.add(post.getId()));
            postMapper.selectList(Wrappers.<PostEntity>lambdaQuery()
                            .eq(PostEntity::getStatus, "PUBLISHED")
                            .orderByDesc(PostEntity::getId)
                            .last("LIMIT " + limit * 2))
                    .stream()
                    .filter(post -> !taken.contains(post.getId()))
                    .forEach(ordered::add);
        }
        return ordered.stream().limit(limit).toList();
    }

    private static long countValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }


    @Override
    public PostTotals postTotals(Long authorUserId) {
        QueryWrapper<PostEntity> wrapper = new QueryWrapper<PostEntity>()
                .select("IFNULL(SUM(CASE WHEN status = 'PUBLISHED' THEN 1 ELSE 0 END), 0) AS published_count",
                        "IFNULL(SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending_count",
                        "IFNULL(SUM(CASE WHEN status = 'HIDDEN' THEN 1 ELSE 0 END), 0) AS hidden_count");
        if (authorUserId != null) wrapper.eq("user_id", authorUserId);
        List<Map<String, Object>> rows = postMapper.selectMaps(wrapper);
        Map<String, Object> totals = rows.isEmpty() ? Map.of() : rows.get(0);
        Long drafts = draftMapper.selectCount(Wrappers.<PostDraftEntity>lambdaQuery()
                .eq(authorUserId != null, PostDraftEntity::getUserId, authorUserId));
        return new PostTotals(
                countValue(totals.get("published_count")),
                countValue(totals.get("pending_count")),
                countValue(totals.get("hidden_count")),
                drafts == null ? 0L : drafts);
    }


    @Override
    public List<PostEntity> pageAdminPosts(AdminPostQuery query) {
        if (query.limit() <= 0) return List.of();
        return postMapper.selectList(adminPostFilter(query)
                .lt(query.beforeId() != null, PostEntity::getId, query.beforeId())
                .orderByDesc(PostEntity::getId)
                .last("LIMIT " + query.limit()));
    }

    @Override
    public long countAdminPosts(AdminPostQuery query) {
        Long count = postMapper.selectCount(adminPostFilter(query));
        return count == null ? 0L : count;
    }

    private LambdaQueryWrapper<PostEntity> adminPostFilter(AdminPostQuery query) {
        LambdaQueryWrapper<PostEntity> wrapper = Wrappers.<PostEntity>lambdaQuery()
                .in(query.statuses() != null && !query.statuses().isEmpty(), PostEntity::getStatus, query.statuses());
        String keyword = query.keyword() == null ? "" : query.keyword().trim();
        if (!keyword.isEmpty()) {
            // The queue searches by post and author id as well as by title, so ids are matched as text.
            wrapper.and(match -> match.like(PostEntity::getTitle, keyword)
                    .or().apply("CAST(id AS CHAR) LIKE CONCAT('%', {0}, '%')", keyword)
                    .or().apply("CAST(user_id AS CHAR) LIKE CONCAT('%', {0}, '%')", keyword));
        }
        return wrapper;
    }


    @Override
    public List<CommentEntity> pageAdminComments(AdminCommentQuery query) {
        if (query.limit() <= 0) return List.of();
        return commentMapper.selectList(adminCommentFilter(query)
                .lt(query.beforeId() != null, CommentEntity::getId, query.beforeId())
                .orderByDesc(CommentEntity::getId)
                .last("LIMIT " + query.limit()));
    }

    @Override
    public long countAdminComments(AdminCommentQuery query) {
        Long count = commentMapper.selectCount(adminCommentFilter(query));
        return count == null ? 0L : count;
    }

    private LambdaQueryWrapper<CommentEntity> adminCommentFilter(AdminCommentQuery query) {
        LambdaQueryWrapper<CommentEntity> wrapper = Wrappers.lambdaQuery();
        String keyword = query.keyword() == null ? "" : query.keyword().trim();
        if (!keyword.isEmpty()) {
            // Governance searches by comment, post and author id as well as by text, so ids are matched as text.
            wrapper.and(match -> match.like(CommentEntity::getContent, keyword)
                    .or().apply("CAST(id AS CHAR) LIKE CONCAT('%', {0}, '%')", keyword)
                    .or().apply("CAST(post_id AS CHAR) LIKE CONCAT('%', {0}, '%')", keyword)
                    .or().apply("CAST(user_id AS CHAR) LIKE CONCAT('%', {0}, '%')", keyword));
        }
        return wrapper;
    }

    @Override
    public List<PostDraftEntity> pageAdminDrafts(AdminDraftQuery query) {
        if (query.limit() <= 0) return List.of();
        LambdaQueryWrapper<PostDraftEntity> wrapper = adminDraftFilter(query);
        TimeCursor before = query.before();
        if (before != null) {
            wrapper.and(after -> after.lt(PostDraftEntity::getUpdatedAt, before.time())
                    .or(tie -> tie.eq(PostDraftEntity::getUpdatedAt, before.time()).lt(PostDraftEntity::getId, before.id())));
        }
        return draftMapper.selectList(wrapper
                .orderByDesc(PostDraftEntity::getUpdatedAt, PostDraftEntity::getId)
                .last("LIMIT " + query.limit()));
    }

    @Override
    public long countAdminDrafts(AdminDraftQuery query) {
        Long count = draftMapper.selectCount(adminDraftFilter(query));
        return count == null ? 0L : count;
    }

    private LambdaQueryWrapper<PostDraftEntity> adminDraftFilter(AdminDraftQuery query) {
        LambdaQueryWrapper<PostDraftEntity> wrapper = Wrappers.lambdaQuery();
        String keyword = query.keyword() == null ? "" : query.keyword().trim();
        if (!keyword.isEmpty()) {
            wrapper.and(match -> match.like(PostDraftEntity::getTitle, keyword)
                    .or().like(PostDraftEntity::getContent, keyword)
                    .or().apply("CAST(id AS CHAR) LIKE CONCAT('%', {0}, '%')", keyword)
                    .or().apply("CAST(user_id AS CHAR) LIKE CONCAT('%', {0}, '%')", keyword));
        }
        return wrapper;
    }

}
