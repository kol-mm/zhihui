package com.aiknowledge.knowledge.store;

import com.aiknowledge.common.LocalJsonStore;
import com.aiknowledge.knowledge.entity.KnowledgeFileEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Repository
@Profile("!mysql")
public class InMemoryKnowledgeStore implements KnowledgeStore {
    private final Path storePath = LocalJsonStore.dataFile("knowledge.json");
    private final AtomicLong ids = new AtomicLong(100);
    private final List<KnowledgeFileEntity> files = new CopyOnWriteArrayList<>();
    private final List<CollectRecord> collects = new CopyOnWriteArrayList<>();
    private final List<ReportRecord> reports = new CopyOnWriteArrayList<>();

    public InMemoryKnowledgeStore() {
        State state = LocalJsonStore.read(storePath, State.class, new State());
        if (state.files != null && !state.files.isEmpty()) {
            files.addAll(state.files);
            if (state.collects != null) {
                collects.addAll(state.collects);
            }
            if (state.reports != null) {
                reports.addAll(state.reports);
            }
            ids.set(files.stream()
                    .map(KnowledgeFileEntity::getId)
                    .filter(id -> id != null)
                    .mapToLong(Long::longValue)
                    .max()
                    .orElse(100L));
            return;
        }

        KnowledgeFileEntity sample = new KnowledgeFileEntity();
        sample.setId(1L);
        sample.setUserId(1L);
        sample.setTitle("AI Knowledge Platform Design");
        sample.setFileType("docx");
        sample.setParseStatus("READY");
        sample.setAuditStatus("APPROVED");
        sample.setViews(0);
        sample.setDownloads(0);
        sample.setCreatedAt(LocalDateTime.now());
        files.add(sample);
        persist();
    }

    @Override
    public KnowledgeFileEntity saveFile(KnowledgeFileEntity file) {
        file.setId(ids.incrementAndGet());
        file.setCreatedAt(LocalDateTime.now());
        files.add(file);
        persist();
        return file;
    }

    @Override
    public List<KnowledgeFileEntity> listFiles() {
        return List.copyOf(files);
    }

    @Override
    public List<KnowledgeFileEntity> searchFiles(String keyword) {
        return files.stream()
                .filter(file -> keyword == null || keyword.isBlank() || file.getTitle().contains(keyword))
                .toList();
    }

    @Override
    public void collect(Long userId, Long fileId) {
        collects.add(new CollectRecord(userId, fileId, LocalDateTime.now()));
        persist();
    }

    @Override
    public void report(Long userId, Long fileId, String reason) {
        reports.add(new ReportRecord(userId, fileId, reason, LocalDateTime.now()));
        persist();
    }

    private void persist() {
        State state = new State();
        state.files = new ArrayList<>(files);
        state.collects = new ArrayList<>(collects);
        state.reports = new ArrayList<>(reports);
        LocalJsonStore.write(storePath, state);
    }

    public static class State {
        public List<KnowledgeFileEntity> files = new ArrayList<>();
        public List<CollectRecord> collects = new ArrayList<>();
        public List<ReportRecord> reports = new ArrayList<>();
    }

    public record CollectRecord(Long userId, Long fileId, LocalDateTime createdAt) {
    }

    public record ReportRecord(Long userId, Long fileId, String reason, LocalDateTime createdAt) {
    }
}
