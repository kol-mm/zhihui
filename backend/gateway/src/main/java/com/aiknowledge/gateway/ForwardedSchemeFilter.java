package com.aiknowledge.gateway;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Locale;

/**
 * Lets the gateway see the scheme the visitor used. Behind a TLS-terminating nginx every request arrives as plain
 * HTTP, so the CORS check took the site's own https:// pages for another origin and refused their POSTs (login
 * included). The scheme is taken from X-Forwarded-Proto, and only when the request comes straight from a proxy on
 * a private address, like nginx in the Docker network.
 *
 * <p>Spring's ForwardedHeaderTransformer would do this too, but it also takes the client address from
 * X-Forwarded-For, which would let a visitor pick the address GatewaySecurityFilter rate-limits by.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ForwardedSchemeFilter implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String scheme = forwardedScheme(request.getHeaders().getFirst("X-Forwarded-Proto"));
        URI uri = request.getURI();
        if (scheme == null || scheme.equalsIgnoreCase(uri.getScheme()) || !fromTrustedProxy(request)) {
            return chain.filter(exchange);
        }
        URI visitorUri = UriComponentsBuilder.fromUri(uri).scheme(scheme).build(true).toUri();
        return chain.filter(exchange.mutate().request(request.mutate().uri(visitorUri).build()).build());
    }

    /** nginx may append its own hop, so the first value is the visitor's. */
    private static String forwardedScheme(String header) {
        if (header == null || header.isBlank()) return null;
        String first = header.split(",")[0].trim().toLowerCase(Locale.ROOT);
        return first.equals("http") || first.equals("https") ? first : null;
    }

    private static boolean fromTrustedProxy(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        InetAddress address = remote == null ? null : remote.getAddress();
        return GatewaySecurityFilter.isTrustedProxy(address);
    }
}
