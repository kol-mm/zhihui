package com.aiknowledge.user.store;

import com.aiknowledge.user.entity.UserEntity;

import java.util.List;
import java.util.Optional;

public interface UserStore {
    Optional<UserEntity> findByUsername(String username);

    Optional<UserEntity> findById(Long userId);

    UserEntity save(UserEntity user);

    Optional<UserEntity> updateProfile(Long userId, String nickname, String avatarUrl, String signature);

    List<UserEntity> listUsers();

    Optional<UserEntity> updateStatus(Long userId, String status);

    boolean follow(Long userId, Long targetUserId);

    boolean unfollow(Long userId, Long targetUserId);

    List<Long> listFollowTargets(Long userId);
}
