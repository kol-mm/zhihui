package com.aiknowledge.knowledge.controller;

import com.aiknowledge.common.AppTime;
import com.aiknowledge.common.AdminAudit;
import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.common.PlatformConfigClient;
import com.aiknowledge.knowledge.entity.KnowledgeFileEntity;
import com.aiknowledge.knowledge.entity.KnowledgeCategoryEntity;
import com.aiknowledge.knowledge.search.LocalFullTextSearchService;
import com.aiknowledge.knowledge.storage.LocalFileStorageService;
import com.aiknowledge.knowledge.storage.DocumentTextExtractor;
import com.aiknowledge.knowledge.storage.KnowledgeMediaStorageService;
import com.aiknowledge.knowledge.store.KnowledgeStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.aiknowledge.common.DailySeries;
import com.aiknowledge.common.ExpiringValue;
import java.time.Duration;
import java.util.Collections;
import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/knowledge")
public class KnowledgeController {
    private static final int KNOWLEDGE_PAGE_MAX = 50;
    private static final Pattern IMAGE_MARKUP = Pattern.compile("!\\[([^]]*)]\\(((?:/knowledge/media/[A-Za-z0-9_-]+)|(?:https?://[^\\s)]+))\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKDOWN_IMAGE_REFERENCE = Pattern.compile("!\\[[^]]*]\\([^)]*\\)");

    private final KnowledgeStore knowledgeStore;
    private final LocalFileStorageService fileStorage;
    private final LocalFullTextSearchService fullTextSearch;
    private final DocumentTextExtractor textExtractor;
    private final PlatformConfigClient platformConfig;
    private final KnowledgeMediaStorageService mediaStorage;
    /**
     * The all-time ranking on the analytics page. It sorts the whole file table and groups every like, a few
     * hundred milliseconds on a large library, and is the same for every period the page asks for, so it is
     * kept for a minute. Deleting, reviewing or editing a file drops it at once, so a removed file never lingers.
     */
    private final ExpiringValue<List<Map<String, Object>>> analyticsRanking = new ExpiringValue<>(Duration.ofSeconds(60));

    private AdminAudit audit = AdminAudit.NONE;

    @Autowired(required = false)
    public void setAdminAudit(AdminAudit audit) {
        this.audit = audit == null ? AdminAudit.NONE : audit;
    }

    @Autowired
    public KnowledgeController(
            KnowledgeStore knowledgeStore,
            LocalFileStorageService fileStorage,
            LocalFullTextSearchService fullTextSearch,
            DocumentTextExtractor textExtractor,
            PlatformConfigClient platformConfig,
            KnowledgeMediaStorageService mediaStorage
    ) {
        this.knowledgeStore = knowledgeStore;
        this.fileStorage = fileStorage;
        this.fullTextSearch = fullTextSearch;
        this.textExtractor = textExtractor;
        this.platformConfig = platformConfig;
        this.mediaStorage = mediaStorage;
    }

    public KnowledgeController(KnowledgeStore knowledgeStore, LocalFileStorageService fileStorage,
                               LocalFullTextSearchService fullTextSearch, DocumentTextExtractor textExtractor) {
        this(knowledgeStore, fileStorage, fullTextSearch, textExtractor, null);
    }

    public KnowledgeController(KnowledgeStore knowledgeStore, LocalFileStorageService fileStorage,
                               LocalFullTextSearchService fullTextSearch, DocumentTextExtractor textExtractor,
                               PlatformConfigClient platformConfig) {
        this(knowledgeStore, fileStorage, fullTextSearch, textExtractor, platformConfig,
                new KnowledgeMediaStorageService("local", "target/test-knowledge-media",
                        "http://127.0.0.1:9000", "ai-knowledge", "aiknowledge", "test-secret"));
    }

    @PostMapping(value = "/file/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> uploadFile(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam("file") MultipartFile multipartFile,
            @RequestParam(name = "title", defaultValue = "") String title,
            @RequestParam(name = "categoryId", required = false) Long categoryId,
            @RequestParam(name = "imageUrls", required = false) List<String> imageUrls
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        if (!LocalAuth.isAdmin(authorization) && !knowledgeUploadEnabled()) return ApiResponse.fail("平台当前未开放用户上传知识资料");
        if (multipartFile.isEmpty()) return ApiResponse.fail("file is required");
        int maxUploadMb = platformConfig == null ? 25 : platformConfig.maxUploadMb();
        if (multipartFile.getSize() > maxUploadMb * 1024L * 1024L) return ApiResponse.fail("file size must not exceed " + maxUploadMb + " MB");
        String filename = multipartFile.getOriginalFilename() == null ? "knowledge.txt" : multipartFile.getOriginalFilename();
        String storedFileUrl = null;
        List<String> storedMediaUrls = new ArrayList<>();
        Long savedFileId = null;
        try {
            byte[] bytes = multipartFile.getBytes();
            String fileType = textExtractor.extension(filename);
            DocumentTextExtractor.ParsedDocument parsed = textExtractor.parse(filename, bytes);
            String extractedContent = parsed.text().trim();
            if (imageUrls != null && !imageUrls.isEmpty()) {
                return ApiResponse.fail("正文图片必须包含在原始文件中，不能单独上传");
            }
            if (isMarkdown(filename) && MARKDOWN_IMAGE_REFERENCE.matcher(extractedContent).find()) {
                return ApiResponse.fail("Markdown 文件不能包含图片");
            }
            String content = extractedContent;
            Map<String, Object> stored = fileStorage.saveFile(filename, bytes, multipartFile.getContentType(), fileType);
            storedFileUrl = String.valueOf(stored.get("fileUrl"));
            List<LocalFullTextSearchService.ContentBlock> contentBlocks =
                    storeParsedBlocks(parsed.blocks(), storedMediaUrls);
            KnowledgeFileEntity file = new KnowledgeFileEntity();
            file.setUserId(userId);
            file.setCategoryId(categoryId);
            file.setTitle(title == null || title.isBlank() ? filename : title.trim());
            file.setFileUrl(storedFileUrl);
            file.setFileType(fileType);
            file.setParseStatus(content.isBlank() ? "EMPTY" : "INDEXED");
            file.setAuditStatus("PENDING");
            file.setViews(0);
            file.setDownloads(0);
            KnowledgeFileEntity saved = knowledgeStore.saveFile(file);
            savedFileId = saved.getId();
            if (!content.isBlank() || !contentBlocks.isEmpty()) {
                fullTextSearch.index(saved.getId(), saved.getTitle(), content, saved.getFileUrl(), contentBlocks);
            }
            Map<String, Object> view = toView(saved);
            view.put("size", bytes.length);
            view.put("storageMode", stored.get("storageMode"));
            return ApiResponse.ok(view);
        } catch (IllegalArgumentException error) {
            rollbackUploadedKnowledge(savedFileId, storedFileUrl, storedMediaUrls);
            return ApiResponse.fail(error.getMessage());
        } catch (Exception error) {
            rollbackUploadedKnowledge(savedFileId, storedFileUrl, storedMediaUrls);
            return ApiResponse.fail("file upload failed: " + error.getMessage());
        }
    }

    public ApiResponse<Map<String, Object>> uploadFile(
            String authorization, MultipartFile multipartFile, String title, Long categoryId
    ) {
        return uploadFile(authorization, multipartFile, title, categoryId, List.of());
    }

    @PostMapping(value = "/media/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> uploadImages(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam("files") List<MultipartFile> files
    ) {
        if (!LocalAuth.isAuthenticated(authorization)) return ApiResponse.fail("valid user authorization is required");
        if (files.isEmpty() || files.size() > 12) return ApiResponse.fail("select between 1 and 12 images");
        try {
            List<byte[]> contents = new java.util.ArrayList<>();
            for (MultipartFile file : files) {
                byte[] bytes = file.getBytes();
                mediaStorage.validate(bytes);
                contents.add(bytes);
            }
            List<String> imageUrls = new java.util.ArrayList<>();
            for (byte[] bytes : contents) imageUrls.add(mediaStorage.save(bytes));
            return ApiResponse.ok(Map.of("imageUrls", imageUrls, "coverUrl", imageUrls.get(0), "count", imageUrls.size()));
        } catch (IllegalArgumentException error) {
            return ApiResponse.fail(error.getMessage());
        } catch (Exception error) {
            return ApiResponse.fail("image upload failed: " + error.getMessage());
        }
    }

    @GetMapping("/media/{token}")
    public ResponseEntity<byte[]> media(@PathVariable String token) {
        KnowledgeMediaStorageService.StoredMedia media = mediaStorage.read(token);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(media.contentType()))
                .contentLength(media.bytes().length).body(media.bytes());
    }

    @GetMapping("/file/{fileId}")
    public ResponseEntity<byte[]> fileContent(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable Long fileId
    ) {
        return storedFileResponse(authorization, fileId, true);
    }

    @GetMapping("/file/{fileId}/preview")
    public ResponseEntity<byte[]> filePreview(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable Long fileId
    ) {
        return storedFileResponse(authorization, fileId, false);
    }

    private ResponseEntity<byte[]> storedFileResponse(String authorization, Long fileId, boolean download) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);
        KnowledgeFileEntity file = knowledgeStore.find(fileId)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        boolean allowed = "APPROVED".equals(file.getAuditStatus()) || userId.equals(file.getUserId()) || LocalAuth.isAdmin(authorization);
        if (!allowed) throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
        LocalFileStorageService.StoredContent stored = fileStorage.read(file.getFileUrl());
        if (download) knowledgeStore.download(userId, fileId);
        String downloadName = file.getTitle() + (file.getFileType() == null || file.getFileType().isBlank() ? "" : "." + file.getFileType());
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(downloadName, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, download ? "no-store" : "private, max-age=300")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(download ? MediaType.APPLICATION_OCTET_STREAM : previewMediaType(file.getFileType()))
                .contentLength(stored.bytes().length)
                .body(stored.bytes());
    }

    private MediaType previewMediaType(String fileType) {
        return switch (fileType == null ? "" : fileType.toLowerCase()) {
            case "pdf" -> MediaType.APPLICATION_PDF;
            case "txt", "md", "markdown", "csv" -> new MediaType("text", "plain", StandardCharsets.UTF_8);
            case "docx" -> MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
                "service", "knowledge-service",
                "time", Instant.now().toString(),
                "dataMode", storeMode(knowledgeStore)
        ));
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
        List<String> imageUrls = stringList(request.get("imageUrls"));
        if (!imageUrls.isEmpty()) {
            return ApiResponse.fail("正文图片必须包含在原始文件中，不能单独上传");
        }
        if ("md".equalsIgnoreCase(fileType)
                && MARKDOWN_IMAGE_REFERENCE.matcher(content).find()) {
            return ApiResponse.fail("Markdown 正文不能包含图片");
        }
        return ApiResponse.ok(fileStorage.saveTextFile(filename, content, fileType));
    }

    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> upload(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        if (!LocalAuth.isAdmin(authorization) && !knowledgeUploadEnabled()) return ApiResponse.fail("平台当前未开放用户上传知识资料");
        String fileType = String.valueOf(request.getOrDefault("fileType", "txt"));
        String content = String.valueOf(request.getOrDefault("content", ""));
        List<String> imageUrls = stringList(request.get("imageUrls"));
        if (!imageUrls.isEmpty()) {
            return ApiResponse.fail("正文图片必须包含在原始文件中，不能单独上传");
        }
        if ("md".equalsIgnoreCase(fileType) && MARKDOWN_IMAGE_REFERENCE.matcher(content).find()) {
            return ApiResponse.fail("Markdown 正文不能包含图片");
        }
        KnowledgeFileEntity file = new KnowledgeFileEntity();
        file.setUserId(userId);
        file.setCategoryId(number(request.get("categoryId"), null));
        file.setTitle(String.valueOf(request.getOrDefault("title", "未命名知识文件")));
        file.setFileUrl(String.valueOf(request.getOrDefault("fileUrl", "")));
        file.setFileType(fileType);
        file.setParseStatus("PENDING");
        file.setAuditStatus("PENDING");
        file.setViews(0);
        file.setDownloads(0);
        KnowledgeFileEntity saved = knowledgeStore.saveFile(file);
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
        Long viewerUserId = LocalAuth.userId(authorization);
        return ApiResponse.ok(toViews(knowledgeStore.listFiles().stream()
                .filter(file -> canViewAll || "APPROVED".equals(file.getAuditStatus())).toList(), viewerUserId));
    }

    /** Newest-first page of knowledge files; filters are applied in the query, not in the browser. */
    @GetMapping("/page")
    public ApiResponse<Map<String, Object>> page(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "cursor", required = false) Long cursor,
            @RequestParam(name = "limit", defaultValue = "12") int limit,
            @RequestParam(name = "categoryId", required = false) Long categoryId,
            @RequestParam(name = "fileType", required = false) String fileType,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "includeAll", defaultValue = "false") boolean includeAll
    ) {
        if (cursor != null && cursor < 0) return ApiResponse.fail("invalid knowledge cursor");
        boolean canViewAll = includeAll && LocalAuth.isAdmin(authorization);
        Long viewerUserId = LocalAuth.userId(authorization);
        int pageSize = Math.max(1, Math.min(KNOWLEDGE_PAGE_MAX, limit));
        KnowledgeStore.FileQuery query = new KnowledgeStore.FileQuery(categoryId, fileType, keyword, canViewAll, cursor, pageSize + 1);
        List<KnowledgeFileEntity> files = knowledgeStore.pageFiles(query);
        boolean hasMore = files.size() > pageSize;
        if (hasMore) files = files.subList(0, pageSize);
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("items", toViews(files, viewerUserId));
        page.put("nextCursor", hasMore ? files.get(files.size() - 1).getId() : null);
        page.put("hasMore", hasMore);
        page.put("total", knowledgeStore.countFiles(new KnowledgeStore.FileQuery(categoryId, fileType, keyword, canViewAll, null, 1)));
        return ApiResponse.ok(page);
    }

    /** Counts for the category filter, so the tabs stay accurate without loading every file. */
    @GetMapping("/category-counts")
    public ApiResponse<Map<String, Object>> categoryCounts(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "fileType", required = false) String fileType,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "includeAll", defaultValue = "false") boolean includeAll
    ) {
        boolean canViewAll = includeAll && LocalAuth.isAdmin(authorization);
        KnowledgeStore.FileQuery query = new KnowledgeStore.FileQuery(null, fileType, keyword, canViewAll, null, 1);
        Map<Long, Long> counts = knowledgeStore.countFilesByCategory(query);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("total", counts.values().stream().mapToLong(Long::longValue).sum());
        view.put("counts", counts.entrySet().stream()
                .map(entry -> Map.of("categoryId", entry.getKey(), "count", entry.getValue()))
                .toList());
        return ApiResponse.ok(view);
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
        KnowledgeCategoryEntity previous = category.getId() == null ? null : knowledgeStore.listCategories().stream()
                .filter(item -> category.getId().equals(item.getId())).findFirst().orElse(null);
        KnowledgeCategoryEntity saved = knowledgeStore.saveCategory(category);
        audit.record(authorization, AdminAudit.Event.of("KNOWLEDGE_CATEGORY_SAVE", AdminAudit.KNOWLEDGE, "CATEGORY",
                        saved.getId(), saved.getName(), null, previous == null ? "新建知识分类" : "修改知识分类")
                .withChanges(new AdminAudit.Changes()
                        .add("name", "名称", previous == null ? null : previous.getName(), saved.getName())
                        .add("parentId", "上级分类", previous == null ? null : previous.getParentId(), saved.getParentId())
                        .add("sortNo", "排序", previous == null ? null : previous.getSortNo(), saved.getSortNo())));
        return ApiResponse.ok(toCategoryView(saved));
    }

    @DeleteMapping("/admin/category")
    public ApiResponse<Map<String, Object>> deleteCategory(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        if (!LocalAuth.isAdmin(authorization)) return ApiResponse.fail("admin authorization is required");
        Long categoryId = number(request.get("categoryId"), 0L);
        String name = knowledgeStore.listCategories().stream().filter(item -> categoryId.equals(item.getId()))
                .map(KnowledgeCategoryEntity::getName).findFirst().orElse("#" + categoryId);
        boolean removed = knowledgeStore.deleteCategory(categoryId);
        if (removed) {
            audit.record(authorization, AdminAudit.Event.of("KNOWLEDGE_CATEGORY_DELETE", AdminAudit.KNOWLEDGE, "CATEGORY",
                    categoryId, name, null, "删除知识分类"));
        }
        return ApiResponse.ok(Map.of("categoryId", categoryId, "removed", removed));
    }

    @GetMapping("/search")
    public ApiResponse<List<Map<String, Object>>> search(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "keyword", defaultValue = "") String keyword
    ) {
        return ApiResponse.ok(knowledgeStore.searchFiles(keyword).stream()
                .filter(file -> canView(file, authorization))
                .map(file -> toView(file, LocalAuth.userId(authorization))).toList());
    }

    @GetMapping("/search/fulltext")
    public ApiResponse<List<Map<String, Object>>> fullTextSearch(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "keyword", defaultValue = "") String keyword
    ) {
        return ApiResponse.ok(fullTextSearch.search(keyword).stream()
                .map(result -> {
                    Object value = result.get("fileId");
                    Long fileId = value instanceof Number number ? number.longValue() : number(value == null ? null : value.toString(), null);
                    return fileId == null ? null : knowledgeStore.find(fileId)
                            .filter(file -> canView(file, authorization))
                            .map(file -> {
                                Map<String, Object> view = toView(file, LocalAuth.userId(authorization));
                                view.put("snippet", result.getOrDefault("snippet", ""));
                                view.put("score", result.getOrDefault("score", 0));
                                return view;
                            }).orElse(null);
                })
                .filter(java.util.Objects::nonNull)
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
                    Map<String, Object> detail = toView(file, LocalAuth.userId(authorization));
                    addPreviewContent(detail, file);
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
        try {
            boolean liked = knowledgeStore.toggleLike(userId, fileId);
            return ApiResponse.ok(Map.of(
                    "userId", userId,
                    "fileId", fileId,
                    "liked", liked,
                    "likes", knowledgeStore.likeCount(fileId)
            ));
        } catch (IllegalArgumentException error) {
            return ApiResponse.fail(error.getMessage());
        }
    }

    @DeleteMapping("/file")
    public ApiResponse<Map<String, Object>> deleteFile(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        Long fileId = number(request.get("fileId"), 0L);
        KnowledgeFileEntity file = knowledgeStore.find(fileId).orElse(null);
        if (file == null) return ApiResponse.fail("knowledge file not found");
        if (!LocalAuth.isAdmin(authorization) && !userId.equals(file.getUserId())) {
            return ApiResponse.fail("access to this knowledge file is denied");
        }
        List<String> mediaUrls = imageUrls(fileId);
        boolean removed = knowledgeStore.deleteFile(fileId);
        analyticsRanking.invalidate();
        if (!removed) return ApiResponse.fail("knowledge file not found");
        if (!userId.equals(file.getUserId())) {
            audit.record(authorization, AdminAudit.Event.of("KNOWLEDGE_DELETE", AdminAudit.KNOWLEDGE, "KNOWLEDGE_FILE",
                    fileId, file.getTitle(), file.getUserId(), "删除他人上传的知识资源"));
        }
        boolean indexRemoved = fullTextSearch.remove(fileId);
        boolean storageRemoved = false;
        try {
            storageRemoved = fileStorage.delete(file.getFileUrl());
        } catch (RuntimeException ignored) {
            // The business record is deleted even if an external object store is temporarily unavailable.
        }
        int mediaRemoved = 0;
        for (String mediaUrl : mediaUrls) {
            if (!mediaUrl.startsWith("/knowledge/media/")) continue;
            try {
                if (mediaStorage.delete(mediaUrl)) mediaRemoved++;
            } catch (RuntimeException ignored) {
                // The knowledge record remains deleted even if media cleanup must be retried later.
            }
        }
        return ApiResponse.ok(Map.of(
                "fileId", fileId,
                "removed", true,
                "indexRemoved", indexRemoved,
                "storageRemoved", storageRemoved,
                "mediaRemoved", mediaRemoved
        ));
    }

    @GetMapping("/ranking")
    public ApiResponse<List<Map<String, Object>>> ranking() {
        if (platformConfig != null && !platformConfig.enabled("user_ranking_enabled", true)) {
            return ApiResponse.ok(List.of());
        }
        List<KnowledgeFileEntity> allFiles = knowledgeStore.listFiles();
        Map<Long, Long> ownerByFile = allFiles.stream().filter(file -> file.getId() != null && file.getUserId() != null)
                .collect(java.util.stream.Collectors.toMap(KnowledgeFileEntity::getId, KnowledgeFileEntity::getUserId, (left, right) -> left));
        Map<Long, Long> reportsByUser = knowledgeStore.listReports(null).stream()
                .map(report -> report.get("fileId"))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::longValue)
                .map(ownerByFile::get)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.groupingBy(userId -> userId, java.util.stream.Collectors.counting()));
        Map<Long, List<KnowledgeFileEntity>> filesByUser = allFiles.stream()
                .filter(file -> file.getUserId() != null && "APPROVED".equals(file.getAuditStatus()))
                .collect(java.util.stream.Collectors.groupingBy(KnowledgeFileEntity::getUserId));
        List<Map<String, Object>> ranking = filesByUser.entrySet().stream()
                .map(entry -> {
                    int views = entry.getValue().stream().mapToInt(file -> file.getViews() == null ? 0 : file.getViews()).sum();
                    int downloads = entry.getValue().stream().mapToInt(file -> file.getDownloads() == null ? 0 : file.getDownloads()).sum();
                    int violations = Math.toIntExact(reportsByUser.getOrDefault(entry.getKey(), 0L))
                            + (int) entry.getValue().stream().filter(file -> "REJECTED".equals(file.getAuditStatus())).count();
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("userId", entry.getKey());
                    item.put("nickname", "User " + entry.getKey());
                    item.put("uploads", entry.getValue().size());
                    item.put("views", views);
                    item.put("downloads", downloads);
                    item.put("score", Math.max(0, entry.getValue().size() * 10 + views + downloads * 2 - violations * 5));
                    item.put("violations", violations);
                    return item;
                })
                .sorted(java.util.Comparator.comparingInt(item -> -((Number) item.get("score")).intValue()))
                .limit(20)
                .toList();
        for (int index = 0; index < ranking.size(); index++) ranking.get(index).put("rank", index + 1);
        return ApiResponse.ok(ranking);
    }

    private boolean knowledgeUploadEnabled() {
        return platformConfig == null || platformConfig.enabled("knowledge_upload_enabled", true);
    }

    @PostMapping("/collect")
    public ApiResponse<Map<String, Object>> collect(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        Long fileId = number(request.get("fileId"), 0L);
        try {
            boolean collected = knowledgeStore.toggleCollect(userId, fileId);
            return ApiResponse.ok(Map.of("userId", userId, "fileId", fileId, "collected", collected));
        } catch (IllegalArgumentException error) {
            return ApiResponse.fail(error.getMessage());
        }
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
            return ApiResponse.ok(toViews(knowledgeStore.listUserFiles(target, type), userId));
        } catch (IllegalArgumentException error) {
            return ApiResponse.fail(error.getMessage());
        }
    }

    /** Paged version of /mine: one page of the user's knowledge activity. */
    @GetMapping("/mine/page")
    public ApiResponse<Map<String, Object>> minePage(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "type", defaultValue = "UPLOADED") String type,
            @RequestParam(name = "userId", required = false) Long requestedUserId,
            @RequestParam(name = "cursor", required = false) Long cursor,
            @RequestParam(name = "limit", defaultValue = "12") int limit
    ) {
        Long userId = LocalAuth.userId(authorization);
        if (userId == null) return ApiResponse.fail("valid user authorization is required");
        if (requestedUserId != null && !LocalAuth.canAccessUser(authorization, requestedUserId)) return ApiResponse.fail("access to this user is denied");
        if (cursor != null && cursor < 0) return ApiResponse.fail("invalid knowledge cursor");
        Long target = requestedUserId == null ? userId : requestedUserId;
        int pageSize = Math.max(1, Math.min(KNOWLEDGE_PAGE_MAX, limit));
        try {
            KnowledgeStore.UserFilePage page = knowledgeStore.pageUserFiles(target, type, cursor, pageSize);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("items", toViews(page.files(), userId));
            view.put("nextCursor", page.nextCursor());
            view.put("hasMore", page.hasMore());
            view.put("total", knowledgeStore.countUserFiles(target, type));
            return ApiResponse.ok(view);
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
        KnowledgeStore.FileTotals totals = knowledgeStore.fileTotals();
        return ApiResponse.ok(Map.of(
                "module", "知识库管理",
                "totalFiles", totals.files(),
                "pendingAudit", totals.pendingAudit(),
                "totalViews", totals.views(),
                "totalDownloads", totals.downloads(),
                "reports", knowledgeStore.countReports(null),
                "openReports", knowledgeStore.countOpenReports(),
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

    @GetMapping("/admin/preview")
    public ApiResponse<Map<String, Object>> adminPreview(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam("fileId") Long fileId
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) return denied;
        return knowledgeStore.find(fileId)
                .map(file -> {
                    Map<String, Object> detail = toView(file);
                    addPreviewContent(detail, file);
                    return ApiResponse.ok(detail);
                })
                .orElseGet(() -> ApiResponse.fail("knowledge file not found"));
    }

    @PostMapping("/admin/report/resolve")
    public ApiResponse<Map<String, Object>> resolveReport(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        if (!LocalAuth.isAdmin(authorization)) return ApiResponse.fail("admin authorization is required");
        String status = String.valueOf(request.getOrDefault("status", "RESOLVED"));
        String result = String.valueOf(request.getOrDefault("result", "handled"));
        return knowledgeStore.resolveReport(number(request.get("reportId"), 0L), status, result)
                .map(report -> {
                    Long fileId = report.get("fileId") instanceof Number number ? number.longValue() : null;
                    KnowledgeFileEntity file = fileId == null ? null : knowledgeStore.find(fileId).orElse(null);
                    audit.record(authorization, AdminAudit.Event.of("KNOWLEDGE_REPORT_RESOLVE", AdminAudit.KNOWLEDGE,
                                    "KNOWLEDGE_REPORT", report.get("id"), file == null ? "#" + fileId : file.getTitle(),
                                    file == null ? null : file.getUserId(), "处理知识资源举报")
                            .with("status", status).with("result", result).with("fileId", fileId));
                    return ApiResponse.ok(report);
                })
                .orElseGet(() -> ApiResponse.fail("report not found"));
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
        if (!List.of("APPROVED", "REJECTED").contains(auditStatus)) {
            return ApiResponse.fail("审核结果无效");
        }
        KnowledgeFileEntity existing = knowledgeStore.find(fileId).orElse(null);
        if (existing == null) {
            return ApiResponse.fail("知识资源不存在");
        }
        if (auditStatus.equals(existing.getAuditStatus())) {
            return ApiResponse.fail("APPROVED".equals(auditStatus) ? "该知识资源已经通过审核" : "该知识资源已经被驳回");
        }
        boolean validTransition = "PENDING".equals(existing.getAuditStatus())
                || ("REJECTED".equals(existing.getAuditStatus()) && "APPROVED".equals(auditStatus));
        if (!validTransition) {
            return ApiResponse.fail("当前状态不支持此审核操作");
        }
        String previousStatus = existing.getAuditStatus();
        var audited = knowledgeStore.auditFile(fileId, auditStatus, reason);
        analyticsRanking.invalidate();
        audited.ifPresent(file -> audit.record(authorization, AdminAudit.Event.of("KNOWLEDGE_AUDIT", AdminAudit.KNOWLEDGE,
                        "KNOWLEDGE_FILE", fileId, file.getTitle(), file.getUserId(),
                        "APPROVED".equals(auditStatus) ? "审核通过知识资源" : "驳回知识资源")
                .withChanges(new AdminAudit.Changes().add("auditStatus", "审核状态", previousStatus, auditStatus))
                .with("reason", reason)));
        return audited
                .map(file -> ApiResponse.ok(Map.of(
                        "file", toView(file),
                        "reason", reason,
                        "updated", true
                )))
                .orElseGet(() -> ApiResponse.fail("知识资源不存在"));
    }

    @PutMapping("/admin/file")
    public ApiResponse<Map<String, Object>> updateFileMetadata(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        if (!LocalAuth.isAdmin(authorization)) return ApiResponse.fail("admin authorization is required");
        Long fileId = number(request.get("fileId"), 0L);
        KnowledgeFileEntity existing = knowledgeStore.find(fileId).orElse(null);
        if (existing == null) return ApiResponse.fail("knowledge file not found");
        String title = String.valueOf(request.getOrDefault("title", existing.getTitle())).trim();
        Long categoryId = request.containsKey("categoryId") ? number(request.get("categoryId"), null) : existing.getCategoryId();
        String auditStatus = String.valueOf(request.getOrDefault("auditStatus", existing.getAuditStatus()));
        if (title.isBlank() || title.length() > 255) return ApiResponse.fail("knowledge title must contain between 1 and 255 characters");
        if (!List.of("PENDING", "APPROVED", "REJECTED", "HIDDEN").contains(auditStatus)) {
            return ApiResponse.fail("invalid knowledge status");
        }
        if (categoryId != null && knowledgeStore.listCategories().stream().noneMatch(item -> categoryId.equals(item.getId()))) {
            return ApiResponse.fail("knowledge category not found");
        }
        AdminAudit.Changes changes = new AdminAudit.Changes()
                .add("title", "标题", existing.getTitle(), title)
                .add("categoryId", "分类", existing.getCategoryId(), categoryId)
                .add("auditStatus", "审核状态", existing.getAuditStatus(), auditStatus);
        var updated = knowledgeStore.updateFileMetadata(fileId, title, categoryId, auditStatus);
        analyticsRanking.invalidate();
        if (updated.isPresent() && !changes.isEmpty()) {
            audit.record(authorization, AdminAudit.Event.of("KNOWLEDGE_EDIT", AdminAudit.KNOWLEDGE, "KNOWLEDGE_FILE",
                    fileId, title, existing.getUserId(), "修改了" + changes.labels()).withChanges(changes));
        }
        return updated
                .map(file -> {
                    fullTextSearch.find(fileId).ifPresent(document ->
                            fullTextSearch.index(fileId, title, document.getContent(), file.getFileUrl(),
                                    document.getContentBlocks()));
                    return ApiResponse.ok(toView(file));
                })
                .orElseGet(() -> ApiResponse.fail("knowledge file not found"));
    }

    /** Builds views for a list with a fixed number of queries instead of three per file. */
    private List<Map<String, Object>> toViews(List<KnowledgeFileEntity> files, Long viewerUserId) {
        if (files.isEmpty()) return List.of();
        List<Long> fileIds = files.stream().map(KnowledgeFileEntity::getId).toList();
        Map<Long, Integer> likeCounts = knowledgeStore.likeCounts(fileIds);
        java.util.Set<Long> liked = knowledgeStore.likedFileIds(viewerUserId, fileIds);
        java.util.Set<Long> collected = knowledgeStore.collectedFileIds(viewerUserId, fileIds);
        return files.stream().map(file -> {
            Map<String, Object> view = baseView(file);
            view.put("likes", likeCounts.getOrDefault(file.getId(), 0));
            view.put("liked", liked.contains(file.getId()));
            view.put("collected", collected.contains(file.getId()));
            List<String> imageUrls = imageUrls(file.getId());
            view.put("imageUrls", imageUrls);
            view.put("coverUrl", imageUrls.isEmpty() ? "" : imageUrls.get(0));
            view.put("createdAt", file.getCreatedAt());
            return view;
        }).toList();
    }

    private Map<String, Object> baseView(KnowledgeFileEntity file) {
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
        return view;
    }

    private Map<String, Object> toView(KnowledgeFileEntity file) {
        return toView(file, null);
    }

    private Map<String, Object> toView(KnowledgeFileEntity file, Long viewerUserId) {
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
        view.put("liked", knowledgeStore.hasLike(viewerUserId, file.getId()));
        view.put("collected", knowledgeStore.hasCollect(viewerUserId, file.getId()));
        List<String> imageUrls = imageUrls(file.getId());
        view.put("imageUrls", imageUrls);
        view.put("coverUrl", imageUrls.isEmpty() ? "" : imageUrls.get(0));
        view.put("createdAt", file.getCreatedAt());
        return view;
    }

    private List<String> imageUrls(Long fileId) {
        return fullTextSearch.find(fileId).map(document -> {
            List<String> urls = new java.util.ArrayList<>();
            document.getContentBlocks().stream()
                    .filter(block -> "image".equals(block.getType()))
                    .map(LocalFullTextSearchService.ContentBlock::getUrl)
                    .filter(url -> url != null && !url.isBlank())
                    .forEach(urls::add);
            if (!urls.isEmpty()) return List.copyOf(urls);
            Matcher matcher = IMAGE_MARKUP.matcher(document.getContent());
            while (matcher.find() && urls.size() < 12) urls.add(matcher.group(2));
            return List.copyOf(urls);
        }).orElse(List.of());
    }

    private void addPreviewContent(Map<String, Object> detail, KnowledgeFileEntity file) {
        LocalFullTextSearchService.SearchDocument document = ensurePreviewDocument(file);
        detail.put("content", document == null || document.getContent().isBlank()
                ? "该资源尚未保存可预览的正文。" : document.getContent());
        detail.put("contentBlocks", document == null ? List.of() : document.getContentBlocks());
        List<String> urls = imageUrls(file.getId());
        detail.put("imageUrls", urls);
        detail.put("coverUrl", urls.isEmpty() ? "" : urls.get(0));
    }

    private synchronized LocalFullTextSearchService.SearchDocument ensurePreviewDocument(KnowledgeFileEntity file) {
        LocalFullTextSearchService.SearchDocument existing = fullTextSearch.find(file.getId()).orElse(null);
        if (!"docx".equalsIgnoreCase(file.getFileType()) || file.getFileUrl() == null || file.getFileUrl().isBlank()) {
            return existing;
        }
        if (existing != null && !existing.getContentBlocks().isEmpty()) return existing;

        List<String> storedMediaUrls = new ArrayList<>();
        try {
            LocalFileStorageService.StoredContent stored = fileStorage.read(file.getFileUrl());
            DocumentTextExtractor.ParsedDocument parsed = textExtractor.parse(stored.objectName(), stored.bytes());
            List<LocalFullTextSearchService.ContentBlock> blocks = storeParsedBlocks(parsed.blocks(), storedMediaUrls);
            return fullTextSearch.index(file.getId(), file.getTitle(), parsed.text().trim(), file.getFileUrl(), blocks);
        } catch (RuntimeException error) {
            storedMediaUrls.forEach(url -> {
                try { mediaStorage.delete(url); } catch (RuntimeException ignored) {}
            });
            return existing;
        }
    }

    private List<LocalFullTextSearchService.ContentBlock> storeParsedBlocks(
            List<DocumentTextExtractor.ParsedBlock> parsedBlocks, List<String> storedMediaUrls
    ) {
        List<LocalFullTextSearchService.ContentBlock> blocks = new ArrayList<>();
        Map<String, String> imageUrlsByDigest = new HashMap<>();
        for (DocumentTextExtractor.ParsedBlock block : parsedBlocks) {
            if (!"image".equals(block.type())) {
                if (block.text() != null && !block.text().isBlank()) {
                    blocks.add(LocalFullTextSearchService.ContentBlock.text(block.type(), block.text()));
                }
                continue;
            }
            byte[] bytes = block.imageBytes();
            if (bytes == null || bytes.length == 0) continue;
            try {
                String digest = imageDigest(bytes);
                String mediaUrl = imageUrlsByDigest.get(digest);
                if (mediaUrl == null) {
                    mediaUrl = mediaStorage.save(bytes);
                    imageUrlsByDigest.put(digest, mediaUrl);
                    storedMediaUrls.add(mediaUrl);
                }
                blocks.add(LocalFullTextSearchService.ContentBlock.image(block.text(), mediaUrl));
            } catch (IllegalArgumentException unsupportedImage) {
                blocks.add(LocalFullTextSearchService.ContentBlock.text("paragraph",
                        "此处图片格式暂不支持在线预览，请下载原文件查看。"));
            }
        }
        return List.copyOf(blocks);
    }

    private String imageDigest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private void rollbackUploadedKnowledge(Long fileId, String fileUrl, List<String> mediaUrls) {
        if (fileId != null) {
            try { knowledgeStore.deleteFile(fileId); } catch (RuntimeException ignored) {}
            try { fullTextSearch.remove(fileId); } catch (RuntimeException ignored) {}
        }
        if (fileUrl != null && !fileUrl.isBlank()) {
            try { fileStorage.delete(fileUrl); } catch (RuntimeException ignored) {}
        }
        for (String mediaUrl : mediaUrls) {
            try { mediaStorage.delete(mediaUrl); } catch (RuntimeException ignored) {}
        }
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> items)) return List.of();
        return items.stream().map(String::valueOf).filter(item -> !item.isBlank()).limit(12).toList();
    }

    private boolean isMarkdown(String filename) {
        return filename != null && filename.toLowerCase(java.util.Locale.ROOT).endsWith(".md");
    }

    private String storeMode(Object store) {
        return store.getClass().getSimpleName().startsWith("MySql") ? "mysql" : "local";
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

    private static final int ANALYTICS_MAX_DAYS = 365;
    private static final int ANALYTICS_TREND_DAYS = 14;
    private static final int ANALYTICS_TOP_LIMIT = 5;

    /**
     * Aggregated numbers for the admin analytics page. The page used to download every file and
     * count in the browser; the database groups the same rows here instead.
     */
    @GetMapping("/admin/analytics")
    public ApiResponse<Map<String, Object>> adminAnalytics(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "days", defaultValue = "30") int days
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) {
            return denied;
        }
        int window = Math.min(Math.max(days, 1), ANALYTICS_MAX_DAYS);
        int trendDays = Math.min(window, ANALYTICS_TREND_DAYS);
        KnowledgeStore.ContentAnalytics totals = knowledgeStore.analytics(LocalDateTime.now().minusDays(window));
        Map<String, Long> perDay = new LinkedHashMap<>();
        knowledgeStore.dailyFileCounts(AppTime.startOfDay(AppTime.today().minusDays(trendDays - 1L)))
                .forEach(entry -> perDay.merge(entry.date(), entry.count(), Long::sum));
        List<Map<String, Object>> ranking = analyticsRanking.get(this::loadAnalyticsRanking);
        Map<String, Object> analytics = new LinkedHashMap<>();
        analytics.put("days", window);
        analytics.put("trendDays", trendDays);
        analytics.put("files", totals.files());
        analytics.put("views", totals.views());
        analytics.put("downloads", totals.downloads());
        analytics.put("likes", totals.likes());
        analytics.put("trend", DailySeries.fill(AppTime.today(), trendDays, perDay));
        analytics.put("top", ranking);
        return ApiResponse.ok(analytics);
    }

    private List<Map<String, Object>> loadAnalyticsRanking() {
        List<KnowledgeFileEntity> top = knowledgeStore.topFiles(ANALYTICS_TOP_LIMIT);
        Map<Long, Integer> topLikes = knowledgeStore.likeCounts(top.stream().map(KnowledgeFileEntity::getId).toList());
        return top.stream().map(file -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", file.getId());
            view.put("title", file.getTitle());
            view.put("views", file.getViews() == null ? 0 : file.getViews());
            view.put("downloads", file.getDownloads() == null ? 0 : file.getDownloads());
            view.put("likes", topLikes.getOrDefault(file.getId(), 0));
            return Collections.unmodifiableMap(view);
        }).toList();
    }


    private static final int ADMIN_FILE_PAGE_MAX = 100;

    /**
     * One page of the moderation file table. The queue used to download every file in every audit
     * state; the status, keyword and cursor are applied by the database here.
     */
    @GetMapping("/admin/files/page")
    public ApiResponse<Map<String, Object>> adminFilesPage(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) Long cursor,
            @RequestParam(name = "limit", defaultValue = "20") int limit
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) {
            return denied;
        }
        if (cursor != null && cursor < 0) {
            return ApiResponse.fail("cursor must not be negative");
        }
        int size = Math.min(Math.max(limit, 1), ADMIN_FILE_PAGE_MAX);
        List<KnowledgeFileEntity> found = knowledgeStore.pageAdminFiles(
                new KnowledgeStore.AdminFileQuery(keyword, status, cursor, size + 1));
        boolean hasMore = found.size() > size;
        List<KnowledgeFileEntity> page = hasMore ? found.subList(0, size) : found;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", toViews(page, LocalAuth.userId(authorization)));
        result.put("nextCursor", page.isEmpty() ? null : page.get(page.size() - 1).getId());
        result.put("hasMore", hasMore);
        // Counting can touch most of the table, so only a first page that is full pays for it: a short first
        // page already is the whole result, and later pages send null.
        Long total = null;
        if (cursor == null) {
            total = hasMore ? knowledgeStore.countAdminFiles(new KnowledgeStore.AdminFileQuery(keyword, status, null, size))
                    : page.size();
        }
        result.put("total", total);
        return ApiResponse.ok(result);
    }


    private static final int ADMIN_REPORT_PAGE_MAX = 100;

    /** One page of the report queue; replaces downloading every report for the moderation tab. */
    @GetMapping("/admin/reports/page")
    public ApiResponse<Map<String, Object>> adminReportsPage(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) Long cursor,
            @RequestParam(name = "limit", defaultValue = "20") int limit
    ) {
        ApiResponse<Map<String, Object>> denied = LocalAuth.requireAdmin(authorization);
        if (denied != null) return denied;
        if (cursor != null && cursor < 0) return ApiResponse.fail("cursor must not be negative");
        int size = Math.min(Math.max(limit, 1), ADMIN_REPORT_PAGE_MAX);
        List<Map<String, Object>> found = knowledgeStore.pageAdminReports(new KnowledgeStore.AdminReportQuery(keyword, status, cursor, size + 1));
        boolean hasMore = found.size() > size;
        List<Map<String, Object>> page = hasMore ? found.subList(0, size) : found;
        Long total = null;
        if (cursor == null) {
            total = hasMore ? knowledgeStore.countAdminReports(new KnowledgeStore.AdminReportQuery(keyword, status, null, size)) : page.size();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", page);
        result.put("nextCursor", page.isEmpty() ? null : page.get(page.size() - 1).get("id"));
        result.put("hasMore", hasMore);
        result.put("total", total);
        return ApiResponse.ok(result);
    }


    private static final int FILE_LOOKUP_MAX = 200;

    /**
     * Titles for a set of file ids, so the AI source picker can label the files an admin already chose without
     * downloading the whole library. Ids that no longer exist are left out.
     */
    @GetMapping("/admin/files/by-ids")
    public ApiResponse<List<Map<String, Object>>> adminFilesByIds(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(name = "ids", defaultValue = "") String ids
    ) {
        if (!LocalAuth.isAdmin(authorization)) return ApiResponse.fail("admin authorization is required");
        java.util.LinkedHashSet<Long> wanted = new java.util.LinkedHashSet<>();
        for (String part : ids.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) continue;
            try {
                long id = Long.parseLong(value);
                if (id <= 0) return ApiResponse.fail("invalid file id");
                wanted.add(id);
            } catch (NumberFormatException error) {
                return ApiResponse.fail("invalid file id");
            }
        }
        if (wanted.size() > FILE_LOOKUP_MAX) return ApiResponse.fail("too many file ids");
        return ApiResponse.ok(knowledgeStore.findFiles(wanted).stream().map(file -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", file.getId());
            view.put("title", file.getTitle());
            view.put("auditStatus", file.getAuditStatus());
            view.put("fileType", file.getFileType());
            return view;
        }).toList());
    }

}
