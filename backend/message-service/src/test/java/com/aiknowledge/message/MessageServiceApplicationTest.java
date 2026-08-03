package com.aiknowledge.message;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.cloud.nacos.discovery.enabled=false")
class MessageServiceApplicationTest {
    @Test
    void contextLoads() {
    }
}
