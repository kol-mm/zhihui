package com.aiknowledge.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GatewayStatusControllerTest {
    @Test
    void reportsRedisProfileWithoutExposingConnectionDetails() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("redis");
        GatewayStatusController controller = new GatewayStatusController(environment);

        Map<String, Object> response = controller.status();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");

        assertEquals("redis", data.get("rateLimitMode"));
        assertEquals("nacos", data.get("discoveryMode"));
        assertEquals(false, data.containsKey("host"));
    }

    @Test
    void reportsDirectDiscoveryWhenNacosIsDisabled() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.cloud.nacos.discovery.enabled", "false");
        GatewayStatusController controller = new GatewayStatusController(environment);

        Map<String, Object> response = controller.status();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");

        assertEquals("direct", data.get("discoveryMode"));
        assertEquals("local", data.get("rateLimitMode"));
    }
}
