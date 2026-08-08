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
import java.util.Optional;
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
        if (documents.isEmpty() && "local".equalsIgnoreCase(mode)) {
            index(1L, "AI Knowledge Platform Design", """
                    AI 知识社区平台采用前后端分离和微服务架构。用户端提供知识上传、全文检索、社区互动、私信通知、个人中心与 AI 问答；管理端提供内容审核、用户治理、工单处理和 AI 配置。

                    本地运行由 Spring Cloud Gateway 统一转发请求，Nacos 负责服务注册与发现。知识、社区、消息和用户服务保留独立边界，AI 服务使用 FastAPI 与 SQLite 保存知识切片、向量和对话历史。

                    默认本地模式不强制依赖外部中间件，文件存储、全文索引、事件总线和向量检索均有本地实现。容器化部署暂不包含在当前版本中。
                    """, "");
            index(101L, "RAG Architecture", """
                    RAG（检索增强生成）先从知识库检索与问题相关的内容片段，再将检索结果作为上下文交给生成模型，从而提高回答的准确性和可追溯性。

                    本项目的本地流程包括：资料上传与解析、正文切片、向量生成、相似度检索、上下文拼接、回答生成和对话历史保存。管理员可以设置单次匹配片段数、数据源范围和回复合规规则。

                    在没有 Milvus 或 ChromaDB 的情况下，系统使用本地向量实现；配置外部向量数据库后，可以替换检索后端而不改变用户端问答流程。
                    """, "");
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

    public Optional<SearchDocument> find(Long fileId) {
        return documents.stream().filter(document -> fileId != null && fileId.equals(document.getFileId())).findFirst();
    }

    public boolean remove(Long fileId) {
        boolean removed = documents.removeIf(document -> fileId != null && fileId.equals(document.getFileId()));
        if (removed) persist();
        return removed;
    }

    public Map<String, Object> status() {
        return Map.of(
                "mode", mode,
                "indexedDocuments", documents.size(),
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
