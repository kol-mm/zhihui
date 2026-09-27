package com.aiknowledge.user.store;

import com.aiknowledge.user.entity.ApiKeyEntity;
import com.aiknowledge.user.mapper.ApiKeyMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("mysql")
public class MySqlApiKeyStore implements ApiKeyStore {
    private final ApiKeyMapper mapper;

    public MySqlApiKeyStore(ApiKeyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ApiKey create(String name, String prefix, String keyHash, List<String> scopes, Long actingUserId,
                         Long createdBy, LocalDateTime expiresAt) {
        LocalDateTime now = LocalDateTime.now();
        ApiKeyEntity row = new ApiKeyEntity();
        row.setName(name);
        row.setPrefix(prefix);
        row.setKeyHash(keyHash);
        row.setScopes(String.join(",", scopes));
        row.setActingUserId(actingUserId);
        row.setCreatedBy(createdBy);
        row.setExpiresAt(expiresAt);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        mapper.insert(row);
        return toKey(row);
    }

    @Override
    public Optional<ApiKey> find(Long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(MySqlApiKeyStore::toKey);
    }

    @Override
    public Optional<ApiKey> findByHash(String keyHash) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<ApiKeyEntity>lambdaQuery()
                .eq(ApiKeyEntity::getKeyHash, keyHash))).map(MySqlApiKeyStore::toKey);
    }

    @Override
    public List<ApiKey> list() {
        return mapper.selectList(Wrappers.<ApiKeyEntity>lambdaQuery().orderByDesc(ApiKeyEntity::getId))
                .stream().map(MySqlApiKeyStore::toKey).toList();
    }

    @Override
    public Optional<ApiKey> update(Long id, String name, List<String> scopes, Long actingUserId,
                                   LocalDateTime expiresAt) {
        // The revoked_at condition makes a revoked key impossible to edit even when two requests race.
        int changed = mapper.update(null, Wrappers.<ApiKeyEntity>lambdaUpdate()
                .set(ApiKeyEntity::getName, name)
                .set(ApiKeyEntity::getScopes, String.join(",", scopes))
                .set(ApiKeyEntity::getActingUserId, actingUserId)
                .set(ApiKeyEntity::getExpiresAt, expiresAt)
                .set(ApiKeyEntity::getUpdatedAt, LocalDateTime.now())
                .eq(ApiKeyEntity::getId, id)
                .isNull(ApiKeyEntity::getRevokedAt));
        return changed == 0 ? Optional.empty() : find(id);
    }

    @Override
    public Optional<ApiKey> rotate(Long id, String prefix, String keyHash) {
        int changed = mapper.update(null, Wrappers.<ApiKeyEntity>lambdaUpdate()
                .set(ApiKeyEntity::getPrefix, prefix)
                .set(ApiKeyEntity::getKeyHash, keyHash)
                .set(ApiKeyEntity::getUpdatedAt, LocalDateTime.now())
                .eq(ApiKeyEntity::getId, id)
                .isNull(ApiKeyEntity::getRevokedAt));
        return changed == 0 ? Optional.empty() : find(id);
    }

    @Override
    public Optional<ApiKey> revoke(Long id, Long revokedBy) {
        LocalDateTime now = LocalDateTime.now();
        int changed = mapper.update(null, Wrappers.<ApiKeyEntity>lambdaUpdate()
                .set(ApiKeyEntity::getRevokedAt, now)
                .set(ApiKeyEntity::getRevokedBy, revokedBy)
                .set(ApiKeyEntity::getUpdatedAt, now)
                .eq(ApiKeyEntity::getId, id)
                .isNull(ApiKeyEntity::getRevokedAt));
        return changed == 0 ? Optional.empty() : find(id);
    }

    @Override
    public void touch(Long id, LocalDateTime usedAt) {
        // One conditional UPDATE: no read first, and nothing written when it was already marked this minute.
        mapper.update(null, Wrappers.<ApiKeyEntity>lambdaUpdate()
                .set(ApiKeyEntity::getLastUsedAt, usedAt)
                .eq(ApiKeyEntity::getId, id)
                .and(wrapper -> wrapper.isNull(ApiKeyEntity::getLastUsedAt).or()
                        .lt(ApiKeyEntity::getLastUsedAt, usedAt.minusMinutes(1))));
    }

    private static ApiKey toKey(ApiKeyEntity row) {
        List<String> scopes = row.getScopes() == null || row.getScopes().isBlank()
                ? List.of() : Arrays.stream(row.getScopes().split(",")).map(String::trim).toList();
        return new ApiKey(row.getId(), row.getName(), row.getPrefix(), row.getKeyHash(), scopes,
                row.getActingUserId(), row.getCreatedBy(), row.getExpiresAt(), row.getLastUsedAt(),
                row.getRevokedAt(), row.getRevokedBy(), row.getCreatedAt(), row.getUpdatedAt());
    }
}
