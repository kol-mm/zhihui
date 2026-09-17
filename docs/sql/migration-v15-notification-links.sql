-- Notifications that open what they are about: target_type (POST or KNOWLEDGE), target_id and, for comments,
-- anchor_id (the comment to scroll to). message-service (MessageSchemaMigration) also adds these on startup.

SET @column_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'message_db' AND TABLE_NAME = 'notification' AND COLUMN_NAME = 'target_type');
SET @column_sql = IF(@column_exists = 0, 'ALTER TABLE message_db.notification ADD COLUMN target_type VARCHAR(16) NULL', 'SELECT 1');
PREPARE column_statement FROM @column_sql;
EXECUTE column_statement;
DEALLOCATE PREPARE column_statement;

SET @column_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'message_db' AND TABLE_NAME = 'notification' AND COLUMN_NAME = 'target_id');
SET @column_sql = IF(@column_exists = 0, 'ALTER TABLE message_db.notification ADD COLUMN target_id BIGINT NULL', 'SELECT 1');
PREPARE column_statement FROM @column_sql;
EXECUTE column_statement;
DEALLOCATE PREPARE column_statement;

SET @column_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'message_db' AND TABLE_NAME = 'notification' AND COLUMN_NAME = 'anchor_id');
SET @column_sql = IF(@column_exists = 0, 'ALTER TABLE message_db.notification ADD COLUMN anchor_id BIGINT NULL', 'SELECT 1');
PREPARE column_statement FROM @column_sql;
EXECUTE column_statement;
DEALLOCATE PREPARE column_statement;
