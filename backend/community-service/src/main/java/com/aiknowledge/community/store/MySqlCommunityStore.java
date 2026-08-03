package com.aiknowledge.community.store;

import com.aiknowledge.community.entity.CommentEntity;
import com.aiknowledge.community.entity.PostCollectEntity;
import com.aiknowledge.community.entity.PostDraftEntity;
import com.aiknowledge.community.entity.PostEntity;
import com.aiknowledge.community.mapper.CommentMapper;
import com.aiknowledge.community.mapper.PostCollectMapper;
import com.aiknowledge.community.mapper.PostDraftMapper;
import com.aiknowledge.community.mapper.PostMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@Profile("mysql")
public class MySqlCommunityStore implements CommunityStore {
    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final PostDraftMapper draftMapper;
    private final PostCollectMapper collectMapper;

    public MySqlCommunityStore(
            PostMapper postMapper,
            CommentMapper commentMapper,
            PostDraftMapper draftMapper,
            PostCollectMapper collectMapper
    ) {
        this.postMapper = postMapper;
        this.commentMapper = commentMapper;
        this.draftMapper = draftMapper;
        this.collectMapper = collectMapper;
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
}
