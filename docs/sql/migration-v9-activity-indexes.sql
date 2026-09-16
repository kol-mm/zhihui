-- Indexes for the paged knowledge activity lists (uploads, collects, likes, downloads, forwards).
-- knowledge-service (KnowledgeSchemaMigration) also applies these on startup.

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'knowledge_db' AND TABLE_NAME = 'knowledge_file' AND INDEX_NAME = 'idx_file_user_id');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE knowledge_db.knowledge_file ADD KEY idx_file_user_id (user_id, id)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'knowledge_db' AND TABLE_NAME = 'knowledge_collect' AND INDEX_NAME = 'idx_collect_user_id');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE knowledge_db.knowledge_collect ADD KEY idx_collect_user_id (user_id, id)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'knowledge_db' AND TABLE_NAME = 'knowledge_like' AND INDEX_NAME = 'idx_like_user_id');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE knowledge_db.knowledge_like ADD KEY idx_like_user_id (user_id, id)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'knowledge_db' AND TABLE_NAME = 'knowledge_download' AND INDEX_NAME = 'idx_download_user_id');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE knowledge_db.knowledge_download ADD KEY idx_download_user_id (user_id, id)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;

SET @index_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'knowledge_db' AND TABLE_NAME = 'knowledge_forward' AND INDEX_NAME = 'idx_forward_user_id');
SET @index_sql = IF(@index_exists = 0, 'ALTER TABLE knowledge_db.knowledge_forward ADD KEY idx_forward_user_id (user_id, id)', 'SELECT 1');
PREPARE index_statement FROM @index_sql;
EXECUTE index_statement;
DEALLOCATE PREPARE index_statement;
