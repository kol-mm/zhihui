package com.aiknowledge.knowledge.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.knowledge.search.LocalFullTextSearchService;
import com.aiknowledge.knowledge.storage.LocalFileStorageService;
import com.aiknowledge.knowledge.store.InMemoryKnowledgeStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeControllerTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/knowledge-" + System.nanoTime());
    }

    private final KnowledgeController controller =
            new KnowledgeController(
                    new InMemoryKnowledgeStore(),
                    new LocalFileStorageService("target/test-uploads", "local", "http://127.0.0.1:9000", "ai-knowledge"),
                    new LocalFullTextSearchService("local", "http://127.0.0.1:9200", "ai-knowledge"),
                    new com.aiknowledge.knowledge.storage.DocumentTextExtractor()
            );
    private final String userAuth = "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("demo");

    @Test
    void uploadedFileCanBeSearched() {
        ApiResponse<Map<String, Object>> upload = controller.upload(userAuth, Map.of(
                "userId", 1L,
                "title", "RAG Architecture",
                "fileType", "pdf"
        ));
        assertEquals(0, upload.code());

        ApiResponse<List<Map<String, Object>>> search = controller.search(userAuth, "RAG");
        assertEquals(0, search.code());
        assertFalse(search.data().isEmpty());
        assertEquals("RAG Architecture", search.data().get(0).get("title"));
    }

    @Test
    void rankingIsCalculatedFromStoredKnowledgeActivity() {
        var uploaded = controller.upload(userAuth, Map.of(
                "title", "Ranking source",
                "fileType", "txt",
                "content", "ranking content"
        ));
        Long fileId = ((Number) uploaded.data().get("id")).longValue();
        String adminAuth = "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("admin");
        controller.audit(adminAuth, Map.of("fileId", fileId, "auditStatus", "APPROVED"));
        controller.view(userAuth, Map.of("fileId", fileId));
        controller.download(userAuth, Map.of("fileId", fileId));
        controller.report(userAuth, Map.of("fileId", fileId, "reason", "ranking violation"));

        var ranking = controller.ranking();
        assertFalse(ranking.data().isEmpty());
        Map<String, Object> currentUser = ranking.data().stream()
                .filter(item -> Long.valueOf(1L).equals(((Number) item.get("userId")).longValue()))
                .findFirst().orElseThrow();
        assertTrue(((Number) currentUser.get("uploads")).intValue() >= 1);
        assertTrue(((Number) currentUser.get("views")).intValue() >= 1);
        assertTrue(((Number) currentUser.get("downloads")).intValue() >= 1);
        assertTrue(((Number) currentUser.get("violations")).intValue() >= 1);
    }

    @Test
    void pendingKnowledgeDoesNotIncreaseContributionUploads() {
        int before = controller.ranking().data().stream()
                .filter(item -> Long.valueOf(1L).equals(((Number) item.get("userId")).longValue()))
                .map(item -> ((Number) item.get("uploads")).intValue())
                .findFirst().orElse(0);

        controller.upload(userAuth, Map.of("title", "Pending contribution", "fileType", "txt", "content", "pending"));

        int after = controller.ranking().data().stream()
                .filter(item -> Long.valueOf(1L).equals(((Number) item.get("userId")).longValue()))
                .map(item -> ((Number) item.get("uploads")).intValue())
                .findFirst().orElse(0);
        assertEquals(before, after);
    }

    @Test
    void textFileCanBeSavedToLocalStorage() {
        ApiResponse<Map<String, Object>> stored = controller.storageUpload(userAuth, Map.of(
                "filename", "rag-note.txt",
                "content", "RAG local storage content",
                "fileType", "txt"
        ));

        assertEquals(0, stored.code());
        assertEquals("local", stored.data().get("storageMode"));
        assertEquals(true, String.valueOf(stored.data().get("fileUrl")).startsWith("storage://"));
    }

    @Test
    void standaloneImagesCannotBeAttachedToKnowledgeBody() {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0};
        var image = new org.springframework.mock.web.MockMultipartFile("files", "cover.png", "image/png", png);
        var uploadedImage = controller.uploadImages(userAuth, List.of(image));
        assertEquals(0, uploadedImage.code());
        String imageUrl = String.valueOf(uploadedImage.data().get("imageUrls") instanceof List<?> urls ? urls.get(0) : "");

        var uploaded = controller.upload(userAuth, Map.of(
                "title", "Illustrated knowledge", "fileType", "md", "content", "# Illustrated body",
                "imageUrls", List.of(imageUrl)));
        assertTrue(uploaded.code() != 0);
        var media = controller.media(imageUrl.substring(imageUrl.lastIndexOf('/') + 1));
        assertEquals(200, media.getStatusCode().value());
        assertEquals("image/png", media.getHeaders().getContentType().toString());
    }

    @Test
    void markdownImagesAreRejectedWhenImagesAreEmbedded() {
        var uploaded = controller.upload(userAuth, Map.of(
                "title", "External image markdown", "fileType", "md",
                "content", "![Architecture](https://example.com/architecture.png)"));
        assertTrue(uploaded.code() != 0);
    }

    @Test
    void multipartTextFileIsStoredIndexedAndDownloadable() {
        var multipart = new org.springframework.mock.web.MockMultipartFile(
                "file", "production-guide.md", "text/markdown", "# Production\nUse signed identities.".getBytes());

        var uploaded = controller.uploadFile(userAuth, multipart, "Production guide", null);

        assertEquals(0, uploaded.code());
        Long fileId = ((Number) uploaded.data().get("id")).longValue();
        assertEquals("INDEXED", uploaded.data().get("parseStatus"));
        var download = controller.fileContent(userAuth, fileId);
        assertEquals(200, download.getStatusCode().value());
        assertTrue(new String(download.getBody()).contains("signed identities"));
    }

    @Test
    void adminCanManageKnowledgeCategories() {
        String adminAuth = "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("admin");
        var created = controller.saveCategory(adminAuth, Map.of("name", "Engineering", "sortNo", 5));
        assertEquals(0, created.code());
        Long categoryId = ((Number) created.data().get("id")).longValue();
        assertTrue(controller.categories().data().stream().anyMatch(item -> categoryId.equals(item.get("id"))));
        assertEquals(500, controller.saveCategory(userAuth, Map.of("name", "Denied")).code());
        assertEquals(true, controller.deleteCategory(adminAuth, Map.of("categoryId", categoryId)).data().get("removed"));
    }

    @Test
    void adminCanEditAndTakeKnowledgeOffline() {
        var uploaded = controller.upload(userAuth, Map.of(
                "title", "Original title", "fileType", "txt", "content", "metadata content"));
        Long fileId = ((Number) uploaded.data().get("id")).longValue();
        String adminAuth = "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("admin");
        var category = controller.saveCategory(adminAuth, Map.of("name", "Operations", "sortNo", 7));
        Long categoryId = ((Number) category.data().get("id")).longValue();

        var updated = controller.updateFileMetadata(adminAuth, Map.of(
                "fileId", fileId,
                "title", "Updated title",
                "categoryId", categoryId,
                "auditStatus", "APPROVED"
        ));
        assertEquals(0, updated.code());
        assertEquals("Updated title", updated.data().get("title"));
        assertEquals(categoryId, updated.data().get("categoryId"));
        assertFalse(controller.search(userAuth, "Updated title").data().isEmpty());

        assertEquals("HIDDEN", controller.updateFileMetadata(adminAuth, Map.of(
                "fileId", fileId, "auditStatus", "HIDDEN")).data().get("auditStatus"));
        assertTrue(controller.list(userAuth, false).data().stream().noneMatch(item -> fileId.equals(item.get("id"))));
        assertEquals(500, controller.updateFileMetadata(userAuth, Map.of("fileId", fileId, "title", "Denied")).code());
    }

    @Test
    void uploadedContentCanBeFoundByFullTextSearch() {
        ApiResponse<Map<String, Object>> upload = controller.upload(userAuth, Map.of(
                "userId", 1L,
                "title", "Vector Search Guide",
                "fileType", "txt",
                "content", "Elasticsearch-compatible local indexing supports semantic preparation."
        ));
        assertEquals(0, upload.code());

        ApiResponse<List<Map<String, Object>>> search = controller.fullTextSearch(userAuth, "Elasticsearch");
        assertEquals(0, search.code());
        assertFalse(search.data().isEmpty());
        assertEquals("Vector Search Guide", search.data().get(0).get("title"));

        ApiResponse<Map<String, Object>> status = controller.searchStatus();
        assertEquals("local", status.data().get("mode"));
        assertEquals(false, status.data().get("elasticsearchReady"));
    }

    @Test
    void readingKnowledgeReturnsItsBody() {
        var uploaded = controller.upload(userAuth, Map.of(
                "userId", 1L,
                "title", "Readable guide",
                "fileType", "txt",
                "content", "This is the complete readable body."
        ));
        Long fileId = ((Number) uploaded.data().get("id")).longValue();

        var detail = controller.view(userAuth, Map.of("fileId", fileId));

        assertEquals(0, detail.code());
        assertTrue(String.valueOf(detail.data().get("content")).contains("complete readable body"));
    }

    @Test
    void personalKnowledgeHistoryTracksForwardedResource() {
        var uploaded = controller.upload(userAuth, Map.of(
                "title", "Personal history guide", "fileType", "txt", "content", "history body"));
        Long fileId = ((Number) uploaded.data().get("id")).longValue();
        assertEquals(0, controller.forward(userAuth, Map.of("fileId", fileId)).code());
        assertTrue(controller.mine(userAuth, "UPLOADED", null).data().stream().anyMatch(item -> fileId.equals(item.get("id"))));
        assertTrue(controller.mine(userAuth, "FORWARDED", null).data().stream().anyMatch(item -> fileId.equals(item.get("id"))));
    }

    @Test
    void knowledgeCollectionTogglesAndIsUserSpecific() {
        var uploaded = controller.upload(userAuth, Map.of(
                "title", "Collection toggle guide", "fileType", "txt", "content", "collection body"));
        Long fileId = ((Number) uploaded.data().get("id")).longValue();

        var first = controller.collect(userAuth, Map.of("fileId", fileId));
        assertEquals(true, first.data().get("collected"));
        assertEquals(true, controller.view(userAuth, Map.of("fileId", fileId)).data().get("collected"));
        assertTrue(controller.mine(userAuth, "COLLECTED", null).data().stream()
                .anyMatch(item -> fileId.equals(item.get("id"))));

        String adminAuth = "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("admin");
        assertEquals(0, controller.audit(adminAuth, Map.of("fileId", fileId, "auditStatus", "APPROVED")).code());
        String otherAuth = "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("other-collector", 3L, "USER");
        assertEquals(false, controller.view(otherAuth, Map.of("fileId", fileId)).data().get("collected"));
        var second = controller.collect(userAuth, Map.of("fileId", fileId));
        assertEquals(false, second.data().get("collected"));
        assertFalse(controller.mine(userAuth, "COLLECTED", null).data().stream()
                .anyMatch(item -> fileId.equals(item.get("id"))));
    }

    @Test
    void publicListHidesPendingKnowledgeButAdminCanIncludeIt() {
        var uploaded = controller.upload(userAuth, Map.of(
                "userId", 1L,
                "title", "Pending private draft",
                "fileType", "txt",
                "content", "This content is waiting for review."
        ));
        Long fileId = ((Number) uploaded.data().get("id")).longValue();

        var publicList = controller.list(null, false);
        assertFalse(publicList.data().stream().anyMatch(file -> fileId.equals(file.get("id"))));

        String otherAuth = "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("other", 3L, "USER");
        var hiddenDetail = controller.view(otherAuth, Map.of("fileId", fileId));
        assertEquals(500, hiddenDetail.code());
        assertTrue(controller.fullTextSearch(otherAuth, "waiting for review").data().isEmpty());

        String adminAuth = "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("admin");
        var adminList = controller.list(adminAuth, true);
        assertTrue(adminList.data().stream().anyMatch(file -> fileId.equals(file.get("id"))));
    }

    @Test
    void adminCanPreviewPendingKnowledgeWithoutIncreasingViews() {
        var uploaded = controller.upload(userAuth, Map.of(
                "title", "Pending review body",
                "fileType", "txt",
                "content", "Administrators must read this complete body before approval."
        ));
        Long fileId = ((Number) uploaded.data().get("id")).longValue();
        String adminAuth = "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("admin");

        var preview = controller.adminPreview(adminAuth, fileId);

        assertEquals(0, preview.code());
        assertTrue(String.valueOf(preview.data().get("content")).contains("complete body"));
        assertEquals(0, ((Number) preview.data().get("views")).intValue());
        assertEquals(500, controller.adminPreview(userAuth, fileId).code());
    }

    @Test
    void adminCanResolveKnowledgeReport() {
        controller.report(userAuth, Map.of("userId", 999L, "fileId", 1L, "reason", "review"));
        String auth = "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("admin");
        var reports = controller.adminReports(auth);
        Long reportId = ((Number) reports.data().get(0).get("id")).longValue();
        var resolved = controller.resolveReport(auth, Map.of("reportId", reportId, "status", "RESOLVED", "result", "closed"));
        assertEquals("RESOLVED", resolved.data().get("status"));
    }

    @Test
    void knowledgeLikeCanBeToggled() {
        var uploaded = controller.upload(userAuth, Map.of(
                "title", "Like toggle guide", "fileType", "txt", "content", "toggle body"));
        Long fileId = ((Number) uploaded.data().get("id")).longValue();

        var liked = controller.like(userAuth, Map.of("fileId", fileId));
        assertEquals(true, liked.data().get("liked"));
        assertEquals(1, ((Number) liked.data().get("likes")).intValue());
        assertEquals(true, controller.view(userAuth, Map.of("fileId", fileId)).data().get("liked"));

        var unliked = controller.like(userAuth, Map.of("fileId", fileId));
        assertEquals(false, unliked.data().get("liked"));
        assertEquals(0, ((Number) unliked.data().get("likes")).intValue());
    }

    @Test
    void ownerCanDeleteKnowledgeAndItsIndex() {
        var uploaded = controller.upload(userAuth, Map.of(
                "title", "Disposable knowledge", "fileType", "txt", "content", "unique disposable phrase"));
        Long fileId = ((Number) uploaded.data().get("id")).longValue();
        controller.like(userAuth, Map.of("fileId", fileId));
        controller.collect(userAuth, Map.of("fileId", fileId));
        controller.report(userAuth, Map.of("fileId", fileId, "reason", "test"));

        String otherAuth = "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("other-delete", 3L, "USER");
        assertEquals(500, controller.deleteFile(otherAuth, Map.of("fileId", fileId)).code());

        var deleted = controller.deleteFile(userAuth, Map.of("fileId", fileId));
        assertEquals(true, deleted.data().get("removed"));
        assertEquals(true, deleted.data().get("indexRemoved"));
        assertTrue(controller.fullTextSearch(userAuth, "unique disposable phrase").data().isEmpty());
        assertFalse(controller.mine(userAuth, "LIKED", null).data().stream().anyMatch(item -> fileId.equals(item.get("id"))));
        assertEquals(500, controller.view(userAuth, Map.of("fileId", fileId)).code());
    }
}
