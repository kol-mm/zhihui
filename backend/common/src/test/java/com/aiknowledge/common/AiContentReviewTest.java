package com.aiknowledge.common;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
