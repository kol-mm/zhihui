package com.aiknowledge.knowledge.store;

import com.aiknowledge.common.LocalJsonStore;
import com.aiknowledge.knowledge.entity.KnowledgeFileEntity;
import com.aiknowledge.knowledge.entity.KnowledgeCategoryEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Repository
@Profile("!mysql")
public class InMemoryKnowledgeStore implements KnowledgeStore {
    private final Path storePath = LocalJsonStore.dataFile("knowledge.json");
    private final AtomicLong ids = new AtomicLong(100);
    private final AtomicLong reportIds = new AtomicLong(500);
    private final List<KnowledgeFileEntity> files = new CopyOnWriteArrayList<>();
    private final List<CollectRecord> collects = new CopyOnWriteArrayList<>();
    private final List<LikeRecord> likes = new CopyOnWriteArrayList<>();
    private final List<ReportRecord> reports = new CopyOnWriteArrayList<>();
    private final List<ActivityRecord> downloads = new CopyOnWriteArrayList<>();
    private final List<ActivityRecord> forwards = new CopyOnWriteArrayList<>();
    private final List<KnowledgeCategoryEntity> categories = new CopyOnWriteArrayList<>();
    private final AtomicLong categoryIds = new AtomicLong(0);

    public InMemoryKnowledgeStore() {
        State state = LocalJsonStore.read(storePath, State.class, new State());
        if (state.files != null && !state.files.isEmpty()) {
            files.addAll(state.files);
            if (state.collects != null) {
                collects.addAll(state.collects);
            }
            if (state.likes != null) {
                likes.addAll(state.likes);
            }
            if (state.reports != null) {
                reports.addAll(state.reports);
            }
            if (state.downloads != null) downloads.addAll(state.downloads);
            if (state.forwards != null) forwards.addAll(state.forwards);
            if (state.categories != null) categories.addAll(state.categories);
            categoryIds.set(categories.stream().map(KnowledgeCategoryEntity::getId).filter(java.util.Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L));
            if (categories.isEmpty()) {
                addDefaultCategory("技术文档", 1);
                addDefaultCategory("产品资料", 2);
                addDefaultCategory("社区知识", 3);
                persist();
            }
            reportIds.set(reports.stream().map(ReportRecord::id).filter(java.util.Objects::nonNull)
                    .mapToLong(Long::longValue).max().orElse(500L));
            for (int index = 0; index < reports.size(); index++) {
                ReportRecord report = reports.get(index);
                if (report.id() == null) {
                    LocalDateTime createdAt = report.createdAt() == null ? LocalDateTime.now() : report.createdAt();
                    reports.set(index, new ReportRecord(reportIds.incrementAndGet(), report.userId(), report.fileId(),
                            report.reason(), "PENDING", "", createdAt, createdAt));
                }
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
        addDefaultCategory("技术文档", 1);
        addDefaultCategory("产品资料", 2);
        addDefaultCategory("社区知识", 3);
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
    public Optional<KnowledgeFileEntity> find(Long fileId) {
        return files.stream().filter(file -> file.getId().equals(fileId)).findFirst();
    }

    @Override
    public Optional<KnowledgeFileEntity> view(Long fileId) {
        Optional<KnowledgeFileEntity> found = files.stream()
                .filter(file -> file.getId().equals(fileId))
                .findFirst();
        found.ifPresent(file -> {
            file.setViews((file.getViews() == null ? 0 : file.getViews()) + 1);
            persist();
        });
        return found;
    }

    @Override
    public Optional<KnowledgeFileEntity> download(Long userId, Long fileId) {
        Optional<KnowledgeFileEntity> found = files.stream()
                .filter(file -> file.getId().equals(fileId))
                .findFirst();
        found.ifPresent(file -> {
            file.setDownloads((file.getDownloads() == null ? 0 : file.getDownloads()) + 1);
            downloads.add(new ActivityRecord(userId, fileId, LocalDateTime.now()));
            persist();
        });
        return found;
    }

    @Override
    public void forward(Long userId, Long fileId) {
        if (find(fileId).isEmpty()) throw new IllegalArgumentException("knowledge file not found");
        forwards.add(new ActivityRecord(userId, fileId, LocalDateTime.now()));
        persist();
    }

    @Override
    public List<KnowledgeFileEntity> listUserFiles(Long userId, String activityType) {
        String type = activityType == null ? "UPLOADED" : activityType.toUpperCase(java.util.Locale.ROOT);
        if ("UPLOADED".equals(type)) return files.stream().filter(file -> userId.equals(file.getUserId())).toList();
        java.util.Set<Long> ids = switch (type) {
            case "COLLECTED" -> collects.stream().filter(item -> userId.equals(item.userId())).map(CollectRecord::fileId).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            case "LIKED" -> likes.stream().filter(item -> userId.equals(item.userId())).map(LikeRecord::fileId).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            case "DOWNLOADED" -> downloads.stream().filter(item -> userId.equals(item.userId())).map(ActivityRecord::fileId).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            case "FORWARDED" -> forwards.stream().filter(item -> userId.equals(item.userId())).map(ActivityRecord::fileId).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            default -> throw new IllegalArgumentException("unsupported activity type");
        };
        return files.stream().filter(file -> ids.contains(file.getId())).toList();
    }

    @Override
    public int like(Long userId, Long fileId) {
        boolean exists = likes.stream()
                .anyMatch(record -> record.userId().equals(userId) && record.fileId().equals(fileId));
        if (!exists) {
            likes.add(new LikeRecord(userId, fileId, LocalDateTime.now()));
            persist();
        }
        return likeCount(fileId);
    }

    @Override
    public int likeCount(Long fileId) {
        return (int) likes.stream()
                .filter(record -> record.fileId().equals(fileId))
                .count();
    }

    @Override
    public void collect(Long userId, Long fileId) {
        boolean exists = collects.stream()
                .anyMatch(record -> record.userId().equals(userId) && record.fileId().equals(fileId));
        if (exists) {
            return;
        }
        collects.add(new CollectRecord(userId, fileId, LocalDateTime.now()));
        persist();
    }

    @Override
    public List<Map<String, Object>> listCollects(Long userId) {
        return collects.stream()
                .filter(record -> userId == null || record.userId().equals(userId))
                .map(this::collectView)
                .toList();
    }

    @Override
    public void report(Long userId, Long fileId, String reason) {
        LocalDateTime now = LocalDateTime.now();
        reports.add(new ReportRecord(reportIds.incrementAndGet(), userId, fileId, reason, "PENDING", "", now, now));
        persist();
    }

    @Override
    public List<Map<String, Object>> listReports(Long userId) {
        return reports.stream()
                .filter(record -> userId == null || record.userId().equals(userId))
                .map(this::reportView)
                .toList();
    }

    @Override
    public Optional<Map<String, Object>> resolveReport(Long reportId, String status, String result) {
        for (int index = 0; index < reports.size(); index++) {
            ReportRecord current = reports.get(index);
            if (reportId.equals(current.id())) {
                ReportRecord updated = new ReportRecord(current.id(), current.userId(), current.fileId(), current.reason(),
                        status, result, current.createdAt(), LocalDateTime.now());
                reports.set(index, updated);
                persist();
                return Optional.of(reportView(updated));
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<KnowledgeFileEntity> auditFile(Long fileId, String auditStatus, String reason) {
        Optional<KnowledgeFileEntity> found = files.stream()
                .filter(file -> file.getId().equals(fileId))
                .findFirst();
        found.ifPresent(file -> {
            file.setAuditStatus(auditStatus);
            persist();
        });
        return found;
    }

    @Override
    public List<KnowledgeCategoryEntity> listCategories() {
        return categories.stream().sorted(java.util.Comparator.comparing(KnowledgeCategoryEntity::getSortNo, java.util.Comparator.nullsLast(Integer::compareTo)).thenComparing(KnowledgeCategoryEntity::getId)).toList();
    }

    @Override
    public KnowledgeCategoryEntity saveCategory(KnowledgeCategoryEntity category) {
        if (category.getId() == null) { category.setId(categoryIds.incrementAndGet()); categories.add(category); }
        else { categories.removeIf(item -> category.getId().equals(item.getId())); categories.add(category); }
        persist(); return category;
    }

    @Override
    public boolean deleteCategory(Long categoryId) {
        boolean removed = categories.removeIf(item -> categoryId.equals(item.getId()));
        if (removed) { files.stream().filter(file -> categoryId.equals(file.getCategoryId())).forEach(file -> file.setCategoryId(null)); persist(); }
        return removed;
    }

    private void addDefaultCategory(String name, int sortNo) {
        KnowledgeCategoryEntity category = new KnowledgeCategoryEntity(); category.setId(categoryIds.incrementAndGet()); category.setName(name); category.setParentId(0L); category.setSortNo(sortNo); categories.add(category);
    }

    private void persist() {
        State state = new State();
        state.files = new ArrayList<>(files);
        state.collects = new ArrayList<>(collects);
        state.likes = new ArrayList<>(likes);
        state.reports = new ArrayList<>(reports);
        state.downloads = new ArrayList<>(downloads);
        state.forwards = new ArrayList<>(forwards);
        state.categories = new ArrayList<>(categories);
        LocalJsonStore.write(storePath, state);
    }

    private Map<String, Object> collectView(CollectRecord record) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("userId", record.userId());
        view.put("fileId", record.fileId());
        view.put("createdAt", record.createdAt());
        return view;
    }

    private Map<String, Object> reportView(ReportRecord record) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", record.id());
        view.put("userId", record.userId());
        view.put("fileId", record.fileId());
        view.put("reason", record.reason());
        view.put("status", record.status() == null ? "PENDING" : record.status());
        view.put("result", record.result() == null ? "" : record.result());
        view.put("createdAt", record.createdAt());
        view.put("updatedAt", record.updatedAt() == null ? record.createdAt() : record.updatedAt());
        return view;
    }

    public static class State {
        public List<KnowledgeFileEntity> files = new ArrayList<>();
        public List<CollectRecord> collects = new ArrayList<>();
        public List<LikeRecord> likes = new ArrayList<>();
        public List<ReportRecord> reports = new ArrayList<>();
        public List<ActivityRecord> downloads = new ArrayList<>();
        public List<ActivityRecord> forwards = new ArrayList<>();
        public List<KnowledgeCategoryEntity> categories = new ArrayList<>();
    }

    public record CollectRecord(Long userId, Long fileId, LocalDateTime createdAt) {
    }

    public record LikeRecord(Long userId, Long fileId, LocalDateTime createdAt) {
    }

    public record ActivityRecord(Long userId, Long fileId, LocalDateTime createdAt) {
    }

    public record ReportRecord(Long id, Long userId, Long fileId, String reason, String status, String result,
                               LocalDateTime createdAt, LocalDateTime updatedAt) {
    }
}
