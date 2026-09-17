package com.aiknowledge.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Browser sessions arrive as the httpOnly {@value #COOKIE} cookie that user-service sets; this filter hands the
 * token to the services as the Authorization header they already read. It also ends revoked sessions (logout,
 * password change or reset, suspension, role change) before their tokens expire.
 *
 * <p>Cookies are sent by the browser on its own, so a write authenticated by the cookie must carry the
 * X-Requested-With header the site's scripts add: another site's form cannot set it, and a cross-site script
 * would first need a CORS approval it does not get. SameSite=Lax on the cookie is the first line of defence.
 */
@Component
public class GatewaySessionFilter implements GlobalFilter, Ordered {
    /** Written by user-service (SessionCookies). */
    static final String COOKIE = "zh_session";
    private static final Set<HttpMethod> SAFE_METHODS = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);
    private static final String EXPIRED_BODY = "{\"code\":401,\"message\":\"登录已失效，请重新登录\",\"data\":null}";
    private static final String FORGED_BODY = "{\"code\":403,\"message\":\"请求来源校验失败，请刷新页面后重试\",\"data\":null}";

    private final SessionTokens tokens;
    private final SessionRevocations revocations;

    public GatewaySessionFilter(SessionTokens tokens, SessionRevocations revocations) {
        this.tokens = tokens;
        this.revocations = revocations;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String headerToken = SessionTokens.bearer(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        HttpCookie cookie = request.getCookies().getFirst(COOKIE);
        String cookieToken = cookie == null || cookie.getValue().isBlank() ? null : cookie.getValue();
        // An explicit header (scripts, the smoke test, a page not yet moved to the cookie) wins over the cookie.
        boolean fromCookie = headerToken == null && cookieToken != null;
        String token = fromCookie ? cookieToken : headerToken;
        if (token == null) return chain.filter(exchange);

        if (fromCookie && !SAFE_METHODS.contains(request.getMethod())
                && !"XMLHttpRequest".equals(request.getHeaders().getFirst("X-Requested-With"))) {
            return reject(exchange, HttpStatus.FORBIDDEN, FORGED_BODY, false);
        }

        SessionTokens.Token session = tokens.read(token);
        if (session == null) {
            // Unreadable or expired: the header goes on unchanged and the service refuses it; a stale cookie is
            // dropped so the request continues signed out.
            if (!fromCookie) return chain.filter(exchange);
            clearCookie(exchange);
            return chain.filter(exchange);
        }
        return revocations.isRevoked(session).flatMap(revoked -> {
            if (revoked) return reject(exchange, HttpStatus.UNAUTHORIZED, EXPIRED_BODY, fromCookie);
            if (!fromCookie) return chain.filter(exchange);
            ServerHttpRequest authorized = request.mutate()
                    .headers(headers -> headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + cookieToken))
                    .build();
            return chain.filter(exchange.mutate().request(authorized).build());
        });
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String body, boolean clearCookie) {
        if (clearCookie) clearCookie(exchange);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private void clearCookie(ServerWebExchange exchange) {
        String forwardedProto = exchange.getRequest().getHeaders().getFirst("X-Forwarded-Proto");
        boolean secure = "https".equalsIgnoreCase(exchange.getRequest().getURI().getScheme())
                || (forwardedProto != null && forwardedProto.split(",")[0].trim().equalsIgnoreCase("https"));
        exchange.getResponse().addCookie(ResponseCookie.from(COOKIE, "")
                .httpOnly(true).secure(secure).sameSite("Lax").path("/").maxAge(0).build());
    }

    /** Right after the rate limiter (GatewaySecurityFilter, -100), so floods are turned away before Redis is asked. */
    @Override
    public int getOrder() {
        return -90;
    }
}
