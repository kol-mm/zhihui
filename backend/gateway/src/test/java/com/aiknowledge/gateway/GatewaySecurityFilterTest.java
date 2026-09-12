package com.aiknowledge.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GatewaySecurityFilterTest {
    @Test
    void loginRequestsAreRateLimitedAndSecurityHeadersAreAdded() {
        GatewaySecurityFilter filter = new GatewaySecurityFilter();
        AtomicInteger forwarded = new AtomicInteger();
        GatewayFilterChain chain = exchange -> {
            forwarded.incrementAndGet();
            return Mono.empty();
        };

        MockServerWebExchange last = null;
        for (int index = 0; index < 11; index++) {
            last = MockServerWebExchange.from(MockServerHttpRequest.post("/user/login")
                    .remoteAddress(new java.net.InetSocketAddress("203.0.113.7", 12345)).build());
            filter.filter(last, chain).block(Duration.ofSeconds(1));
        }

        assertEquals(10, forwarded.get());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, last.getResponse().getStatusCode());
        assertEquals("nosniff", last.getResponse().getHeaders().getFirst("X-Content-Type-Options"));
        assertNotNull(last.getResponse().getHeaders().getFirst("Content-Security-Policy"));
    }

    @Test
    void captchaRefreshesDoNotConsumeTheLoginLimit() {
        GatewaySecurityFilter filter = new GatewaySecurityFilter();
        AtomicInteger forwarded = new AtomicInteger();
        GatewayFilterChain chain = exchange -> {
            forwarded.incrementAndGet();
            return Mono.empty();
        };

        for (int index = 0; index < 20; index++) {
            var captcha = MockServerWebExchange.from(MockServerHttpRequest.get("/user/captcha")
                    .remoteAddress(new java.net.InetSocketAddress("203.0.113.8", 12345)).build());
            filter.filter(captcha, chain).block(Duration.ofSeconds(1));
        }
        var login = MockServerWebExchange.from(MockServerHttpRequest.post("/user/login")
                .remoteAddress(new java.net.InetSocketAddress("203.0.113.8", 12345)).build());
        filter.filter(login, chain).block(Duration.ofSeconds(1));

        assertEquals(21, forwarded.get());
        assertNull(login.getResponse().getStatusCode());
    }

    @Test
    void forwardedClientsBehindTheDockerProxyHaveIndependentLimits() {
        GatewaySecurityFilter filter = new GatewaySecurityFilter();
        AtomicInteger forwarded = new AtomicInteger();
        GatewayFilterChain chain = exchange -> {
            forwarded.incrementAndGet();
            return Mono.empty();
        };

        for (int index = 0; index < 10; index++) {
            var firstClient = MockServerWebExchange.from(MockServerHttpRequest.post("/user/login")
                    .header("X-Forwarded-For", "203.0.113.10, 172.18.0.1")
                    .remoteAddress(new java.net.InetSocketAddress("172.18.0.4", 12345)).build());
            filter.filter(firstClient, chain).block(Duration.ofSeconds(1));
        }
        var secondClient = MockServerWebExchange.from(MockServerHttpRequest.post("/user/login")
                .header("X-Forwarded-For", "203.0.113.11, 172.18.0.1")
                .remoteAddress(new java.net.InetSocketAddress("172.18.0.4", 12345)).build());
        filter.filter(secondClient, chain).block(Duration.ofSeconds(1));

        assertEquals(11, forwarded.get());
        assertNull(secondClient.getResponse().getStatusCode());
    }
}
