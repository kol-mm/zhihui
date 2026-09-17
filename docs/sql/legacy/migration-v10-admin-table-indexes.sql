-- Indexes for the paged admin tables: user management and the feedback ticket queue.
-- user-service (UserSchemaMigration) and message-service (MessageSchemaMigration) also apply these on startup.

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'user_db' AND TABLE_NAME = 'user' AND INDEX_NAME = 'idx_user_status_id');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE user_db.user ADD KEY idx_user_status_id (status, id)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'user_db' AND TABLE_NAME = 'user' AND INDEX_NAME = 'idx_user_role_id');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE user_db.user ADD KEY idx_user_role_id (role, id)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'message_db' AND TABLE_NAME = 'feedback_ticket' AND INDEX_NAME = 'idx_feedback_status_id');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE message_db.feedback_ticket ADD KEY idx_feedback_status_id (status, id)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'message_db' AND TABLE_NAME = 'feedback_ticket' AND INDEX_NAME = 'idx_feedback_user_ticket');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE message_db.feedback_ticket ADD KEY idx_feedback_user_ticket (user_id, id)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;
