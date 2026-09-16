-- Indexes for knowledge-library paging, batched like/collect lookups and notification listing.
-- knowledge-service (KnowledgeSchemaMigration) and message-service (MessageSchemaMigration) also apply these on startup.

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'knowledge_db' AND TABLE_NAME = 'knowledge_file' AND INDEX_NAME = 'idx_file_audit');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE knowledge_db.knowledge_file ADD KEY idx_file_audit (audit_status, id)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'knowledge_db' AND TABLE_NAME = 'knowledge_file' AND INDEX_NAME = 'idx_file_category_audit');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE knowledge_db.knowledge_file ADD KEY idx_file_category_audit (category_id, audit_status, id)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'knowledge_db' AND TABLE_NAME = 'knowledge_like' AND INDEX_NAME = 'idx_file_like_file');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE knowledge_db.knowledge_like ADD KEY idx_file_like_file (file_id)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'knowledge_db' AND TABLE_NAME = 'knowledge_collect' AND INDEX_NAME = 'idx_file_collect_file');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE knowledge_db.knowledge_collect ADD KEY idx_file_collect_file (file_id)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'message_db' AND TABLE_NAME = 'notification' AND INDEX_NAME = 'idx_notification_user_id');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE message_db.notification ADD KEY idx_notification_user_id (user_id, id)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;
