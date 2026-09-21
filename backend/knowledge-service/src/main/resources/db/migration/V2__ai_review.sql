-- 审核结果的来源与理由。AI 审核开启后，自动通过或驳回的记录由此可以追溯；
-- 人工审核填写的理由此前只是传入后被丢弃，从这一版开始一并保存。
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'knowledge_file' AND COLUMN_NAME = 'audit_source') = 0,
    'ALTER TABLE `knowledge_file` ADD COLUMN `audit_source` VARCHAR(16) NULL', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'knowledge_file' AND COLUMN_NAME = 'audit_reason') = 0,
    'ALTER TABLE `knowledge_file` ADD COLUMN `audit_reason` VARCHAR(255) NULL', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;
