package com.aiknowledge.gateway;

import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

@RestController
public class GatewayStatusController {
    private final Environment environment;

    public GatewayStatusController(Environment environment) {
        this.environment = environment;
    }

    @GetMapping("/gateway/status")
    public Map<String, Object> status() {
        boolean redisEnabled = Arrays.stream(environment.getActiveProfiles())
                .anyMatch("redis"::equalsIgnoreCase);
        boolean nacosEnabled = environment.getProperty(
                "spring.cloud.nacos.discovery.enabled",
                Boolean.class,
                true
        );
        return Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of(
                        "service", "gateway",
                        "time", Instant.now().toString(),
                        "rateLimitMode", redisEnabled ? "redis" : "local",
                        "discoveryMode", nacosEnabled ? "nacos" : "direct"
                )
        );
    }
}
