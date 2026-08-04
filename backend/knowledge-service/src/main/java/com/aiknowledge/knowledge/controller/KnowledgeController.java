package com.aiknowledge.knowledge.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.knowledge.entity.KnowledgeFileEntity;
import com.aiknowledge.knowledge.entity.KnowledgeCategoryEntity;
import com.aiknowledge.knowledge.search.LocalFullTextSearchService;
import com.aiknowledge.knowledge.storage.LocalFileStorageService;
import com.aiknowledge.knowledge.storage.DocumentTextExtractor;
import com.aiknowledge.knowledge.store.KnowledgeStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/knowledge")
public class KnowledgeController {
    private final KnowledgeStore knowledgeStore;
    private final LocalFileStorageService fileStorage;
    private final LocalFullTextSearchService fullTextSearch;
    private final DocumentTextExtractor textExtractor;

    public KnowledgeController(
            KnowledgeStore knowledgeStore,
            LocalFileStorageService fileStorage,
            LocalFullTextSearchService fullTextSearch,
            DocumentTextExtractor textExtractor
    ) {
        this.knowledgeStore = knowledgeStore;
        this.fileStorage = fileStorage;
        this.fullTextSearch = fullTextSearch;
        this.textExtractor = textExtractor;
    }

    @PostMapping(value = "/file/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> uploadFile(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam("file") MultipartFile multipartFile,
            @RequestParam(name = "title", defaultValue = "") String title,
            @RequestParam(name = "categoryId", required = false) Long categoryId
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        if (multipartFile.isEmpty()) return ApiResponse.fail("file is required");
        if (multipartFile.getSize() > 25L * 1024 * 1024) return ApiResponse.fail("file size must not exceed 25 MB");
        String filename = multipartFile.getOriginalFilename() == null ? "knowledge.txt" : multipartFile.getOriginalFilename();
        try {
            byte[] bytes = multipartFile.getBytes();
            String fileType = textExtractor.extension(filename);
            String content = textExtractor.extract(filename, bytes).trim();
            Map<String, Object> stored = fileStorage.saveFile(filename, bytes, multipartFile.getContentType(), fileType);
            KnowledgeFileEntity file = new KnowledgeFileEntity();
            file.setUserId(userId);
            file.setCategoryId(categoryId);
            file.setTitle(title == null || title.isBlank() ? filename : title.trim());
            file.setFileUrl(String.valueOf(stored.get("fileUrl")));
            file.setFileType(fileType);
            file.setParseStatus(content.isBlank() ? "EMPTY" : "INDEXED");
            file.setAuditStatus("PENDING");
            file.setViews(0);
            file.setDownloads(0);
            KnowledgeFileEntity saved = knowledgeStore.saveFile(file);
            if (!content.isBlank()) fullTextSearch.index(saved.getId(), saved.getTitle(), content, saved.getFileUrl());
            Map<String, Object> view = toView(saved);
            view.put("size", bytes.length);
            view.put("storageMode", stored.get("storageMode"));
            return ApiResponse.ok(view);
        } catch (IllegalArgumentException error) {
            return ApiResponse.fail(error.getMessage());
        } catch (Exception error) {
            return ApiResponse.fail("file upload failed: " + error.getMessage());
        }
    }

    @GetMapping("/file/{fileId}")
    public ResponseEntity<byte[]> fileContent(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable Long fileId
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);
        KnowledgeFileEntity file = knowledgeStore.find(fileId)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        boolean allowed = "APPROVED".equals(file.getAuditStatus()) || userId.equals(file.getUserId()) || LocalAuth.isAdmin(authorization);
        if (!allowed) throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
        LocalFileStorageService.StoredContent stored = fileStorage.read(file.getFileUrl());
        knowledgeStore.download(userId, fileId);
        String downloadName = file.getTitle() + (file.getFileType() == null || file.getFileType().isBlank() ? "" : "." + file.getFileType());
        ContentDisposition disposition = ContentDisposition.attachment().filename(downloadName, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(stored.bytes().length)
                .body(stored.bytes());
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
    public ApiResponse<Map<String, Object>> storageUpload(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        if (!LocalAuth.isAuthenticated(authorization)) return ApiResponse.fail("valid user authorization is required");
        String filename = String.valueOf(request.getOrDefault("filename", request.getOrDefault("title", "knowledge.txt")));
        String content = String.valueOf(request.getOrDefault("content", ""));
        String fileType = String.valueOf(request.getOrDefault("fileType", "txt"));
        return ApiResponse.ok(fileStorage.saveTextFile(filename, content, fileType));
    }

    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> upload(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        KnowledgeFileEntity file = new KnowledgeFileEntity();
        file.setUserId(userId);
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
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "includeAll", defaultValue = "false") boolean includeAll
    ) {
        boolean canViewAll = includeAll && LocalAuth.isAdmin(authorization);
        return ApiResponse.ok(knowledgeStore.listFiles().stream()
                .filter(file -> canViewAll || "APPROVED".equals(file.getAuditStatus()))
                .map(this::toView).toList());
    }

    @GetMapping("/categories")
    public ApiResponse<List<Map<String, Object>>> categories() {
        return ApiResponse.ok(knowledgeStore.listCategories().stream().map(this::toCategoryView).toList());
    }

    @PostMapping("/admin/category")
    public ApiResponse<Map<String, Object>> saveCategory(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        if (!LocalAuth.isAdmin(authorization)) return ApiResponse.fail("admin authorization is required");
        KnowledgeCategoryEntity category = new KnowledgeCategoryEntity();
        category.setId(number(request.get("id"), null));
        category.setName(String.valueOf(request.getOrDefault("name", "")).trim());
        category.setParentId(number(request.get("parentId"), 0L));
        category.setSortNo(number(request.get("sortNo"), 0L).intValue());
        if (category.getName().isBlank()) return ApiResponse.fail("category name is required");
        return ApiResponse.ok(toCategoryView(knowledgeStore.saveCategory(category)));
    }

    @DeleteMapping("/admin/category")
    public ApiResponse<Map<String, Object>> deleteCategory(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        if (!LocalAuth.isAdmin(authorization)) return ApiResponse.fail("admin authorization is required");
        Long categoryId = number(request.get("categoryId"), 0L);
        return ApiResponse.ok(Map.of("categoryId", categoryId, "removed", knowledgeStore.deleteCategory(categoryId)));
    }

    @GetMapping("/search")
    public ApiResponse<List<Map<String, Object>>> search(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "keyword", defaultValue = "") String keyword
    ) {
        return ApiResponse.ok(knowledgeStore.searchFiles(keyword).stream()
                .filter(file -> canView(file, authorization))
                .map(this::toView).toList());
    }

    @GetMapping("/search/fulltext")
    public ApiResponse<List<Map<String, Object>>> fullTextSearch(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "keyword", defaultValue = "") String keyword
    ) {
        return ApiResponse.ok(fullTextSearch.search(keyword).stream()
                .filter(result -> {
                    Object value = result.get("fileId");
                    Long fileId = value instanceof Number number ? number.longValue() : number(value == null ? null : value.toString(), null);
                    return fileId != null && knowledgeStore.find(fileId).map(file -> canView(file, authorization)).orElse(false);
                })
                .toList());
    }

    @GetMapping("/search/status")
    public ApiResponse<Map<String, Object>> searchStatus() {
        return ApiResponse.ok(fullTextSearch.status());
    }

    @PostMapping("/view")
    public ApiResponse<Map<String, Object>> view(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long fileId = number(request.get("fileId"), 0L);
        return knowledgeStore.view(fileId)
                .filter(file -> canView(file, authorization))
                .map(file -> {
                    Map<String, Object> detail = toView(file);
                    detail.put("content", fullTextSearch.find(fileId)
                            .map(LocalFullTextSearchService.SearchDocument::getContent)
                            .orElse("该资源尚未保存可预览的正文。"));
                    return ApiResponse.ok(detail);
                })
                .orElseGet(() -> ApiResponse.fail("knowledge file not found"));
    }

    @PostMapping("/download")
    public ApiResponse<Map<String, Object>> download(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
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
    public ApiResponse<Map<String, Object>> like(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        Long fileId = number(request.get("fileId"), 0L);
        int likes = knowledgeStore.like(userId, fileId);
        return ApiResponse.ok(Map.of("userId", userId, "fileId", fileId, "liked", true, "likes", likes));
    }

    @GetMapping("/ranking")
    public ApiResponse<List<Map<String, Object>>> ranking() {
        Map<Long, List<KnowledgeFileEntity>> filesByUser = knowledgeStore.listFiles().stream()
                .filter(file -> file.getUserId() != null)
                .collect(java.util.stream.Collectors.groupingBy(KnowledgeFileEntity::getUserId));
        List<Map<String, Object>> ranking = filesByUser.entrySet().stream()
                .map(entry -> {
                    int views = entry.getValue().stream().mapToInt(file -> file.getViews() == null ? 0 : file.getViews()).sum();
                    int downloads = entry.getValue().stream().mapToInt(file -> file.getDownloads() == null ? 0 : file.getDownloads()).sum();
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("userId", entry.getKey());
                    item.put("nickname", "User " + entry.getKey());
                    item.put("uploads", entry.getValue().size());
                    item.put("views", views);
                    item.put("downloads", downloads);
                    item.put("score", entry.getValue().size() * 10 + views + downloads * 2);
                    item.put("violations", 0);
                    return item;
                })
                .sorted(java.util.Comparator.comparingInt(item -> -((Number) item.get("score")).intValue()))
                .limit(20)
                .toList();
        for (int index = 0; index < ranking.size(); index++) ranking.get(index).put("rank", index + 1);
        return ApiResponse.ok(ranking);
    }

    @PostMapping("/collect")
    public ApiResponse<Map<String, Object>> collect(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        Long fileId = number(request.get("fileId"), 0L);
        knowledgeStore.collect(userId, fileId);
        return ApiResponse.ok(Map.of("userId", userId, "fileId", fileId, "collected", true));
    }

    @GetMapping("/collects")
    public ApiResponse<List<Map<String, Object>>> collects(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "userId", required = false) Long userId
    ) {
        if (!LocalAuth.canAccessUser(authorization, userId)) return ApiResponse.fail("access to this user is denied");
        return ApiResponse.ok(knowledgeStore.listCollects(userId));
    }

    @GetMapping("/mine")
    public ApiResponse<List<Map<String, Object>>> mine(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "type", defaultValue = "UPLOADED") String type,
            @RequestParam(name = "userId", required = false) Long requestedUserId
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        if (requestedUserId != null && !LocalAuth.canAccessUser(authorization, requestedUserId)) return ApiResponse.fail("access to this user is denied");
        Long target = requestedUserId == null ? userId : requestedUserId;
        try {
            return ApiResponse.ok(knowledgeStore.listUserFiles(target, type).stream().map(this::toView).toList());
        } catch (IllegalArgumentException error) {
            return ApiResponse.fail(error.getMessage());
        }
    }

    @PostMapping("/report")
    public ApiResponse<Map<String, Object>> report(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        Long fileId = number(request.get("fileId"), 0L);
        knowledgeStore.report(userId, fileId, String.valueOf(request.getOrDefault("reason", "")));
        return ApiResponse.ok(Map.of("fileId", fileId, "status", "REPORTED"));
    }

    @GetMapping("/reports")
    public ApiResponse<List<Map<String, Object>>> reports(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "userId", required = false) Long userId
    ) {
        if (!LocalAuth.canAccessUser(authorization, userId)) return ApiResponse.fail("access to this user is denied");
        return ApiResponse.ok(knowledgeStore.listReports(userId));
    }

    @PostMapping("/forward")
    public ApiResponse<Map<String, Object>> forward(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        Long fileId = number(request.get("fileId"), 0L);
        try {
            knowledgeStore.forward(userId, fileId);
            return ApiResponse.ok(Map.of("fileId", fileId, "forwarded", true));
        } catch (IllegalArgumentException error) {
            return ApiResponse.fail(error.getMessage());
        }
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

    private Map<String, Object> toCategoryView(KnowledgeCategoryEntity category) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", category.getId()); view.put("name", category.getName());
        view.put("parentId", category.getParentId()); view.put("sortNo", category.getSortNo());
        return view;
    }

    private boolean canView(KnowledgeFileEntity file, String authorization) {
        Long userId = LocalAuth.userId(authorization);
        return userId != null && ("APPROVED".equals(file.getAuditStatus())
                || userId.equals(file.getUserId())
                || LocalAuth.isAdmin(authorization));
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
