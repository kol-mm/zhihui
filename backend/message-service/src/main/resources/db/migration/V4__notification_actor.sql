-- 通知记录触发者，用于在通知列表中显示回复人的昵称与头像。旧通知没有这一列，保持为空即可。
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'notification' AND COLUMN_NAME = 'actor_user_id') = 0,
    'ALTER TABLE `notification` ADD COLUMN `actor_user_id` BIGINT NULL', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;
