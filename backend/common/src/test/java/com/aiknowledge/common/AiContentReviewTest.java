package com.aiknowledge.common;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiContentReviewTest {

    @Test
    void waitsTwoSecondsLongerThanAiServiceGivesItsModel() {
        assertEquals(Duration.ofSeconds(10), AiContentReview.waitFor(null));
        assertEquals(Duration.ofSeconds(10), AiContentReview.waitFor(""));
        assertEquals(Duration.ofSeconds(17), AiContentReview.waitFor("15"));
        assertEquals(Duration.ofMillis(10_500), AiContentReview.waitFor(" 8.5 "));
    }

    @Test
    void aValueThatIsNotAPositiveNumberFallsBackInsteadOfStoppingStartUp() {
        for (String value : new String[]{"abc", "0", "-3", "NaN", "Infinity"}) {
            assertEquals(Duration.ofSeconds(10), AiContentReview.waitFor(value), value);
        }
    }

    /**
     * ai-service answers just after its model's allowance runs out — the slowest answer it ever gives. Waiting
     * exactly that allowance, as equal settings on both sides did, lost its reason; waitFor keeps it.
     */
    @Test
    void anAnswerThatArrivesJustAfterTheModelsAllowanceStillCarriesItsReason() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/review", exchange -> {
            try {
                Thread.sleep(1_300);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"code\":0,\"data\":{\"decision\":\"ESCALATE\",\"confidence\":0,\"reason\":\"模型响应超时\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/review";
            AiContentReview equalWait = new AiContentReview(endpoint, "token", Duration.ofSeconds(1));
            AiContentReview longerWait = new AiContentReview(endpoint, "token", AiContentReview.waitFor("1"));

            assertEquals("AI 审核暂不可用", equalWait.review("POST", "标题", "正文").reason());
            assertEquals("模型响应超时", longerWait.review("POST", "标题", "正文").reason());
        } finally {
            server.stop(0);
        }
    }

    /** An ai-service stand-in that records each request and answers with the given verdict. */
    private static final class StubAiService implements AutoCloseable {
        final List<Map<String, Object>> received = new ArrayList<>();
        private final HttpServer server;
        private volatile String verdict = "{\"decision\":\"APPROVE\",\"confidence\":0.97,\"reason\":\"正常\"}";

        StubAiService() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/review", exchange -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> request = new ObjectMapper().readValue(exchange.getRequestBody(), Map.class);
                received.add(request);
                byte[] body = ("{\"code\":0,\"data\":" + verdict + "}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }

        AiContentReview client() {
            return new AiContentReview("http://127.0.0.1:" + server.getAddress().getPort() + "/review", "token",
                    Duration.ofSeconds(10));
        }

        StubAiService answering(String decision, String reason) {
            verdict = "{\"decision\":\"" + decision + "\",\"confidence\":0.97,\"reason\":\"" + reason + "\"}";
            return this;
        }

        String sentText() {
            return String.valueOf(received.get(received.size() - 1).get("text"));
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** ai-service refuses text over 2,000,000 characters, which sent every such document to a person as an outage. */
    @Test
    void textLongerThanAiServiceAcceptsIsCutToItsLimit() throws Exception {
        try (StubAiService ai = new StubAiService().answering("REJECT", "包含广告")) {
            String document = "字".repeat(AiContentReview.MAX_TEXT_CHARS + 5_000);

            AiContentReview.Verdict verdict = ai.client().review("KNOWLEDGE", "标题", document);

            assertEquals(AiContentReview.MAX_TEXT_CHARS, ai.sentText().length());
            assertTrue(document.startsWith(ai.sentText()));
            // A rejection of what was reviewed stands, and says how much that was.
            assertEquals(AiContentReview.REJECT, verdict.decision());
            assertTrue(verdict.reason().startsWith("包含广告；"), verdict.reason());
            assertTrue(verdict.reason().contains("原文共 " + document.length() + " 字"), verdict.reason());
        }
    }

    /** What was cut off was never reviewed, so nothing cut is published on an approval. */
    @Test
    void anApprovalOfCutTextGoesToAPerson() throws Exception {
        try (StubAiService ai = new StubAiService().answering("APPROVE", "内容正常")) {
            AiContentReview.Verdict verdict = ai.client().review("KNOWLEDGE", "标题",
                    "字".repeat(AiContentReview.MAX_TEXT_CHARS + 1));

            assertEquals(AiContentReview.ESCALATE, verdict.decision());
            assertTrue(verdict.reason().contains("只有前 " + AiContentReview.MAX_TEXT_CHARS + " 字经过审核"),
                    verdict.reason());
        }
    }

    @Test
    void textWithinTheLimitIsSentWholeAndItsVerdictLeftAlone() throws Exception {
        try (StubAiService ai = new StubAiService().answering("APPROVE", "内容正常")) {
            String document = "字".repeat(AiContentReview.MAX_TEXT_CHARS);

            AiContentReview.Verdict verdict = ai.client().review("KNOWLEDGE", "标题", document);

            assertEquals(document, ai.sentText());
            assertEquals(AiContentReview.APPROVE, verdict.decision());
            assertEquals("内容正常", verdict.reason());
        }
    }

    @Test
    void anOverlongTitleIsCutTooAndItsApprovalGoesToAPerson() throws Exception {
        try (StubAiService ai = new StubAiService().answering("APPROVE", "内容正常")) {
            AiContentReview.Verdict verdict = ai.client().review("KNOWLEDGE", "题".repeat(600), "正文");

            assertEquals(AiContentReview.MAX_TITLE_CHARS, String.valueOf(ai.received.get(0).get("title")).length());
            assertEquals(AiContentReview.ESCALATE, verdict.decision());
            assertTrue(verdict.reason().contains("标题超过"), verdict.reason());
        }
    }

    /** ai-service counts code points; a pair cut in half would be an invalid character and fail the request. */
    @Test
    void aCutNeverSplitsASurrogatePair() {
        String emoji = "\uD83D\uDE00";
        String value = "a".repeat(9) + emoji + "b";
        assertEquals("a".repeat(9), AiContentReview.capped(value, 10));
        assertEquals("a".repeat(9) + emoji, AiContentReview.capped(value, 11));
        assertEquals(value, AiContentReview.capped(value, value.length()));
    }
}
