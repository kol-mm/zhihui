package com.aiknowledge.knowledge.store;

import com.aiknowledge.knowledge.entity.KnowledgeCollectEntity;
import com.aiknowledge.knowledge.entity.KnowledgeFileEntity;
import com.aiknowledge.knowledge.entity.KnowledgeReportEntity;
import com.aiknowledge.knowledge.mapper.KnowledgeCollectMapper;
import com.aiknowledge.knowledge.mapper.KnowledgeFileMapper;
import com.aiknowledge.knowledge.mapper.KnowledgeReportMapper;
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
    private final KnowledgeReportMapper reportMapper;

    public MySqlKnowledgeStore(
            KnowledgeFileMapper fileMapper,
            KnowledgeCollectMapper collectMapper,
            KnowledgeReportMapper reportMapper
    ) {
        this.fileMapper = fileMapper;
        this.collectMapper = collectMapper;
        this.reportMapper = reportMapper;
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
    public Optional<KnowledgeFileEntity> auditFile(Long fileId, String auditStatus, String reason) {
        KnowledgeFileEntity file = fileMapper.selectById(fileId);
        if (file == null) {
            return Optional.empty();
        }
        file.setAuditStatus(auditStatus);
        fileMapper.updateById(file);
        return Optional.of(file);
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
