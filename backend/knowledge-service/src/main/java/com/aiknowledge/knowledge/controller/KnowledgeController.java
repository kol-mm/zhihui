package com.aiknowledge.knowledge.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.knowledge.entity.KnowledgeFileEntity;
import com.aiknowledge.knowledge.search.LocalFullTextSearchService;
import com.aiknowledge.knowledge.storage.LocalFileStorageService;
import com.aiknowledge.knowledge.store.KnowledgeStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/knowledge")
public class KnowledgeController {
    private final KnowledgeStore knowledgeStore;
    private final LocalFileStorageService fileStorage;
    private final LocalFullTextSearchService fullTextSearch;

    public KnowledgeController(
            KnowledgeStore knowledgeStore,
            LocalFileStorageService fileStorage,
            LocalFullTextSearchService fullTextSearch
    ) {
        this.knowledgeStore = knowledgeStore;
        this.fileStorage = fileStorage;
        this.fullTextSearch = fullTextSearch;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of("service", "knowledge-service", "time", Instant.now().toString()));
    }

    @GetMapping("/storage/status")
    public ApiResponse<Map<String, Object>> storageStatus() {
        return ApiResponse.ok(fileStorage.status());
    }

    @PostMapping("/storage/upload")
    public ApiResponse<Map<String, Object>> storageUpload(@RequestBody Map<String, Object> request) {
        String filename = String.valueOf(request.getOrDefault("filename", request.getOrDefault("title", "knowledge.txt")));
        String content = String.valueOf(request.getOrDefault("content", ""));
        String fileType = String.valueOf(request.getOrDefault("fileType", "txt"));
        return ApiResponse.ok(fileStorage.saveTextFile(filename, content, fileType));
    }

    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> upload(@RequestBody Map<String, Object> request) {
        KnowledgeFileEntity file = new KnowledgeFileEntity();
        file.setUserId(number(request.get("userId"), 1L));
        file.setCategoryId(number(request.get("categoryId"), null));
        file.setTitle(String.valueOf(request.getOrDefault("title", "Untitled knowledge file")));
        file.setFileUrl(String.valueOf(request.getOrDefault("fileUrl", "")));
        file.setFileType(String.valueOf(request.getOrDefault("fileType", "txt")));
        file.setParseStatus("PENDING");
        file.setAuditStatus("PENDING");
        file.setViews(0);
        file.setDownloads(0);
        KnowledgeFileEntity saved = knowledgeStore.saveFile(file);
        String content = String.valueOf(request.getOrDefault("content", ""));
        if (!content.isBlank()) {
            fullTextSearch.index(saved.getId(), saved.getTitle(), content, saved.getFileUrl());
            saved.setParseStatus("INDEXED");
        }
        return ApiResponse.ok(toView(saved));
    }

    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(knowledgeStore.listFiles().stream().map(this::toView).toList());
    }

    @GetMapping("/search")
    public ApiResponse<List<Map<String, Object>>> search(@RequestParam(name = "keyword", defaultValue = "") String keyword) {
        return ApiResponse.ok(knowledgeStore.searchFiles(keyword).stream().map(this::toView).toList());
    }

    @GetMapping("/search/fulltext")
    public ApiResponse<List<Map<String, Object>>> fullTextSearch(
            @RequestParam(name = "keyword", defaultValue = "") String keyword
    ) {
        return ApiResponse.ok(fullTextSearch.search(keyword));
    }

    @GetMapping("/search/status")
    public ApiResponse<Map<String, Object>> searchStatus() {
        return ApiResponse.ok(fullTextSearch.status());
    }

    @PostMapping("/view")
    public ApiResponse<Map<String, Object>> view(@RequestBody Map<String, Object> request) {
        Long fileId = number(request.get("fileId"), 0L);
        return knowledgeStore.view(fileId)
                .map(file -> ApiResponse.ok(toView(file)))
                .orElseGet(() -> ApiResponse.fail("knowledge file not found"));
    }

    @PostMapping("/download")
    public ApiResponse<Map<String, Object>> download(@RequestBody Map<String, Object> request) {
        Long userId = number(request.get("userId"), 1L);
        Long fileId = number(request.get("fileId"), 0L);
        return knowledgeStore.download(userId, fileId)
                .map(file -> ApiResponse.ok(Map.of(
                        "file", toView(file),
                        "downloadUrl", file.getFileUrl() == null ? "" : file.getFileUrl(),
                        "downloaded", true
                )))
                .orElseGet(() -> ApiResponse.fail("knowledge file not found"));
    }

    @PostMapping("/like")
    public ApiResponse<Map<String, Object>> like(@RequestBody Map<String, Object> request) {
        Long userId = number(request.get("userId"), 1L);
        Long fileId = number(request.get("fileId"), 0L);
        int likes = knowledgeStore.like(userId, fileId);
        return ApiResponse.ok(Map.of("userId", userId, "fileId", fileId, "liked", true, "likes", likes));
    }

    @GetMapping("/ranking")
    public ApiResponse<List<Map<String, Object>>> ranking() {
        return ApiResponse.ok(List.of(
                Map.of("rank", 1, "userId", 1L, "nickname", "Demo User", "uploads", 12, "views", 386, "downloads", 92, "violations", 0),
                Map.of("rank", 2, "userId", 2L, "nickname", "Knowledge Builder", "uploads", 8, "views", 241, "downloads", 56, "violations", 1),
                Map.of("rank", 3, "userId", 3L, "nickname", "AI Learner", "uploads", 5, "views", 180, "downloads", 33, "violations", 0)
        ));
    }

    @PostMapping("/collect")
    public ApiResponse<Map<String, Object>> collect(@RequestBody Map<String, Object> request) {
        Long userId = number(request.get("userId"), 1L);
        Long fileId = number(request.get("fileId"), 0L);
        knowledgeStore.collect(userId, fileId);
        return ApiResponse.ok(Map.of("userId", userId, "fileId", fileId, "collected", true));
    }

    @GetMapping("/collects")
    public ApiResponse<List<Map<String, Object>>> collects(@RequestParam(name = "userId", required = false) Long userId) {
        return ApiResponse.ok(knowledgeStore.listCollects(userId));
    }

    @PostMapping("/report")
    public ApiResponse<Map<String, Object>> report(@RequestBody Map<String, Object> request) {
        Long userId = number(request.get("userId"), 1L);
        Long fileId = number(request.get("fileId"), 0L);
        knowledgeStore.report(userId, fileId, String.valueOf(request.getOrDefault("reason", "")));
        return ApiResponse.ok(Map.of("fileId", fileId, "status", "REPORTED"));
    }

    @GetMapping("/reports")
    public ApiResponse<List<Map<String, Object>>> reports(@RequestParam(name = "userId", required = false) Long userId) {
        return ApiResponse.ok(knowledgeStore.listReports(userId));
    }

    @PostMapping("/forward")
    public ApiResponse<Map<String, Object>> forward(@RequestBody Map<String, Object> request) {
        return ApiResponse.ok(Map.of("fileId", request.getOrDefault("fileId", 0), "forwarded", true));
    }

    @GetMapping("/admin/overview")
    public ApiResponse<Map<String, Object>> adminOverview(@RequestHeader(name = "Authorization", required = false) String authorization) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) {
            return denied;
        }
        List<KnowledgeFileEntity> files = knowledgeStore.listFiles();
        long pendingAudit = files.stream().filter(file -> "PENDING".equals(file.getAuditStatus())).count();
        int totalViews = files.stream().mapToInt(file -> file.getViews() == null ? 0 : file.getViews()).sum();
        int totalDownloads = files.stream().mapToInt(file -> file.getDownloads() == null ? 0 : file.getDownloads()).sum();
        return ApiResponse.ok(Map.of(
                "module", "知识库管理",
                "totalFiles", files.size(),
                "pendingAudit", pendingAudit,
                "totalViews", totalViews,
                "totalDownloads", totalDownloads,
                "reports", knowledgeStore.listReports(null).size(),
                "capabilities", List.of("资源审核", "分类维护", "AI解析状态追踪", "违规举报处理")
        ));
    }

    @GetMapping("/admin/reports")
    public ApiResponse<List<Map<String, Object>>> adminReports(@RequestHeader(name = "Authorization", required = false) String authorization) {
        if (!LocalAuth.isAdmin(authorization)) {
            return ApiResponse.fail("admin authorization is required");
        }
        return ApiResponse.ok(knowledgeStore.listReports(null));
    }

    @PostMapping("/admin/report/resolve")
    public ApiResponse<Map<String, Object>> resolveReport(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        if (!LocalAuth.isAdmin(authorization)) return ApiResponse.fail("admin authorization is required");
        return knowledgeStore.resolveReport(number(request.get("reportId"), 0L),
                        String.valueOf(request.getOrDefault("status", "RESOLVED")),
                        String.valueOf(request.getOrDefault("result", "handled")))
                .map(ApiResponse::ok).orElseGet(() -> ApiResponse.fail("report not found"));
    }

    @PostMapping("/admin/audit")
    public ApiResponse<Map<String, Object>> audit(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) {
            return denied;
        }
        Long fileId = number(request.get("fileId"), 0L);
        String auditStatus = String.valueOf(request.getOrDefault("auditStatus", "APPROVED"));
        String reason = String.valueOf(request.getOrDefault("reason", ""));
        return knowledgeStore.auditFile(fileId, auditStatus, reason)
                .map(file -> ApiResponse.ok(Map.of(
                        "file", toView(file),
                        "reason", reason,
                        "updated", true
                )))
                .orElseGet(() -> ApiResponse.fail("knowledge file not found"));
    }

    private Map<String, Object> toView(KnowledgeFileEntity file) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", file.getId());
        view.put("userId", file.getUserId());
        view.put("categoryId", file.getCategoryId());
        view.put("title", file.getTitle());
        view.put("fileUrl", file.getFileUrl());
        view.put("fileType", file.getFileType());
        view.put("parseStatus", file.getParseStatus());
        view.put("auditStatus", file.getAuditStatus());
        view.put("views", file.getViews());
        view.put("downloads", file.getDownloads());
        view.put("likes", knowledgeStore.likeCount(file.getId()));
        view.put("createdAt", file.getCreatedAt());
        return view;
    }

    private Long number(Object value, Long fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(value.toString());
    }
}
