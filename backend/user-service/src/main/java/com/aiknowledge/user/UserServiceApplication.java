package com.aiknowledge.user;

import com.aiknowledge.common.PlatformConfigClient;
import com.aiknowledge.user.security.AdminPasswordCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class UserServiceApplication {
    public static void main(String[] args) {
        // A one-off command run on the server (see AdminPasswordCommand), rather than the service itself.
        if (args.length > 0 && AdminPasswordCommand.NAME.equals(args[0])) {
            System.exit(AdminPasswordCommand.launch(UserServiceApplication.class, args));
        }
        SpringApplication.run(UserServiceApplication.class, args);
    }

    @Bean
    PlatformConfigClient platformConfigClient(
            ObjectMapper objectMapper,
            @Value("${platform.ai-config-url:http://127.0.0.1:8200/ai/config/public}") String configUrl
    ) {
        return new PlatformConfigClient(objectMapper, configUrl);
    }
}
