package com.aiknowledge.community.store;

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
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

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
                .eq(PostEntity::getStatus, "PUBLISHED")
                .orderByDesc(PostEntity::getCreatedAt));
    }

    @Override
    public CommentEntity saveComment(CommentEntity comment) {
        commentMapper.insert(comment);
        return comment;
    }

    @Override
    public List<CommentEntity> listComments(Long postId) {
        return commentMapper.selectList(Wrappers.<CommentEntity>lambdaQuery()
                .eq(postId != null, CommentEntity::getPostId, postId)
                .orderByAsc(CommentEntity::getCreatedAt));
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
    public PostCollectEntity collectPost(PostCollectEntity collect) {
        collectMapper.insert(collect);
        return collect;
    }

    @Override
    public Optional<PostEntity> auditPost(Long postId, String status, String reason) {
        PostEntity post = postMapper.selectById(postId);
        if (post == null) {
            return Optional.empty();
        }
        post.setStatus(status);
        postMapper.updateById(post);
        return Optional.of(post);
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
    public boolean likePost(Long userId, Long postId) {
        Long existing = likeMapper.selectCount(Wrappers.<PostLikeEntity>lambdaQuery()
                .eq(PostLikeEntity::getUserId, userId).eq(PostLikeEntity::getPostId, postId));
        if (existing != null && existing > 0) return false;
        PostLikeEntity like = new PostLikeEntity();
        like.setUserId(userId);
        like.setPostId(postId);
        like.setSource("POST");
        like.setCreatedAt(LocalDateTime.now());
        try {
            likeMapper.insert(like);
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    @Override
    public long countPostLikes(Long postId) {
        Long count = likeMapper.selectCount(Wrappers.<PostLikeEntity>lambdaQuery().eq(PostLikeEntity::getPostId, postId));
        return count == null ? 0 : count;
    }
    @Override public boolean removeComment(Long commentId) { return commentMapper.deleteById(commentId) > 0; }
    @Override public boolean removeDraft(Long draftId) { return draftMapper.deleteById(draftId) > 0; }
}
