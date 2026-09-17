package com.aiknowledge.user.store;

import com.aiknowledge.user.entity.PasswordResetRequestEntity;
import com.aiknowledge.user.mapper.PasswordResetRequestMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Every state change is a conditional UPDATE, so two requests racing on one row cannot both win. */
@Repository
@Profile("mysql")
public class MySqlPasswordResetStore implements PasswordResetStore {
    private static final List<String> UNFINISHED = List.of(PENDING, ISSUED, EXPIRED);

    private final PasswordResetRequestMapper mapper;

    public MySqlPasswordResetStore(PasswordResetRequestMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<ResetRequest> findOpen(Long userId) {
        return mapper.selectList(Wrappers.<PasswordResetRequestEntity>lambdaQuery()
                        .eq(PasswordResetRequestEntity::getUserId, userId)
                        .in(PasswordResetRequestEntity::getStatus, PENDING, ISSUED)
                        .orderByDesc(PasswordResetRequestEntity::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().map(MySqlPasswordResetStore::toRecord);
    }

    @Override
    public Optional<ResetRequest> find(Long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(MySqlPasswordResetStore::toRecord);
    }

    @Override
    public ResetRequest create(Long userId, String username, String contact) {
        PasswordResetRequestEntity entity = new PasswordResetRequestEntity();
        LocalDateTime now = LocalDateTime.now();
        entity.setUserId(userId);
        entity.setUsername(username);
        entity.setContact(contact);
        entity.setStatus(PENDING);
        entity.setFailedAttempts(0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        return toRecord(entity);
    }

    @Override
    public Optional<ResetRequest> refresh(Long id, String contact) {
        int updated = mapper.update(null, Wrappers.<PasswordResetRequestEntity>lambdaUpdate()
                .set(PasswordResetRequestEntity::getContact, contact)
                .set(PasswordResetRequestEntity::getUpdatedAt, LocalDateTime.now())
                .eq(PasswordResetRequestEntity::getId, id)
                .in(PasswordResetRequestEntity::getStatus, PENDING, ISSUED));
        return updated == 0 ? Optional.empty() : find(id);
    }

    @Override
    public Optional<ResetRequest> issue(Long id, String codeHash, LocalDateTime expiresAt, Long adminId) {
        int updated = mapper.update(null, Wrappers.<PasswordResetRequestEntity>lambdaUpdate()
                .set(PasswordResetRequestEntity::getStatus, ISSUED)
                .set(PasswordResetRequestEntity::getCodeHash, codeHash)
                .set(PasswordResetRequestEntity::getCodeExpiresAt, expiresAt)
                .set(PasswordResetRequestEntity::getFailedAttempts, 0)
                .set(PasswordResetRequestEntity::getHandledBy, adminId)
                .set(PasswordResetRequestEntity::getUpdatedAt, LocalDateTime.now())
                .eq(PasswordResetRequestEntity::getId, id)
                .in(PasswordResetRequestEntity::getStatus, UNFINISHED));
        return updated == 0 ? Optional.empty() : find(id);
    }

    @Override
    public Optional<ResetRequest> recordFailure(Long id, int maxAttempts) {
        // status is assigned first: MySQL applies SET clauses left to right, so it still sees the old count.
        int updated = mapper.update(null, Wrappers.<PasswordResetRequestEntity>update()
                .setSql("status = IF(failed_attempts + 1 >= " + Math.max(1, maxAttempts) + ", '" + EXPIRED + "', status)")
                .setSql("failed_attempts = failed_attempts + 1")
                .set("updated_at", LocalDateTime.now())
                .eq("id", id)
                .eq("status", ISSUED));
        return updated == 0 ? Optional.empty() : find(id);
    }

    @Override
    public boolean complete(Long id, String codeHash) {
        return mapper.update(null, Wrappers.<PasswordResetRequestEntity>lambdaUpdate()
                .set(PasswordResetRequestEntity::getStatus, COMPLETED)
                .set(PasswordResetRequestEntity::getUpdatedAt, LocalDateTime.now())
                .eq(PasswordResetRequestEntity::getId, id)
                .eq(PasswordResetRequestEntity::getStatus, ISSUED)
                .eq(PasswordResetRequestEntity::getCodeHash, codeHash)) > 0;
    }

    @Override
    public Optional<ResetRequest> close(Long id, Long adminId, String note) {
        int updated = mapper.update(null, Wrappers.<PasswordResetRequestEntity>lambdaUpdate()
                .set(PasswordResetRequestEntity::getStatus, CLOSED)
                .set(PasswordResetRequestEntity::getHandledBy, adminId)
                .set(PasswordResetRequestEntity::getNote, note)
                .set(PasswordResetRequestEntity::getUpdatedAt, LocalDateTime.now())
                .eq(PasswordResetRequestEntity::getId, id)
                .in(PasswordResetRequestEntity::getStatus, UNFINISHED));
        return updated == 0 ? Optional.empty() : find(id);
    }

    @Override
    public List<ResetRequest> page(String status, Long beforeId, int limit) {
        if (limit <= 0) return List.of();
        return mapper.selectList(Wrappers.<PasswordResetRequestEntity>lambdaQuery()
                        .eq(status != null && !status.isBlank(), PasswordResetRequestEntity::getStatus, status)
                        .lt(beforeId != null, PasswordResetRequestEntity::getId, beforeId)
                        .orderByDesc(PasswordResetRequestEntity::getId)
                        .last("LIMIT " + limit))
                .stream().map(MySqlPasswordResetStore::toRecord).toList();
    }

    @Override
    public long count(String status) {
        Long count = mapper.selectCount(Wrappers.<PasswordResetRequestEntity>lambdaQuery()
                .eq(status != null && !status.isBlank(), PasswordResetRequestEntity::getStatus, status));
        return count == null ? 0L : count;
    }

    private static ResetRequest toRecord(PasswordResetRequestEntity entity) {
        return new ResetRequest(entity.getId(), entity.getUserId(), entity.getUsername(), entity.getContact(),
                entity.getStatus(), entity.getCodeHash(), entity.getCodeExpiresAt(),
                entity.getFailedAttempts() == null ? 0 : entity.getFailedAttempts(), entity.getHandledBy(),
                entity.getNote(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
