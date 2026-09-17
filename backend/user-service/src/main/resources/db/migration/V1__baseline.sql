-- user_db schema at the switch to Flyway.
-- Applied by Flyway when the service starts (spring.flyway, mysql profile). Existing databases are baselined at
-- version 0, so this script also runs on them: every statement is idempotent.


CREATE TABLE IF NOT EXISTS `user` (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL DEFAULT '',
  avatar_url VARCHAR(500),
  nickname VARCHAR(64) NOT NULL,
  signature VARCHAR(255),
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  role VARCHAR(16) NOT NULL DEFAULT 'USER',
  publish_policy VARCHAR(32) NOT NULL DEFAULT 'STANDARD',
  messaging_enabled TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_user_status_id (status, id),
  KEY idx_user_role_id (role, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS role (id BIGINT PRIMARY KEY AUTO_INCREMENT, code VARCHAR(64) NOT NULL UNIQUE, name VARCHAR(64) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS permission (id BIGINT PRIMARY KEY AUTO_INCREMENT, code VARCHAR(128) NOT NULL UNIQUE, name VARCHAR(64) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS user_role (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, role_id BIGINT NOT NULL, UNIQUE KEY uk_user_role (user_id, role_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS user_follow (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, target_user_id BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY uk_follow (user_id, target_user_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS user_block (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, blocked_user_id BIGINT NOT NULL, reason VARCHAR(255), created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY uk_block (user_id, blocked_user_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS user_report (id BIGINT PRIMARY KEY AUTO_INCREMENT, reporter_id BIGINT NOT NULL, target_user_id BIGINT NOT NULL, reason VARCHAR(255), status VARCHAR(32) NOT NULL DEFAULT 'PENDING', result VARCHAR(255), created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, KEY idx_user_report_status (status, created_at), KEY idx_user_report_status_id (status, id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS user_behavior_log (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, behavior_type VARCHAR(32) NOT NULL, target_type VARCHAR(32) NOT NULL, target_id BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, KEY idx_user_behavior (user_id, behavior_type, created_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS password_reset_request (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, username VARCHAR(64) NOT NULL, contact VARCHAR(100), status VARCHAR(16) NOT NULL DEFAULT 'PENDING', code_hash VARCHAR(100), code_expires_at DATETIME, failed_attempts INT NOT NULL DEFAULT 0, handled_by BIGINT, note VARCHAR(255), created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, KEY idx_reset_status_id (status, id), KEY idx_reset_user_status (user_id, status)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Indexes added after the first release.
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND INDEX_NAME = 'idx_user_status_id') = 0, 'ALTER TABLE `user` ADD KEY `idx_user_status_id` (status, id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND INDEX_NAME = 'idx_user_role_id') = 0, 'ALTER TABLE `user` ADD KEY `idx_user_role_id` (role, id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_report' AND INDEX_NAME = 'idx_user_report_status_id') = 0, 'ALTER TABLE `user_report` ADD KEY `idx_user_report_status_id` (status, id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

-- The demo and admin accounts of a new installation (passwords demo / admin123; change them after the first login).
INSERT INTO `user` (id, username, password_hash, nickname, status, role, publish_policy, messaging_enabled)
SELECT * FROM (
SELECT 1 AS id, 'demo' AS username, '$2a$10$s.FoZgtcejwp0LTrreQ2oO7yrGQ8pgeBuaXyIQvGx1WbD1awva9Ja' AS password_hash, 'Demo User' AS nickname, 'ACTIVE' AS status, 'USER' AS role, 'STANDARD' AS publish_policy, 1 AS messaging_enabled
UNION ALL SELECT 2, 'admin', '$2a$10$HpKsfL9ZMThcXh4e8zmZeOcgwyNea4eqc6mXVWwYVFHGNrj2bywkS', 'Local Admin', 'ACTIVE', 'ADMIN', 'STANDARD', 1
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM `user`);
