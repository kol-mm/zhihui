package com.aiknowledge.community.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the service's migrations against real MySQL databases; skipped unless MIGRATION_TEST_JDBC_URL is set. */
@EnabledIfEnvironmentVariable(named = MigrationDatabase.ENABLED_BY, matches = ".+")
class FlywayMigrationTest {
    @Test
    void anEmptyDatabaseGetsTheSchema() throws Exception {
        try (MigrationDatabase db = new MigrationDatabase("zc_mig_community_fresh")) {
            db.flyway(new V2__Backfill_comment_roots()).migrate();
            assertEquals("1,2", db.text("SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history"));
            assertTrue(db.hasColumn("comment", "is_root"));
            assertTrue(db.hasIndex("comment", "idx_comment_root"));
            assertEquals(0, db.flyway(new V2__Backfill_comment_roots()).migrate().migrationsExecuted);
        }
    }

    @Test
    void commentsFromBeforeThreadsGetTheirThreadRoots() throws Exception {
        try (MigrationDatabase db = new MigrationDatabase("zc_mig_community_legacy")) {
            db.execute(
                    "CREATE TABLE post (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, title VARCHAR(255) NOT NULL, "
                            + "content TEXT, status VARCHAR(32) DEFAULT 'PENDING', created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                            + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)",
                    "CREATE TABLE comment (id BIGINT PRIMARY KEY AUTO_INCREMENT, post_id BIGINT NOT NULL, user_id BIGINT NOT NULL, "
                            + "parent_id BIGINT DEFAULT 0, content TEXT NOT NULL, source VARCHAR(32) DEFAULT 'POST', "
                            + "status VARCHAR(32) DEFAULT 'VISIBLE', created_at DATETIME DEFAULT CURRENT_TIMESTAMP)",
                    "INSERT INTO post (id, user_id, title, status) VALUES (1, 1, 'kept', 'PUBLISHED')",
                    // 1 <- 2 <- 3 is a thread; 4 replies to a comment that is gone; 5 is a root with no parent at all.
                    "INSERT INTO comment (id, post_id, user_id, parent_id, content) VALUES "
                            + "(1, 1, 1, 0, 'root'), (2, 1, 2, 1, 'reply'), (3, 1, 1, 2, 'nested'), (4, 1, 2, 99, 'orphan')",
                    "INSERT INTO comment (id, post_id, user_id, parent_id, content) VALUES (5, 1, 2, NULL, 'null parent')");

            db.flyway(new V2__Backfill_comment_roots()).migrate();

            assertEquals("0,1,2", db.text("SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history"));
            assertEquals("1:1:1,2:1:0,3:1:0,4:4:1,5:5:1",
                    db.text("SELECT GROUP_CONCAT(CONCAT(id, ':', root_id, ':', is_root) ORDER BY id) FROM comment"));
            assertTrue(db.hasIndex("comment", "idx_comment_thread"));
            assertTrue(db.hasIndex("post", "idx_post_status_created"));
            assertEquals(0, db.number("SELECT COUNT(*) FROM post_draft"));
            assertEquals(0, db.flyway(new V2__Backfill_comment_roots()).migrate().migrationsExecuted);
        }
    }
}
