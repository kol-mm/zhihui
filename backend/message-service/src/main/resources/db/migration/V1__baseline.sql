-- message_db schema at the switch to Flyway.
-- Applied by Flyway when the service starts (spring.flyway, mysql profile). Existing databases are baselined at
-- version 0, so this script also runs on them: every statement is idempotent.


CREATE TABLE IF NOT EXISTS chat_session (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_a_id BIGINT NOT NULL, user_b_id BIGINT NOT NULL, status VARCHAR(32) DEFAULT 'ACTIVE', updated_at DATETIME DEFAULT CURRENT_TIMESTAMP, KEY idx_session_pair (user_a_id, user_b_id), KEY idx_session_user_b (user_b_id), KEY idx_session_updated (updated_at, id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS chat_message (id BIGINT PRIMARY KEY AUTO_INCREMENT, session_id BIGINT NOT NULL, sender_id BIGINT NOT NULL, content TEXT NOT NULL, status VARCHAR(32) DEFAULT 'NORMAL', created_at DATETIME DEFAULT CURRENT_TIMESTAMP, KEY idx_message_session (session_id, created_at), KEY idx_message_session_id (session_id, id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS notification (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, type VARCHAR(32) NOT NULL, title VARCHAR(255) NOT NULL, content TEXT, is_read TINYINT DEFAULT 0, created_at DATETIME DEFAULT CURRENT_TIMESTAMP, target_type VARCHAR(16) NULL, target_id BIGINT NULL, anchor_id BIGINT NULL, KEY idx_notification_user (user_id, is_read, id), KEY idx_notification_user_id (user_id, id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS feedback_ticket (id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, type VARCHAR(32) NOT NULL, content TEXT NOT NULL, status VARCHAR(32) DEFAULT 'PENDING', official_reply TEXT, assignee_user_id BIGINT, assigned_at DATETIME, closed_at DATETIME, created_at DATETIME DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, KEY idx_feedback_assignee (assignee_user_id, status), KEY idx_feedback_status_id (status, id), KEY idx_feedback_user_ticket (user_id, id), KEY idx_feedback_created (created_at), KEY idx_feedback_type_status (type, status)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS faq (id BIGINT PRIMARY KEY AUTO_INCREMENT, question VARCHAR(255) NOT NULL, answer TEXT NOT NULL, sort_no INT DEFAULT 0, enabled TINYINT DEFAULT 1) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Notification links (v15).
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notification' AND COLUMN_NAME = 'target_type') = 0, 'ALTER TABLE `notification` ADD COLUMN `target_type` VARCHAR(16) NULL', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notification' AND COLUMN_NAME = 'target_id') = 0, 'ALTER TABLE `notification` ADD COLUMN `target_id` BIGINT NULL', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notification' AND COLUMN_NAME = 'anchor_id') = 0, 'ALTER TABLE `notification` ADD COLUMN `anchor_id` BIGINT NULL', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

-- Indexes added after the first release.
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_message' AND INDEX_NAME = 'idx_message_session_id') = 0, 'ALTER TABLE `chat_message` ADD KEY `idx_message_session_id` (session_id, id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_session' AND INDEX_NAME = 'idx_session_pair') = 0, 'ALTER TABLE `chat_session` ADD KEY `idx_session_pair` (user_a_id, user_b_id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_session' AND INDEX_NAME = 'idx_session_user_b') = 0, 'ALTER TABLE `chat_session` ADD KEY `idx_session_user_b` (user_b_id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_session' AND INDEX_NAME = 'idx_session_updated') = 0, 'ALTER TABLE `chat_session` ADD KEY `idx_session_updated` (updated_at, id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notification' AND INDEX_NAME = 'idx_notification_user') = 0, 'ALTER TABLE `notification` ADD KEY `idx_notification_user` (user_id, is_read, id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notification' AND INDEX_NAME = 'idx_notification_user_id') = 0, 'ALTER TABLE `notification` ADD KEY `idx_notification_user_id` (user_id, id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feedback_ticket' AND INDEX_NAME = 'idx_feedback_status_id') = 0, 'ALTER TABLE `feedback_ticket` ADD KEY `idx_feedback_status_id` (status, id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feedback_ticket' AND INDEX_NAME = 'idx_feedback_user_ticket') = 0, 'ALTER TABLE `feedback_ticket` ADD KEY `idx_feedback_user_ticket` (user_id, id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feedback_ticket' AND INDEX_NAME = 'idx_feedback_created') = 0, 'ALTER TABLE `feedback_ticket` ADD KEY `idx_feedback_created` (created_at)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'feedback_ticket' AND INDEX_NAME = 'idx_feedback_type_status') = 0, 'ALTER TABLE `feedback_ticket` ADD KEY `idx_feedback_type_status` (type, status)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

-- The starter FAQ of a new installation.
INSERT INTO `faq` (id, question, answer, sort_no, enabled)
SELECT * FROM (
SELECT 1 AS id, 'How do I upload knowledge?' AS question, 'Open the knowledge library and choose Upload.' AS answer, 10 AS sort_no, 1 AS enabled
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM `faq`);
