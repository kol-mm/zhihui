package com.aiknowledge.knowledge.controller;

import com.aiknowledge.common.ApiResponse;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.knowledge.search.LocalFullTextSearchService;
import com.aiknowledge.knowledge.storage.DocumentTextExtractor;
import com.aiknowledge.knowledge.storage.LocalFileStorageService;
import com.aiknowledge.knowledge.store.InMemoryKnowledgeStore;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reader should be able to start on the first pages of a large document. Without ranges the whole file has to
 * arrive before anything is drawn, which is what made opening a resource so slow.
 */
class KnowledgeRangeTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/knowledge-range-" + System.nanoTime());
    }

    private static final String MEMBER = "Bearer " + LocalAuth.issueToken("demo", 1L, "USER");
    private static final byte[] BODY = "0123456789abcdefghijklmnopqrstuvwxyz".repeat(64).getBytes(StandardCharsets.UTF_8);

    private final InMemoryKnowledgeStore store = new InMemoryKnowledgeStore();
    private final KnowledgeController controller = new KnowledgeController(
            store,
            new LocalFileStorageService("target/test-uploads-range", "local", "http://127.0.0.1:9000", "ai-knowledge"),
            new LocalFullTextSearchService("local", "http://127.0.0.1:9200", "ai-knowledge"),
            new DocumentTextExtractor());

    private long storedFileId() {
        ApiResponse<Map<String, Object>> uploaded = controller.uploadFile(
                MEMBER, new MockMultipartFile("file", "handbook.txt", "text/plain", BODY), "手册", null, null);
        assertEquals(0, uploaded.code(), uploaded.message());
        return ((Number) uploaded.data().get("id")).longValue();
    }

    private byte[] bytesOf(ResponseEntity<Resource> response) {
        try (InputStream stream = response.getBody().getInputStream()) {
            ByteArrayOutputStream collected = new ByteArrayOutputStream();
            stream.transferTo(collected);
            return collected.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("could not read the body", error);
        }
    }

    @Test
    void aWholeFileIsStillServedAndSaysThatRangesAreAvailable() {
        ResponseEntity<Resource> response = controller.filePreview(MEMBER, null, storedFileId());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("bytes", response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES));
        assertEquals(BODY.length, response.getHeaders().getContentLength());
        assertArrayEquals(BODY, bytesOf(response));
    }

    @Test
    void theFirstPagesCanBeFetchedWithoutTheRest() {
        ResponseEntity<Resource> response = controller.filePreview(MEMBER, "bytes=0-99", storedFileId());

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals("bytes 0-99/" + BODY.length, response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals(100, response.getHeaders().getContentLength());
        byte[] expected = new byte[100];
        System.arraycopy(BODY, 0, expected, 0, 100);
        assertArrayEquals(expected, bytesOf(response), "the range must stop where it was asked to");
    }

    @Test
    void aRangeFromTheMiddleReadsFromTheRightPlace() {
        ResponseEntity<Resource> response = controller.filePreview(MEMBER, "bytes=1000-1049", storedFileId());

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        byte[] expected = new byte[50];
        System.arraycopy(BODY, 1000, expected, 0, 50);
        assertArrayEquals(expected, bytesOf(response));
    }

    @Test
    void anOpenEndedRangeRunsToTheEndOfTheFile() {
        long fileId = storedFileId();
        ResponseEntity<Resource> response = controller.filePreview(MEMBER, "bytes=" + (BODY.length - 10) + "-", fileId);

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals(10, response.getHeaders().getContentLength());
        assertEquals("bytes " + (BODY.length - 10) + "-" + (BODY.length - 1) + "/" + BODY.length,
                response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
    }

    @Test
    void aRangeBeyondTheEndIsRefusedRatherThanSilentlyTruncated() {
        ResponseEntity<Resource> response = controller.filePreview(MEMBER, "bytes=999999-1000000", storedFileId());

        assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, response.getStatusCode());
        assertEquals("bytes */" + BODY.length, response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
    }

    @Test
    void headersThisEndpointDoesNotServeFallBackToTheWholeFile() {
        for (String header : new String[]{"pages=1-2", "bytes=abc-def", "bytes=0-10, 20-30", "bytes=", "bytes=-0"}) {
            ResponseEntity<Resource> response = controller.filePreview(MEMBER, header, storedFileId());
            assertEquals(HttpStatus.OK, response.getStatusCode(), header + " should fall back to the whole file");
        }
    }

    @Test
    void aSuffixRangeReturnsTheLastBytes() {
        ResponseEntity<Resource> response = controller.filePreview(MEMBER, "bytes=-20", storedFileId());

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals(20, response.getHeaders().getContentLength());
        byte[] expected = new byte[20];
        System.arraycopy(BODY, BODY.length - 20, expected, 0, 20);
        assertArrayEquals(expected, bytesOf(response));
    }

    @Test
    void rangeParsingHandlesTheShapesAReaderSends() {
        assertEquals(0, KnowledgeController.parseRange("bytes=0-99", 500).start());
        assertEquals(99, KnowledgeController.parseRange("bytes=0-99", 500).end());
        assertEquals(499, KnowledgeController.parseRange("bytes=400-", 500).end());
        assertEquals(480, KnowledgeController.parseRange("bytes=-20", 500).start());
        // Asked for more than there is: clamped to the last byte rather than refused.
        assertEquals(499, KnowledgeController.parseRange("bytes=100-9999", 500).end());
        assertNull(KnowledgeController.parseRange(null, 500));
        assertNull(KnowledgeController.parseRange("bytes=50-10", 500), "an end before the start is not a range");
        assertNotNull(KnowledgeController.parseRange("bytes=600-700", 500));
        assertTrue(KnowledgeController.parseRange("bytes=600-700", 500).start() < 0, "past the end, so unsatisfiable");
    }

    @Test
    void fetchingRangesDoesNotCountAsManyDownloads() {
        long fileId = storedFileId();
        int downloadsBefore = store.find(fileId).orElseThrow().getDownloads();

        controller.fileContent(MEMBER, "bytes=0-99", fileId);
        controller.fileContent(MEMBER, "bytes=100-199", fileId);
        controller.fileContent(MEMBER, "bytes=200-299", fileId);

        assertEquals(downloadsBefore, store.find(fileId).orElseThrow().getDownloads(),
                "one download split into pieces is still one download, not three");

        // A plain request is still a download.
        controller.fileContent(MEMBER, null, fileId);
        assertEquals(downloadsBefore + 1, store.find(fileId).orElseThrow().getDownloads());
    }
}
