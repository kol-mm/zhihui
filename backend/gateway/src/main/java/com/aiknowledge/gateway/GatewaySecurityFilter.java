package com.aiknowledge.gateway;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class GatewaySecurityFilter implements GlobalFilter, Ordered {
    private static final long WINDOW_MILLIS = 60_000L;
    static final String CLIENT_IP_HEADER = "X-Client-Ip";
    private static final java.util.regex.Pattern INTERNAL_PATH =
            java.util.regex.Pattern.compile("(^|/)internal(/|$)", java.util.regex.Pattern.CASE_INSENSITIVE);

    private final Cache<String, WindowCounter> counters = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofMinutes(3))
            .build();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        addSecurityHeaders(exchange);
        String path = exchange.getRequest().getPath().value();
        // Service-to-service endpoints are only reachable inside the container network.
        if (INTERNAL_PATH.matcher(path).find()) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        int limit = requestLimit(path, exchange.getRequest().getMethod().name());
        String client = clientKey(exchange);
        String key = client + ':' + rateClass(path, exchange.getRequest().getMethod().name());
        if (!allow(key, limit)) {
            byte[] body = "{\"code\":429,\"message\":\"too many requests\",\"data\":{}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "60");
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        }
        // Services record this address in the admin action log; whatever the browser sent under the name is replaced.
        String clientIp = auditAddress(exchange, client.substring("ip:".length()));
        return chain.filter(exchange.mutate()
                .request(exchange.getRequest().mutate().headers(headers -> headers.set(CLIENT_IP_HEADER, clientIp)).build())
                .build());
    }

    private boolean allow(String key, int limit) {
        long now = System.currentTimeMillis();
        while (true) {
            WindowCounter current = counters.get(key, ignored -> new WindowCounter(now));
            if (now - current.startedAt >= WINDOW_MILLIS) {
                WindowCounter replacement = new WindowCounter(now);
                if (!counters.asMap().replace(key, current, replacement)) continue;
                current = replacement;
            }
            return current.count.incrementAndGet() <= limit;
        }
    }

    private int requestLimit(String path, String method) {
        if (path.equals("/user/login")) return 10;
        if (path.equals("/user/register")) return 5;
        if (path.equals("/user/captcha")) return 20;
        if (path.equals("/user/password-reset/request")) return 5;
        if (path.equals("/user/password-reset/complete")) return 10;
        if (path.equals("/user/email/code")) return 5;
        if (path.equals("/user/email/verify")) return 10;
        if (!"GET".equals(method) && !"OPTIONS".equals(method)) return 120;
        return 600;
    }

    private String rateClass(String path, String method) {
        if (path.equals("/user/login")) return "auth-login";
        if (path.equals("/user/register")) return "auth-register";
        if (path.equals("/user/captcha")) return "auth-captcha";
        if (path.equals("/user/password-reset/request")) return "auth-reset-request";
        if (path.equals("/user/password-reset/complete")) return "auth-reset-complete";
        if (path.equals("/user/email/code")) return "email-code";
        if (path.equals("/user/email/verify")) return "email-verify";
        // Reads and writes are counted apart: pages load many reads, which must not use up the smaller write allowance.
        return "GET".equals(method) || "OPTIONS".equals(method) ? "general-read" : "general-write";
    }

    /**
     * The address to record for an administrator's action. Rate limiting counts only addresses this gateway can
     * vouch for, which on a private network is the proxy itself; the log is more useful with the address nginx saw,
     * so the chain's first entry is used when it holds nothing public.
     */
    private String auditAddress(ServerWebExchange exchange, String rateLimitAddress) {
        if (!isTrustedProxy(parseAddress(rateLimitAddress))) return rateLimitAddress;
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) return rateLimitAddress;
        InetAddress first = parseAddress(forwarded.split(",")[0]);
        return first == null ? rateLimitAddress : first.getHostAddress();
    }

    private String clientKey(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        InetAddress address = remote == null ? null : remote.getAddress();
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (isTrustedProxy(address) && forwarded != null && !forwarded.isBlank()) {
            String[] chain = forwarded.split(",");
            for (int index = chain.length - 1; index >= 0; index--) {
                InetAddress candidate = parseAddress(chain[index]);
                if (candidate != null && !isTrustedProxy(candidate)) return "ip:" + candidate.getHostAddress();
            }
        }
        return "ip:" + (address == null ? "unknown" : address.getHostAddress());
    }

    /** Loopback and private addresses: nginx and the Docker network, never a visitor reaching the gateway directly. */
    static boolean isTrustedProxy(InetAddress address) {
        return address != null && (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress());
    }

    private InetAddress parseAddress(String value) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.isEmpty() || !candidate.matches("[0-9A-Fa-f:.]{2,45}")) return null;
        try {
            return InetAddress.getByName(candidate);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void addSecurityHeaders(ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        headers.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        if ("https".equalsIgnoreCase(exchange.getRequest().getURI().getScheme())) {
            headers.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
    }

    @Override
    public int getOrder() {
        return -100;
    }

    private static final class WindowCounter {
        private final long startedAt;
        private final AtomicInteger count = new AtomicInteger();

        private WindowCounter(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
