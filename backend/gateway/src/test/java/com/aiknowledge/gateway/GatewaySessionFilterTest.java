package com.aiknowledge.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewaySessionFilterTest {
    private static final String SECRET = "gateway-test-secret";
    private final SessionTokens tokens = new SessionTokens(SECRET);
    private final Set<String> revokedTokenIds = new HashSet<>();
    private final GatewaySessionFilter filter = new GatewaySessionFilter(tokens,
            token -> Mono.just(revokedTokenIds.contains(token.tokenId())));
    private final List<ServerWebExchange> forwarded = new ArrayList<>();
    private final GatewayFilterChain chain = exchange -> {
        forwarded.add(exchange);
        return Mono.empty();
    };

    @Test
    void theSessionCookieReachesServicesAsTheAuthorizationHeader() {
        String token = token("t1", 0);
        run(MockServerHttpRequest.get("/user/session").cookie(new HttpCookie("zh_session", token)));
        assertEquals("Bearer " + token, forwardedAuthorization());

        forwarded.clear();
        run(MockServerHttpRequest.post("/post/create").header("X-Requested-With", "XMLHttpRequest")
                .cookie(new HttpCookie("zh_session", token)));
        assertEquals("Bearer " + token, forwardedAuthorization());
    }

    @Test
    void cookieWritesWithoutTheScriptHeaderAreRefused() {
        MockServerWebExchange exchange = run(MockServerHttpRequest.post("/user/password")
                .cookie(new HttpCookie("zh_session", token("t1", 0))));
        assertTrue(forwarded.isEmpty());
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());

        // A header-authenticated write needs no such header: another site cannot set Authorization.
        String token = token("t2", 0);
        run(MockServerHttpRequest.post("/user/password").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
        assertEquals("Bearer " + token, forwardedAuthorization());
    }

    @Test
    void revokedSessionsAreTurnedAwayAndTheCookieIsDropped() {
        revokedTokenIds.add("gone");
        MockServerWebExchange exchange = run(MockServerHttpRequest.get("/knowledge/page")
                .cookie(new HttpCookie("zh_session", token("gone", 0))));
        assertTrue(forwarded.isEmpty());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals("", exchange.getResponse().getCookies().getFirst("zh_session").getValue());
        assertEquals(Duration.ZERO, exchange.getResponse().getCookies().getFirst("zh_session").getMaxAge());

        MockServerWebExchange header = run(MockServerHttpRequest.get("/knowledge/page")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("gone", 0)));
        assertEquals(HttpStatus.UNAUTHORIZED, header.getResponse().getStatusCode());
        assertNull(header.getResponse().getCookies().getFirst("zh_session"));
    }

    @Test
    void unreadableCookiesAreDroppedAndTheRequestContinuesSignedOut() {
        for (String bad : new String[]{"not-a-token", token("t1", -10_000), forge("t1")}) {
            forwarded.clear();
            MockServerWebExchange exchange = run(MockServerHttpRequest.get("/knowledge/page").cookie(new HttpCookie("zh_session", bad)));
            assertEquals(1, forwarded.size(), bad);
            assertNull(forwardedAuthorization());
            assertEquals(Duration.ZERO, exchange.getResponse().getCookies().getFirst("zh_session").getMaxAge());
        }
        // A bad header is left for the service to refuse.
        forwarded.clear();
        run(MockServerHttpRequest.get("/knowledge/page").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"));
        assertEquals("Bearer not-a-token", forwardedAuthorization());
    }

    @Test
    void anExplicitHeaderWinsOverTheCookie() {
        String header = token("header", 0);
        run(MockServerHttpRequest.get("/user/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + header)
                .cookie(new HttpCookie("zh_session", token("cookie", 0))));
        assertEquals("Bearer " + header, forwardedAuthorization());

        forwarded.clear();
        run(MockServerHttpRequest.get("/knowledge/page"));
        assertEquals(1, forwarded.size());
        assertNull(forwardedAuthorization());
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisDecidesByTokenOrByAccountAndFailsOpen() {
        long now = Instant.now().getEpochSecond();
        SessionTokens.Token token = new SessionTokens.Token(5L, "abc", now - 10, now + 60);
        assertTrue(SessionRevocations.Redis.revoked(token, Arrays.asList("1", null)));
        assertTrue(SessionRevocations.Redis.revoked(token, Arrays.asList(null, String.valueOf(now))));
        assertFalse(SessionRevocations.Redis.revoked(token, Arrays.asList(null, String.valueOf(now - 10))));
        assertFalse(SessionRevocations.Redis.revoked(token, Arrays.asList(null, null)));
        assertFalse(SessionRevocations.Redis.revoked(token, Arrays.asList(null, "garbage")));

        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        AtomicReference<List<String>> keys = new AtomicReference<>();
        when(values.multiGet(anyList())).thenAnswer(call -> {
            keys.set(call.getArgument(0));
            return Mono.just(Arrays.asList(null, String.valueOf(now)));
        });
        SessionRevocations.Redis revocations = new SessionRevocations.Redis(redis, Duration.ofMillis(200));
        assertTrue(revocations.isRevoked(token).block(Duration.ofSeconds(1)));
        assertEquals(List.of("zh:auth:revoked-token:abc", "zh:auth:revoked-before:5"), keys.get());

        when(values.multiGet(anyList())).thenReturn(Mono.error(new IllegalStateException("redis down")));
        assertFalse(revocations.isRevoked(token).block(Duration.ofSeconds(1)));
        when(values.multiGet(anyList())).thenReturn(Mono.never());
        assertFalse(revocations.isRevoked(token).block(Duration.ofSeconds(1)));
    }

    private MockServerWebExchange run(MockServerHttpRequest.BaseBuilder<?> request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request.build());
        filter.filter(exchange, chain).block(Duration.ofSeconds(1));
        return exchange;
    }

    private String forwardedAuthorization() {
        assertEquals(1, forwarded.size());
        return forwarded.get(0).getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    }

    /** A one-hour token as user-service's LocalAuth writes it, issued {@code shiftSeconds} from now (negative: earlier). */
    private static String token(String tokenId, long shiftSeconds) {
        return sign(SECRET, tokenId, shiftSeconds);
    }

    private static String forge(String tokenId) {
        return sign("some-other-secret", tokenId, 0);
    }

    private static String sign(String secret, String tokenId, long shiftSeconds) {
        try {
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            long issuedAt = Instant.now().getEpochSecond() + shiftSeconds;
            String header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String payload = encoder.encodeToString(("{\"sub\":\"demo\",\"uid\":1,\"role\":\"USER\",\"iat\":" + issuedAt
                    + ",\"exp\":" + (issuedAt + 3600) + ",\"jti\":\"" + tokenId + "\"}").getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return header + "." + payload + "." + encoder.encodeToString(mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
