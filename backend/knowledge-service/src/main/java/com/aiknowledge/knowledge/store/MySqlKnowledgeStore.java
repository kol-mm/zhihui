package com.aiknowledge.knowledge.store;

import com.aiknowledge.knowledge.entity.KnowledgeCollectEntity;
import com.aiknowledge.knowledge.entity.KnowledgeFileEntity;
import com.aiknowledge.knowledge.entity.KnowledgeLikeEntity;
import com.aiknowledge.knowledge.entity.KnowledgeReportEntity;
import com.aiknowledge.knowledge.entity.KnowledgeDownloadEntity;
import com.aiknowledge.knowledge.entity.KnowledgeForwardEntity;
import com.aiknowledge.knowledge.entity.KnowledgeCategoryEntity;
import com.aiknowledge.knowledge.mapper.KnowledgeCollectMapper;
import com.aiknowledge.knowledge.mapper.KnowledgeFileMapper;
import com.aiknowledge.knowledge.mapper.KnowledgeLikeMapper;
import com.aiknowledge.knowledge.mapper.KnowledgeReportMapper;
import com.aiknowledge.knowledge.mapper.KnowledgeDownloadMapper;
import com.aiknowledge.knowledge.mapper.KnowledgeForwardMapper;
import com.aiknowledge.knowledge.mapper.KnowledgeCategoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
@Profile("mysql")
public class MySqlKnowledgeStore implements KnowledgeStore {
    private final KnowledgeFileMapper fileMapper;
    private final KnowledgeCollectMapper collectMapper;
    private final KnowledgeLikeMapper likeMapper;
    private final KnowledgeReportMapper reportMapper;
    private final KnowledgeDownloadMapper downloadMapper;
    private final KnowledgeForwardMapper forwardMapper;
    private final KnowledgeCategoryMapper categoryMapper;

    public MySqlKnowledgeStore(
            KnowledgeFileMapper fileMapper,
            KnowledgeCollectMapper collectMapper,
            KnowledgeLikeMapper likeMapper,
            KnowledgeReportMapper reportMapper,
            KnowledgeDownloadMapper downloadMapper,
            KnowledgeForwardMapper forwardMapper,
            KnowledgeCategoryMapper categoryMapper
    ) {
        this.fileMapper = fileMapper;
        this.collectMapper = collectMapper;
        this.likeMapper = likeMapper;
        this.reportMapper = reportMapper;
        this.downloadMapper = downloadMapper;
        this.forwardMapper = forwardMapper;
        this.categoryMapper = categoryMapper;
    }

    @Override
    public KnowledgeFileEntity saveFile(KnowledgeFileEntity file) {
        fileMapper.insert(file);
        return file;
    }

    @Override
    public List<KnowledgeFileEntity> listFiles() {
        return fileMapper.selectList(Wrappers.<KnowledgeFileEntity>lambdaQuery()
                .orderByDesc(KnowledgeFileEntity::getCreatedAt));
    }

    @Override
    public List<KnowledgeFileEntity> pageFiles(FileQuery query) {
        return fileMapper.selectList(fileFilter(query)
                .lt(query.beforeId() != null && query.beforeId() > 0, KnowledgeFileEntity::getId, query.beforeId())
                .orderByDesc(KnowledgeFileEntity::getId)
                .last("LIMIT " + Math.max(1, query.limit())));
    }

    @Override
    public long countFiles(FileQuery query) {
        Long count = fileMapper.selectCount(fileFilter(query));
        return count == null ? 0 : count;
    }

    @Override
    public Map<Long, Long> countFilesByCategory(FileQuery query) {
        Map<Long, Long> counts = new LinkedHashMap<>();
        QueryWrapper<KnowledgeFileEntity> wrapper = new QueryWrapper<>();
        wrapper.select("IFNULL(category_id, 0) AS category_id", "COUNT(*) AS file_count");
        if (!query.includeAll()) wrapper.eq("audit_status", "APPROVED");
        if (query.categoryId() != null && query.categoryId() > 0) wrapper.eq("category_id", query.categoryId());
        if (query.fileType() != null && !query.fileType().isBlank()) wrapper.eq("file_type", query.fileType());
        if (query.keyword() != null && !query.keyword().isBlank()) wrapper.like("title", query.keyword());
        wrapper.groupBy("IFNULL(category_id, 0)");
        fileMapper.selectMaps(wrapper).forEach(row ->
                counts.put(((Number) row.get("category_id")).longValue(), ((Number) row.get("file_count")).longValue()));
        return counts;
    }

    private LambdaQueryWrapper<KnowledgeFileEntity> fileFilter(FileQuery query) {
        return Wrappers.<KnowledgeFileEntity>lambdaQuery()
                .eq(!query.includeAll(), KnowledgeFileEntity::getAuditStatus, "APPROVED")
                .eq(query.categoryId() != null && query.categoryId() > 0, KnowledgeFileEntity::getCategoryId, query.categoryId())
                .eq(query.fileType() != null && !query.fileType().isBlank(), KnowledgeFileEntity::getFileType, query.fileType())
                .like(query.keyword() != null && !query.keyword().isBlank(), KnowledgeFileEntity::getTitle, query.keyword());
    }

    @Override
    public Map<Long, Integer> likeCounts(Collection<Long> fileIds) {
        Map<Long, Integer> counts = new HashMap<>();
        if (fileIds == null || fileIds.isEmpty()) return counts;
        likeMapper.selectMaps(new QueryWrapper<KnowledgeLikeEntity>()
                        .select("file_id AS file_id", "COUNT(*) AS like_count")
                        .in("file_id", fileIds)
                        .groupBy("file_id"))
                .forEach(row -> counts.put(((Number) row.get("file_id")).longValue(), ((Number) row.get("like_count")).intValue()));
        return counts;
    }

    @Override
    public Set<Long> likedFileIds(Long userId, Collection<Long> fileIds) {
        if (userId == null || fileIds == null || fileIds.isEmpty()) return Set.of();
        Set<Long> liked = new HashSet<>();
        likeMapper.selectList(Wrappers.<KnowledgeLikeEntity>lambdaQuery()
                        .select(KnowledgeLikeEntity::getFileId)
                        .eq(KnowledgeLikeEntity::getUserId, userId)
                        .in(KnowledgeLikeEntity::getFileId, fileIds))
                .forEach(like -> liked.add(like.getFileId()));
        return liked;
    }

    @Override
    public Set<Long> collectedFileIds(Long userId, Collection<Long> fileIds) {
        if (userId == null || fileIds == null || fileIds.isEmpty()) return Set.of();
        Set<Long> collected = new HashSet<>();
        collectMapper.selectList(Wrappers.<KnowledgeCollectEntity>lambdaQuery()
                        .select(KnowledgeCollectEntity::getFileId)
                        .eq(KnowledgeCollectEntity::getUserId, userId)
                        .in(KnowledgeCollectEntity::getFileId, fileIds))
                .forEach(collect -> collected.add(collect.getFileId()));
        return collected;
    }

    @Override
    public List<KnowledgeFileEntity> searchFiles(String keyword) {
        return fileMapper.selectList(Wrappers.<KnowledgeFileEntity>lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), KnowledgeFileEntity::getTitle, keyword)
                .orderByDesc(KnowledgeFileEntity::getCreatedAt));
    }

    @Override
    public Optional<KnowledgeFileEntity> find(Long fileId) {
        return Optional.ofNullable(fileMapper.selectById(fileId));
    }

    @Override
    public Optional<KnowledgeFileEntity> view(Long fileId) {
        KnowledgeFileEntity file = fileMapper.selectById(fileId);
        if (file == null) {
            return Optional.empty();
        }
        file.setViews((file.getViews() == null ? 0 : file.getViews()) + 1);
        fileMapper.updateById(file);
        return Optional.of(file);
    }

    @Override
    public Optional<KnowledgeFileEntity> download(Long userId, Long fileId) {
        KnowledgeFileEntity file = fileMapper.selectById(fileId);
        if (file == null) {
            return Optional.empty();
        }
        file.setDownloads((file.getDownloads() == null ? 0 : file.getDownloads()) + 1);
        fileMapper.updateById(file);
        KnowledgeDownloadEntity download = new KnowledgeDownloadEntity();
        download.setUserId(userId); download.setFileId(fileId); download.setCreatedAt(LocalDateTime.now());
        downloadMapper.insert(download);
        return Optional.of(file);
    }

    @Override
    public void forward(Long userId, Long fileId) {
        if (fileMapper.selectById(fileId) == null) throw new IllegalArgumentException("knowledge file not found");
        KnowledgeForwardEntity forward = new KnowledgeForwardEntity();
        forward.setUserId(userId); forward.setFileId(fileId); forward.setCreatedAt(LocalDateTime.now());
        forwardMapper.insert(forward);
    }

    @Override
    public List<KnowledgeFileEntity> listUserFiles(Long userId, String activityType) {
        String type = activityType == null ? "UPLOADED" : activityType.toUpperCase(java.util.Locale.ROOT);
        if ("UPLOADED".equals(type)) {
            return fileMapper.selectList(Wrappers.<KnowledgeFileEntity>lambdaQuery()
                    .eq(KnowledgeFileEntity::getUserId, userId).orderByDesc(KnowledgeFileEntity::getCreatedAt));
        }
        List<Long> ids = switch (type) {
            case "COLLECTED" -> collectMapper.selectList(Wrappers.<KnowledgeCollectEntity>lambdaQuery()
                    .eq(KnowledgeCollectEntity::getUserId, userId).orderByDesc(KnowledgeCollectEntity::getCreatedAt))
                    .stream().map(KnowledgeCollectEntity::getFileId).distinct().toList();
            case "LIKED" -> likeMapper.selectList(Wrappers.<KnowledgeLikeEntity>lambdaQuery()
                    .eq(KnowledgeLikeEntity::getUserId, userId).orderByDesc(KnowledgeLikeEntity::getCreatedAt))
                    .stream().map(KnowledgeLikeEntity::getFileId).distinct().toList();
            case "DOWNLOADED" -> downloadMapper.selectList(Wrappers.<KnowledgeDownloadEntity>lambdaQuery()
                    .eq(KnowledgeDownloadEntity::getUserId, userId).orderByDesc(KnowledgeDownloadEntity::getCreatedAt))
                    .stream().map(KnowledgeDownloadEntity::getFileId).distinct().toList();
            case "FORWARDED" -> forwardMapper.selectList(Wrappers.<KnowledgeForwardEntity>lambdaQuery()
                    .eq(KnowledgeForwardEntity::getUserId, userId).orderByDesc(KnowledgeForwardEntity::getCreatedAt))
                    .stream().map(KnowledgeForwardEntity::getFileId).distinct().toList();
            default -> throw new IllegalArgumentException("unsupported activity type");
        };
        if (ids.isEmpty()) return List.of();
        Map<Long, KnowledgeFileEntity> filesById = fileMapper.selectBatchIds(ids).stream()
                .collect(java.util.stream.Collectors.toMap(KnowledgeFileEntity::getId, file -> file));
        return ids.stream().map(filesById::get).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public UserFilePage pageUserFiles(Long userId, String activityType, Long beforeId, int limit) {
        String type = activityType == null ? "UPLOADED" : activityType.toUpperCase(java.util.Locale.ROOT);
        int pageSize = Math.max(1, limit);
        if ("UPLOADED".equals(type)) {
            List<KnowledgeFileEntity> files = fileMapper.selectList(Wrappers.<KnowledgeFileEntity>lambdaQuery()
                    .eq(KnowledgeFileEntity::getUserId, userId)
                    .lt(beforeId != null && beforeId > 0, KnowledgeFileEntity::getId, beforeId)
                    .orderByDesc(KnowledgeFileEntity::getId)
                    .last("LIMIT " + (pageSize + 1)));
            boolean hasMore = files.size() > pageSize;
            if (hasMore) files = files.subList(0, pageSize);
            Long nextCursor = hasMore ? files.get(files.size() - 1).getId() : null;
            return new UserFilePage(files, nextCursor, hasMore);
        }
        List<ActivityRow> rows = activityRows(userId, type, beforeId, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) rows = rows.subList(0, pageSize);
        if (rows.isEmpty()) return new UserFilePage(List.of(), null, false);
        Long nextCursor = hasMore ? rows.get(rows.size() - 1).rowId() : null;
        List<Long> fileIds = rows.stream().map(ActivityRow::fileId).distinct().toList();
        Map<Long, KnowledgeFileEntity> filesById = fileMapper.selectBatchIds(fileIds).stream()
                .collect(java.util.stream.Collectors.toMap(KnowledgeFileEntity::getId, file -> file));
        return new UserFilePage(fileIds.stream().map(filesById::get).filter(java.util.Objects::nonNull).toList(), nextCursor, hasMore);
    }

    @Override
    public long countUserFiles(Long userId, String activityType) {
        String type = activityType == null ? "UPLOADED" : activityType.toUpperCase(java.util.Locale.ROOT);
        if ("UPLOADED".equals(type)) {
            Long count = fileMapper.selectCount(Wrappers.<KnowledgeFileEntity>lambdaQuery()
                    .eq(KnowledgeFileEntity::getUserId, userId));
            return count == null ? 0 : count;
        }
        // Downloads and forwards repeat, so the activity count is the number of distinct files.
        QueryWrapper<?> wrapper = new QueryWrapper<>();
        wrapper.select("COUNT(DISTINCT file_id) AS file_count").eq("user_id", userId);
        List<Object> counts = switch (type) {
            case "COLLECTED" -> collectMapper.selectObjs(castWrapper(wrapper));
            case "LIKED" -> likeMapper.selectObjs(castWrapper(wrapper));
            case "DOWNLOADED" -> downloadMapper.selectObjs(castWrapper(wrapper));
            case "FORWARDED" -> forwardMapper.selectObjs(castWrapper(wrapper));
            default -> throw new IllegalArgumentException("unsupported activity type");
        };
        Object value = counts.isEmpty() ? null : counts.get(0);
        return value instanceof Number number ? number.longValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static <T> QueryWrapper<T> castWrapper(QueryWrapper<?> wrapper) {
        return (QueryWrapper<T>) wrapper;
    }

    private record ActivityRow(Long rowId, Long fileId) { }

    private List<ActivityRow> activityRows(Long userId, String type, Long beforeId, int limit) {
        return switch (type) {
            case "COLLECTED" -> collectMapper.selectList(Wrappers.<KnowledgeCollectEntity>lambdaQuery()
                            .eq(KnowledgeCollectEntity::getUserId, userId)
                            .lt(beforeId != null && beforeId > 0, KnowledgeCollectEntity::getId, beforeId)
                            .orderByDesc(KnowledgeCollectEntity::getId).last("LIMIT " + limit))
                    .stream().map(row -> new ActivityRow(row.getId(), row.getFileId())).toList();
            case "LIKED" -> likeMapper.selectList(Wrappers.<KnowledgeLikeEntity>lambdaQuery()
                            .eq(KnowledgeLikeEntity::getUserId, userId)
                            .lt(beforeId != null && beforeId > 0, KnowledgeLikeEntity::getId, beforeId)
                            .orderByDesc(KnowledgeLikeEntity::getId).last("LIMIT " + limit))
                    .stream().map(row -> new ActivityRow(row.getId(), row.getFileId())).toList();
            case "DOWNLOADED" -> downloadMapper.selectList(Wrappers.<KnowledgeDownloadEntity>lambdaQuery()
                            .eq(KnowledgeDownloadEntity::getUserId, userId)
                            .lt(beforeId != null && beforeId > 0, KnowledgeDownloadEntity::getId, beforeId)
                            .orderByDesc(KnowledgeDownloadEntity::getId).last("LIMIT " + limit))
                    .stream().map(row -> new ActivityRow(row.getId(), row.getFileId())).toList();
            case "FORWARDED" -> forwardMapper.selectList(Wrappers.<KnowledgeForwardEntity>lambdaQuery()
                            .eq(KnowledgeForwardEntity::getUserId, userId)
                            .lt(beforeId != null && beforeId > 0, KnowledgeForwardEntity::getId, beforeId)
                            .orderByDesc(KnowledgeForwardEntity::getId).last("LIMIT " + limit))
                    .stream().map(row -> new ActivityRow(row.getId(), row.getFileId())).toList();
            default -> throw new IllegalArgumentException("unsupported activity type");
        };
    }

    @Override
    @Transactional
    public boolean toggleLike(Long userId, Long fileId) {
        if (fileMapper.selectById(fileId) == null) throw new IllegalArgumentException("knowledge file not found");
        var match = Wrappers.<KnowledgeLikeEntity>lambdaQuery()
                .eq(KnowledgeLikeEntity::getUserId, userId)
                .eq(KnowledgeLikeEntity::getFileId, fileId);
        Long existing = likeMapper.selectCount(match);
        if (existing != null && existing > 0) {
            likeMapper.delete(match);
            return false;
        }
        KnowledgeLikeEntity like = new KnowledgeLikeEntity();
        like.setUserId(userId);
        like.setFileId(fileId);
        like.setCreatedAt(LocalDateTime.now());
        try {
            likeMapper.insert(like);
            return true;
        } catch (DuplicateKeyException ignored) {
            likeMapper.delete(Wrappers.<KnowledgeLikeEntity>lambdaQuery()
                    .eq(KnowledgeLikeEntity::getUserId, userId)
                    .eq(KnowledgeLikeEntity::getFileId, fileId));
            return false;
        }
    }

    @Override
    public boolean hasLike(Long userId, Long fileId) {
        if (userId == null || fileId == null) return false;
        Long count = likeMapper.selectCount(Wrappers.<KnowledgeLikeEntity>lambdaQuery()
                .eq(KnowledgeLikeEntity::getUserId, userId)
                .eq(KnowledgeLikeEntity::getFileId, fileId));
        return count != null && count > 0;
    }

    @Override
    public int likeCount(Long fileId) {
        Long count = likeMapper.selectCount(Wrappers.<KnowledgeLikeEntity>lambdaQuery()
                .eq(KnowledgeLikeEntity::getFileId, fileId));
        return count == null ? 0 : count.intValue();
    }

    @Override
    @Transactional
    public boolean toggleCollect(Long userId, Long fileId) {
        if (fileMapper.selectById(fileId) == null) throw new IllegalArgumentException("knowledge file not found");
        var match = Wrappers.<KnowledgeCollectEntity>lambdaQuery()
                .eq(KnowledgeCollectEntity::getUserId, userId)
                .eq(KnowledgeCollectEntity::getFileId, fileId);
        Long existing = collectMapper.selectCount(match);
        if (existing != null && existing > 0) {
            collectMapper.delete(match);
            return false;
        }
        KnowledgeCollectEntity collect = new KnowledgeCollectEntity();
        collect.setUserId(userId);
        collect.setFileId(fileId);
        collect.setCreatedAt(LocalDateTime.now());
        collectMapper.insert(collect);
        return true;
    }

    @Override
    public boolean hasCollect(Long userId, Long fileId) {
        if (userId == null || fileId == null) return false;
        Long count = collectMapper.selectCount(Wrappers.<KnowledgeCollectEntity>lambdaQuery()
                .eq(KnowledgeCollectEntity::getUserId, userId)
                .eq(KnowledgeCollectEntity::getFileId, fileId));
        return count != null && count > 0;
    }

    @Override
    public List<Map<String, Object>> listCollects(Long userId) {
        return collectMapper.selectList(Wrappers.<KnowledgeCollectEntity>lambdaQuery()
                        .eq(userId != null, KnowledgeCollectEntity::getUserId, userId)
                        .orderByDesc(KnowledgeCollectEntity::getCreatedAt))
                .stream()
                .map(this::collectView)
                .toList();
    }

    @Override
    public void report(Long userId, Long fileId, String reason) {
        KnowledgeReportEntity report = new KnowledgeReportEntity();
        report.setUserId(userId);
        report.setFileId(fileId);
        report.setReason(reason);
        report.setStatus("PENDING");
        report.setCreatedAt(LocalDateTime.now());
        reportMapper.insert(report);
    }

    @Override
    public List<Map<String, Object>> listReports(Long userId) {
        return reportMapper.selectList(Wrappers.<KnowledgeReportEntity>lambdaQuery()
                        .eq(userId != null, KnowledgeReportEntity::getUserId, userId)
                        .orderByDesc(KnowledgeReportEntity::getCreatedAt))
                .stream()
                .map(this::reportView)
                .toList();
    }

    @Override
    public Optional<Map<String, Object>> resolveReport(Long reportId, String status, String result) {
        KnowledgeReportEntity report = reportMapper.selectById(reportId);
        if (report == null) return Optional.empty();
        report.setStatus(status);
        reportMapper.updateById(report);
        Map<String, Object> view = reportView(report);
        view.put("result", result);
        return Optional.of(view);
    }

    @Override
    public Optional<KnowledgeFileEntity> auditFile(Long fileId, String auditStatus, String reason) {
        KnowledgeFileEntity file = fileMapper.selectById(fileId);
        if (file == null) {
            return Optional.empty();
        }
        file.setAuditStatus(auditStatus);
        fileMapper.updateById(file);
        return Optional.of(file);
    }

    @Override
    public Optional<KnowledgeFileEntity> updateFileMetadata(Long fileId, String title, Long categoryId, String auditStatus) {
        KnowledgeFileEntity file = fileMapper.selectById(fileId);
        if (file == null) return Optional.empty();
        file.setTitle(title);
        file.setCategoryId(categoryId);
        file.setAuditStatus(auditStatus);
        fileMapper.updateById(file);
        return Optional.of(file);
    }

    @Override
    @Transactional
    public boolean deleteFile(Long fileId) {
        if (fileMapper.selectById(fileId) == null) return false;
        collectMapper.delete(Wrappers.<KnowledgeCollectEntity>lambdaQuery().eq(KnowledgeCollectEntity::getFileId, fileId));
        likeMapper.delete(Wrappers.<KnowledgeLikeEntity>lambdaQuery().eq(KnowledgeLikeEntity::getFileId, fileId));
        reportMapper.delete(Wrappers.<KnowledgeReportEntity>lambdaQuery().eq(KnowledgeReportEntity::getFileId, fileId));
        downloadMapper.delete(Wrappers.<KnowledgeDownloadEntity>lambdaQuery().eq(KnowledgeDownloadEntity::getFileId, fileId));
        forwardMapper.delete(Wrappers.<KnowledgeForwardEntity>lambdaQuery().eq(KnowledgeForwardEntity::getFileId, fileId));
        return fileMapper.deleteById(fileId) > 0;
    }

    @Override
    public List<KnowledgeCategoryEntity> listCategories() {
        return categoryMapper.selectList(Wrappers.<KnowledgeCategoryEntity>lambdaQuery()
                .orderByAsc(KnowledgeCategoryEntity::getSortNo).orderByAsc(KnowledgeCategoryEntity::getId));
    }

    @Override
    public KnowledgeCategoryEntity saveCategory(KnowledgeCategoryEntity category) {
        if (category.getId() == null) categoryMapper.insert(category); else categoryMapper.updateById(category);
        return category;
    }

    @Override
    public boolean deleteCategory(Long categoryId) {
        return categoryMapper.deleteById(categoryId) > 0;
    }

    private Map<String, Object> collectView(KnowledgeCollectEntity collect) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", collect.getId());
        view.put("userId", collect.getUserId());
        view.put("fileId", collect.getFileId());
        view.put("createdAt", collect.getCreatedAt());
        return view;
    }

    private Map<String, Object> reportView(KnowledgeReportEntity report) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", report.getId());
        view.put("userId", report.getUserId());
        view.put("fileId", report.getFileId());
        view.put("reason", report.getReason());
        view.put("status", report.getStatus());
        view.put("createdAt", report.getCreatedAt());
        return view;
    }
}
