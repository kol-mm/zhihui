package com.aiknowledge.knowledge.search;

import com.aiknowledge.common.LocalJsonStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class LocalFullTextSearchService {
    private final Path storePath = LocalJsonStore.dataFile("search-index.json");
    private final List<SearchDocument> documents = new CopyOnWriteArrayList<>();
    private final String mode;
    private final String elasticEndpoint;
    private final String elasticIndex;

    public LocalFullTextSearchService(
            @Value("${knowledge.search.mode:local}") String mode,
            @Value("${knowledge.search.elasticsearch.endpoint:http://127.0.0.1:9200}") String elasticEndpoint,
            @Value("${knowledge.search.elasticsearch.index:ai-knowledge}") String elasticIndex
    ) {
        this.mode = mode;
        this.elasticEndpoint = elasticEndpoint;
        this.elasticIndex = elasticIndex;
        State state = LocalJsonStore.read(storePath, State.class, new State());
        if (state.documents != null) {
            documents.addAll(state.documents);
        }
    }

    public SearchDocument index(Long fileId, String title, String content, String fileUrl) {
        documents.removeIf(document -> fileId != null && fileId.equals(document.getFileId()));
        SearchDocument document = new SearchDocument();
        document.setFileId(fileId);
        document.setTitle(title == null ? "" : title);
        document.setContent(content == null ? "" : content);
        document.setFileUrl(fileUrl == null ? "" : fileUrl);
        document.setIndexedAt(LocalDateTime.now());
        documents.add(document);
        persist();
        return document;
    }

    public List<Map<String, Object>> search(String keyword) {
        String query = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        return documents.stream()
                .map(document -> result(document, query))
                .filter(result -> query.isBlank() || ((Integer) result.get("score")) > 0)
                .sorted(Comparator.comparing(result -> (Integer) result.get("score"), Comparator.reverseOrder()))
                .toList();
    }

    public Map<String, Object> status() {
        return Map.of(
                "mode", mode,
                "indexedDocuments", documents.size(),
                "elasticsearchEndpoint", elasticEndpoint,
                "elasticsearchIndex", elasticIndex,
                "elasticsearchReady", !"local".equalsIgnoreCase(mode)
        );
    }

    private Map<String, Object> result(SearchDocument document, String query) {
        String title = document.getTitle().toLowerCase(Locale.ROOT);
        String content = document.getContent().toLowerCase(Locale.ROOT);
        int score = query.isBlank() ? 1 : occurrences(title, query) * 3 + occurrences(content, query);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileId", document.getFileId());
        result.put("title", document.getTitle());
        result.put("fileUrl", document.getFileUrl());
        result.put("snippet", snippet(document.getContent(), query));
        result.put("score", score);
        result.put("indexedAt", document.getIndexedAt());
        return result;
    }

    private int occurrences(String value, String query) {
        if (query.isBlank()) { return 0; }
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(query, index)) >= 0) {
            count++;
            index += query.length();
        }
        return count;
    }

    private String snippet(String content, String query) {
        if (content == null || content.isBlank()) { return ""; }
        int start = query.isBlank() ? 0 : Math.max(0, content.toLowerCase(Locale.ROOT).indexOf(query) - 40);
        int end = Math.min(content.length(), start + 160);
        return content.substring(start, end);
    }

    private void persist() {
        State state = new State();
        state.documents = new ArrayList<>(documents);
        LocalJsonStore.write(storePath, state);
    }

    public static class State {
        public List<SearchDocument> documents = new ArrayList<>();
    }

    public static class SearchDocument {
        private Long fileId;
        private String title;
        private String content;
        private String fileUrl;
        private LocalDateTime indexedAt;

        public Long getFileId() { return fileId; }
        public void setFileId(Long fileId) { this.fileId = fileId; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getFileUrl() { return fileUrl; }
        public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }
        public LocalDateTime getIndexedAt() { return indexedAt; }
        public void setIndexedAt(LocalDateTime indexedAt) { this.indexedAt = indexedAt; }
    }
}
