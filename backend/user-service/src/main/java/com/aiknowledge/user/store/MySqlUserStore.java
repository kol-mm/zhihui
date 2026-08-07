package com.aiknowledge.user.store;

import com.aiknowledge.user.entity.UserEntity;
import com.aiknowledge.user.entity.UserFollowEntity;
import com.aiknowledge.user.entity.UserBlockEntity;
import com.aiknowledge.user.entity.UserBehaviorLogEntity;
import com.aiknowledge.user.entity.UserReportEntity;
import com.aiknowledge.user.mapper.UserBlockMapper;
import com.aiknowledge.user.mapper.UserBehaviorLogMapper;
import com.aiknowledge.user.mapper.UserReportMapper;
import com.aiknowledge.user.mapper.UserFollowMapper;
import com.aiknowledge.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("mysql")
public class MySqlUserStore implements UserStore {
    private final UserMapper userMapper;
    private final UserFollowMapper followMapper;
    private final UserBlockMapper blockMapper;
    private final UserBehaviorLogMapper behaviorMapper;
    private final UserReportMapper reportMapper;

    public MySqlUserStore(UserMapper userMapper, UserFollowMapper followMapper,
                          UserBlockMapper blockMapper, UserBehaviorLogMapper behaviorMapper,
                          UserReportMapper reportMapper) {
        this.userMapper = userMapper;
        this.followMapper = followMapper;
        this.blockMapper = blockMapper;
        this.behaviorMapper = behaviorMapper;
        this.reportMapper = reportMapper;
    }

    @Override
    public Optional<UserEntity> findByUsername(String username) {
        return Optional.ofNullable(userMapper.selectOne(
                Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getUsername, username)
        ));
    }

    @Override
    public Optional<UserEntity> findById(Long userId) {
        return Optional.ofNullable(userMapper.selectById(userId));
    }

    @Override
    public List<UserEntity> findByIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return List.of();
        return userMapper.selectBatchIds(userIds);
    }

    @Override
    public UserEntity save(UserEntity user) {
        userMapper.insert(user);
        return user;
    }

    @Override
    public Optional<UserEntity> updateProfile(Long userId, String nickname, String avatarUrl, String signature) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            return Optional.empty();
        }
        user.setNickname(nickname);
        user.setAvatarUrl(avatarUrl);
        user.setSignature(signature);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        return Optional.of(user);
    }

    @Override
    public Optional<UserEntity> updatePassword(Long userId, String passwordHash) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) return Optional.empty();
        user.setPasswordHash(passwordHash);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        return Optional.of(user);
    }

    @Override
    public List<UserEntity> listUsers() {
        return userMapper.selectList(Wrappers.emptyWrapper());
    }

    @Override
    public Optional<UserEntity> updateStatus(Long userId, String status) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            return Optional.empty();
        }
        user.setStatus(status);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        return Optional.of(user);
    }

    @Override
    public Optional<UserEntity> updateGovernance(Long userId, String role, String publishPolicy, boolean messagingEnabled) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) return Optional.empty();
        user.setRole(role);
        user.setPublishPolicy(publishPolicy);
        user.setMessagingEnabled(messagingEnabled);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        return Optional.of(user);
    }

    @Override
    public boolean follow(Long userId, Long targetUserId) {
        UserFollowEntity existing = followMapper.selectOne(Wrappers.<UserFollowEntity>lambdaQuery()
                .eq(UserFollowEntity::getUserId, userId)
                .eq(UserFollowEntity::getTargetUserId, targetUserId));
        if (existing == null) {
            UserFollowEntity follow = new UserFollowEntity();
            follow.setUserId(userId);
            follow.setTargetUserId(targetUserId);
            follow.setCreatedAt(LocalDateTime.now());
            followMapper.insert(follow);
        }
        return true;
    }

    @Override
    public boolean unfollow(Long userId, Long targetUserId) {
        return followMapper.delete(Wrappers.<UserFollowEntity>lambdaQuery()
                .eq(UserFollowEntity::getUserId, userId)
                .eq(UserFollowEntity::getTargetUserId, targetUserId)) > 0;
    }

    @Override
    public List<Long> listFollowTargets(Long userId) {
        return followMapper.selectList(Wrappers.<UserFollowEntity>lambdaQuery()
                        .eq(userId != null, UserFollowEntity::getUserId, userId)
                        .orderByDesc(UserFollowEntity::getCreatedAt))
                .stream()
                .map(UserFollowEntity::getTargetUserId)
                .toList();
    }

    @Override public List<Long> listFollowerIds(Long userId) {
        return followMapper.selectList(Wrappers.<UserFollowEntity>lambdaQuery().eq(UserFollowEntity::getTargetUserId, userId))
                .stream().map(UserFollowEntity::getUserId).toList();
    }
    @Override
    public boolean block(Long userId, Long targetUserId) {
        unfollow(userId, targetUserId);
        UserBlockEntity existing = blockMapper.selectOne(Wrappers.<UserBlockEntity>lambdaQuery()
                .eq(UserBlockEntity::getUserId, userId).eq(UserBlockEntity::getBlockedUserId, targetUserId));
        if (existing != null) return false;
        UserBlockEntity block = new UserBlockEntity();
        block.setUserId(userId); block.setBlockedUserId(targetUserId); block.setCreatedAt(LocalDateTime.now());
        blockMapper.insert(block);
        return true;
    }

    @Override
    public boolean unblock(Long userId, Long targetUserId) {
        return blockMapper.delete(Wrappers.<UserBlockEntity>lambdaQuery()
                .eq(UserBlockEntity::getUserId, userId).eq(UserBlockEntity::getBlockedUserId, targetUserId)) > 0;
    }

    @Override
    public List<Long> listBlockedIds(Long userId) {
        return blockMapper.selectList(Wrappers.<UserBlockEntity>lambdaQuery()
                        .eq(UserBlockEntity::getUserId, userId).orderByDesc(UserBlockEntity::getCreatedAt))
                .stream().map(UserBlockEntity::getBlockedUserId).toList();
    }

    @Override
    public UserReport reportUser(Long reporterId, Long targetUserId, String reason) {
        UserReportEntity report = new UserReportEntity();
        report.setReporterId(reporterId); report.setTargetUserId(targetUserId); report.setReason(reason);
        report.setStatus("PENDING"); report.setCreatedAt(LocalDateTime.now()); report.setUpdatedAt(LocalDateTime.now());
        reportMapper.insert(report);
        return toReport(report);
    }

    @Override
    public List<UserReport> listUserReports() {
        return reportMapper.selectList(Wrappers.<UserReportEntity>lambdaQuery()
                        .orderByDesc(UserReportEntity::getCreatedAt))
                .stream().map(this::toReport).toList();
    }

    @Override
    public Optional<UserReport> resolveUserReport(Long reportId, String status, String result) {
        UserReportEntity report = reportMapper.selectById(reportId);
        if (report == null) return Optional.empty();
        report.setStatus(status); report.setResult(result); report.setUpdatedAt(LocalDateTime.now());
        reportMapper.updateById(report);
        return Optional.of(toReport(report));
    }

    @Override
    public BehaviorRecord recordBehavior(Long userId, String action, String targetType, Long targetId) {
        UserBehaviorLogEntity behavior = new UserBehaviorLogEntity();
        behavior.setUserId(userId); behavior.setBehaviorType(action); behavior.setTargetType(targetType);
        behavior.setTargetId(targetId); behavior.setCreatedAt(LocalDateTime.now());
        behaviorMapper.insert(behavior);
        return new BehaviorRecord(behavior.getId(), userId, action, targetType, targetId, behavior.getCreatedAt());
    }

    @Override
    public List<BehaviorRecord> listBehaviors(Long userId) {
        return behaviorMapper.selectList(Wrappers.<UserBehaviorLogEntity>lambdaQuery()
                        .eq(userId != null, UserBehaviorLogEntity::getUserId, userId)
                        .orderByDesc(UserBehaviorLogEntity::getCreatedAt))
                .stream().map(item -> new BehaviorRecord(item.getId(), item.getUserId(), item.getBehaviorType(),
                        item.getTargetType(), item.getTargetId(), item.getCreatedAt())).toList();
    }

    private UserReport toReport(UserReportEntity report) {
        return new UserReport(report.getId(), report.getReporterId(), report.getTargetUserId(), report.getReason(),
                report.getStatus(), report.getResult(), report.getCreatedAt(), report.getUpdatedAt());
    }
}
