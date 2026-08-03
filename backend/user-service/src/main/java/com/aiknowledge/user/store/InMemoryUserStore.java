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
    private final Map<String, UserEntity> users = new ConcurrentHashMap<>();

    public InMemoryUserStore(PasswordEncoder passwordEncoder) {
        State state = LocalJsonStore.read(storePath, State.class, new State());
        if (state.users != null && !state.users.isEmpty()) {
            state.users.forEach(user -> users.put(user.getUsername(), user));
            ids.set(state.users.stream()
                    .map(UserEntity::getId)
                    .filter(id -> id != null)
                    .mapToLong(Long::longValue)
                    .max()
                    .orElse(1000L));
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

    private void persist() {
        State state = new State();
        state.users = new ArrayList<>(users.values());
        LocalJsonStore.write(storePath, state);
    }

    public static class State {
        public List<UserEntity> users = new ArrayList<>();
    }
}
