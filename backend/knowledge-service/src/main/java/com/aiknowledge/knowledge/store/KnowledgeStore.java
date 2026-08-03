package com.aiknowledge.knowledge.store;

import com.aiknowledge.knowledge.entity.KnowledgeFileEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface KnowledgeStore {
    KnowledgeFileEntity saveFile(KnowledgeFileEntity file);
    List<KnowledgeFileEntity> listFiles();
    List<KnowledgeFileEntity> searchFiles(String keyword);
    void collect(Long userId, Long fileId);
    List<Map<String, Object>> listCollects(Long userId);
    void report(Long userId, Long fileId, String reason);
    List<Map<String, Object>> listReports(Long userId);
    Optional<KnowledgeFileEntity> auditFile(Long fileId, String auditStatus, String reason);
}
