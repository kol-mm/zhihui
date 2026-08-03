package com.aiknowledge.user;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.cloud.nacos.discovery.enabled=false")
class UserServiceApplicationTest {
    @Test
    void contextLoads() {
    }
}
