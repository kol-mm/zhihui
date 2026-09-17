-- community_db schema at the switch to Flyway.
-- Applied by Flyway when the service starts (spring.flyway, mysql profile). Existing databases are baselined at
-- version 0, so this script also runs on them: every statement is idempotent.


CREATE TABLE IF NOT EXISTS post (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, title VARCHAR(255) NOT NULL, content TEXT, status VARCHAR(32) DEFAULT 'PENDING', created_at DATETIME DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, KEY idx_post_user (user_id, created_at), KEY idx_post_status_id (status, id), KEY idx_post_status_created (status, created_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS post_image (id BIGINT PRIMARY KEY AUTO_INCREMENT, post_id BIGINT NOT NULL, image_url VARCHAR(500) NOT NULL, sort_no INT DEFAULT 0, KEY idx_post_image_post (post_id, sort_no)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS comment (id BIGINT PRIMARY KEY AUTO_INCREMENT, post_id BIGINT NOT NULL, user_id BIGINT NOT NULL, parent_id BIGINT DEFAULT 0, root_id BIGINT NULL, is_root TINYINT(1) NOT NULL DEFAULT 0, content TEXT NOT NULL, source VARCHAR(32) DEFAULT 'POST', status VARCHAR(32) DEFAULT 'VISIBLE', created_at DATETIME DEFAULT CURRENT_TIMESTAMP, KEY idx_comment_post (post_id, created_at), KEY idx_comment_thread (post_id, root_id, id), KEY idx_comment_root (post_id, is_root, id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS post_like (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, post_id BIGINT NOT NULL, source VARCHAR(32) DEFAULT 'POST', created_at DATETIME DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY uk_post_like (user_id, post_id), KEY idx_post_like_post (post_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS post_collect (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, post_id BIGINT NOT NULL, source VARCHAR(32) DEFAULT 'POST', created_at DATETIME DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY uk_post_collect (user_id, post_id), KEY idx_post_collect_post (post_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS post_draft (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, title VARCHAR(255), content TEXT, image_urls_json TEXT, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, KEY idx_draft_updated (updated_at, id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Comment threads (v5, v7): the thread root of every comment. is_root is only marked when the column is new; V2
-- fills root_id for comments written before threads existed.
SET @needs_is_root = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comment' AND COLUMN_NAME = 'is_root') = 0;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comment' AND COLUMN_NAME = 'root_id') = 0, 'ALTER TABLE `comment` ADD COLUMN `root_id` BIGINT NULL AFTER parent_id', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comment' AND COLUMN_NAME = 'is_root') = 0, 'ALTER TABLE `comment` ADD COLUMN `is_root` TINYINT(1) NOT NULL DEFAULT 0 AFTER root_id', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF(@needs_is_root, 'UPDATE comment SET is_root = 1 WHERE root_id = id', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

-- Indexes added after the first release.
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comment' AND INDEX_NAME = 'idx_comment_thread') = 0, 'ALTER TABLE `comment` ADD KEY `idx_comment_thread` (post_id, root_id, id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comment' AND INDEX_NAME = 'idx_comment_root') = 0, 'ALTER TABLE `comment` ADD KEY `idx_comment_root` (post_id, is_root, id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'post_like' AND INDEX_NAME = 'idx_post_like_post') = 0, 'ALTER TABLE `post_like` ADD KEY `idx_post_like_post` (post_id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'post_collect' AND INDEX_NAME = 'idx_post_collect_post') = 0, 'ALTER TABLE `post_collect` ADD KEY `idx_post_collect_post` (post_id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'post_image' AND INDEX_NAME = 'idx_post_image_post') = 0, 'ALTER TABLE `post_image` ADD KEY `idx_post_image_post` (post_id, sort_no)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'post' AND INDEX_NAME = 'idx_post_status_id') = 0, 'ALTER TABLE `post` ADD KEY `idx_post_status_id` (status, id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'post' AND INDEX_NAME = 'idx_post_status_created') = 0, 'ALTER TABLE `post` ADD KEY `idx_post_status_created` (status, created_at)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'post_draft' AND INDEX_NAME = 'idx_draft_updated') = 0, 'ALTER TABLE `post_draft` ADD KEY `idx_draft_updated` (updated_at, id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;
