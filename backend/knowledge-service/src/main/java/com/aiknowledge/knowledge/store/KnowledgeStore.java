package com.aiknowledge.knowledge.store;

import com.aiknowledge.knowledge.entity.KnowledgeFileEntity;
import com.aiknowledge.knowledge.entity.KnowledgeCategoryEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface KnowledgeStore {
    KnowledgeFileEntity saveFile(KnowledgeFileEntity file);
    List<KnowledgeFileEntity> listFiles();
    List<KnowledgeFileEntity> searchFiles(String keyword);
    Optional<KnowledgeFileEntity> find(Long fileId);
    Optional<KnowledgeFileEntity> view(Long fileId);
    Optional<KnowledgeFileEntity> download(Long userId, Long fileId);
    void forward(Long userId, Long fileId);
    List<KnowledgeFileEntity> listUserFiles(Long userId, String activityType);
    int like(Long userId, Long fileId);
    int likeCount(Long fileId);
    boolean toggleCollect(Long userId, Long fileId);
    boolean hasCollect(Long userId, Long fileId);
    List<Map<String, Object>> listCollects(Long userId);
    void report(Long userId, Long fileId, String reason);
    List<Map<String, Object>> listReports(Long userId);
    Optional<Map<String, Object>> resolveReport(Long reportId, String status, String result);
    Optional<KnowledgeFileEntity> auditFile(Long fileId, String auditStatus, String reason);
    List<KnowledgeCategoryEntity> listCategories();
    KnowledgeCategoryEntity saveCategory(KnowledgeCategoryEntity category);
    boolean deleteCategory(Long categoryId);
}
