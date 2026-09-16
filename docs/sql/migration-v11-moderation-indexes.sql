-- Indexes for the paged moderation queues and the open-report badge.
-- community-service (CommunitySchemaMigration) and knowledge-service (KnowledgeSchemaMigration) also apply these on startup.

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'community_db' AND TABLE_NAME = 'post' AND INDEX_NAME = 'idx_post_status_id');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE community_db.post ADD KEY idx_post_status_id (status, id)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'knowledge_db' AND TABLE_NAME = 'knowledge_report' AND INDEX_NAME = 'idx_knowledge_report_status');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE knowledge_db.knowledge_report ADD KEY idx_knowledge_report_status (status)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;
