-- AI 生成的摘要与建议分类。上传后由 AI 服务在后台生成；上传者未选分类时建议分类会直接采用，
-- 已选的分类不会被改动，建议只供审核时参考。
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'knowledge_file' AND COLUMN_NAME = 'summary') = 0,
    'ALTER TABLE `knowledge_file` ADD COLUMN `summary` VARCHAR(500) NULL', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'knowledge_file' AND COLUMN_NAME = 'suggested_category_id') = 0,
    'ALTER TABLE `knowledge_file` ADD COLUMN `suggested_category_id` BIGINT NULL', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;
