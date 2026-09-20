package com.aiknowledge.knowledge.controller;

import com.aiknowledge.common.AdminAudit;
import com.aiknowledge.common.AuditEntry;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.knowledge.search.LocalFullTextSearchService;
import com.aiknowledge.knowledge.storage.DocumentTextExtractor;
import com.aiknowledge.knowledge.storage.LocalFileStorageService;
import com.aiknowledge.knowledge.store.InMemoryKnowledgeStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which knowledge actions end up in the administrator action log. */
class KnowledgeAuditTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/knowledge-audit-" + System.nanoTime());
    }

    private static final String MEMBER = "Bearer " + LocalAuth.issueToken("reader", 41L, "USER");
    private static final String ADMIN = "Bearer " + LocalAuth.issueToken("admin", 2L, "ADMIN");

    private final List<AuditEntry> entries = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AdminAudit recorder = (authorization, event) -> {
        AuditEntry entry = AuditEntry.forRequest(authorization, event, "test");
        if (entry != null) entries.add(entry);
    };

    private List<String> actions() {
        return entries.stream().map(AuditEntry::action).toList();
    }

    private final KnowledgeController controller = new KnowledgeController(
            new InMemoryKnowledgeStore(),
            new LocalFileStorageService("target/test-uploads", "local", "http://127.0.0.1:9000", "ai-knowledge"),
            new LocalFullTextSearchService("local", "http://127.0.0.1:9200", "ai-knowledge"),
            new DocumentTextExtractor());

    {
        controller.setAdminAudit(recorder);
    }

    private Long upload(String auth, String title) {
        return ((Number) controller.upload(auth, Map.of("title", title, "fileType", "txt", "content", "正文")).data().get("id")).longValue();
    }

    @Test
    void reviewsEditsAndDeletionsOfOthersAreRecorded() {
        String title = "审计样本 " + System.nanoTime();
        Long fileId = upload(MEMBER, title);
        assertEquals(List.of(), actions(), "a member's own upload is not an admin action");

        controller.audit(ADMIN, Map.of("fileId", fileId, "auditStatus", "REJECTED", "reason", "内容不完整"));
        controller.audit(ADMIN, Map.of("fileId", fileId, "auditStatus", "APPROVED"));
        controller.updateFileMetadata(ADMIN, Map.of("fileId", fileId, "title", title + " 修订"));
        controller.updateFileMetadata(ADMIN, Map.of("fileId", fileId, "title", title + " 修订"));
        controller.report(MEMBER, Map.of("fileId", fileId, "reason", "侵权"));
        Long reportId = reportIdFor(fileId);
        controller.resolveReport(ADMIN, Map.of("reportId", reportId, "status", "RESOLVED", "result", "已下架"));
        controller.deleteFile(ADMIN, Map.of("fileId", fileId));

        assertEquals(List.of("KNOWLEDGE_AUDIT", "KNOWLEDGE_AUDIT", "KNOWLEDGE_EDIT", "KNOWLEDGE_REPORT_RESOLVE", "KNOWLEDGE_DELETE"), actions());
        AuditEntry rejected = entries.get(0);
        assertEquals(41L, rejected.subjectUserId());
        assertEquals("驳回知识资源", rejected.summary());
        assertEquals("内容不完整", rejected.detail().get("reason"));
        assertEquals(List.of(Map.of("field", "auditStatus", "label", "审核状态", "before", "PENDING", "after", "REJECTED")),
                rejected.detail().get("changes"));
        assertEquals("修改了标题", entries.get(2).summary());
        assertEquals(title + " 修订", entries.get(3).targetLabel());
        assertEquals("已下架", entries.get(3).detail().get("result"));
        assertEquals("删除他人上传的知识资源", entries.get(4).summary());
        assertEquals(String.valueOf(fileId), entries.get(4).targetId());
    }

    @Test
    void ownersDeletingTheirOwnFilesAreNotRecorded() {
        Long fileId = upload(ADMIN, "管理员自己的资料 " + System.nanoTime());
        controller.deleteFile(ADMIN, Map.of("fileId", fileId));
        Long memberFile = upload(MEMBER, "成员资料 " + System.nanoTime());
        controller.deleteFile(MEMBER, Map.of("fileId", memberFile));
        assertEquals(List.of(), actions());
    }

    @Test
    void categoryChangesAreRecorded() {
        String name = "审计分类 " + System.nanoTime();
        Long categoryId = ((Number) controller.saveCategory(ADMIN, Map.of("name", name)).data().get("id")).longValue();
        controller.saveCategory(ADMIN, Map.of("id", categoryId, "name", name + "（新）", "sortNo", 3));
        controller.deleteCategory(ADMIN, Map.of("categoryId", categoryId));
        controller.deleteCategory(ADMIN, Map.of("categoryId", categoryId));
        assertEquals(List.of("KNOWLEDGE_CATEGORY_SAVE", "KNOWLEDGE_CATEGORY_SAVE", "KNOWLEDGE_CATEGORY_DELETE"), actions());
        assertEquals("新建知识分类", entries.get(0).summary());
        assertEquals("修改知识分类", entries.get(1).summary());
        String changes = entries.get(1).detail().get("changes").toString();
        assertTrue(changes.contains("sortNo") && changes.contains("name"), changes);
        assertEquals(name + "（新）", entries.get(2).targetLabel());
    }

    private Long reportIdFor(Long fileId) {
        return controller.adminReports(ADMIN).data().stream()
                .filter(report -> fileId.equals(((Number) report.get("fileId")).longValue()))
                .map(report -> ((Number) report.get("id")).longValue())
                .findFirst().orElseThrow();
    }
}
