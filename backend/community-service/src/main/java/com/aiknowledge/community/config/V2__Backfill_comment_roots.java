package com.aiknowledge.community.config;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Gives every comment written before comment threads existed its thread root. Replies follow their parent one
 * level per pass, so the loop needs at most as many passes as the deepest thread. Flyway runs it once, after V1;
 * Spring Boot hands the bean to Flyway, and the class name carries the version.
 */
@Component
public class V2__Backfill_comment_roots extends BaseJavaMigration {
    private static final Logger log = LoggerFactory.getLogger(V2__Backfill_comment_roots.class);
    private static final int MAX_PASSES = 64;

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            try (ResultSet missing = statement.executeQuery("SELECT COUNT(*) FROM comment WHERE root_id IS NULL")) {
                if (!missing.next() || missing.getLong(1) == 0) return;
            }
            int updated = statement.executeUpdate(
                    "UPDATE comment SET root_id = id, is_root = 1 WHERE root_id IS NULL AND (parent_id IS NULL OR parent_id <= 0)");
            for (int pass = 0; pass < MAX_PASSES; pass++) {
                int replies = statement.executeUpdate("UPDATE comment child JOIN comment parent ON child.parent_id = parent.id "
                        + "SET child.root_id = parent.root_id WHERE child.root_id IS NULL AND parent.root_id IS NOT NULL");
                updated += replies;
                if (replies == 0) break;
            }
            // Replies whose parent no longer exists (or parent cycles) become their own thread root.
            updated += statement.executeUpdate("UPDATE comment SET root_id = id, is_root = 1 WHERE root_id IS NULL");
            log.info("Backfilled comment.root_id for {} rows", updated);
        }
    }
}
