package com.aiknowledge.knowledge.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.common.PlatformConfigClient;
import com.aiknowledge.knowledge.search.LocalFullTextSearchService;
import com.aiknowledge.knowledge.storage.DocumentTextExtractor;
import com.aiknowledge.knowledge.storage.LocalFileStorageService;
import com.aiknowledge.knowledge.store.InMemoryKnowledgeStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** PDFs may be far larger than other knowledge files. */
class KnowledgeUploadLimitTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/knowledge-limits-" + System.nanoTime());
    }

    private static final String MEMBER = "Bearer " + LocalAuth.issueToken("demo", 1L, "USER");

    private final PlatformConfigClient config = mock(PlatformConfigClient.class);
    private final KnowledgeController controller = new KnowledgeController(
            new InMemoryKnowledgeStore(),
            new LocalFileStorageService("target/test-uploads-limits", "local", "http://127.0.0.1:9000", "ai-knowledge"),
            new LocalFullTextSearchService("local", "http://127.0.0.1:9200", "ai-knowledge"),
            new DocumentTextExtractor(), config);

    private MockMultipartFile file(String name, int sizeBytes) {
        return new MockMultipartFile("file", name, "application/octet-stream", new byte[sizeBytes]);
    }

    @Test
    void aPdfMayUseThePdfCeilingWhileOtherFilesKeepTheirs() {
        when(config.maxUploadMb()).thenReturn(1);
        when(config.pdfMaxUploadMb()).thenReturn(4);
        when(config.enabled("knowledge_upload_enabled", true)).thenReturn(true);

        ApiResponse<Map<String, Object>> text = controller.uploadFile(MEMBER, file("notes.txt", 2 * 1024 * 1024), "", null, null);
        assertEquals("文件大小不能超过 1 MB", text.message(), "other files keep the general limit");

        // The same size is not refused for a PDF (this stand-in cannot be parsed, so only the size rule is checked).
        ApiResponse<Map<String, Object>> pdf = controller.uploadFile(MEMBER, file("book.pdf", 2 * 1024 * 1024), "大文件", null, null);
        assertNotEquals("文件大小不能超过 1 MB", pdf.message());
        assertNotEquals("文件大小不能超过 4 MB", pdf.message());

        ApiResponse<Map<String, Object>> tooBig = controller.uploadFile(MEMBER, file("book.pdf", 5 * 1024 * 1024), "", null, null);
        assertEquals("文件大小不能超过 4 MB", tooBig.message(), "and PDFs still have a ceiling");
    }

    @Test
    void veryLargeFilesAreStoredWithoutBeingParsed() {
        when(config.maxUploadMb()).thenReturn(200);
        when(config.pdfMaxUploadMb()).thenReturn(200);
        when(config.enabled("knowledge_upload_enabled", true)).thenReturn(true);

        // Above the extraction threshold the bytes go straight to storage, so the heap never holds the document.
        ApiResponse<Map<String, Object>> uploaded = controller.uploadFile(MEMBER,
                file("huge.pdf", 31 * 1024 * 1024), "超大 PDF", null, null);
        assertEquals(0, uploaded.code(), uploaded.message());
        assertEquals("TOO_LARGE", uploaded.data().get("parseStatus"));
        assertEquals(false, uploaded.data().get("textExtracted"));
        assertTrue(String.valueOf(uploaded.data().get("title")).contains("超大 PDF"));
    }
}
