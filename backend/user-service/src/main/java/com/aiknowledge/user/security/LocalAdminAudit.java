package com.aiknowledge.user.security;

import com.aiknowledge.common.AdminAudit;
import com.aiknowledge.common.AuditEntry;
import com.aiknowledge.user.store.AuditLogStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** The user service keeps the log, so its own actions are written straight to it. */
@Component
public class LocalAdminAudit implements AdminAudit {
    private static final Logger log = LoggerFactory.getLogger(LocalAdminAudit.class);
    private final AuditLogStore store;

    public LocalAdminAudit(AuditLogStore store) {
        this.store = store;
    }

    @Override
    public void record(String authorization, Event event) {
        AuditEntry entry = null;
        try {
            entry = AuditEntry.forRequest(authorization, event, "user-service");
            if (entry != null) store.save(entry);
        } catch (RuntimeException failure) {
            log.error("Admin audit entry could not be stored ({}): {}", failure.toString(), entry == null ? event : entry);
        }
    }
}
