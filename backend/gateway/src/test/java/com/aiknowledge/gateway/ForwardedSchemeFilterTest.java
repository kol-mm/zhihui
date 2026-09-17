package com.aiknowledge.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.reactive.CorsUtils;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForwardedSchemeFilterTest {
    private final ForwardedSchemeFilter filter = new ForwardedSchemeFilter();

    @Test
    void theSitesOwnHttpsPagesAreNotTakenForAnotherOrigin() {
        MockServerHttpRequest.BodyBuilder request = MockServerHttpRequest.post("http://zhihui.example/user/login")
                .header("Origin", "https://zhihui.example")
                .header("X-Forwarded-Proto", "https,http")
                .remoteAddress(new InetSocketAddress("172.18.0.9", 40000));
        assertTrue(CorsUtils.isCorsRequest(request.build()));

        ServerHttpRequest seen = run(request);
        assertEquals("https", seen.getURI().getScheme());
        assertEquals("zhihui.example", seen.getURI().getHost());
        assertEquals("/user/login", seen.getURI().getPath());
        assertFalse(CorsUtils.isCorsRequest(seen));
    }

    @Test
    void theHeaderIsIgnoredUnlessAProxySentIt() {
        ServerHttpRequest direct = run(MockServerHttpRequest.post("http://zhihui.example/user/login")
                .header("X-Forwarded-Proto", "https")
                .remoteAddress(new InetSocketAddress("203.0.113.7", 40000)));
        assertEquals("http", direct.getURI().getScheme());

        ServerHttpRequest junk = run(MockServerHttpRequest.get("http://zhihui.example/user/session?x=1")
                .header("X-Forwarded-Proto", "gopher")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 40000)));
        assertEquals("http", junk.getURI().getScheme());
        assertEquals("x=1", junk.getURI().getQuery());
    }

    @Test
    void aPortInTheHostIsKept() {
        ServerHttpRequest seen = run(MockServerHttpRequest.post("http://127.0.0.1:8088/user/login")
                .header("Origin", "http://127.0.0.1:8088")
                .header("X-Forwarded-Proto", "http")
                .remoteAddress(new InetSocketAddress("172.18.0.9", 40000)));
        assertEquals(8088, seen.getURI().getPort());
        assertFalse(CorsUtils.isCorsRequest(seen));
    }

    private ServerHttpRequest run(MockServerHttpRequest.BaseBuilder<?> request) {
        AtomicReference<ServerHttpRequest> seen = new AtomicReference<>();
        filter.filter(MockServerWebExchange.from(request.build()), exchange -> {
            seen.set(exchange.getRequest());
            return Mono.empty();
        }).block(Duration.ofSeconds(1));
        return seen.get();
    }
}
