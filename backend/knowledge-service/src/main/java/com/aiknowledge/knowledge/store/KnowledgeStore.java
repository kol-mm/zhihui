package com.aiknowledge.knowledge.store;

import com.aiknowledge.knowledge.entity.KnowledgeFileEntity;
import com.aiknowledge.knowledge.entity.KnowledgeCategoryEntity;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface KnowledgeStore {
    KnowledgeFileEntity saveFile(KnowledgeFileEntity file);
    List<KnowledgeFileEntity> listFiles();

    /**
     * Newest-first page of knowledge files matching the filters.
     *
     * @param includeAll true for admins: include files that are not approved yet
     * @param beforeId   cursor: only files with a smaller id (optional)
     */
    record FileQuery(Long categoryId, String fileType, String keyword, boolean includeAll, Long beforeId, int limit) { }

    List<KnowledgeFileEntity> pageFiles(FileQuery query);

    long countFiles(FileQuery query);

    /** File count per category id for the same filters; the key 0 carries files without a category. */
    Map<Long, Long> countFilesByCategory(FileQuery query);

    Map<Long, Integer> likeCounts(Collection<Long> fileIds);

    Set<Long> likedFileIds(Long userId, Collection<Long> fileIds);

    Set<Long> collectedFileIds(Long userId, Collection<Long> fileIds);
    List<KnowledgeFileEntity> searchFiles(String keyword);
    Optional<KnowledgeFileEntity> find(Long fileId);
    Optional<KnowledgeFileEntity> view(Long fileId);
    Optional<KnowledgeFileEntity> download(Long userId, Long fileId);
    void forward(Long userId, Long fileId);
    List<KnowledgeFileEntity> listUserFiles(Long userId, String activityType);

    /**
     * One page of a user's knowledge activity (uploads, collects, likes, downloads, forwards), newest first.
     * The cursor is the last row id of the underlying activity, so repeated files do not shift the page.
     */
    record UserFilePage(List<KnowledgeFileEntity> files, Long nextCursor, boolean hasMore) { }

    UserFilePage pageUserFiles(Long userId, String activityType, Long beforeId, int limit);

    long countUserFiles(Long userId, String activityType);
    boolean toggleLike(Long userId, Long fileId);
    boolean hasLike(Long userId, Long fileId);
    int likeCount(Long fileId);
    boolean toggleCollect(Long userId, Long fileId);
    boolean hasCollect(Long userId, Long fileId);
    List<Map<String, Object>> listCollects(Long userId);
    void report(Long userId, Long fileId, String reason);
    List<Map<String, Object>> listReports(Long userId);
    Optional<Map<String, Object>> resolveReport(Long reportId, String status, String result);
    Optional<KnowledgeFileEntity> auditFile(Long fileId, String auditStatus, String reason);
    Optional<KnowledgeFileEntity> updateFileMetadata(Long fileId, String title, Long categoryId, String auditStatus);
    boolean deleteFile(Long fileId);
    List<KnowledgeCategoryEntity> listCategories();
    KnowledgeCategoryEntity saveCategory(KnowledgeCategoryEntity category);
    boolean deleteCategory(Long categoryId);
}
