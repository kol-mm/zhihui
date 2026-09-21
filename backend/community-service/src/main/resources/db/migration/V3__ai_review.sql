-- 帖子审核结果的来源与理由，与知识库保持一致：AI 自动通过或驳回时记录模型给出的理由，人工审核同样保留。
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'post' AND COLUMN_NAME = 'audit_source') = 0,
    'ALTER TABLE `post` ADD COLUMN `audit_source` VARCHAR(16) NULL', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'post' AND COLUMN_NAME = 'audit_reason') = 0,
    'ALTER TABLE `post` ADD COLUMN `audit_reason` VARCHAR(255) NULL', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;
