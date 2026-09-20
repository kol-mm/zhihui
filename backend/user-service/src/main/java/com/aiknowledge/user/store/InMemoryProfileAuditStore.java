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

/** The review queue for the local profile, kept in a JSON file like the other in-memory stores. */
@Repository
@Profile("!mysql")
public class InMemoryProfileAuditStore implements ProfileAuditStore {
    private final Path storePath = LocalJsonStore.dataFile("profile-changes.json");
    private final List<ProfileChange> changes = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong();

    public InMemoryProfileAuditStore() {
        State state = LocalJsonStore.read(storePath, State.class, new State());
        if (state.changes != null) changes.addAll(state.changes);
        changes.stream().mapToLong(ProfileChange::id).max().ifPresent(ids::set);
    }

    @Override
    public synchronized Optional<ProfileChange> findOpen(Long userId) {
        return changes.stream()
                .filter(change -> change.userId().equals(userId) && PENDING.equals(change.status()))
                .findFirst();
    }

    @Override
    public synchronized Optional<ProfileChange> findLatest(Long userId) {
        return changes.stream().filter(change -> change.userId().equals(userId))
                .max(Comparator.comparingLong(ProfileChange::id));
    }

    @Override
    public synchronized Optional<ProfileChange> find(Long changeId) {
        return changes.stream().filter(change -> change.id().equals(changeId)).findFirst();
    }

    @Override
    public synchronized ProfileChange submit(Long userId, String nickname, String signature,
                                             String beforeNickname, String beforeSignature) {
        LocalDateTime now = LocalDateTime.now();
        ProfileChange open = findOpen(userId).orElse(null);
        // The same row is reused, so replacing a proposal does not move the member to the back of the queue.
        ProfileChange change = new ProfileChange(open == null ? ids.incrementAndGet() : open.id(), userId, nickname,
                signature, beforeNickname, beforeSignature, PENDING, null, null,
                open == null ? now : open.createdAt(), now);
        if (open != null) changes.remove(open);
        changes.add(change);
        persist();
        return change;
    }

    @Override
    public synchronized Optional<ProfileChange> resolve(Long changeId, String status, String reason, Long reviewerId) {
        ProfileChange open = find(changeId).filter(change -> PENDING.equals(change.status())).orElse(null);
        if (open == null) return Optional.empty();
        ProfileChange decided = new ProfileChange(open.id(), open.userId(), open.nickname(), open.signature(),
                open.beforeNickname(), open.beforeSignature(), status, reason, reviewerId, open.createdAt(),
                LocalDateTime.now());
        changes.remove(open);
        changes.add(decided);
        persist();
        return Optional.of(decided);
    }

    @Override
    public synchronized List<ProfileChange> listChanges() {
        return List.copyOf(changes);
    }

    private void persist() {
        State state = new State();
        state.changes = new ArrayList<>(changes);
        LocalJsonStore.write(storePath, state);
    }

    public static class State {
        public List<ProfileChange> changes = new ArrayList<>();
    }
}
