package com.aiknowledge.knowledge.config;

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
    void anEmptyDatabaseGetsTheSchemaAndTheStarterCategories() throws Exception {
        try (MigrationDatabase db = new MigrationDatabase("zc_mig_knowledge_fresh")) {
            db.flyway().migrate();
            assertEquals("1", db.text("SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history"));
            assertEquals(3, db.number("SELECT COUNT(*) FROM knowledge_category"));
            assertTrue(db.hasIndex("knowledge_file", "idx_file_audit_created"));
            assertEquals(0, db.flyway().migrate().migrationsExecuted);
        }
    }

    @Test
    void aDatabaseFromBeforeFlywayIsUpgradedAndKeepsItsCategories() throws Exception {
        try (MigrationDatabase db = new MigrationDatabase("zc_mig_knowledge_legacy")) {
            db.execute(
                    "CREATE TABLE knowledge_category (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(64) NOT NULL, "
                            + "parent_id BIGINT DEFAULT 0, sort_no INT DEFAULT 0)",
                    "CREATE TABLE knowledge_file (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, category_id BIGINT, "
                            + "title VARCHAR(255) NOT NULL, file_url VARCHAR(500), file_type VARCHAR(16), parse_status VARCHAR(32) DEFAULT 'PENDING', "
                            + "audit_status VARCHAR(32) DEFAULT 'PENDING', views INT DEFAULT 0, downloads INT DEFAULT 0, "
                            + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, KEY idx_file_user (user_id))",
                    "CREATE TABLE knowledge_like (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, file_id BIGINT NOT NULL, "
                            + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY uk_file_like (user_id, file_id))",
                    "INSERT INTO knowledge_category (id, name) VALUES (5, 'Docs')",
                    "INSERT INTO knowledge_file (id, user_id, title, audit_status) VALUES (9, 1, 'kept', 'APPROVED')");

            db.flyway().migrate();

            assertEquals("0,1", db.text("SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history"));
            for (String index : new String[]{"idx_file_audit", "idx_file_category_audit", "idx_file_user_id", "idx_file_audit_created"}) {
                assertTrue(db.hasIndex("knowledge_file", index), index);
            }
            assertTrue(db.hasIndex("knowledge_like", "idx_file_like_file"));
            assertTrue(db.hasIndex("knowledge_like", "idx_like_user_id"));
            assertTrue(db.hasIndex("knowledge_report", "idx_knowledge_report_status"));
            assertEquals("Docs", db.text("SELECT GROUP_CONCAT(name) FROM knowledge_category"));
            assertEquals("kept", db.text("SELECT title FROM knowledge_file WHERE id = 9"));
            assertEquals(0, db.flyway().migrate().migrationsExecuted);
        }
    }
}
