package com.aiknowledge.user.store;

import com.aiknowledge.common.LocalJsonStore;
import com.aiknowledge.user.entity.UserEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
@Profile("!mysql")
public class InMemoryUserStore implements UserStore {
    private final Path storePath = LocalJsonStore.dataFile("users.json");
    private final AtomicLong ids = new AtomicLong(1000);
    private final AtomicLong followIds = new AtomicLong(2000);
    private final Map<String, UserEntity> users = new ConcurrentHashMap<>();
    private final List<FollowRecord> follows = new ArrayList<>();

    public InMemoryUserStore(PasswordEncoder passwordEncoder) {
        State state = LocalJsonStore.read(storePath, State.class, new State());
        if (state.users != null && !state.users.isEmpty()) {
            state.users.forEach(user -> users.put(user.getUsername(), user));
            if (state.follows != null) {
                follows.addAll(state.follows);
            }
            ids.set(state.users.stream()
                    .map(UserEntity::getId)
                    .filter(id -> id != null)
                    .mapToLong(Long::longValue)
                    .max()
                    .orElse(1000L));
            followIds.set(follows.stream()
                    .map(FollowRecord::id)
                    .filter(id -> id != null)
                    .mapToLong(Long::longValue)
                    .max()
                    .orElse(2000L));
            return;
        }

        UserEntity demo = new UserEntity();
        demo.setId(1L);
        demo.setUsername("demo");
        demo.setPasswordHash(passwordEncoder.encode("demo"));
        demo.setNickname("Demo User");
        demo.setStatus("ACTIVE");
        demo.setCreatedAt(LocalDateTime.now());
        demo.setUpdatedAt(LocalDateTime.now());
        users.put(demo.getUsername(), demo);
        persist();
    }

    @Override
    public Optional<UserEntity> findByUsername(String username) {
        return Optional.ofNullable(users.get(username));
    }

    @Override
    public UserEntity save(UserEntity user) {
        if (user.getId() == null) {
            user.setId(ids.incrementAndGet());
        }
        if (user.getCreatedAt() == null) {
            user.setCreatedAt(LocalDateTime.now());
        }
        user.setUpdatedAt(LocalDateTime.now());
        users.put(user.getUsername(), user);
        persist();
        return user;
    }

    @Override
    public List<UserEntity> listUsers() {
        return List.copyOf(users.values());
    }

    @Override
    public Optional<UserEntity> updateStatus(Long userId, String status) {
        Optional<UserEntity> found = users.values().stream()
                .filter(user -> user.getId().equals(userId))
                .findFirst();
        found.ifPresent(user -> {
            user.setStatus(status);
            user.setUpdatedAt(LocalDateTime.now());
            persist();
        });
        return found;
    }

    @Override
    public boolean follow(Long userId, Long targetUserId) {
        boolean exists = follows.stream()
                .anyMatch(follow -> follow.userId().equals(userId) && follow.targetUserId().equals(targetUserId));
        if (!exists) {
            follows.add(new FollowRecord(followIds.incrementAndGet(), userId, targetUserId, LocalDateTime.now()));
            persist();
        }
        return true;
    }

    @Override
    public boolean unfollow(Long userId, Long targetUserId) {
        boolean removed = follows.removeIf(follow -> follow.userId().equals(userId) && follow.targetUserId().equals(targetUserId));
        if (removed) {
            persist();
        }
        return removed;
    }

    @Override
    public List<Long> listFollowTargets(Long userId) {
        return follows.stream()
                .filter(follow -> userId == null || follow.userId().equals(userId))
                .map(FollowRecord::targetUserId)
                .toList();
    }

    private void persist() {
        State state = new State();
        state.users = new ArrayList<>(users.values());
        state.follows = new ArrayList<>(follows);
        LocalJsonStore.write(storePath, state);
    }

    public static class State {
        public List<UserEntity> users = new ArrayList<>();
        public List<FollowRecord> follows = new ArrayList<>();
    }

    public record FollowRecord(Long id, Long userId, Long targetUserId, LocalDateTime createdAt) {
    }
}
