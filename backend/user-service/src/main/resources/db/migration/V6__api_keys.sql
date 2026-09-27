-- 平台接口密钥：外部系统调用本平台 API 的凭证，只有超级管理员可以管理。
-- 只保存完整密钥的 SHA-256 摘要；完整密钥只在创建或轮换时显示一次，此后只能看到 prefix。
-- 权限由 scopes 决定，任何密钥都不能访问管理接口；带写权限的密钥必须指定一个代为操作的普通账号。
-- updated_at 由程序在修改时写入而不是 ON UPDATE：记录最近使用时间不应被当成一次修改。
CREATE TABLE IF NOT EXISTS api_key (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL,
  prefix VARCHAR(16) NOT NULL,
  key_hash CHAR(64) NOT NULL,
  scopes VARCHAR(255) NOT NULL,
  acting_user_id BIGINT NULL,
  created_by BIGINT NOT NULL,
  expires_at DATETIME NULL,
  last_used_at DATETIME NULL,
  revoked_at DATETIME NULL,
  revoked_by BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_api_key_hash (key_hash),
  KEY idx_api_key_acting_user (acting_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
