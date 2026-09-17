package com.aiknowledge.user.config;

import com.aiknowledge.user.security.RedisTokenRevocations;
import com.aiknowledge.user.security.TokenRevocations;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class TokenRevocationConfig {
    /** Redis in the Docker stack (AUTH_REVOCATION_STORE=redis); in memory otherwise, where no gateway reads it. */
    @Bean
    public TokenRevocations tokenRevocations(@Value("${auth.revocation.store:memory}") String store,
                                             ObjectProvider<StringRedisTemplate> redis) {
        return "redis".equalsIgnoreCase(store.trim())
                ? new RedisTokenRevocations(redis.getObject())
                : TokenRevocations.inMemory();
    }
}
