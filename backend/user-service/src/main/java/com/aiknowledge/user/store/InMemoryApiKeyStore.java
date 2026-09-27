package com.aiknowledge.user.store;

import com.aiknowledge.common.LocalJsonStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/** API keys for the local profile, kept in a JSON file like the other in-memory stores. */
@Repository
@Profile("!mysql")
public class InMemoryApiKeyStore implements ApiKeyStore {
    private final Path storePath;
    private final List<ApiKey> keys = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong();

    public InMemoryApiKeyStore() {
        this(LocalJsonStore.dataFile("api-keys.json"));
    }

    InMemoryApiKeyStore(Path storePath) {
        this.storePath = storePath;
        State state = LocalJsonStore.read(storePath, State.class, new State());
        if (state.keys != null) keys.addAll(state.keys);
        keys.stream().mapToLong(ApiKey::id).max().ifPresent(ids::set);
    }

    @Override
    public synchronized ApiKey create(String name, String prefix, String keyHash, List<String> scopes,
                                      Long actingUserId, Long createdBy, LocalDateTime expiresAt) {
        if (keys.stream().anyMatch(key -> key.keyHash().equals(keyHash))) {
            throw new IllegalStateException("an API key with this digest already exists");
        }
        LocalDateTime now = LocalDateTime.now();
        ApiKey key = new ApiKey(ids.incrementAndGet(), name, prefix, keyHash, List.copyOf(scopes), actingUserId,
                createdBy, expiresAt, null, null, null, now, now);
        keys.add(key);
        persist();
        return key;
    }

    @Override
    public synchronized Optional<ApiKey> find(Long id) {
        return keys.stream().filter(key -> key.id().equals(id)).findFirst();
    }

    @Override
    public synchronized Optional<ApiKey> findByHash(String keyHash) {
        return keys.stream().filter(key -> key.keyHash().equals(keyHash)).findFirst();
    }

    @Override
    public synchronized List<ApiKey> list() {
        return keys.stream().sorted(Comparator.comparingLong(ApiKey::id).reversed()).toList();
    }

    @Override
    public synchronized Optional<ApiKey> update(Long id, String name, List<String> scopes, Long actingUserId,
                                                LocalDateTime expiresAt) {
        return replaceLive(id, key -> new ApiKey(key.id(), name, key.prefix(), key.keyHash(), List.copyOf(scopes),
                actingUserId, key.createdBy(), expiresAt, key.lastUsedAt(), null, null, key.createdAt(),
                LocalDateTime.now()));
    }

    @Override
    public synchronized Optional<ApiKey> rotate(Long id, String prefix, String keyHash) {
        return replaceLive(id, key -> new ApiKey(key.id(), key.name(), prefix, keyHash, key.scopes(),
                key.actingUserId(), key.createdBy(), key.expiresAt(), key.lastUsedAt(), null, null, key.createdAt(),
                LocalDateTime.now()));
    }

    @Override
    public synchronized Optional<ApiKey> revoke(Long id, Long revokedBy) {
        LocalDateTime now = LocalDateTime.now();
        return replaceLive(id, key -> new ApiKey(key.id(), key.name(), key.prefix(), key.keyHash(), key.scopes(),
                key.actingUserId(), key.createdBy(), key.expiresAt(), key.lastUsedAt(), now, revokedBy,
                key.createdAt(), now));
    }

    @Override
    public synchronized void touch(Long id, LocalDateTime usedAt) {
        find(id).filter(key -> key.lastUsedAt() == null || key.lastUsedAt().isBefore(usedAt.minusMinutes(1)))
                .ifPresent(key -> {
                    keys.set(keys.indexOf(key), new ApiKey(key.id(), key.name(), key.prefix(), key.keyHash(),
                            key.scopes(), key.actingUserId(), key.createdBy(), key.expiresAt(), usedAt,
                            key.revokedAt(), key.revokedBy(), key.createdAt(), key.updatedAt()));
                    persist();
                });
    }

    /** Applies a change to a key that is still live; a revoked key is left exactly as it was. */
    private Optional<ApiKey> replaceLive(Long id, UnaryOperator<ApiKey> change) {
        ApiKey current = find(id).orElse(null);
        if (current == null || current.revoked()) return Optional.empty();
        ApiKey next = change.apply(current);
        keys.set(keys.indexOf(current), next);
        persist();
        return Optional.of(next);
    }

    private void persist() {
        State state = new State();
        state.keys = new ArrayList<>(keys);
        LocalJsonStore.write(storePath, state);
    }

    public static class State {
        public List<ApiKey> keys = new ArrayList<>();
    }
}
