-- Indexes for the admin analytics time windows (created in the last N days) and the feedback overview counts.
-- knowledge-service, community-service and message-service also apply these on startup.
-- created_at is written once, so the window indexes add no cost to the view, like or status updates.

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'knowledge_db' AND TABLE_NAME = 'knowledge_file' AND INDEX_NAME = 'idx_file_audit_created');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE knowledge_db.knowledge_file ADD KEY idx_file_audit_created (audit_status, created_at)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'community_db' AND TABLE_NAME = 'post' AND INDEX_NAME = 'idx_post_status_created');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE community_db.post ADD KEY idx_post_status_created (status, created_at)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'message_db' AND TABLE_NAME = 'feedback_ticket' AND INDEX_NAME = 'idx_feedback_created');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE message_db.feedback_ticket ADD KEY idx_feedback_created (created_at)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;

-- The feedback overview groups tickets by type and status; this index answers that without reading rows.
SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'message_db' AND TABLE_NAME = 'feedback_ticket' AND INDEX_NAME = 'idx_feedback_type_status');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE message_db.feedback_ticket ADD KEY idx_feedback_type_status (type, status)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;
