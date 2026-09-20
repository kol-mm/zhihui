package com.aiknowledge.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminAuditTest {
    private static final String ADMIN = "Bearer " + LocalAuth.issueToken("admin", 2L, "ADMIN");
    private static final String MEMBER = "Bearer " + LocalAuth.issueToken("demo", 1L, "USER");
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private HttpServer server;

    @AfterEach
    void cleanUp() {
        RequestContextHolder.resetRequestAttributes();
        if (server != null) server.stop(0);
    }

    @Test
    void changesKeepOnlyWhatDiffers() {
        AdminAudit.Changes changes = new AdminAudit.Changes()
                .add("role", "角色", "USER", "ADMIN")
                .add("status", "账号状态", "ACTIVE", "ACTIVE")
                .add("signature", "个人签名", null, "")
                .addHidden("password", "登录密码");
        assertEquals("角色、登录密码", changes.labels());
        List<Map<String, Object>> list = changes.list();
        assertEquals(Map.of("field", "role", "label", "角色", "before", "USER", "after", "ADMIN"), list.get(0));
        assertEquals(true, list.get(1).get("hidden"));
        assertFalse(list.get(1).containsKey("after"), "hidden values are never kept");

        AdminAudit.Event event = AdminAudit.Event.of("USER_GOVERNANCE", AdminAudit.ACCOUNTS, "USER", 7L, "Alice", 7L, "修改")
                .withChanges(new AdminAudit.Changes());
        assertFalse(event.detail().containsKey("changes"), "an empty change list is left out");
        assertThrows(IllegalArgumentException.class,
                () -> AdminAudit.Event.of("X", "OTHER", "USER", 1L, "x", null, "x"));
    }

    @Test
    void onlyAdministratorsProduceEntries() {
        AdminAudit.Event event = AdminAudit.Event.of("USER_STATUS", AdminAudit.ACCOUNTS, "USER", 7L, "Alice", 7L, "停用");
        assertNull(AuditEntry.forRequest(null, event, "test"));
        assertNull(AuditEntry.forRequest(MEMBER, event, "test"));
        assertNull(AuditEntry.forRequest("Bearer forged", event, "test"));

        AuditEntry entry = AuditEntry.forRequest(ADMIN, event.with("reason", "spam"), "user-service");
        assertEquals(2L, entry.actorId());
        assertEquals("admin", entry.actorName());
        assertEquals("7", entry.targetId());
        assertEquals("spam", entry.detail().get("reason"));
        assertEquals("user-service", entry.source());
        assertNull(entry.clientIp(), "no request, no address");
    }

    @Test
    void theClientAddressComesFromTheGatewayHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.18.0.9");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        AdminAudit.Event event = AdminAudit.Event.of("USER_STATUS", AdminAudit.ACCOUNTS, "USER", 7L, "x".repeat(300), 7L, "停用");
        AuditEntry direct = AuditEntry.forRequest(ADMIN, event, "test");
        assertEquals("172.18.0.9", direct.clientIp());
        assertEquals(200, direct.targetLabel().length(), "long names are shortened");
        assertTrue(direct.targetLabel().endsWith("…"));

        request.addHeader(AuditEntry.CLIENT_IP_HEADER, "203.0.113.7");
        assertEquals("203.0.113.7", AuditEntry.forRequest(ADMIN, event, "test").clientIp());
    }

    @Test
    void entriesAreDeliveredWithTheInternalToken() throws Exception {
        List<String> tokens = new CopyOnWriteArrayList<>();
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        AtomicInteger failuresLeft = new AtomicInteger();
        String url = startServer(tokens, bodies, failuresLeft);
        HttpAdminAudit audit = new HttpAdminAudit(json, url, "secret-token", "knowledge-service", new long[]{0, 0, 0});

        audit.record(MEMBER, AdminAudit.Event.of("KNOWLEDGE_DELETE", AdminAudit.KNOWLEDGE, "KNOWLEDGE_FILE", 3L, "t", 1L, "删除"));
        audit.record(ADMIN, AdminAudit.Event.of("KNOWLEDGE_DELETE", AdminAudit.KNOWLEDGE, "KNOWLEDGE_FILE", 3L, "文档", 1L, "删除"));
        audit.destroy();

        assertEquals(List.of("secret-token"), tokens, "only the administrator's action is sent");
        JsonNode body = bodies.get(0);
        assertEquals("KNOWLEDGE_DELETE", body.path("action").asText());
        assertEquals("文档", body.path("targetLabel").asText());
        assertEquals(2, body.path("actorId").asInt());
        assertEquals("knowledge-service", body.path("source").asText());
        assertTrue(body.path("occurredAt").asText().endsWith("Z"), "sent as an ISO instant: " + body.path("occurredAt"));
        // The receiving service reads it back into the same record.
        AuditEntry parsed = json.treeToValue(body, AuditEntry.class);
        assertEquals(1L, parsed.subjectUserId());
        assertTrue(parsed.occurredAt() != null);
    }

    @Test
    void deliveryIsRetriedAndFinallyLogged() throws Exception {
        List<String> tokens = new CopyOnWriteArrayList<>();
        AtomicInteger failuresLeft = new AtomicInteger(2);
        String url = startServer(tokens, new CopyOnWriteArrayList<>(), failuresLeft);
        HttpAdminAudit audit = new HttpAdminAudit(json, url, "secret-token", "test", new long[]{0, 0, 0});
        AuditEntry entry = AuditEntry.forRequest(ADMIN,
                AdminAudit.Event.of("FAQ_DELETE", AdminAudit.SUPPORT, "FAQ", 1L, "q", null, "删除"), "test");

        audit.deliver(entry);
        assertEquals(3, tokens.size(), "two failures, then success");

        failuresLeft.set(5);
        tokens.clear();
        audit.deliver(entry);
        assertEquals(3, tokens.size(), "gives up after three attempts");

        server.stop(0);
        server = null;
        audit.deliver(entry); // unreachable: logged, no exception
        audit.destroy();
    }

    private String startServer(List<String> tokens, List<JsonNode> bodies, AtomicInteger failuresLeft) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/user/internal/audit", exchange -> {
            tokens.add(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            bodies.add(json.readTree(exchange.getRequestBody()));
            boolean fail = failuresLeft.getAndUpdate(left -> Math.max(0, left - 1)) > 0;
            byte[] response = (fail ? "{\"code\":500,\"message\":\"down\"}" : "{\"code\":0,\"data\":{\"id\":1}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/user/internal/audit";
    }
}
