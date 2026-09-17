package com.aiknowledge.user.config;

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
    void anEmptyDatabaseGetsTheSchemaAndTheStarterAccounts() throws Exception {
        try (MigrationDatabase db = new MigrationDatabase("zc_mig_user_fresh")) {
            db.flyway().migrate();
            assertEquals("1", db.text("SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history"));
            assertEquals(2, db.number("SELECT COUNT(*) FROM user"));
            assertEquals("ADMIN", db.text("SELECT role FROM user WHERE username = 'admin'"));
            assertTrue(db.hasIndex("password_reset_request", "idx_reset_status_id"));
            assertEquals(0, db.flyway().migrate().migrationsExecuted);
        }
    }

    @Test
    void aDatabaseFromBeforeFlywayIsUpgradedAndKeepsItsAccounts() throws Exception {
        try (MigrationDatabase db = new MigrationDatabase("zc_mig_user_legacy")) {
            db.execute(
                    "CREATE TABLE user (id BIGINT PRIMARY KEY AUTO_INCREMENT, username VARCHAR(64) NOT NULL UNIQUE, "
                            + "password_hash VARCHAR(255) NOT NULL DEFAULT '', avatar_url VARCHAR(500), nickname VARCHAR(64) NOT NULL, "
                            + "signature VARCHAR(255), status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', role VARCHAR(16) NOT NULL DEFAULT 'USER', "
                            + "publish_policy VARCHAR(32) NOT NULL DEFAULT 'STANDARD', messaging_enabled TINYINT(1) NOT NULL DEFAULT 1, "
                            + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                            + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)",
                    "CREATE TABLE user_report (id BIGINT PRIMARY KEY AUTO_INCREMENT, reporter_id BIGINT NOT NULL, "
                            + "target_user_id BIGINT NOT NULL, reason VARCHAR(255), status VARCHAR(32) NOT NULL DEFAULT 'PENDING', "
                            + "result VARCHAR(255), created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                            + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, KEY idx_user_report_status (status, created_at))",
                    // The operator deleted the demo account long ago; the starter accounts must not come back.
                    "INSERT INTO user (id, username, password_hash, nickname, role) VALUES "
                            + "(2, 'admin', 'kept-hash', 'Ops', 'ADMIN'), (7, 'alice', 'hash', 'Alice', 'USER')");

            db.flyway().migrate();

            assertEquals("0,1", db.text("SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history"));
            assertTrue(db.hasIndex("user", "idx_user_status_id"));
            assertTrue(db.hasIndex("user", "idx_user_role_id"));
            assertTrue(db.hasIndex("user_report", "idx_user_report_status_id"));
            assertEquals(0, db.number("SELECT COUNT(*) FROM password_reset_request"));
            assertEquals(2, db.number("SELECT COUNT(*) FROM user"));
            assertEquals(0, db.number("SELECT COUNT(*) FROM user WHERE username = 'demo'"));
            assertEquals("kept-hash", db.text("SELECT password_hash FROM user WHERE username = 'admin'"));
            assertEquals(0, db.flyway().migrate().migrationsExecuted);
        }
    }
}
