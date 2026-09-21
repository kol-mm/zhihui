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
            assertEquals("1,2,3,4,5", db.text("SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history"));
            assertEquals(2, db.number("SELECT COUNT(*) FROM user"));
            assertEquals("ADMIN", db.text("SELECT role FROM user WHERE username = 'admin'"));
            // Exactly one super administrator, and it is the seeded admin account.
            assertEquals(1, db.number("SELECT COUNT(*) FROM user WHERE super_admin = 1"));
            assertEquals("admin", db.text("SELECT username FROM user WHERE super_admin = 1"));
            assertTrue(db.hasIndex("password_reset_request", "idx_reset_status_id"));
            assertTrue(db.hasIndex("user", "uk_user_verified_email"));
            assertEquals(0, db.number("SELECT COUNT(*) FROM email_verification"));
            assertTrue(db.hasIndex("admin_audit_log", "idx_audit_category_id"));
            assertTrue(db.hasIndex("user_profile_change", "uk_profile_change_open"));
            assertTrue(db.hasColumn("admin_audit_log", "subject_user_id"));
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

            assertEquals("0,1,2,3,4,5", db.text("SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history"));
            // The operator's own admin account becomes the super administrator; the member does not.
            assertEquals(1, db.number("SELECT COUNT(*) FROM user WHERE super_admin = 1"));
            assertEquals("admin", db.text("SELECT username FROM user WHERE super_admin = 1"));
            assertEquals(0, db.number("SELECT super_admin FROM user WHERE username = 'alice'"));
            assertTrue(db.hasColumn("user", "email"));
            assertEquals(0, db.number("SELECT COUNT(*) FROM user WHERE email IS NOT NULL"));
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

    @Test
    void onlyVerifiedAddressesHaveToBeUnique() throws Exception {
        try (MigrationDatabase db = new MigrationDatabase("zc_mig_user_email")) {
            db.flyway().migrate();
            db.execute("INSERT INTO user (id, username, password_hash, nickname) VALUES "
                    + "(11, 'u11', 'h', 'U11'), (12, 'u12', 'h', 'U12'), (13, 'u13', 'h', 'U13')");
            // Any number of accounts may bind the same address while it is unverified.
            db.execute("UPDATE user SET email = 'same@example.com' WHERE id IN (11, 12, 13)",
                    "UPDATE user SET email_verified_at = NOW() WHERE id = 11");
            assertEquals("same@example.com", db.text("SELECT verified_email FROM user WHERE id = 11"));
            assertEquals(null, db.text("SELECT verified_email FROM user WHERE id = 12"));
            SQLException taken = assertThrows(SQLException.class,
                    () -> db.execute("UPDATE user SET email_verified_at = NOW() WHERE id = 12"));
            assertEquals(1062, taken.getErrorCode());
            // Unbinding frees the address for another account.
            db.execute("UPDATE user SET email = NULL, email_verified_at = NULL WHERE id = 11",
                    "UPDATE user SET email_verified_at = NOW() WHERE id = 12");
            assertEquals(1, db.number("SELECT COUNT(*) FROM user WHERE verified_email = 'same@example.com'"));
        }
    }

    @Test
    void aMemberCanOnlyHaveOneProfileChangeWaiting() throws Exception {
        try (MigrationDatabase db = new MigrationDatabase("zc_mig_user_profile")) {
            db.flyway().migrate();
            db.execute("INSERT INTO user_profile_change (user_id, nickname, before_nickname) VALUES (5, '新昵称', '原昵称')");
            SQLException taken = assertThrows(SQLException.class, () -> db.execute(
                    "INSERT INTO user_profile_change (user_id, nickname, before_nickname) VALUES (5, '再改一次', '原昵称')"));
            assertEquals(1062, taken.getErrorCode());
            // Deciding the first one frees the member to submit again.
            db.execute("UPDATE user_profile_change SET status = 'REJECTED' WHERE user_id = 5",
                    "INSERT INTO user_profile_change (user_id, nickname, before_nickname) VALUES (5, '再改一次', '原昵称')");
            assertEquals(1, db.number("SELECT COUNT(*) FROM user_profile_change WHERE status = 'PENDING'"));
            // Another member is unaffected.
            db.execute("INSERT INTO user_profile_change (user_id, nickname, before_nickname) VALUES (6, '别人', '原')");
            assertEquals(2, db.number("SELECT COUNT(*) FROM user_profile_change WHERE status = 'PENDING'"));
        }
    }
}
