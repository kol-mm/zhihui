package com.aiknowledge.message.controller;

import com.aiknowledge.common.AdminAudit;
import com.aiknowledge.common.AuditEntry;
import com.aiknowledge.common.LocalAuth;
import com.aiknowledge.message.event.LocalEventBusService;
import com.aiknowledge.message.store.InMemoryMessageStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which conversation and support actions end up in the administrator action log. */
class MessageAuditTest {
    static {
        System.setProperty("LOCAL_STORE_DIR", "target/test-local-store/message-audit-" + System.nanoTime());
    }

    private static final String ALICE = "Bearer " + LocalAuth.issueToken("alice", 61L, "USER");
    private static final String ADMIN = "Bearer " + LocalAuth.issueToken("admin", 2L, "ADMIN");

    private final List<AuditEntry> entries = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AdminAudit recorder = (authorization, event) -> {
        AuditEntry entry = AuditEntry.forRequest(authorization, event, "test");
        if (entry != null) entries.add(entry);
    };

    private List<String> actions() {
        return entries.stream().map(AuditEntry::action).toList();
    }

    private final MessageController controller = new MessageController(new InMemoryMessageStore(),
            new LocalEventBusService("local", "127.0.0.1", 5672, "ai-knowledge.events"));

    {
        controller.setAdminAudit(recorder);
    }

    @Test
    void conversationModerationIsRecorded() {
        Long sessionId = ((Number) controller.createSession(ALICE, Map.of("targetUserId", 62L)).data().get("id")).longValue();
        controller.send(ALICE, Map.of("sessionId", sessionId, "content", "你好"));

        controller.updateSessionStatus(ADMIN, Map.of("sessionId", sessionId, "status", "RESTRICTED"));
        controller.updateSessionStatus(ADMIN, Map.of("sessionId", sessionId, "status", "RESTRICTED"));
        controller.clear(ADMIN, Map.of("sessionId", sessionId));
        controller.deleteSession(ADMIN, Map.of("sessionId", sessionId));
        controller.clear(ADMIN, Map.of());

        assertEquals(List.of("SESSION_STATUS", "MESSAGES_CLEAR", "SESSION_DELETE", "MESSAGES_CLEAR_ALL"), actions());
        assertEquals(List.of(61L, 62L), entries.get(0).detail().get("participants"));
        assertTrue(entries.get(1).summary().startsWith("清空会话聊天记录"));
        assertEquals("全部私信会话", entries.get(3).targetLabel());
    }

    @Test
    void participantsManagingTheirOwnConversationsAreNotRecorded() {
        Long sessionId = ((Number) controller.createSession(ADMIN, Map.of("targetUserId", 63L)).data().get("id")).longValue();
        Long messageId = ((Number) controller.send(ADMIN, Map.of("sessionId", sessionId, "content", "通知")).data().get("id")).longValue();
        controller.deleteMessage(ADMIN, Map.of("messageId", messageId));
        controller.clear(ADMIN, Map.of("sessionId", sessionId));
        controller.deleteSession(ADMIN, Map.of("sessionId", sessionId));
        assertEquals(List.of(), actions());
    }

    @Test
    void supportWorkIsRecordedWithoutAnswersInFull() {
        Long ticketId = ((Number) controller.createTicket(ALICE, Map.of("type", "BUG", "content", "页面   打不开")).data().get("id")).longValue();
        controller.assignTicket(ADMIN, Map.of("ticketId", ticketId, "assigneeUserId", 2L));
        controller.assignTicket(ADMIN, Map.of("ticketId", ticketId, "assigneeUserId", 2L));
        controller.replyTicket(ADMIN, Map.of("ticketId", ticketId, "status", "RESOLVED", "reply", "已修复"));

        Long faqId = ((Number) controller.saveFaq(ADMIN, Map.of("question", "如何找回密码", "answer", "联系管理员")).data().get("id")).longValue();
        controller.saveFaq(ADMIN, Map.of("id", faqId, "question", "如何找回密码", "answer", "在登录页申请重置"));
        controller.saveFaq(ADMIN, Map.of("id", faqId, "question", "如何找回密码", "answer", "在登录页申请重置"));
        controller.deleteFaq(ADMIN, Map.of("faqId", faqId));

        assertEquals(List.of("TICKET_ASSIGN", "TICKET_REPLY", "FAQ_SAVE", "FAQ_SAVE", "FAQ_DELETE"), actions());
        AuditEntry assign = entries.get(0);
        assertEquals(61L, assign.subjectUserId());
        assertTrue(assign.targetLabel().startsWith("工单 #" + ticketId + "：页面 打不开"), assign.targetLabel());
        String reply = entries.get(1).detail().get("changes").toString();
        assertTrue(reply.contains("RESOLVED") && reply.contains("已修复"), reply);
        assertEquals("新建常见问题", entries.get(2).summary());
        String faqEdit = entries.get(3).detail().get("changes").toString();
        assertTrue(faqEdit.contains("答案") && faqEdit.contains("hidden"), faqEdit);
        assertFalse(faqEdit.contains("在登录页申请重置"), "answers are summarised, not copied");
        assertEquals("如何找回密码", entries.get(4).targetLabel());
    }
}
