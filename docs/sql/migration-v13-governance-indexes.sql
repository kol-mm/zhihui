-- Indexes for the paged governance and report tables: drafts and conversations are read most recently
-- updated first, user reports newest first within a status. community-service, message-service and
-- user-service (their *SchemaMigration classes) also apply these on startup.

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'community_db' AND TABLE_NAME = 'post_draft' AND INDEX_NAME = 'idx_draft_updated');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE community_db.post_draft ADD KEY idx_draft_updated (updated_at, id)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'message_db' AND TABLE_NAME = 'chat_session' AND INDEX_NAME = 'idx_session_updated');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE message_db.chat_session ADD KEY idx_session_updated (updated_at, id)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'user_db' AND TABLE_NAME = 'user_report' AND INDEX_NAME = 'idx_user_report_status_id');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE user_db.user_report ADD KEY idx_user_report_status_id (status, id)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;
