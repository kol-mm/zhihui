package com.aiknowledge.gateway;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Configuration
public class SessionRevocationConfig {
    /** Redis in the Docker stack (AUTH_REVOCATION_STORE=redis); without it no session counts as revoked. */
    @Bean
    SessionRevocations sessionRevocations(@Value("${auth.revocation.store:memory}") String store,
                                          ObjectProvider<ReactiveStringRedisTemplate> redis) {
        if (!"redis".equalsIgnoreCase(store.trim())) return token -> Mono.just(false);
        return new SessionRevocations.Redis(redis.getObject(), Duration.ofMillis(500));
    }

    @Bean
    SessionTokens sessionTokens() {
        return SessionTokens.fromEnvironment();
    }
}
