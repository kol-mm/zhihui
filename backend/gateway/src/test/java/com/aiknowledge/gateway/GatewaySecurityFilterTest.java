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
}
