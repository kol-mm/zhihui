package com.aiknowledge.gateway;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Configuration
public class ApiKeyConfig {
    static final String DEVELOPMENT_INTERNAL_TOKEN = "ai-knowledge-local-internal";
    static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(2);

    /**
     * Talks to user-service directly — the same address the user-service route uses — rather than back through
     * this gateway, which turns away any path with an internal segment.
     *
     * <p>The internal token is not checked at startup the way the signing key is: a deployment that never uses
     * API keys should not refuse to start over one, and a wrong token already fails safe, because user-service
     * refuses the question and every key request is answered 503.
     */
    @Bean
    ApiKeyVerifier apiKeyVerifier(WebClient.Builder builder,
                                  ObjectProvider<ReactorLoadBalancerExchangeFilterFunction> loadBalancer,
                                  @Value("${USER_SERVICE_URL:lb://user-service}") String userService) {
        String configured = System.getenv("PLATFORM_INTERNAL_USER_TOKEN");
        String token = configured == null || configured.isBlank() ? DEVELOPMENT_INTERNAL_TOKEN : configured.trim();
        String base = userService.trim();
        WebClient.Builder client = builder.clone();
        if (base.startsWith("lb://")) {
            base = "http://" + base.substring("lb://".length());
            ReactorLoadBalancerExchangeFilterFunction balancer = loadBalancer.getIfAvailable();
            if (balancer != null) client.filter(balancer);
        }
        return new ApiKeyVerifier.Remote(client.baseUrl(base).build(), token, VERIFY_TIMEOUT);
    }
}
