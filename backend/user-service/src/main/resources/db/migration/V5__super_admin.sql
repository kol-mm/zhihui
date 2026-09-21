-- 超级管理员：只有超级管理员可以任命或撤销管理员，并修改 AI 上游（模型、接口地址）等敏感设置。
-- 普通管理员保留原有的内容审核、用户治理等权限。
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'user' AND COLUMN_NAME = 'super_admin') = 0,
    'ALTER TABLE `user` ADD COLUMN `super_admin` TINYINT(1) NOT NULL DEFAULT 0', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

-- 首位超级管理员沿用已有的 admin 账号。
UPDATE `user` SET super_admin = 1 WHERE role = 'ADMIN' AND username = 'admin';

-- 若没有名为 admin 的账号，则取最早创建的管理员，避免升级后无人可以任命管理员。
SET @promoted = (SELECT COUNT(*) FROM `user` WHERE super_admin = 1);
UPDATE `user` SET super_admin = 1 WHERE role = 'ADMIN' AND @promoted = 0 ORDER BY id LIMIT 1;
