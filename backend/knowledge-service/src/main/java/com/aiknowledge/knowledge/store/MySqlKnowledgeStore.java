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
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    public int like(Long userId, Long fileId) {
        Long existing = likeMapper.selectCount(Wrappers.<KnowledgeLikeEntity>lambdaQuery()
                .eq(KnowledgeLikeEntity::getUserId, userId)
                .eq(KnowledgeLikeEntity::getFileId, fileId));
        if (existing == null || existing == 0) {
            KnowledgeLikeEntity like = new KnowledgeLikeEntity();
            like.setUserId(userId);
            like.setFileId(fileId);
            like.setCreatedAt(LocalDateTime.now());
            likeMapper.insert(like);
        }
        return likeCount(fileId);
    }

    @Override
    public int likeCount(Long fileId) {
        Long count = likeMapper.selectCount(Wrappers.<KnowledgeLikeEntity>lambdaQuery()
                .eq(KnowledgeLikeEntity::getFileId, fileId));
        return count == null ? 0 : count.intValue();
    }

    @Override
    public void collect(Long userId, Long fileId) {
        Long existing = collectMapper.selectCount(Wrappers.<KnowledgeCollectEntity>lambdaQuery()
                .eq(KnowledgeCollectEntity::getUserId, userId)
                .eq(KnowledgeCollectEntity::getFileId, fileId));
        if (existing != null && existing > 0) {
            return;
        }
        KnowledgeCollectEntity collect = new KnowledgeCollectEntity();
        collect.setUserId(userId);
        collect.setFileId(fileId);
        collect.setCreatedAt(LocalDateTime.now());
        collectMapper.insert(collect);
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
