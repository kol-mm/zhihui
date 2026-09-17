USE community_db;

SET @draft_image_column_exists = (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'post_draft'
    AND COLUMN_NAME = 'image_urls_json'
);
SET @draft_image_sql = IF(
  @draft_image_column_exists = 0,
  'ALTER TABLE post_draft ADD COLUMN image_urls_json TEXT NULL AFTER content',
  'SELECT 1'
);
PREPARE draft_image_statement FROM @draft_image_sql;
EXECUTE draft_image_statement;
DEALLOCATE PREPARE draft_image_statement;
