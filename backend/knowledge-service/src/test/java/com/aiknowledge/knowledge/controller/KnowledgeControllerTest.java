package com.aiknowledge.knowledge.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.knowledge.search.LocalFullTextSearchService;
import com.aiknowledge.knowledge.storage.LocalFileStorageService;
import com.aiknowledge.knowledge.store.InMemoryKnowledgeStore;
import com.aiknowledge.common.PlatformConfigClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void knowledgePagesByCursorAndFiltersServerSide() {
        // The in-memory store is shared across tests, so every assertion is scoped by this marker.
        String marker = "分页样本";
        String adminAuth = "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("admin", 2L, "ADMIN");
        List<Long> approved = new java.util.ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Long fileId = ((Number) controller.upload(userAuth, Map.of(
                    "title", marker + " " + i, "fileType", i % 2 == 0 ? "pdf" : "txt", "content", "内容 " + i,
                    "categoryId", i % 2 == 0 ? 2L : 1L)).data().get("id")).longValue();
            controller.audit(adminAuth, Map.of("fileId", fileId, "auditStatus", "APPROVED"));
            approved.add(fileId);
        }
        Long pending = ((Number) controller.upload(userAuth, Map.of(
                "title", marker + " 待审", "fileType", "pdf", "content", "待审内容")).data().get("id")).longValue();

        Map<String, Object> first = controller.page(userAuth, null, 2, null, null, marker, false).data();
        assertEquals(List.of(approved.get(4), approved.get(3)), idsOf(first));
        assertEquals(true, first.get("hasMore"));
        assertEquals(5L, ((Number) first.get("total")).longValue());
        assertFalse(idsOf(first).contains(pending));

        Map<String, Object> second = controller.page(userAuth, ((Number) first.get("nextCursor")).longValue(), 2, null, null, marker, false).data();
        assertEquals(List.of(approved.get(2), approved.get(1)), idsOf(second));
        Map<String, Object> third = controller.page(userAuth, ((Number) second.get("nextCursor")).longValue(), 2, null, null, marker, false).data();
        assertEquals(List.of(approved.get(0)), idsOf(third));
        assertEquals(false, third.get("hasMore"));

        Map<String, Object> pdfOnly = controller.page(userAuth, null, 20, null, "pdf", marker, false).data();
        assertEquals(2, itemsOf(pdfOnly).size());
        assertTrue(itemsOf(pdfOnly).stream().allMatch(item -> "pdf".equals(item.get("fileType"))));
        Map<String, Object> byCategory = controller.page(userAuth, null, 20, 1L, null, marker, false).data();
        assertEquals(3, itemsOf(byCategory).size());
        assertTrue(itemsOf(byCategory).stream().allMatch(item -> ((Number) item.get("categoryId")).longValue() == 1L));
        assertEquals(1, itemsOf(controller.page(userAuth, null, 20, null, null, marker + " 3", false).data()).size());

        // Files awaiting audit stay hidden from members even if they ask for everything.
        assertFalse(idsOf(controller.page(userAuth, null, 50, null, null, marker, true).data()).contains(pending));
        assertTrue(idsOf(controller.page(adminAuth, null, 50, null, null, marker, true).data()).contains(pending));
        assertEquals(500, controller.page(userAuth, -1L, 20, null, null, null, false).code());

        Map<String, Object> counts = controller.categoryCounts(userAuth, null, marker, false).data();
        assertEquals(5L, ((Number) counts.get("total")).longValue());
        assertEquals(3L, categoryCount(counts, 1L));
        assertEquals(2L, categoryCount(counts, 2L));
        assertEquals(6L, ((Number) controller.categoryCounts(adminAuth, null, marker, true).data().get("total")).longValue());
    }

    @Test
    void knowledgeActivityPagesByCursorPerType() {
        String ownerAuth = "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("activity-owner", 31L, "USER");
        String adminAuth = "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("admin", 2L, "ADMIN");
        List<Long> uploads = new java.util.ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Long fileId = ((Number) controller.upload(ownerAuth, Map.of(
                    "title", "活动样本 " + i, "fileType", "txt", "content", "内容 " + i)).data().get("id")).longValue();
            controller.audit(adminAuth, Map.of("fileId", fileId, "auditStatus", "APPROVED"));
            uploads.add(fileId);
        }

        Map<String, Object> first = controller.minePage(ownerAuth, "UPLOADED", null, null, 2).data();
        assertEquals(List.of(uploads.get(4), uploads.get(3)), idsOf(first));
        assertEquals(true, first.get("hasMore"));
        assertEquals(5L, ((Number) first.get("total")).longValue());
        Map<String, Object> second = controller.minePage(ownerAuth, "UPLOADED", null, ((Number) first.get("nextCursor")).longValue(), 2).data();
        assertEquals(List.of(uploads.get(2), uploads.get(1)), idsOf(second));
        Map<String, Object> third = controller.minePage(ownerAuth, "UPLOADED", null, ((Number) second.get("nextCursor")).longValue(), 2).data();
        assertEquals(List.of(uploads.get(0)), idsOf(third));
        assertEquals(false, third.get("hasMore"));

        controller.collect(ownerAuth, Map.of("fileId", uploads.get(0)));
        controller.collect(ownerAuth, Map.of("fileId", uploads.get(2)));
        Map<String, Object> collected = controller.minePage(ownerAuth, "COLLECTED", null, null, 1).data();
        assertEquals(1, itemsOf(collected).size());
        assertEquals(2L, ((Number) collected.get("total")).longValue());
        assertEquals(true, collected.get("hasMore"));
        List<Long> collectedIds = new java.util.ArrayList<>(idsOf(collected));
        collectedIds.addAll(idsOf(controller.minePage(ownerAuth, "COLLECTED", null, ((Number) collected.get("nextCursor")).longValue(), 5).data()));
        assertEquals(2, new java.util.HashSet<>(collectedIds).size());
        assertTrue(collectedIds.containsAll(List.of(uploads.get(0), uploads.get(2))));

        assertEquals(0, itemsOf(controller.minePage(ownerAuth, "LIKED", null, null, 10).data()).size());
        assertEquals(500, controller.minePage(ownerAuth, "SOMETHING", null, null, 10).code());
        assertEquals(500, controller.minePage(ownerAuth, "UPLOADED", null, -1L, 10).code());
        assertEquals(500, controller.minePage(ownerAuth, "UPLOADED", 99L, null, 10).code());
        assertEquals(0, controller.minePage(adminAuth, "UPLOADED", 31L, null, 10).code());
    }

    @Test
    void listViewsCarryBatchedLikeAndCollectState() {
        Long fileId = ((Number) controller.upload(userAuth, Map.of(
                "title", "批量状态样本", "fileType", "txt", "content", "内容")).data().get("id")).longValue();
        String adminAuth = "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("admin", 2L, "ADMIN");
        controller.audit(adminAuth, Map.of("fileId", fileId, "auditStatus", "APPROVED"));
        controller.like(userAuth, Map.of("fileId", fileId));
        controller.collect(userAuth, Map.of("fileId", fileId));

        Map<String, Object> mine = itemsOf(controller.page(userAuth, null, 50, null, null, "批量状态样本", false).data()).get(0);
        assertEquals(1, ((Number) mine.get("likes")).intValue());
        assertEquals(true, mine.get("liked"));
        assertEquals(true, mine.get("collected"));

        String otherAuth = "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("other", 9L, "USER");
        Map<String, Object> theirs = itemsOf(controller.page(otherAuth, null, 50, null, null, "批量状态样本", false).data()).get(0);
        assertEquals(1, ((Number) theirs.get("likes")).intValue());
        assertEquals(false, theirs.get("liked"));
        assertEquals(false, theirs.get("collected"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private static List<Long> idsOf(Map<String, Object> page) {
        return itemsOf(page).stream().map(item -> ((Number) item.get("id")).longValue()).toList();
    }

    @SuppressWarnings("unchecked")
    private static long categoryCount(Map<String, Object> counts, long categoryId) {
        return ((List<Map<String, Object>>) counts.get("counts")).stream()
                .filter(entry -> ((Number) entry.get("categoryId")).longValue() == categoryId)
                .mapToLong(entry -> ((Number) entry.get("count")).longValue())
                .findFirst().orElse(0);
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
    void approvedKnowledgeCannotBeApprovedAgain() {
        var uploaded = controller.upload(userAuth, Map.of(
                "title", "Review once",
                "fileType", "txt",
                "content", "review state"
        ));
        Long fileId = ((Number) uploaded.data().get("id")).longValue();
        String adminAuth = "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("admin");

        assertEquals(0, controller.audit(adminAuth, Map.of("fileId", fileId, "auditStatus", "APPROVED")).code());
        var repeated = controller.audit(adminAuth, Map.of("fileId", fileId, "auditStatus", "APPROVED"));

        assertTrue(repeated.code() != 0);
        assertEquals("该知识资源已经通过审核", repeated.message());
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
    void userKnowledgeUploadAndRankingCanBeDisabled() {
        PlatformConfigClient config = mock(PlatformConfigClient.class);
        when(config.enabled("knowledge_upload_enabled", true)).thenReturn(false);
        when(config.enabled("user_ranking_enabled", true)).thenReturn(false);
        KnowledgeController restricted = new KnowledgeController(
                new InMemoryKnowledgeStore(),
                new LocalFileStorageService("target/test-uploads", "local", "http://127.0.0.1:9000", "ai-knowledge"),
                new LocalFullTextSearchService("local", "http://127.0.0.1:9200", "ai-knowledge"),
                new com.aiknowledge.knowledge.storage.DocumentTextExtractor(), config
        );

        assertEquals(500, restricted.upload(userAuth, Map.of("title", "Blocked", "fileType", "txt")).code());
        assertTrue(restricted.ranking().data().isEmpty());
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
    void previewUsesInlineContentWithoutIncreasingDownloadCount() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            document.save(output);
        }
        var multipart = new org.springframework.mock.web.MockMultipartFile(
                "file", "preview.pdf", "application/pdf", output.toByteArray());
        var uploaded = controller.uploadFile(userAuth, multipart, "Preview only", null);
        Long fileId = ((Number) uploaded.data().get("id")).longValue();

        var preview = controller.filePreview(userAuth, fileId);
        assertEquals(200, preview.getStatusCode().value());
        assertEquals("application/pdf", preview.getHeaders().getContentType().toString());
        assertTrue(preview.getHeaders().getFirst("Content-Disposition").startsWith("inline"));
        assertEquals(0, ((Number) controller.adminPreview(
                "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("admin"), fileId
        ).data().get("downloads")).intValue());

        controller.fileContent(userAuth, fileId);
        assertEquals(1, ((Number) controller.adminPreview(
                "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("admin"), fileId
        ).data().get("downloads")).intValue());
    }

    @Test
    void docxEmbeddedImageIsRenderedInDocumentOrderAndDeletedWithKnowledge() throws Exception {
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            var paragraph = document.createParagraph();
            paragraph.createRun().setText("图片之前");
            paragraph.createRun().addPicture(new ByteArrayInputStream(png),
                    org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG,
                    "preview.png", org.apache.poi.util.Units.toEMU(80), org.apache.poi.util.Units.toEMU(80));
            paragraph.createRun().setText("图片之后");
            document.write(output);
        }

        var multipart = new org.springframework.mock.web.MockMultipartFile(
                "file", "illustrated.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", output.toByteArray());
        var uploaded = controller.uploadFile(userAuth, multipart, "图文知识", null);

        assertEquals(0, uploaded.code());
        Long fileId = ((Number) uploaded.data().get("id")).longValue();
        String adminAuth = "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("admin", 2L, "ADMIN");
        var preview = controller.adminPreview(adminAuth, fileId);
        assertEquals(0, preview.code());

        @SuppressWarnings("unchecked")
        List<LocalFullTextSearchService.ContentBlock> blocks =
                (List<LocalFullTextSearchService.ContentBlock>) preview.data().get("contentBlocks");
        assertEquals(3, blocks.size());
        assertEquals("图片之前", blocks.get(0).getText());
        assertEquals("image", blocks.get(1).getType());
        assertEquals("图片之后", blocks.get(2).getText());
        String imageUrl = blocks.get(1).getUrl();
        assertEquals(200, controller.media(imageUrl.substring(imageUrl.lastIndexOf('/') + 1)).getStatusCode().value());

        var deleted = controller.deleteFile(userAuth, Map.of("fileId", fileId));
        assertEquals(0, deleted.code());
        assertEquals(1, ((Number) deleted.data().get("mediaRemoved")).intValue());
    }

    @Test
    void docxTablesArePreservedAsPreviewBlocks() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            var table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("项目");
            table.getRow(0).getCell(1).setText("状态");
            table.getRow(1).getCell(0).setText("移动端阅读");
            table.getRow(1).getCell(1).setText("完成");
            document.write(output);
        }
        var multipart = new org.springframework.mock.web.MockMultipartFile(
                "file", "table.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", output.toByteArray());
        var uploaded = controller.uploadFile(userAuth, multipart, "表格知识", null);
        Long fileId = ((Number) uploaded.data().get("id")).longValue();

        var preview = controller.adminPreview(
                "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("admin"), fileId);
        @SuppressWarnings("unchecked")
        List<LocalFullTextSearchService.ContentBlock> blocks =
                (List<LocalFullTextSearchService.ContentBlock>) preview.data().get("contentBlocks");

        assertEquals(1, blocks.size());
        assertEquals("table", blocks.get(0).getType());
        assertTrue(blocks.get(0).getText().contains("移动端阅读\t完成"));
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
        assertFalse(status.data().containsKey("elasticsearchEndpoint"));
        assertFalse(status.data().containsKey("elasticsearchIndex"));

        ApiResponse<Map<String, Object>> storageStatus = controller.storageStatus();
        assertFalse(storageStatus.data().containsKey("localRoot"));
        assertFalse(storageStatus.data().containsKey("minioEndpoint"));
        assertFalse(storageStatus.data().containsKey("minioBucket"));
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

    @Test
    void adminAnalyticsAggregatesApprovedFilesAndFillsTheTrend() {
        String adminAuth = "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("admin", 2L, "ADMIN");
        // The in-memory store carries data from the other tests, so every total is asserted as a delta.
        Map<String, Object> before = controller.adminAnalytics(adminAuth, 30).data();
        long baseFiles = number(before.get("files"));
        long baseViews = number(before.get("views"));
        long baseLikes = number(before.get("likes"));

        String title = "统计样本 " + System.nanoTime();
        Long fileId = ((Number) controller.upload(userAuth, Map.of(
                "title", title, "fileType", "pdf", "content", "统计内容")).data().get("id")).longValue();
        controller.audit(adminAuth, Map.of("fileId", fileId, "auditStatus", "APPROVED"));
        controller.view(userAuth, Map.of("fileId", fileId));
        controller.like(userAuth, Map.of("fileId", fileId));

        Map<String, Object> after = controller.adminAnalytics(adminAuth, 30).data();
        assertEquals(baseFiles + 1, number(after.get("files")));
        assertEquals(baseViews + 1, number(after.get("views")));
        assertEquals(baseLikes + 1, number(after.get("likes")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trend = (List<Map<String, Object>>) after.get("trend");
        assertEquals(14, trend.size());
        assertEquals(14, number(after.get("trendDays")));
        assertEquals(com.aiknowledge.common.AppTime.today().toString(), trend.get(13).get("date"));
        assertEquals(com.aiknowledge.common.AppTime.today().minusDays(13).toString(), trend.get(0).get("date"));
        assertTrue(number(trend.get(13).get("count")) >= 1);
        // A shorter period asks for fewer bars but still one per day.
        assertEquals(7, ((List<?>) controller.adminAnalytics(adminAuth, 7).data().get("trend")).size());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> top = (List<Map<String, Object>>) after.get("top");
        assertTrue(top.size() <= 5);
        assertTrue(top.stream().anyMatch(item -> title.equals(item.get("title"))));
        long previous = Long.MAX_VALUE;
        for (Map<String, Object> item : top) {
            long score = number(item.get("views")) + number(item.get("downloads")) * 2 + number(item.get("likes")) * 2;
            assertTrue(score <= previous, "ranking is not sorted by engagement");
            previous = score;
        }

        // The ranking is kept for a minute: more views do not show up yet, the period's totals do.
        Map<String, Object> ranked = top.stream().filter(item -> title.equals(item.get("title"))).findFirst().orElseThrow();
        controller.view(userAuth, Map.of("fileId", fileId));
        Map<String, Object> cached = controller.adminAnalytics(adminAuth, 7).data();
        assertEquals(baseViews + 2, number(controller.adminAnalytics(adminAuth, 30).data().get("views")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cachedTop = (List<Map<String, Object>>) cached.get("top");
        assertEquals(number(ranked.get("views")), number(cachedTop.stream()
                .filter(item -> title.equals(item.get("title"))).findFirst().orElseThrow().get("views")));

        // Taking a file down drops it from the ranking at once.
        controller.updateFileMetadata(adminAuth, Map.of("fileId", fileId, "auditStatus", "HIDDEN"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> afterHide = (List<Map<String, Object>>) controller.adminAnalytics(adminAuth, 30).data().get("top");
        assertTrue(afterHide.stream().noneMatch(item -> fileId.equals(((Number) item.get("id")).longValue())));

        assertEquals(500, controller.adminAnalytics(userAuth, 30).code());
    }

    private static long number(Object value) {
        return value instanceof Number found ? found.longValue() : 0L;
    }


    @Test
    void adminOverviewCountsFilesWithoutReadingThemAll() {
        String adminAuth = "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("admin", 2L, "ADMIN");
        Map<String, Object> before = controller.adminOverview(adminAuth).data();
        long baseFiles = number(before.get("totalFiles"));
        long basePending = number(before.get("pendingAudit"));
        long baseViews = number(before.get("totalViews"));
        long baseReports = number(before.get("reports"));

        Long approved = ((Number) controller.upload(userAuth, Map.of(
                "title", "概览样本 " + System.nanoTime(), "fileType", "pdf", "content", "概览内容")).data().get("id")).longValue();
        controller.audit(adminAuth, Map.of("fileId", approved, "auditStatus", "APPROVED"));
        controller.upload(userAuth, Map.of(
                "title", "概览待审 " + System.nanoTime(), "fileType", "pdf", "content", "待审内容"));
        controller.view(userAuth, Map.of("fileId", approved));
        controller.report(userAuth, Map.of("fileId", approved, "reason", "概览举报"));

        Map<String, Object> after = controller.adminOverview(adminAuth).data();
        assertEquals(baseFiles + 2, number(after.get("totalFiles")));
        assertEquals(basePending + 1, number(after.get("pendingAudit")));
        assertEquals(baseViews + 1, number(after.get("totalViews")));
        assertEquals(baseReports + 1, number(after.get("reports")));
        assertEquals(500, controller.adminOverview(userAuth).code());
    }


    @Test
    void adminFilePageCoversEveryAuditStateAndCountsOpenReports() {
        String adminAuth = "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("admin", 2L, "ADMIN");
        String marker = "审核队列" + System.nanoTime();
        List<Long> created = new java.util.ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            created.add(((Number) controller.upload(userAuth, Map.of(
                    "title", marker + " " + i, "fileType", i == 3 ? "md" : "pdf", "content", "队列内容 " + i)).data().get("id")).longValue());
        }
        controller.audit(adminAuth, Map.of("fileId", created.get(0), "auditStatus", "APPROVED"));
        controller.audit(adminAuth, Map.of("fileId", created.get(1), "auditStatus", "REJECTED"));

        // Newest first and across every audit state, unlike the member-facing library.
        Map<String, Object> first = controller.adminFilesPage(adminAuth, marker, null, null, 2).data();
        assertEquals(List.of(created.get(2), created.get(1)), idsOf(first));
        assertEquals(true, first.get("hasMore"));
        assertEquals(3L, ((Number) first.get("total")).longValue());
        Map<String, Object> second = controller.adminFilesPage(
                adminAuth, marker, null, ((Number) first.get("nextCursor")).longValue(), 2).data();
        assertEquals(List.of(created.get(0)), idsOf(second));
        assertEquals(false, second.get("hasMore"));
        assertEquals(null, second.get("total"));

        assertEquals(List.of(created.get(2)), idsOf(controller.adminFilesPage(adminAuth, marker, "PENDING", null, 20).data()));
        assertEquals(List.of(created.get(1)), idsOf(controller.adminFilesPage(adminAuth, marker, "REJECTED", null, 20).data()));
        assertEquals(List.of(created.get(0)), idsOf(controller.adminFilesPage(adminAuth, marker, "APPROVED", null, 20).data()));
        assertEquals(List.of(created.get(2)), idsOf(controller.adminFilesPage(adminAuth, marker + " 3", null, null, 20).data()));
        assertEquals(500, controller.adminFilesPage(userAuth, marker, null, null, 20).code());
        assertEquals(500, controller.adminFilesPage(adminAuth, null, null, -1L, 20).code());

        long openBefore = number(controller.adminOverview(adminAuth).data().get("openReports"));
        controller.report(userAuth, Map.of("fileId", created.get(0), "reason", "队列举报"));
        assertEquals(openBefore + 1, number(controller.adminOverview(adminAuth).data().get("openReports")));
        Long reportId = controller.adminReports(adminAuth).data().stream()
                .filter(report -> created.get(0).equals(((Number) report.get("fileId")).longValue()))
                .map(report -> ((Number) report.get("id")).longValue())
                .findFirst().orElseThrow();
        controller.resolveReport(adminAuth, Map.of("reportId", reportId, "status", "RESOLVED", "result", "已处理"));
        assertEquals(openBefore, number(controller.adminOverview(adminAuth).data().get("openReports")));
    }


    @Test
    void reportQueuePagesNewestFirstWithStatusAndKeyword() {
        String adminAuth = "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("admin", 2L, "ADMIN");
        String marker = "举报队列" + System.nanoTime();
        Long fileId = ((Number) controller.upload(userAuth, Map.of(
                "title", marker, "fileType", "txt", "content", "被举报的内容")).data().get("id")).longValue();
        for (int i = 1; i <= 3; i++) {
            controller.report(userAuth, Map.of("fileId", fileId, "reason", marker + " 原因 " + i));
        }

        Map<String, Object> first = controller.adminReportsPage(adminAuth, marker, null, null, 2).data();
        List<Long> firstIds = idsOf(first);
        assertEquals(2, firstIds.size());
        assertTrue(firstIds.get(0) > firstIds.get(1));
        assertEquals(true, first.get("hasMore"));
        assertEquals(3L, number(first.get("total")));
        Map<String, Object> second = controller.adminReportsPage(
                adminAuth, marker, null, ((Number) first.get("nextCursor")).longValue(), 2).data();
        List<Long> secondIds = idsOf(second);
        assertEquals(1, secondIds.size());
        assertTrue(secondIds.get(0) < firstIds.get(1));
        assertEquals(null, second.get("total"));

        controller.resolveReport(adminAuth, Map.of("reportId", firstIds.get(0), "status", "RESOLVED", "result", "已处理"));
        assertEquals(List.of(firstIds.get(0)), idsOf(controller.adminReportsPage(adminAuth, marker, "RESOLVED", null, 20).data()));
        assertEquals(2L, number(controller.adminReportsPage(adminAuth, marker, "PENDING", null, 20).data().get("total")));
        // The reported file id is searchable too.
        assertEquals(3, idsOf(controller.adminReportsPage(adminAuth, String.valueOf(fileId), null, null, 50).data()).stream()
                .filter(id -> firstIds.contains(id) || secondIds.contains(id)).count());

        assertEquals(500, controller.adminReportsPage(userAuth, marker, null, null, 20).code());
        assertEquals(500, controller.adminReportsPage(adminAuth, marker, null, -1L, 20).code());
    }


    @Test
    void adminCanLookUpFileTitlesById() {
        String adminAuth = "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("admin", 2L, "ADMIN");
        Long first = ((Number) controller.upload(userAuth, Map.of("title", "来源甲", "fileType", "txt", "content", "甲")).data().get("id")).longValue();
        Long second = ((Number) controller.upload(userAuth, Map.of("title", "来源乙", "fileType", "txt", "content", "乙")).data().get("id")).longValue();

        List<Map<String, Object>> found = controller.adminFilesByIds(adminAuth, first + ", " + second + ",987654321").data();
        assertEquals(java.util.Set.of(first, second),
                found.stream().map(item -> ((Number) item.get("id")).longValue()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(found.stream().anyMatch(item -> "来源甲".equals(item.get("title"))));
        assertTrue(controller.adminFilesByIds(adminAuth, "").data().isEmpty());

        assertEquals(500, controller.adminFilesByIds(adminAuth, "1,abc").code());
        assertEquals(500, controller.adminFilesByIds(adminAuth, "0").code());
        String tooMany = java.util.stream.IntStream.rangeClosed(1, 201).mapToObj(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        assertEquals(500, controller.adminFilesByIds(adminAuth, tooMany).code());
        assertEquals(500, controller.adminFilesByIds(userAuth, String.valueOf(first)).code());
    }

}
