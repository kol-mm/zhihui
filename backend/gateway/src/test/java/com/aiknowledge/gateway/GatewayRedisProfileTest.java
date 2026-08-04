package com.aiknowledge.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.profiles.active=redis",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=6379"
})
class GatewayRedisProfileTest {
    @Autowired
    private ApplicationContext context;

    @Autowired
    private KeyResolver clientKeyResolver;

    @Test
    void redisRateLimiterProfileLoads() {
        assertNotNull(clientKeyResolver);
        assertTrue(context.containsBean("redisRateLimiter"));
    }
}
