package com.aiknowledge.community.store;

import com.aiknowledge.common.LocalJsonStore;
import com.aiknowledge.community.entity.CommentEntity;
import com.aiknowledge.community.entity.PostCollectEntity;
import com.aiknowledge.community.entity.PostDraftEntity;
import com.aiknowledge.community.entity.PostEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Repository
@Profile("!mysql")
public class InMemoryCommunityStore implements CommunityStore {
    private final Path storePath = LocalJsonStore.dataFile("community.json");
    private final AtomicLong postIds = new AtomicLong(10);
    private final AtomicLong commentIds = new AtomicLong(100);
    private final AtomicLong draftIds = new AtomicLong(1000);
    private final AtomicLong collectIds = new AtomicLong(2000);
    private final AtomicLong imageIds = new AtomicLong(3000);
    private final AtomicLong likeIds = new AtomicLong(4000);
    private final List<PostEntity> posts = new CopyOnWriteArrayList<>();
    private final List<CommentEntity> comments = new CopyOnWriteArrayList<>();
    private final List<PostDraftEntity> drafts = new CopyOnWriteArrayList<>();
    private final List<PostCollectEntity> collects = new CopyOnWriteArrayList<>();
    private final List<PostImageRecord> images = new CopyOnWriteArrayList<>();
    private final List<PostLikeRecord> likes = new CopyOnWriteArrayList<>();

    public InMemoryCommunityStore() {
        State state = LocalJsonStore.read(storePath, State.class, new State());
        if (state.posts != null && !state.posts.isEmpty()) {
            posts.addAll(state.posts);
            if (state.comments != null) {
                comments.addAll(state.comments);
            }
            if (state.drafts != null) {
                drafts.addAll(state.drafts);
            }
            if (state.collects != null) {
                collects.addAll(state.collects);
            }
            if (state.images != null) images.addAll(state.images);
            if (state.likes != null) likes.addAll(state.likes);
            postIds.set(maxId(posts, 10L));
            commentIds.set(maxId(comments, 100L));
            draftIds.set(maxId(drafts, 1000L));
            collectIds.set(maxId(collects, 2000L));
            imageIds.set(maxRecordId(images.stream().map(PostImageRecord::id).toList(), 3000L));
            likeIds.set(maxRecordId(likes.stream().map(PostLikeRecord::id).toList(), 4000L));
            return;
        }

        PostEntity sample = new PostEntity();
        sample.setId(1L);
        sample.setUserId(1L);
        sample.setTitle("Welcome to AI Knowledge Community");
        sample.setContent("Local development community feed is ready.");
        sample.setStatus("PUBLISHED");
        sample.setCreatedAt(LocalDateTime.now());
        sample.setUpdatedAt(LocalDateTime.now());
        posts.add(sample);
        persist();
    }

    @Override
    public PostEntity savePost(PostEntity post) {
        post.setId(postIds.incrementAndGet());
        post.setCreatedAt(LocalDateTime.now());
        post.setUpdatedAt(LocalDateTime.now());
        posts.add(post);
        persist();
        return post;
    }

    @Override
    public PostEntity updatePost(PostEntity post) {
        findPost(post.getId()).ifPresent(existing -> {
            existing.setTitle(post.getTitle());
            existing.setContent(post.getContent());
            existing.setStatus(post.getStatus());
            existing.setUpdatedAt(LocalDateTime.now());
        });
        persist();
        return findPost(post.getId()).orElse(post);
    }

    @Override
    public Optional<PostEntity> findPost(Long id) {
        return posts.stream().filter(post -> post.getId().equals(id)).findFirst();
    }

    @Override
    public List<PostEntity> feed(Long authorUserId) {
        return posts.stream()
                .filter(post -> authorUserId == null || post.getUserId().equals(authorUserId))
                .sorted(Comparator.comparing(PostEntity::getCreatedAt)
                        .thenComparing(PostEntity::getId)
                        .reversed())
                .toList();
    }

    @Override
    public CommentEntity saveComment(CommentEntity comment) {
        comment.setId(commentIds.incrementAndGet());
        comment.setCreatedAt(LocalDateTime.now());
        comments.add(comment);
        persist();
        return comment;
    }

    @Override
    public List<CommentEntity> listComments(Long postId) {
        return comments.stream()
                .filter(comment -> postId == null || comment.getPostId().equals(postId))
                .sorted(Comparator.comparing(CommentEntity::getCreatedAt))
                .toList();
    }

    @Override
    public PostDraftEntity saveDraft(PostDraftEntity draft) {
        draft.setId(draftIds.incrementAndGet());
        draft.setUpdatedAt(LocalDateTime.now());
        drafts.add(draft);
        persist();
        return draft;
    }

    @Override
    public PostDraftEntity updateDraft(PostDraftEntity draft) {
        PostDraftEntity existing = findDraft(draft.getId()).orElseThrow();
        existing.setTitle(draft.getTitle());
        existing.setContent(draft.getContent());
        existing.setUpdatedAt(LocalDateTime.now());
        persist();
        return existing;
    }

    @Override
    public Optional<PostDraftEntity> findDraft(Long id) {
        return drafts.stream().filter(draft -> draft.getId().equals(id)).findFirst();
    }

    @Override
    public List<PostDraftEntity> listDrafts(Long userId) {
        return drafts.stream()
                .filter(draft -> userId == null || draft.getUserId().equals(userId))
                .sorted(Comparator.comparing(PostDraftEntity::getUpdatedAt)
                        .thenComparing(PostDraftEntity::getId)
                        .reversed())
                .toList();
    }

    @Override
    public synchronized boolean togglePostCollect(Long userId, Long postId) {
        boolean removed = collects.removeIf(item -> item.getUserId().equals(userId) && item.getPostId().equals(postId));
        if (removed) {
            persist();
            return false;
        }
        PostCollectEntity collect = new PostCollectEntity();
        collect.setId(collectIds.incrementAndGet());
        collect.setUserId(userId);
        collect.setPostId(postId);
        collect.setSource("SQUARE");
        collect.setCreatedAt(LocalDateTime.now());
        collects.add(collect);
        persist();
        return true;
    }

    @Override
    public boolean hasPostCollect(Long userId, Long postId) {
        return userId != null && collects.stream()
                .anyMatch(item -> item.getUserId().equals(userId) && item.getPostId().equals(postId));
    }

    @Override
    public List<PostEntity> listCollectedPosts(Long userId) {
        return collects.stream()
                .filter(item -> item.getUserId().equals(userId))
                .sorted(Comparator.comparing(PostCollectEntity::getCreatedAt).reversed())
                .map(item -> findPost(item.getPostId()).orElse(null))
                .filter(post -> post != null && "PUBLISHED".equals(post.getStatus()))
                .toList();
    }

    @Override
    public Optional<PostEntity> auditPost(Long postId, String status, String reason) {
        Optional<PostEntity> found = findPost(postId);
        found.ifPresent(post -> {
            post.setStatus(status);
            post.setUpdatedAt(LocalDateTime.now());
            persist();
        });
        return found;
    }

    @Override
    public void savePostImages(Long postId, List<String> imageUrls) {
        images.removeIf(item -> item.postId().equals(postId));
        imageUrls.stream().filter(url -> url != null && !url.isBlank()).limit(9)
                .forEach(url -> images.add(new PostImageRecord(imageIds.incrementAndGet(), postId, url, LocalDateTime.now())));
        persist();
    }

    @Override
    public List<String> listPostImages(Long postId) {
        return images.stream().filter(item -> item.postId().equals(postId)).map(PostImageRecord::imageUrl).toList();
    }

    @Override
    public synchronized boolean togglePostLike(Long userId, Long postId) {
        boolean removed = likes.removeIf(item -> item.userId().equals(userId) && item.postId().equals(postId));
        if (removed) {
            persist();
            return false;
        }
        likes.add(new PostLikeRecord(likeIds.incrementAndGet(), userId, postId, LocalDateTime.now()));
        persist();
        return true;
    }

    @Override
    public boolean hasPostLike(Long userId, Long postId) {
        return userId != null && likes.stream().anyMatch(item -> item.userId().equals(userId) && item.postId().equals(postId));
    }

    @Override
    public long countPostLikes(Long postId) {
        return likes.stream().filter(item -> item.postId().equals(postId)).count();
    }

    @Override
    public boolean removeComment(Long commentId) {
        boolean removed = comments.removeIf(item -> item.getId().equals(commentId));
        if (removed) persist();
        return removed;
    }

    @Override
    public boolean removeDraft(Long draftId) {
        boolean removed = drafts.removeIf(item -> item.getId().equals(draftId));
        if (removed) persist();
        return removed;
    }

    private long maxRecordId(List<Long> values, long fallback) {
        return values.stream().filter(java.util.Objects::nonNull).mapToLong(Long::longValue).max().orElse(fallback);
    }

    private long maxId(List<?> items, long fallback) {
        return items.stream()
                .map(item -> {
                    if (item instanceof PostEntity post) {
                        return post.getId();
                    }
                    if (item instanceof CommentEntity comment) {
                        return comment.getId();
                    }
                    if (item instanceof PostDraftEntity draft) {
                        return draft.getId();
                    }
                    if (item instanceof PostCollectEntity collect) {
                        return collect.getId();
                    }
                    return null;
                })
                .filter(id -> id != null)
                .mapToLong(Long::longValue)
                .max()
                .orElse(fallback);
    }

    private void persist() {
        State state = new State();
        state.posts = new ArrayList<>(posts);
        state.comments = new ArrayList<>(comments);
        state.drafts = new ArrayList<>(drafts);
        state.collects = new ArrayList<>(collects);
        state.images = new ArrayList<>(images);
        state.likes = new ArrayList<>(likes);
        LocalJsonStore.write(storePath, state);
    }

    public static class State {
        public List<PostEntity> posts = new ArrayList<>();
        public List<CommentEntity> comments = new ArrayList<>();
        public List<PostDraftEntity> drafts = new ArrayList<>();
        public List<PostCollectEntity> collects = new ArrayList<>();
        public List<PostImageRecord> images = new ArrayList<>();
        public List<PostLikeRecord> likes = new ArrayList<>();
    }
}
