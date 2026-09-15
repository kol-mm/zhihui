USE community_db;

-- Thread-paginated comments: every comment records the id of its top-level thread root.
-- community-service also applies these steps on startup (CommunitySchemaMigration).

SET @comment_root_column_exists = (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'comment'
    AND COLUMN_NAME = 'root_id'
);
SET @comment_root_column_sql = IF(
  @comment_root_column_exists = 0,
  'ALTER TABLE comment ADD COLUMN root_id BIGINT NULL AFTER parent_id',
  'SELECT 1'
);
PREPARE comment_root_column_statement FROM @comment_root_column_sql;
EXECUTE comment_root_column_statement;
DEALLOCATE PREPARE comment_root_column_statement;

SET @comment_thread_index_exists = (
  SELECT COUNT(*)
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'comment'
    AND INDEX_NAME = 'idx_comment_thread'
);
SET @comment_thread_index_sql = IF(
  @comment_thread_index_exists = 0,
  'ALTER TABLE comment ADD KEY idx_comment_thread (post_id, root_id, id)',
  'SELECT 1'
);
PREPARE comment_thread_index_statement FROM @comment_thread_index_sql;
EXECUTE comment_thread_index_statement;
DEALLOCATE PREPARE comment_thread_index_statement;

DROP PROCEDURE IF EXISTS backfill_comment_root_ids;
DELIMITER $$
CREATE PROCEDURE backfill_comment_root_ids()
BEGIN
  DECLARE pass_count INT DEFAULT 0;
  DECLARE updated_rows INT DEFAULT 1;
  UPDATE comment SET root_id = id WHERE root_id IS NULL AND (parent_id IS NULL OR parent_id <= 0);
  -- is_root (migration v7) is derived from root_id there, so v5 stays runnable on its own.
  WHILE updated_rows > 0 AND pass_count < 64 DO
    UPDATE comment child JOIN comment parent ON child.parent_id = parent.id
      SET child.root_id = parent.root_id
      WHERE child.root_id IS NULL AND parent.root_id IS NOT NULL;
    SET updated_rows = ROW_COUNT();
    SET pass_count = pass_count + 1;
  END WHILE;
  -- Replies whose parent no longer exists become their own thread root.
  UPDATE comment SET root_id = id WHERE root_id IS NULL;
END$$
DELIMITER ;

CALL backfill_comment_root_ids();
DROP PROCEDURE IF EXISTS backfill_comment_root_ids;
