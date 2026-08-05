package com.aiknowledge.community.store;

import com.aiknowledge.community.entity.CommentEntity;
import com.aiknowledge.community.entity.PostDraftEntity;
import com.aiknowledge.community.entity.PostEntity;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

public interface CommunityStore {
    PostEntity savePost(PostEntity post);

    PostEntity updatePost(PostEntity post);

    Optional<PostEntity> findPost(Long id);

    List<PostEntity> feed(Long authorUserId);

    CommentEntity saveComment(CommentEntity comment);

    List<CommentEntity> listComments(Long postId);

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

    boolean togglePostLike(Long userId, Long postId);

    boolean hasPostLike(Long userId, Long postId);

    long countPostLikes(Long postId);

    boolean removeComment(Long commentId);

    boolean removeDraft(Long draftId);

    record PostImageRecord(Long id, Long postId, String imageUrl, LocalDateTime createdAt) { }
    record PostLikeRecord(Long id, Long userId, Long postId, LocalDateTime createdAt) { }
}
