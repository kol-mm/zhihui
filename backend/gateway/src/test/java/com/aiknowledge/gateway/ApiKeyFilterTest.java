package com.aiknowledge.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyFilterTest {
    private static final String KEY = "zk_live-key-for-tests";
    private static final String DELEGATED = "header.payload.signature";

    private final AtomicInteger asked = new AtomicInteger();
    private final List<ServerWebExchange> forwarded = new ArrayList<>();
    private final GatewayFilterChain chain = exchange -> {
        forwarded.add(exchange);
        return Mono.empty();
    };

    private ApiKeyFilter answering(Mono<ApiKeyVerifier.Verification> answer) {
        return new ApiKeyFilter(key -> {
            asked.incrementAndGet();
            return answer;
        });
    }

    private static Mono<ApiKeyVerifier.Verification> live(String token, String... scopes) {
        return Mono.just(new ApiKeyVerifier.Verification(true, 7, Set.of(scopes), token, null));
    }

    @Test
    void requestsWithoutAKeyAreNotTouched() {
        ApiKeyFilter filter = answering(live(null, "knowledge:read"));
        MockServerHttpRequest.BaseBuilder<?> anonymous = MockServerHttpRequest.get("/knowledge/list");
        run(filter, anonymous);
        run(filter, MockServerHttpRequest.get("/user/admin/users/page").header(HttpHeaders.AUTHORIZATION, "Bearer a.b.c"));
        run(filter, MockServerHttpRequest.get("/knowledge/list").cookie(new HttpCookie("zh_session", "a.b.c")));

        assertEquals(0, asked.get(), "only a zk_ bearer is a key");
        assertEquals(3, forwarded.size());
        assertEquals("Bearer a.b.c", forwarded.get(1).getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void aPathNoScopeOpensIsRefusedWithoutAskingUserService() {
        ApiKeyFilter filter = answering(live(null, "knowledge:read", "knowledge:write", "community:read", "community:write"));
        for (String path : new String[]{"/user/admin/api-keys", "/knowledge/admin/files/page", "/message/sessions", "/ai/ask"}) {
            MockServerWebExchange exchange = run(filter, keyed(MockServerHttpRequest.get(path)));
            assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode(), path);
        }
        assertEquals(0, asked.get(), "even a key holding every scope is not looked up for a closed path");
        assertTrue(forwarded.isEmpty());
    }

    @Test
    void anInvalidKeyIsUnauthorized() {
        MockServerWebExchange exchange = run(answering(Mono.just(ApiKeyVerifier.Verification.invalid("revoked"))),
                keyed(MockServerHttpRequest.get("/knowledge/list")));
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals(ApiKeyFilter.INVALID_BODY, exchange.getResponse().getBodyAsString().block());
        assertFalse(exchange.getResponse().getBodyAsString().block().contains("revoked"), "the reason stays in the log");
        assertTrue(forwarded.isEmpty());
    }

    @Test
    void aLiveKeyWithoutTheScopeIsForbiddenAndToldWhichOneItNeeds() {
        MockServerWebExchange exchange = run(answering(live(null, "knowledge:read")),
                keyed(MockServerHttpRequest.post("/knowledge/upload")));
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        assertEquals(ApiKeyFilter.missingScopeBody("knowledge:write"), exchange.getResponse().getBodyAsString().block());
        assertTrue(forwarded.isEmpty());
    }

    @Test
    void aKeyActingForAnAccountGoesOnAsThatAccountsShortLivedToken() {
        run(answering(live(DELEGATED, "community:write")),
                keyed(MockServerHttpRequest.post("/post/create").cookie(new HttpCookie("zh_session", "someone-elses-session"))));

        HttpHeaders headers = forwarded.get(0).getRequest().getHeaders();
        assertEquals("Bearer " + DELEGATED, headers.getFirst(HttpHeaders.AUTHORIZATION));
        assertNull(headers.getFirst(HttpHeaders.COOKIE), "a key request never runs as a browser session");
        assertFalse(String.valueOf(headers).contains(KEY), "the key itself never reaches a service");
    }

    @Test
    void aKeyActingForNobodyGoesOnAnonymously() {
        run(answering(live(null, "knowledge:read")), keyed(MockServerHttpRequest.get("/knowledge/list")));
        HttpHeaders headers = forwarded.get(0).getRequest().getHeaders();
        assertNull(headers.getFirst(HttpHeaders.AUTHORIZATION));
        assertFalse(String.valueOf(headers).contains(KEY));
    }

    @Test
    void aKeyThatCannotBeVerifiedIsRefusedNotLetThrough() {
        for (Mono<ApiKeyVerifier.Verification> failure : List.<Mono<ApiKeyVerifier.Verification>>of(
                Mono.error(new IllegalStateException("user-service is down")),
                Mono.empty())) {
            forwarded.clear();
            MockServerWebExchange exchange = run(answering(failure), keyed(MockServerHttpRequest.get("/knowledge/list")));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
            assertEquals(ApiKeyFilter.UNAVAILABLE_BODY, exchange.getResponse().getBodyAsString().block());
            assertTrue(forwarded.isEmpty());
        }
    }

    @Test
    void anErrorFromTheServiceItselfIsNotMistakenForAnUnverifiableKey() {
        ApiKeyFilter filter = answering(live(null, "knowledge:read"));
        MockServerWebExchange exchange = MockServerWebExchange.from(keyed(MockServerHttpRequest.get("/knowledge/list")));
        GatewayFilterChain failing = ignored -> Mono.error(new IllegalStateException("knowledge-service blew up"));
        IllegalStateException thrown = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> filter.filter(exchange, failing).block());
        assertEquals("knowledge-service blew up", thrown.getMessage());
        assertNull(exchange.getResponse().getStatusCode(), "no 503 was written over the service's own error");
    }

    @Test
    void userServicesAnswerIsReadStrictly() {
        assertEquals(IllegalStateException.class, errorOf(Map.of("code", 500, "message", "internal token is required")));
        assertEquals(IllegalStateException.class, errorOf(Map.of("code", 0)));
        assertEquals(IllegalStateException.class, errorOf(Map.of("message", "no code")));

        ApiKeyVerifier.Verification invalid = ApiKeyVerifier.Remote.read(Map.of("code", 0,
                "data", Map.of("valid", false, "reason", "expired"))).block();
        assertFalse(invalid.valid());

        ApiKeyVerifier.Verification valid = ApiKeyVerifier.Remote.read(Map.of("code", 0, "data",
                Map.of("valid", true, "keyId", 7, "scopes", List.of("knowledge:read"), "token", DELEGATED))).block();
        assertTrue(valid.valid());
        assertEquals(7, valid.keyId());
        assertEquals(Set.of("knowledge:read"), valid.scopes());
        assertEquals(DELEGATED, valid.token());
    }

    private static Class<?> errorOf(Map<String, Object> body) {
        try {
            ApiKeyVerifier.Remote.read(body).block();
            return null;
        } catch (RuntimeException error) {
            return error.getClass();
        }
    }

    private static MockServerHttpRequest.BaseBuilder<?> keyed(MockServerHttpRequest.BaseBuilder<?> request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + KEY);
    }

    private MockServerWebExchange run(ApiKeyFilter filter, MockServerHttpRequest.BaseBuilder<?> request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        filter.filter(exchange, chain).block();
        return exchange;
    }
}
