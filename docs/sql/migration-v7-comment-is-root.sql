USE community_db;

-- Thread roots get an indexed flag so paging top-level comments is an index range scan instead of
-- evaluating root_id = id for every comment on the post. Run after migration-v5-comment-threads.sql.
-- (A generated column is not possible: MySQL forbids AUTO_INCREMENT columns in generated column expressions.)
-- community-service also applies these steps on startup (CommunitySchemaMigration).

SET @is_root_column_exists = (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'comment'
    AND COLUMN_NAME = 'is_root'
);
SET @is_root_column_sql = IF(
  @is_root_column_exists = 0,
  'ALTER TABLE comment ADD COLUMN is_root TINYINT(1) NOT NULL DEFAULT 0 AFTER root_id',
  'SELECT 1'
);
PREPARE is_root_column_statement FROM @is_root_column_sql;
EXECUTE is_root_column_statement;
DEALLOCATE PREPARE is_root_column_statement;

UPDATE comment SET is_root = 1 WHERE is_root = 0 AND root_id = id;

SET @comment_root_index_exists = (
  SELECT COUNT(*)
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'comment'
    AND INDEX_NAME = 'idx_comment_root'
);
SET @comment_root_index_sql = IF(
  @comment_root_index_exists = 0,
  'ALTER TABLE comment ADD KEY idx_comment_root (post_id, is_root, id)',
  'SELECT 1'
);
PREPARE comment_root_index_statement FROM @comment_root_index_sql;
EXECUTE comment_root_index_statement;
DEALLOCATE PREPARE comment_root_index_statement;
