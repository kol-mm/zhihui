package com.aiknowledge.knowledge.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The AI service's index follows the library: approved text in, everything else out, in the order it happened. */
class AiKnowledgeIndexTest {
    /** Runs work at once, so the order of requests is the order of calls. */
    private static final class DirectExecutor extends AbstractExecutorService {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    private record Received(String path, String token, Map<String, Object> body) { }

    @Test
    void publishingAndWithdrawingReachTheAiServiceInOrderWithTheInternalToken() throws Exception {
        List<Received> received = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ai/internal/index", exchange -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = new ObjectMapper().readValue(exchange.getRequestBody(), Map.class);
            received.add(new Received(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("X-Internal-Token"), body));
            byte[] answer = "{\"code\":0,\"data\":{}}".getBytes();
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        try {
            AiKnowledgeIndex index = new AiKnowledgeIndex(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/ai/internal/index/", "internal-token", new DirectExecutor());

            index.publish(5L, "检索增强生成", "正文");
            index.publish(6L, "空文档", "   ");
            index.withdraw(5L);

            assertEquals(List.of("/ai/internal/index", "/ai/internal/index/remove", "/ai/internal/index/remove"),
                    received.stream().map(Received::path).toList());
            assertEquals(Map.of("file_id", 5, "title", "检索增强生成", "text", "正文"), received.get(0).body());
            assertEquals(Map.of("file_id", 6), received.get(1).body(), "a file without text is taken out, not indexed empty");
            assertEquals(Map.of("file_id", 5), received.get(2).body());
            assertEquals(List.of("internal-token", "internal-token", "internal-token"),
                    received.stream().map(Received::token).toList());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anUnreachableAiServiceIsLoggedNotThrown() {
        AiKnowledgeIndex index = new AiKnowledgeIndex("http://127.0.0.1:9/ai/internal/index", "t", new DirectExecutor());
        index.publish(5L, "标题", "正文");
        index.withdraw(5L);
        index.publish(null, "标题", "正文");
    }
}
