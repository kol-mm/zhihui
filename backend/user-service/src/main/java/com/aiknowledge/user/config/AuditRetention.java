package com.aiknowledge.user.config;

import com.aiknowledge.user.store.AuditLogStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;

/** Removes action log entries older than AUDIT_RETENTION_DAYS (default 365; 0 keeps them all), once a day. */
@Configuration
@EnableScheduling
public class AuditRetention {
    private static final Logger log = LoggerFactory.getLogger(AuditRetention.class);
    private final AuditLogStore store;
    private final int retentionDays;

    public AuditRetention(AuditLogStore store, @Value("${app.audit.retention-days:365}") int retentionDays) {
        this.store = store;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "0 23 3 * * *")
    public void prune() {
        if (retentionDays <= 0) return;
        try {
            int removed = store.removeBefore(LocalDateTime.now().minusDays(retentionDays));
            if (removed > 0) log.info("Removed {} admin audit entries older than {} days", removed, retentionDays);
        } catch (RuntimeException failure) {
            log.warn("Admin audit retention failed: {}", failure.toString());
        }
    }
}
