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
    private final AtomicLong relationIds = new AtomicLong(3000);
    private final AtomicLong reportIds = new AtomicLong(4000);
    private final AtomicLong behaviorIds = new AtomicLong(5000);
    private final Map<String, UserEntity> users = new ConcurrentHashMap<>();
    private final List<FollowRecord> follows = new ArrayList<>();
    private final List<BlockRecord> blocks = new ArrayList<>();
    private final List<UserReport> reports = new ArrayList<>();
    private final List<BehaviorRecord> behaviors = new ArrayList<>();

    public InMemoryUserStore(PasswordEncoder passwordEncoder) {
        State state = LocalJsonStore.read(storePath, State.class, new State());
        if (state.users != null && !state.users.isEmpty()) {
            state.users.forEach(user -> users.put(user.getUsername(), user));
            if (state.follows != null) {
                follows.addAll(state.follows);
            }
            if (state.blocks != null) blocks.addAll(state.blocks);
            if (state.reports != null) reports.addAll(state.reports);
            if (state.behaviors != null) behaviors.addAll(state.behaviors);
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
            relationIds.set(maxId(blocks.stream().map(BlockRecord::id).toList(), 3000L));
            reportIds.set(maxId(reports.stream().map(UserReport::id).toList(), 4000L));
            behaviorIds.set(maxId(behaviors.stream().map(BehaviorRecord::id).toList(), 5000L));
            ensureDefaultUsers(passwordEncoder);
            return;
        }

        ensureDefaultUsers(passwordEncoder);
        persist();
    }

    private void ensureDefaultUsers(PasswordEncoder passwordEncoder) {
        if (!users.containsKey("demo")) {
            UserEntity demo = new UserEntity();
            demo.setId(1L);
            demo.setUsername("demo");
            demo.setPasswordHash(passwordEncoder.encode("demo"));
            demo.setNickname("Demo User");
            demo.setStatus("ACTIVE");
            demo.setCreatedAt(LocalDateTime.now());
            demo.setUpdatedAt(LocalDateTime.now());
            users.put(demo.getUsername(), demo);
        }
        if (!users.containsKey("admin")) {
            UserEntity admin = new UserEntity();
            admin.setId(2L);
            admin.setUsername("admin");
            admin.setPasswordHash(passwordEncoder.encode("admin123"));
            admin.setNickname("Local Admin");
            admin.setStatus("ACTIVE");
            admin.setCreatedAt(LocalDateTime.now());
            admin.setUpdatedAt(LocalDateTime.now());
            users.put(admin.getUsername(), admin);
        }
        ids.set(users.values().stream()
                .map(UserEntity::getId)
                .filter(id -> id != null)
                .mapToLong(Long::longValue)
                .max()
                .orElse(1000L));
    }

    @Override
    public Optional<UserEntity> findByUsername(String username) {
        return Optional.ofNullable(users.get(username));
    }

    @Override
    public Optional<UserEntity> findById(Long userId) {
        return users.values().stream()
                .filter(user -> user.getId().equals(userId))
                .findFirst();
    }

    @Override
    public List<UserEntity> findByIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return List.of();
        java.util.Set<Long> requested = new java.util.LinkedHashSet<>(userIds);
        return users.values().stream().filter(user -> requested.contains(user.getId())).toList();
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
    public Optional<UserEntity> updateProfile(Long userId, String nickname, String avatarUrl, String signature) {
        Optional<UserEntity> found = findById(userId);
        found.ifPresent(user -> {
            user.setNickname(nickname);
            user.setAvatarUrl(avatarUrl);
            user.setSignature(signature);
            user.setUpdatedAt(LocalDateTime.now());
            persist();
        });
        return found;
    }

    @Override
    public Optional<UserEntity> updatePassword(Long userId, String passwordHash) {
        Optional<UserEntity> found = findById(userId);
        found.ifPresent(user -> {
            user.setPasswordHash(passwordHash);
            user.setUpdatedAt(LocalDateTime.now());
            persist();
        });
        return found;
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

    @Override
    public List<Long> listFollowerIds(Long userId) {
        return follows.stream().filter(follow -> follow.targetUserId().equals(userId))
                .map(FollowRecord::userId).toList();
    }

    @Override
    public boolean block(Long userId, Long targetUserId) {
        unfollow(userId, targetUserId);
        if (blocks.stream().noneMatch(item -> item.userId().equals(userId) && item.targetUserId().equals(targetUserId))) {
            blocks.add(new BlockRecord(relationIds.incrementAndGet(), userId, targetUserId, LocalDateTime.now()));
            persist();
        }
        return true;
    }

    @Override
    public boolean unblock(Long userId, Long targetUserId) {
        boolean removed = blocks.removeIf(item -> item.userId().equals(userId) && item.targetUserId().equals(targetUserId));
        if (removed) persist();
        return removed;
    }

    @Override
    public List<Long> listBlockedIds(Long userId) {
        return blocks.stream().filter(item -> item.userId().equals(userId)).map(BlockRecord::targetUserId).toList();
    }

    @Override
    public UserReport reportUser(Long reporterId, Long targetUserId, String reason) {
        LocalDateTime now = LocalDateTime.now();
        UserReport report = new UserReport(reportIds.incrementAndGet(), reporterId, targetUserId, reason,
                "PENDING", "", now, now);
        reports.add(report);
        persist();
        return report;
    }

    @Override
    public List<UserReport> listUserReports() {
        return List.copyOf(reports);
    }

    @Override
    public Optional<UserReport> resolveUserReport(Long reportId, String status, String result) {
        for (int index = 0; index < reports.size(); index++) {
            UserReport current = reports.get(index);
            if (current.id().equals(reportId)) {
                UserReport updated = new UserReport(current.id(), current.reporterId(), current.targetUserId(),
                        current.reason(), status, result, current.createdAt(), LocalDateTime.now());
                reports.set(index, updated);
                persist();
                return Optional.of(updated);
            }
        }
        return Optional.empty();
    }

    @Override
    public BehaviorRecord recordBehavior(Long userId, String action, String targetType, Long targetId) {
        BehaviorRecord record = new BehaviorRecord(behaviorIds.incrementAndGet(), userId, action, targetType,
                targetId, LocalDateTime.now());
        behaviors.add(record);
        persist();
        return record;
    }

    @Override
    public List<BehaviorRecord> listBehaviors(Long userId) {
        return behaviors.stream().filter(item -> item.userId().equals(userId)).toList();
    }

    private long maxId(List<Long> values, long fallback) {
        return values.stream().filter(java.util.Objects::nonNull).mapToLong(Long::longValue).max().orElse(fallback);
    }

    private void persist() {
        State state = new State();
        state.users = new ArrayList<>(users.values());
        state.follows = new ArrayList<>(follows);
        state.blocks = new ArrayList<>(blocks);
        state.reports = new ArrayList<>(reports);
        state.behaviors = new ArrayList<>(behaviors);
        LocalJsonStore.write(storePath, state);
    }

    public static class State {
        public List<UserEntity> users = new ArrayList<>();
        public List<FollowRecord> follows = new ArrayList<>();
        public List<BlockRecord> blocks = new ArrayList<>();
        public List<UserReport> reports = new ArrayList<>();
        public List<BehaviorRecord> behaviors = new ArrayList<>();
    }

    public record FollowRecord(Long id, Long userId, Long targetUserId, LocalDateTime createdAt) {
    }

    public record BlockRecord(Long id, Long userId, Long targetUserId, LocalDateTime createdAt) {
    }
}
