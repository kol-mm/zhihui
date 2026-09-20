package com.aiknowledge.user.store;

import com.aiknowledge.common.AppTime;
import com.aiknowledge.common.AuditEntry;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** The action log for the local profile; it ends with the process. */
@Repository
@Profile("!mysql")
public class InMemoryAuditLogStore implements AuditLogStore {
    private final List<StoredEntry> entries = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong();

    @Override
    public synchronized StoredEntry save(AuditEntry entry) {
        StoredEntry stored = new StoredEntry(ids.incrementAndGet(),
                LocalDateTime.ofInstant(entry.occurredAt(), AppTime.storageZone()), entry);
        entries.add(stored);
        return stored;
    }

    @Override
    public synchronized List<StoredEntry> page(Query query) {
        return entries.stream()
                .filter(stored -> AuditLogStore.matches(stored, query))
                .sorted(Comparator.comparingLong(StoredEntry::id).reversed())
                .limit(Math.max(query.limit(), 0))
                .toList();
    }

    @Override
    public synchronized int removeBefore(LocalDateTime cutoff) {
        int before = entries.size();
        entries.removeIf(stored -> stored.createdAt().isBefore(cutoff));
        return before - entries.size();
    }
}
