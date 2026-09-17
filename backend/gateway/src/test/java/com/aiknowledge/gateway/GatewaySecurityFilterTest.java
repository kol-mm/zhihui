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

    @Test
    void browsingDoesNotUseUpTheWriteAllowance() {
        GatewaySecurityFilter filter = new GatewaySecurityFilter();
        AtomicInteger forwarded = new AtomicInteger();
        GatewayFilterChain chain = exchange -> {
            forwarded.incrementAndGet();
            return Mono.empty();
        };
        java.net.InetSocketAddress client = new java.net.InetSocketAddress("203.0.113.20", 12345);

        // A busy console: far more reads than the write limit, all allowed.
        for (int index = 0; index < 300; index++) {
            filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/user/admin/users/page").remoteAddress(client).build()), chain)
                    .block(Duration.ofSeconds(1));
        }
        var save = MockServerWebExchange.from(MockServerHttpRequest.delete("/user/email").remoteAddress(client).build());
        filter.filter(save, chain).block(Duration.ofSeconds(1));
        assertEquals(301, forwarded.get());
        assertNull(save.getResponse().getStatusCode());

        // Writes still have their own limit of 120 a minute.
        for (int index = 1; index < 120; index++) {
            filter.filter(MockServerWebExchange.from(MockServerHttpRequest.post("/post/like").remoteAddress(client).build()), chain)
                    .block(Duration.ofSeconds(1));
        }
        var overLimit = MockServerWebExchange.from(MockServerHttpRequest.post("/post/like").remoteAddress(client).build());
        filter.filter(overLimit, chain).block(Duration.ofSeconds(1));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, overLimit.getResponse().getStatusCode());

        // Reads keep working after the writes ran out.
        var read = MockServerWebExchange.from(MockServerHttpRequest.get("/user/email").remoteAddress(client).build());
        filter.filter(read, chain).block(Duration.ofSeconds(1));
        assertNull(read.getResponse().getStatusCode());
    }

    @Test
    void emailCodesHaveTheirOwnTightLimit() {
        GatewaySecurityFilter filter = new GatewaySecurityFilter();
        GatewayFilterChain chain = exchange -> Mono.empty();
        java.net.InetSocketAddress client = new java.net.InetSocketAddress("203.0.113.21", 12345);
        MockServerWebExchange last = null;
        for (int index = 0; index < 6; index++) {
            last = MockServerWebExchange.from(MockServerHttpRequest.post("/user/email/code").remoteAddress(client).build());
            filter.filter(last, chain).block(Duration.ofSeconds(1));
        }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, last.getResponse().getStatusCode());
        // Other writes are unaffected.
        var bind = MockServerWebExchange.from(MockServerHttpRequest.post("/user/email").remoteAddress(client).build());
        filter.filter(bind, chain).block(Duration.ofSeconds(1));
        assertNull(bind.getResponse().getStatusCode());

        for (int index = 0; index < 11; index++) {
            last = MockServerWebExchange.from(MockServerHttpRequest.post("/user/email/verify").remoteAddress(client).build());
            filter.filter(last, chain).block(Duration.ofSeconds(1));
        }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, last.getResponse().getStatusCode());
    }
}
