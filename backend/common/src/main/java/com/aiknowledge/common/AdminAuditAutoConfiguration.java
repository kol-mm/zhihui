package com.aiknowledge.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;

/** Services report administrator actions to the user service, which keeps the log and provides its own recorder. */
@AutoConfiguration(after = JacksonAutoConfiguration.class)
@ConditionalOnClass(name = "jakarta.servlet.http.HttpServletRequest")
public class AdminAuditAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(AdminAudit.class)
    AdminAudit httpAdminAudit(
            ObjectMapper objectMapper,
            @Value("${platform.audit-url:http://127.0.0.1:8101/user/internal/audit}") String auditUrl,
            @Value("${platform.internal-user-token:ai-knowledge-local-internal}") String internalToken,
            @Value("${spring.application.name:service}") String source
    ) {
        return new HttpAdminAudit(objectMapper, auditUrl, internalToken, source);
    }
}
