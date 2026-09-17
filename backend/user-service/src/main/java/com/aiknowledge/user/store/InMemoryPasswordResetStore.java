package com.aiknowledge.user.store;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Reset requests for the local profile; they are not written to disk and end with the process. */
@Repository
@Profile("!mysql")
public class InMemoryPasswordResetStore implements PasswordResetStore {
    private final Map<Long, ResetRequest> requests = new LinkedHashMap<>();
    private long nextId = 1;

    @Override
    public synchronized Optional<ResetRequest> findOpen(Long userId) {
        return requests.values().stream()
                .filter(request -> request.userId().equals(userId) && request.open())
                .max(Comparator.comparing(ResetRequest::id));
    }

    @Override
    public synchronized Optional<ResetRequest> find(Long id) {
        return Optional.ofNullable(requests.get(id));
    }

    @Override
    public synchronized ResetRequest create(Long userId, String username, String contact) {
        LocalDateTime now = LocalDateTime.now();
        ResetRequest request = new ResetRequest(nextId++, userId, username, contact, PENDING, null, null, 0,
                null, null, now, now);
        requests.put(request.id(), request);
        return request;
    }

    @Override
    public synchronized Optional<ResetRequest> refresh(Long id, String contact) {
        return replace(id, request -> request.open(), request -> new ResetRequest(request.id(), request.userId(),
                request.username(), contact, request.status(), request.codeHash(), request.codeExpiresAt(),
                request.failedAttempts(), request.handledBy(), request.note(), request.createdAt(), LocalDateTime.now()));
    }

    @Override
    public synchronized Optional<ResetRequest> issue(Long id, String codeHash, LocalDateTime expiresAt, Long adminId) {
        return replace(id, request -> !COMPLETED.equals(request.status()) && !CLOSED.equals(request.status()),
                request -> new ResetRequest(request.id(), request.userId(), request.username(), request.contact(),
                        ISSUED, codeHash, expiresAt, 0, adminId, request.note(), request.createdAt(), LocalDateTime.now()));
    }

    @Override
    public synchronized Optional<ResetRequest> recordFailure(Long id, int maxAttempts) {
        return replace(id, request -> ISSUED.equals(request.status()), request -> {
            int attempts = request.failedAttempts() + 1;
            return new ResetRequest(request.id(), request.userId(), request.username(), request.contact(),
                    attempts >= maxAttempts ? EXPIRED : ISSUED, request.codeHash(), request.codeExpiresAt(), attempts,
                    request.handledBy(), request.note(), request.createdAt(), LocalDateTime.now());
        });
    }

    @Override
    public synchronized boolean complete(Long id, String codeHash) {
        return replace(id, request -> ISSUED.equals(request.status()) && Objects.equals(codeHash, request.codeHash()),
                request -> new ResetRequest(request.id(), request.userId(), request.username(), request.contact(),
                        COMPLETED, request.codeHash(), request.codeExpiresAt(), request.failedAttempts(),
                        request.handledBy(), request.note(), request.createdAt(), LocalDateTime.now()))
                .isPresent();
    }

    @Override
    public synchronized Optional<ResetRequest> close(Long id, Long adminId, String note) {
        return replace(id, request -> !COMPLETED.equals(request.status()) && !CLOSED.equals(request.status()),
                request -> new ResetRequest(request.id(), request.userId(), request.username(), request.contact(),
                        CLOSED, request.codeHash(), request.codeExpiresAt(), request.failedAttempts(), adminId, note,
                        request.createdAt(), LocalDateTime.now()));
    }

    @Override
    public synchronized List<ResetRequest> page(String status, Long beforeId, int limit) {
        return requests.values().stream()
                .filter(request -> status == null || status.isBlank() || status.equals(request.status()))
                .filter(request -> beforeId == null || request.id() < beforeId)
                .sorted(Comparator.comparing(ResetRequest::id).reversed())
                .limit(Math.max(limit, 0))
                .toList();
    }

    @Override
    public synchronized long count(String status) {
        return requests.values().stream()
                .filter(request -> status == null || status.isBlank() || status.equals(request.status()))
                .count();
    }

    private Optional<ResetRequest> replace(Long id, java.util.function.Predicate<ResetRequest> allowed,
                                           java.util.function.UnaryOperator<ResetRequest> change) {
        ResetRequest current = requests.get(id);
        if (current == null || !allowed.test(current)) return Optional.empty();
        ResetRequest updated = change.apply(current);
        requests.put(id, updated);
        return Optional.of(updated);
    }
}
