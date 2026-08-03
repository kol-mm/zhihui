package com.aiknowledge.community.store;

import com.aiknowledge.community.entity.CommentEntity;
import com.aiknowledge.community.entity.PostCollectEntity;
import com.aiknowledge.community.entity.PostDraftEntity;
import com.aiknowledge.community.entity.PostEntity;

import java.util.List;
import java.util.Optional;

public interface CommunityStore {
    PostEntity savePost(PostEntity post);

    PostEntity updatePost(PostEntity post);

    Optional<PostEntity> findPost(Long id);

    List<PostEntity> feed(Long authorUserId);

    CommentEntity saveComment(CommentEntity comment);

    PostDraftEntity saveDraft(PostDraftEntity draft);

    List<PostDraftEntity> listDrafts(Long userId);

    PostCollectEntity collectPost(PostCollectEntity collect);
}
