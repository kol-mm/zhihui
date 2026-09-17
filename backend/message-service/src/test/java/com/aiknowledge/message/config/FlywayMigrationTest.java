package com.aiknowledge.message.config;

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
    void anEmptyDatabaseGetsTheSchemaAndOneConversationPerPair() throws Exception {
        try (MigrationDatabase db = new MigrationDatabase("zc_mig_message_fresh")) {
            db.flyway().migrate();
            assertEquals("1,2,3", db.text("SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history"));
            assertEquals(1, db.number("SELECT COUNT(*) FROM faq"));
            assertTrue(db.hasIndex("chat_session", "uk_session_pair"));
            assertFalse(db.hasIndex("chat_session", "idx_session_pair"));
            assertTrue(db.hasIndex("chat_message_removal", "idx_removal_session"));

            db.execute("INSERT INTO chat_session (user_a_id, user_b_id) VALUES (3, 5)");
            assertThrows(SQLException.class, () -> db.execute("INSERT INTO chat_session (user_a_id, user_b_id) VALUES (3, 5)"));
            // The smaller id always comes first, so the reversed pair cannot slip past the unique key.
            assertThrows(SQLException.class, () -> db.execute("INSERT INTO chat_session (user_a_id, user_b_id) VALUES (5, 3)"));
            assertEquals(0, db.flyway().migrate().migrationsExecuted);
        }
    }

    @Test
    void duplicateConversationsFromBeforeTheConstraintAreMerged() throws Exception {
        try (MigrationDatabase db = new MigrationDatabase("zc_mig_message_legacy")) {
            db.execute(
                    "CREATE TABLE chat_session (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_a_id BIGINT NOT NULL, user_b_id BIGINT NOT NULL, "
                            + "status VARCHAR(32) DEFAULT 'ACTIVE', updated_at DATETIME DEFAULT CURRENT_TIMESTAMP)",
                    "CREATE TABLE chat_message (id BIGINT PRIMARY KEY AUTO_INCREMENT, session_id BIGINT NOT NULL, sender_id BIGINT NOT NULL, "
                            + "content TEXT NOT NULL, status VARCHAR(32) DEFAULT 'NORMAL', created_at DATETIME DEFAULT CURRENT_TIMESTAMP)",
                    "CREATE TABLE notification (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, type VARCHAR(32) NOT NULL, "
                            + "title VARCHAR(255) NOT NULL, content TEXT, is_read TINYINT DEFAULT 0, created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                            + "target_type VARCHAR(16) NULL, target_id BIGINT NULL, anchor_id BIGINT NULL)",
                    "CREATE TABLE faq (id BIGINT PRIMARY KEY AUTO_INCREMENT, question VARCHAR(255) NOT NULL, answer TEXT NOT NULL, "
                            + "sort_no INT DEFAULT 0, enabled TINYINT DEFAULT 1)",
                    "INSERT INTO faq (id, question, answer) VALUES (8, 'Our own question', 'Our own answer')",
                    // Users 3 and 5 ended up with two conversations, one of them stored the other way round.
                    "INSERT INTO chat_session (id, user_a_id, user_b_id, updated_at) VALUES "
                            + "(1, 5, 3, '2026-01-01 00:00:00'), (2, 3, 5, '2026-02-01 00:00:00'), (3, 3, 7, '2026-01-15 00:00:00')",
                    "INSERT INTO chat_message (id, session_id, sender_id, content) VALUES (1, 1, 5, 'first'), (2, 2, 3, 'second'), (3, 3, 7, 'other')",
                    "INSERT INTO notification (user_id, type, title, target_type, target_id, anchor_id) VALUES (5, 'MESSAGE', 'new', 'CHAT', 2, 2)");

            db.flyway().migrate();

            assertEquals("0,1,2,3", db.text("SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history"));
            assertEquals("1:3:5:2026-02-01 00:00:00,3:3:7:2026-01-15 00:00:00", db.text(
                    "SELECT GROUP_CONCAT(CONCAT(id, ':', user_a_id, ':', user_b_id, ':', updated_at) ORDER BY id) FROM chat_session"));
            assertEquals("1:1,2:1,3:3", db.text("SELECT GROUP_CONCAT(CONCAT(id, ':', session_id) ORDER BY id) FROM chat_message"));
            assertEquals(1, db.number("SELECT target_id FROM notification WHERE target_type = 'CHAT'"));
            assertEquals("Our own question", db.text("SELECT GROUP_CONCAT(question) FROM faq"));
            assertTrue(db.hasColumn("notification", "anchor_id"));
            assertTrue(db.hasIndex("notification", "idx_notification_user_id"));
            assertThrows(SQLException.class, () -> db.execute("INSERT INTO chat_session (user_a_id, user_b_id) VALUES (3, 5)"));
            assertEquals(0, db.flyway().migrate().migrationsExecuted);
        }
    }
}
