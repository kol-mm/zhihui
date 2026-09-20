package com.aiknowledge.user.store;

import com.aiknowledge.user.entity.UserProfileChangeEntity;
import com.aiknowledge.user.mapper.UserProfileChangeMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("mysql")
public class MySqlProfileAuditStore implements ProfileAuditStore {
    private final UserProfileChangeMapper mapper;

    public MySqlProfileAuditStore(UserProfileChangeMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<ProfileChange> findOpen(Long userId) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<UserProfileChangeEntity>lambdaQuery()
                .eq(UserProfileChangeEntity::getUserId, userId)
                .eq(UserProfileChangeEntity::getStatus, PENDING))).map(MySqlProfileAuditStore::toChange);
    }

    @Override
    public Optional<ProfileChange> findLatest(Long userId) {
        return mapper.selectList(Wrappers.<UserProfileChangeEntity>lambdaQuery()
                        .eq(UserProfileChangeEntity::getUserId, userId)
                        .orderByDesc(UserProfileChangeEntity::getId).last("LIMIT 1"))
                .stream().findFirst().map(MySqlProfileAuditStore::toChange);
    }

    @Override
    public Optional<ProfileChange> find(Long changeId) {
        return Optional.ofNullable(mapper.selectById(changeId)).map(MySqlProfileAuditStore::toChange);
    }

    @Override
    public ProfileChange submit(Long userId, String nickname, String signature,
                                String beforeNickname, String beforeSignature) {
        try {
            return write(userId, nickname, signature, beforeNickname, beforeSignature);
        } catch (DuplicateKeyException racing) {
            // uk_profile_change_open: another tab opened a change first, so update that one instead.
            return write(userId, nickname, signature, beforeNickname, beforeSignature);
        }
    }

    private ProfileChange write(Long userId, String nickname, String signature,
                                String beforeNickname, String beforeSignature) {
        UserProfileChangeEntity row = findOpen(userId).map(open -> {
            UserProfileChangeEntity existing = mapper.selectById(open.id());
            return existing == null ? new UserProfileChangeEntity() : existing;
        }).orElseGet(UserProfileChangeEntity::new);
        row.setUserId(userId);
        row.setNickname(nickname);
        row.setSignature(signature);
        row.setBeforeNickname(beforeNickname);
        row.setBeforeSignature(beforeSignature);
        row.setStatus(PENDING);
        row.setReason(null);
        row.setReviewerId(null);
        row.setUpdatedAt(LocalDateTime.now());
        if (row.getId() == null) {
            row.setCreatedAt(LocalDateTime.now());
            mapper.insert(row);
        } else {
            // An explicit update: updateById would skip the nulls that clear an earlier rejection.
            mapper.update(null, Wrappers.<UserProfileChangeEntity>lambdaUpdate()
                    .set(UserProfileChangeEntity::getNickname, nickname)
                    .set(UserProfileChangeEntity::getSignature, signature)
                    .set(UserProfileChangeEntity::getBeforeNickname, beforeNickname)
                    .set(UserProfileChangeEntity::getBeforeSignature, beforeSignature)
                    .set(UserProfileChangeEntity::getReason, null)
                    .set(UserProfileChangeEntity::getReviewerId, null)
                    .set(UserProfileChangeEntity::getUpdatedAt, row.getUpdatedAt())
                    .eq(UserProfileChangeEntity::getId, row.getId()));
        }
        return toChange(mapper.selectById(row.getId()));
    }

    @Override
    public Optional<ProfileChange> resolve(Long changeId, String status, String reason, Long reviewerId) {
        // Only an open change can be decided, and only once: the status in the condition settles a double click.
        int updated = mapper.update(null, Wrappers.<UserProfileChangeEntity>lambdaUpdate()
                .set(UserProfileChangeEntity::getStatus, status)
                .set(UserProfileChangeEntity::getReason, reason)
                .set(UserProfileChangeEntity::getReviewerId, reviewerId)
                .set(UserProfileChangeEntity::getUpdatedAt, LocalDateTime.now())
                .eq(UserProfileChangeEntity::getId, changeId)
                .eq(UserProfileChangeEntity::getStatus, PENDING));
        return updated == 0 ? Optional.empty() : find(changeId);
    }

    @Override
    public List<ProfileChange> listChanges() {
        return mapper.selectList(Wrappers.emptyWrapper()).stream().map(MySqlProfileAuditStore::toChange).toList();
    }

    @Override
    public List<ProfileChange> pageChanges(ChangeQuery query) {
        LambdaQueryWrapper<UserProfileChangeEntity> where = filter(query)
                .lt(query.beforeId() != null, UserProfileChangeEntity::getId, query.beforeId())
                .orderByDesc(UserProfileChangeEntity::getId)
                .last("LIMIT " + Math.max(0, Math.min(query.limit(), 500)));
        return mapper.selectList(where).stream().map(MySqlProfileAuditStore::toChange).toList();
    }

    @Override
    public long countChanges(ChangeQuery query) {
        return mapper.selectCount(filter(query));
    }

    @Override
    public long countPending() {
        return mapper.selectCount(Wrappers.<UserProfileChangeEntity>lambdaQuery()
                .eq(UserProfileChangeEntity::getStatus, PENDING));
    }

    private LambdaQueryWrapper<UserProfileChangeEntity> filter(ChangeQuery query) {
        String keyword = query.keyword() == null || query.keyword().isBlank() ? null : query.keyword().trim();
        String status = query.status() == null || query.status().isBlank() ? null : query.status().trim();
        LambdaQueryWrapper<UserProfileChangeEntity> where = Wrappers.<UserProfileChangeEntity>lambdaQuery()
                .eq(status != null, UserProfileChangeEntity::getStatus, status);
        if (keyword != null) {
            where.and(nested -> nested.like(UserProfileChangeEntity::getNickname, keyword)
                    .or().apply("CAST(id AS CHAR) LIKE CONCAT('%', {0}, '%')", keyword)
                    .or().apply("CAST(user_id AS CHAR) LIKE CONCAT('%', {0}, '%')", keyword));
        }
        return where;
    }

    private static ProfileChange toChange(UserProfileChangeEntity row) {
        return new ProfileChange(row.getId(), row.getUserId(), row.getNickname(), row.getSignature(),
                row.getBeforeNickname(), row.getBeforeSignature(), row.getStatus(), row.getReason(),
                row.getReviewerId(), row.getCreatedAt(), row.getUpdatedAt());
    }
}
