package com.aiknowledge.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Requests made with a platform API key: {@code Authorization: Bearer zk_...}.
 *
 * <p>The services only understand session tokens, so a key never reaches them. This filter asks user-service
 * whether the key is live, checks the request against the key's scopes, and then forwards it either anonymously
 * or — for a key that acts for an account — with a minute-long ordinary member's token in its place. Whatever
 * the client sent alongside, cookies included, is dropped: a key request never runs as a browser session.
 *
 * <p>Every doubt closes. A path no scope opens is refused before user-service is asked; a key that cannot be
 * verified is refused as unavailable, never let through.
 */
@Component
public class ApiKeyFilter implements GlobalFilter, Ordered {
    static final String KEY_PREFIX = "zk_";
    static final String NOT_OPEN_BODY = "{\"code\":403,\"message\":\"此接口不对接口密钥开放\",\"data\":null}";
    static final String INVALID_BODY = "{\"code\":401,\"message\":\"接口密钥无效、已过期或已撤销\",\"data\":null}";
    static final String UNAVAILABLE_BODY = "{\"code\":503,\"message\":\"接口密钥暂时无法校验，请稍后重试\",\"data\":null}";
    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    private final ApiKeyVerifier verifier;

    public ApiKeyFilter(ApiKeyVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String bearer = SessionTokens.bearer(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (bearer == null || !bearer.startsWith(KEY_PREFIX)) return chain.filter(exchange);

        String scope = ApiKeyScopes.required(request.getPath().value(), request.getMethod());
        if (scope == null) return reject(exchange, HttpStatus.FORBIDDEN, NOT_OPEN_BODY);

        return verifier.verify(bearer)
                .map(Optional::of)
                .switchIfEmpty(Mono.just(Optional.empty()))
                .onErrorResume(error -> {
                    log.warn("API key could not be verified, request refused: {}", error.toString());
                    return Mono.just(Optional.empty());
                })
                // Only the question to user-service is guarded above; errors from the services themselves go on
                // as they would for any other request.
                .flatMap(answer -> {
                    if (answer.isEmpty()) return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_BODY);
                    ApiKeyVerifier.Verification verification = answer.get();
                    if (!verification.valid()) return reject(exchange, HttpStatus.UNAUTHORIZED, INVALID_BODY);
                    if (!verification.scopes().contains(scope)) {
                        return reject(exchange, HttpStatus.FORBIDDEN, missingScopeBody(scope));
                    }
                    ServerHttpRequest forwarded = request.mutate().headers(headers -> {
                        headers.remove(HttpHeaders.COOKIE);
                        headers.remove(HttpHeaders.AUTHORIZATION);
                        if (verification.token() != null) {
                            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + verification.token());
                        }
                    }).build();
                    return chain.filter(exchange.mutate().request(forwarded).build());
                });
    }

    static String missingScopeBody(String scope) {
        return "{\"code\":403,\"message\":\"接口密钥没有此权限，需要 " + scope + "\",\"data\":null}";
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String body) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /**
     * After the session filter (-90), which leaves a {@code zk_} bearer untouched because it is not a session
     * token, and before routing.
     */
    @Override
    public int getOrder() {
        return -85;
    }
}
