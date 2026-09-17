USE user_db;

DROP PROCEDURE IF EXISTS add_column_if_missing;
DELIMITER $$
CREATE PROCEDURE add_column_if_missing(
  IN database_name VARCHAR(64),
  IN table_name_value VARCHAR(64),
  IN column_name_value VARCHAR(64),
  IN column_definition VARCHAR(255)
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = database_name
      AND TABLE_NAME = table_name_value
      AND COLUMN_NAME = column_name_value
  ) THEN
    SET @migration_sql = CONCAT(
      'ALTER TABLE `', database_name, '`.`', table_name_value,
      '` ADD COLUMN `', column_name_value, '` ', column_definition
    );
    PREPARE migration_statement FROM @migration_sql;
    EXECUTE migration_statement;
    DEALLOCATE PREPARE migration_statement;
  END IF;
END$$
DELIMITER ;

CALL add_column_if_missing('user_db', 'user', 'role', "VARCHAR(16) NOT NULL DEFAULT 'USER'");
CALL add_column_if_missing('user_db', 'user', 'publish_policy', "VARCHAR(32) NOT NULL DEFAULT 'STANDARD'");
CALL add_column_if_missing('user_db', 'user', 'messaging_enabled', 'TINYINT(1) NOT NULL DEFAULT 1');

UPDATE user_db.user SET role = 'ADMIN' WHERE username = 'admin';
UPDATE user_db.user SET role = 'USER' WHERE role IS NULL OR role = '';
UPDATE user_db.user SET publish_policy = 'STANDARD' WHERE publish_policy IS NULL OR publish_policy = '';

DROP PROCEDURE IF EXISTS add_column_if_missing;
