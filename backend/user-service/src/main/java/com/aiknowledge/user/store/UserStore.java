package com.aiknowledge.user.store;

import com.aiknowledge.user.entity.UserEntity;

import java.util.List;
import java.time.LocalDateTime;
import java.util.Optional;

public interface UserStore {
    Optional<UserEntity> findByUsername(String username);

    Optional<UserEntity> findById(Long userId);

    List<UserEntity> findByIds(List<Long> userIds);

    UserEntity save(UserEntity user);

    Optional<UserEntity> updateProfile(Long userId, String nickname, String avatarUrl, String signature);

    Optional<UserEntity> updatePassword(Long userId, String passwordHash);

    List<UserEntity> listUsers();

    Optional<UserEntity> updateStatus(Long userId, String status);

    boolean follow(Long userId, Long targetUserId);

    boolean unfollow(Long userId, Long targetUserId);

    List<Long> listFollowTargets(Long userId);

    List<Long> listFollowerIds(Long userId);

    boolean block(Long userId, Long targetUserId);

    boolean unblock(Long userId, Long targetUserId);

    List<Long> listBlockedIds(Long userId);

    UserReport reportUser(Long reporterId, Long targetUserId, String reason);

    List<UserReport> listUserReports();

    Optional<UserReport> resolveUserReport(Long reportId, String status, String result);

    BehaviorRecord recordBehavior(Long userId, String action, String targetType, Long targetId);

    List<BehaviorRecord> listBehaviors(Long userId);

    record UserReport(Long id, Long reporterId, Long targetUserId, String reason, String status,
                      String result, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    record BehaviorRecord(Long id, Long userId, String action, String targetType, Long targetId,
                          LocalDateTime createdAt) {
    }
}
