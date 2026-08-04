package com.aiknowledge.user.store;

import com.aiknowledge.user.entity.UserEntity;
import com.aiknowledge.user.entity.UserFollowEntity;
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
    private final InMemoryUserStore localRelations;

    public MySqlUserStore(UserMapper userMapper, UserFollowMapper followMapper,
                          org.springframework.security.crypto.password.PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.followMapper = followMapper;
        this.localRelations = new InMemoryUserStore(passwordEncoder);
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
    @Override public boolean block(Long userId, Long targetUserId) { unfollow(userId, targetUserId); return localRelations.block(userId, targetUserId); }
    @Override public boolean unblock(Long userId, Long targetUserId) { return localRelations.unblock(userId, targetUserId); }
    @Override public List<Long> listBlockedIds(Long userId) { return localRelations.listBlockedIds(userId); }
    @Override public UserReport reportUser(Long reporterId, Long targetUserId, String reason) { return localRelations.reportUser(reporterId, targetUserId, reason); }
    @Override public List<UserReport> listUserReports() { return localRelations.listUserReports(); }
    @Override public Optional<UserReport> resolveUserReport(Long reportId, String status, String result) { return localRelations.resolveUserReport(reportId, status, result); }
    @Override public BehaviorRecord recordBehavior(Long userId, String action, String targetType, Long targetId) { return localRelations.recordBehavior(userId, action, targetType, targetId); }
    @Override public List<BehaviorRecord> listBehaviors(Long userId) { return localRelations.listBehaviors(userId); }
}
