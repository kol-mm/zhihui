-- knowledge_db schema at the switch to Flyway.
-- Applied by Flyway when the service starts (spring.flyway, mysql profile). Existing databases are baselined at
-- version 0, so this script also runs on them: every statement is idempotent.


CREATE TABLE IF NOT EXISTS knowledge_category (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(64) NOT NULL, parent_id BIGINT DEFAULT 0, sort_no INT DEFAULT 0) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS knowledge_file (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, category_id BIGINT, title VARCHAR(255) NOT NULL, file_url VARCHAR(500), file_type VARCHAR(16), parse_status VARCHAR(32) DEFAULT 'PENDING', audit_status VARCHAR(32) DEFAULT 'PENDING', views INT DEFAULT 0, downloads INT DEFAULT 0, created_at DATETIME DEFAULT CURRENT_TIMESTAMP, KEY idx_file_user (user_id), KEY idx_file_user_id (user_id, id), KEY idx_file_category (category_id), KEY idx_file_audit (audit_status, id), KEY idx_file_category_audit (category_id, audit_status, id), KEY idx_file_audit_created (audit_status, created_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS knowledge_collect (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, file_id BIGINT NOT NULL, created_at DATETIME DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY uk_file_collect (user_id, file_id), KEY idx_file_collect_file (file_id), KEY idx_collect_user_id (user_id, id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS knowledge_like (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, file_id BIGINT NOT NULL, created_at DATETIME DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY uk_file_like (user_id, file_id), KEY idx_file_like_file (file_id), KEY idx_like_user_id (user_id, id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS knowledge_download (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, file_id BIGINT NOT NULL, created_at DATETIME DEFAULT CURRENT_TIMESTAMP, KEY idx_download_file (file_id), KEY idx_download_user_id (user_id, id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS knowledge_forward (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, file_id BIGINT NOT NULL, created_at DATETIME DEFAULT CURRENT_TIMESTAMP, KEY idx_forward_user (user_id, created_at), KEY idx_forward_user_id (user_id, id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS knowledge_report (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, file_id BIGINT NOT NULL, reason VARCHAR(255), status VARCHAR(32) DEFAULT 'PENDING', created_at DATETIME DEFAULT CURRENT_TIMESTAMP, KEY idx_knowledge_report_status (status)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Indexes added after the first release.
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_file' AND INDEX_NAME = 'idx_file_audit') = 0, 'ALTER TABLE `knowledge_file` ADD KEY `idx_file_audit` (audit_status, id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_file' AND INDEX_NAME = 'idx_file_category_audit') = 0, 'ALTER TABLE `knowledge_file` ADD KEY `idx_file_category_audit` (category_id, audit_status, id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_file' AND INDEX_NAME = 'idx_file_user_id') = 0, 'ALTER TABLE `knowledge_file` ADD KEY `idx_file_user_id` (user_id, id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_file' AND INDEX_NAME = 'idx_file_audit_created') = 0, 'ALTER TABLE `knowledge_file` ADD KEY `idx_file_audit_created` (audit_status, created_at)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_like' AND INDEX_NAME = 'idx_file_like_file') = 0, 'ALTER TABLE `knowledge_like` ADD KEY `idx_file_like_file` (file_id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_like' AND INDEX_NAME = 'idx_like_user_id') = 0, 'ALTER TABLE `knowledge_like` ADD KEY `idx_like_user_id` (user_id, id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_collect' AND INDEX_NAME = 'idx_file_collect_file') = 0, 'ALTER TABLE `knowledge_collect` ADD KEY `idx_file_collect_file` (file_id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_collect' AND INDEX_NAME = 'idx_collect_user_id') = 0, 'ALTER TABLE `knowledge_collect` ADD KEY `idx_collect_user_id` (user_id, id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_download' AND INDEX_NAME = 'idx_download_user_id') = 0, 'ALTER TABLE `knowledge_download` ADD KEY `idx_download_user_id` (user_id, id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_forward' AND INDEX_NAME = 'idx_forward_user_id') = 0, 'ALTER TABLE `knowledge_forward` ADD KEY `idx_forward_user_id` (user_id, id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_report' AND INDEX_NAME = 'idx_knowledge_report_status') = 0, 'ALTER TABLE `knowledge_report` ADD KEY `idx_knowledge_report_status` (status)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

-- Starter categories of a new installation.
INSERT INTO `knowledge_category` (id, name, parent_id, sort_no)
SELECT * FROM (
SELECT 1 AS id, 'Product' AS name, 0 AS parent_id, 10 AS sort_no
UNION ALL SELECT 2, 'Engineering', 0, 20
UNION ALL SELECT 3, 'Operations', 0, 30
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM `knowledge_category`);
