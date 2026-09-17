-- Indexes for feed paging, batched post lookups, chat paging, conversation lists and notifications.
-- community-service (CommunitySchemaMigration) and message-service (MessageSchemaMigration) also apply these on startup.

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'community_db' AND TABLE_NAME = 'post_like' AND INDEX_NAME = 'idx_post_like_post');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE community_db.post_like ADD KEY idx_post_like_post (post_id)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'community_db' AND TABLE_NAME = 'post_collect' AND INDEX_NAME = 'idx_post_collect_post');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE community_db.post_collect ADD KEY idx_post_collect_post (post_id)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'community_db' AND TABLE_NAME = 'post_image' AND INDEX_NAME = 'idx_post_image_post');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE community_db.post_image ADD KEY idx_post_image_post (post_id, sort_no)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'message_db' AND TABLE_NAME = 'chat_message' AND INDEX_NAME = 'idx_message_session_id');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE message_db.chat_message ADD KEY idx_message_session_id (session_id, id)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'message_db' AND TABLE_NAME = 'chat_session' AND INDEX_NAME = 'idx_session_pair');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE message_db.chat_session ADD KEY idx_session_pair (user_a_id, user_b_id)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'message_db' AND TABLE_NAME = 'chat_session' AND INDEX_NAME = 'idx_session_user_b');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE message_db.chat_session ADD KEY idx_session_user_b (user_b_id)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'message_db' AND TABLE_NAME = 'notification' AND INDEX_NAME = 'idx_notification_user');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE message_db.notification ADD KEY idx_notification_user (user_id, is_read, id)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;
