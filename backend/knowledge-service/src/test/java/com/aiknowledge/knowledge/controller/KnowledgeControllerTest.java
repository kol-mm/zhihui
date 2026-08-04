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

class KnowledgeControllerTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/knowledge-" + System.nanoTime());
    }

    private final KnowledgeController controller =
            new KnowledgeController(
                    new InMemoryKnowledgeStore(),
                    new LocalFileStorageService("target/test-uploads", "local", "http://127.0.0.1:9000", "ai-knowledge"),
                    new LocalFullTextSearchService("local", "http://127.0.0.1:9200", "ai-knowledge")
            );

    @Test
    void uploadedFileCanBeSearched() {
        ApiResponse<Map<String, Object>> upload = controller.upload(Map.of(
                "userId", 1L,
                "title", "RAG Architecture",
                "fileType", "pdf"
        ));
        assertEquals(0, upload.code());

        ApiResponse<List<Map<String, Object>>> search = controller.search("RAG");
        assertEquals(0, search.code());
        assertFalse(search.data().isEmpty());
        assertEquals("RAG Architecture", search.data().get(0).get("title"));
    }

    @Test
    void textFileCanBeSavedToLocalStorage() {
        ApiResponse<Map<String, Object>> stored = controller.storageUpload(Map.of(
                "filename", "rag-note.txt",
                "content", "RAG local storage content",
                "fileType", "txt"
        ));

        assertEquals(0, stored.code());
        assertEquals("local", stored.data().get("storageMode"));
        assertEquals(true, String.valueOf(stored.data().get("fileUrl")).startsWith("local-file://"));
        assertEquals(true, Files.exists(Path.of(String.valueOf(stored.data().get("localPath")))));
    }

    @Test
    void uploadedContentCanBeFoundByFullTextSearch() {
        ApiResponse<Map<String, Object>> upload = controller.upload(Map.of(
                "userId", 1L,
                "title", "Vector Search Guide",
                "fileType", "txt",
                "content", "Elasticsearch-compatible local indexing supports semantic preparation."
        ));
        assertEquals(0, upload.code());

        ApiResponse<List<Map<String, Object>>> search = controller.fullTextSearch("Elasticsearch");
        assertEquals(0, search.code());
        assertFalse(search.data().isEmpty());
        assertEquals("Vector Search Guide", search.data().get(0).get("title"));

        ApiResponse<Map<String, Object>> status = controller.searchStatus();
        assertEquals("local", status.data().get("mode"));
        assertEquals(false, status.data().get("elasticsearchReady"));
    }
}
