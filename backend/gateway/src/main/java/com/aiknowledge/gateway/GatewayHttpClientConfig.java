package com.aiknowledge.gateway;

import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * How long the gateway may keep using an address it looked up for a service.
 *
 * <p>Reactor Netty caches DNS answers for as long as the answer says, and Docker's embedded DNS says 600 seconds.
 * Recreating a container — which every deploy of that service does — can give it a new address; the gateway kept
 * sending requests to the old one and answered 500 for up to ten minutes (seen after ai was redeployed:
 * "Connection refused: ai/172.18.0.7"). nginx in front had the same problem and looks names up every ten seconds
 * (deploy/docker/nginx.conf); the gateway now does the same.
 */
@Configuration
public class GatewayHttpClientConfig {
    static final Duration DNS_CACHE_MAX = Duration.ofSeconds(10);
    static final Duration DNS_NEGATIVE_CACHE = Duration.ofSeconds(1);

    @Bean
    HttpClientCustomizer shortDnsCache() {
        return httpClient -> httpClient.resolver(spec -> spec
                .cacheMaxTimeToLive(DNS_CACHE_MAX)
                .cacheNegativeTimeToLive(DNS_NEGATIVE_CACHE));
    }
}
