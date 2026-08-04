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
        controller.view(userAuth, Map.of("fileId", fileId));
        controller.download(userAuth, Map.of("fileId", fileId));

        var ranking = controller.ranking();
        assertFalse(ranking.data().isEmpty());
        Map<String, Object> currentUser = ranking.data().stream()
                .filter(item -> Long.valueOf(1L).equals(((Number) item.get("userId")).longValue()))
                .findFirst().orElseThrow();
        assertTrue(((Number) currentUser.get("uploads")).intValue() >= 1);
        assertTrue(((Number) currentUser.get("views")).intValue() >= 1);
        assertTrue(((Number) currentUser.get("downloads")).intValue() >= 1);
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
    void adminCanResolveKnowledgeReport() {
        controller.report(userAuth, Map.of("userId", 999L, "fileId", 1L, "reason", "review"));
        String auth = "Bearer " + com.aiknowledge.common.LocalAuth.issueToken("admin");
        var reports = controller.adminReports(auth);
        Long reportId = ((Number) reports.data().get(0).get("id")).longValue();
        var resolved = controller.resolveReport(auth, Map.of("reportId", reportId, "status", "RESOLVED", "result", "closed"));
        assertEquals("RESOLVED", resolved.data().get("status"));
    }
}
