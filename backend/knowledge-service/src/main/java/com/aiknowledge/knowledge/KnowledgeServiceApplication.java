package com.aiknowledge.knowledge;

import com.aiknowledge.common.PlatformConfigClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class KnowledgeServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(KnowledgeServiceApplication.class, args);
    }

    @Bean
    PlatformConfigClient platformConfigClient(
            ObjectMapper objectMapper,
            @Value("${platform.ai-config-url:http://127.0.0.1:8200/ai/config/public}") String configUrl
    ) {
        return new PlatformConfigClient(objectMapper, configUrl);
    }
}
