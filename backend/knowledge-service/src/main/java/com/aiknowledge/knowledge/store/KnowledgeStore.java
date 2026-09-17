package com.aiknowledge.knowledge.store;

import com.aiknowledge.common.AppTime;
import com.aiknowledge.knowledge.entity.KnowledgeFileEntity;
import com.aiknowledge.knowledge.entity.KnowledgeCategoryEntity;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;

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

    /** Totals behind the admin analytics page: approved files created since the cutoff. */
    record ContentAnalytics(long files, long views, long downloads, long likes) { }

    /** One day of a trend series, keyed by ISO date. */
    record DailyCount(String date, long count) { }

    /**
     * Aggregates approved files created since {@code since}. MySQL answers this with grouped
     * queries; the in-memory profile folds the same numbers over the file list.
     */
    default ContentAnalytics analytics(LocalDateTime since) {
        List<KnowledgeFileEntity> files = analyticsFiles(since);
        Map<Long, Integer> likes = likeCounts(files.stream().map(KnowledgeFileEntity::getId).toList());
        return new ContentAnalytics(
                files.size(),
                files.stream().mapToLong(file -> file.getViews() == null ? 0L : file.getViews()).sum(),
                files.stream().mapToLong(file -> file.getDownloads() == null ? 0L : file.getDownloads()).sum(),
                likes.values().stream().mapToLong(Integer::longValue).sum());
    }

    /** New approved files per day; days without uploads are left out and filled in by the caller. */
    default List<DailyCount> dailyFileCounts(LocalDateTime since) {
        Map<String, Long> perDay = new LinkedHashMap<>();
        for (KnowledgeFileEntity file : analyticsFiles(since)) {
            if (file.getCreatedAt() == null) continue;
            perDay.merge(AppTime.businessDate(file.getCreatedAt()).toString(), 1L, Long::sum);
        }
        return perDay.entrySet().stream().map(entry -> new DailyCount(entry.getKey(), entry.getValue())).toList();
    }

    /** The most engaging approved files, ranked the way the analytics page ranks them. */
    default List<KnowledgeFileEntity> topFiles(int limit) {
        if (limit <= 0) return List.of();
        List<KnowledgeFileEntity> approved = listFiles().stream()
                .filter(file -> "APPROVED".equals(file.getAuditStatus()))
                .toList();
        Map<Long, Integer> likes = likeCounts(approved.stream().map(KnowledgeFileEntity::getId).toList());
        return approved.stream()
                .sorted(Comparator.comparingLong(
                        (KnowledgeFileEntity file) -> engagementScore(file, likes.getOrDefault(file.getId(), 0))).reversed())
                .limit(limit)
                .toList();
    }

    private List<KnowledgeFileEntity> analyticsFiles(LocalDateTime since) {
        return listFiles().stream()
                .filter(file -> "APPROVED".equals(file.getAuditStatus()))
                .filter(file -> file.getCreatedAt() == null || !file.getCreatedAt().isBefore(since))
                .toList();
    }

    /** Views plus double weight for downloads and likes, matching the ranking the page shows. */
    static long engagementScore(KnowledgeFileEntity file, int likes) {
        long views = file.getViews() == null ? 0L : file.getViews();
        long downloads = file.getDownloads() == null ? 0L : file.getDownloads();
        return views + downloads * 2L + likes * 2L;
    }


    /** Counters behind the knowledge admin overview, across every file whatever its audit state. */
    record FileTotals(long files, long pendingAudit, long views, long downloads) { }

    /** MySQL answers this with one grouped query; the in-memory profile folds the file list. */
    default FileTotals fileTotals() {
        List<KnowledgeFileEntity> files = listFiles();
        return new FileTotals(
                files.size(),
                files.stream().filter(file -> "PENDING".equals(file.getAuditStatus())).count(),
                files.stream().mapToLong(file -> file.getViews() == null ? 0L : file.getViews()).sum(),
                files.stream().mapToLong(file -> file.getDownloads() == null ? 0L : file.getDownloads()).sum());
    }

    /** Number of content reports, without reading the reports themselves. */
    default long countReports(Long userId) {
        return listReports(userId).size();
    }


    /**
     * One page of the moderation file table, newest first and across every audit state.
     *
     * @param keyword     matches the id, title or file type (optional)
     * @param auditStatus PENDING, APPROVED, REJECTED or HIDDEN (optional)
     * @param beforeId    cursor: only files with a smaller id (optional)
     */
    record AdminFileQuery(String keyword, String auditStatus, Long beforeId, int limit) { }

    /** MySQL pages this by keyset; the in-memory profile filters the file list the same way. */
    default List<KnowledgeFileEntity> pageAdminFiles(AdminFileQuery query) {
        return matchingAdminFiles(query)
                .filter(file -> query.beforeId() == null || file.getId() < query.beforeId())
                .limit(Math.max(query.limit(), 0))
                .toList();
    }

    /** How many files match the moderation filters, ignoring the cursor. */
    default long countAdminFiles(AdminFileQuery query) {
        return matchingAdminFiles(query).count();
    }

    private java.util.stream.Stream<KnowledgeFileEntity> matchingAdminFiles(AdminFileQuery query) {
        String keyword = query.keyword() == null ? "" : query.keyword().trim().toLowerCase();
        return listFiles().stream()
                .sorted(Comparator.comparing(KnowledgeFileEntity::getId).reversed())
                .filter(file -> query.auditStatus() == null || query.auditStatus().isBlank()
                        || query.auditStatus().equals(file.getAuditStatus()))
                .filter(file -> keyword.isEmpty()
                        || String.valueOf(file.getId()).contains(keyword)
                        || (file.getTitle() != null && file.getTitle().toLowerCase().contains(keyword))
                        || (file.getFileType() != null && file.getFileType().toLowerCase().contains(keyword)));
    }

    /** Reports that still need a decision, for the moderation badge. */
    default long countOpenReports() {
        return listReports(null).stream().filter(report -> !"RESOLVED".equals(report.get("status"))).count();
    }


    /**
     * One page of the moderation report queue, newest first.
     *
     * @param keyword  matches the report id, the reported file id or the reason (optional)
     * @param status   PENDING, PROCESSING or RESOLVED (optional)
     * @param beforeId cursor: only reports with a smaller id (optional)
     */
    record AdminReportQuery(String keyword, String status, Long beforeId, int limit) { }

    default List<Map<String, Object>> pageAdminReports(AdminReportQuery query) {
        return matchingAdminReports(query)
                .filter(report -> query.beforeId() == null || reportId(report) < query.beforeId())
                .limit(Math.max(query.limit(), 0))
                .toList();
    }

    default long countAdminReports(AdminReportQuery query) {
        return matchingAdminReports(query).count();
    }

    private java.util.stream.Stream<Map<String, Object>> matchingAdminReports(AdminReportQuery query) {
        String keyword = query.keyword() == null ? "" : query.keyword().trim().toLowerCase();
        return listReports(null).stream()
                .sorted(Comparator.comparingLong(KnowledgeStore::reportId).reversed())
                .filter(report -> query.status() == null || query.status().isBlank() || query.status().equals(report.get("status")))
                .filter(report -> keyword.isEmpty()
                        || String.valueOf(report.get("id")).contains(keyword)
                        || String.valueOf(report.get("fileId")).contains(keyword)
                        || String.valueOf(report.get("reason")).toLowerCase().contains(keyword));
    }

    private static long reportId(Map<String, Object> report) {
        return report.get("id") instanceof Number number ? number.longValue() : 0L;
    }


    /** The files with these ids that still exist, in no particular order. */
    default List<KnowledgeFileEntity> findFiles(Collection<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) return List.of();
        java.util.Set<Long> wanted = new java.util.HashSet<>(fileIds);
        return listFiles().stream().filter(file -> wanted.contains(file.getId())).toList();
    }

}
