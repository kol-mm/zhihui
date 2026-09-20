-- 会员提交的资料修改（昵称与个性签名）。开启 profile_audit_required 或成员为 PRE_REVIEW 时先进入审核队列。
-- open_user_id 只在待审核时有值，唯一键因此保证每位成员同时只有一条待审核记录（与 V2 的 verified_email 同样的写法）。
CREATE TABLE IF NOT EXISTS user_profile_change (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  nickname VARCHAR(64) NOT NULL,
  signature VARCHAR(255) NULL,
  before_nickname VARCHAR(64) NOT NULL,
  before_signature VARCHAR(255) NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  reason VARCHAR(255) NULL,
  reviewer_id BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  open_user_id BIGINT GENERATED ALWAYS AS (IF(status = 'PENDING', user_id, NULL)) STORED,
  UNIQUE KEY uk_profile_change_open (open_user_id),
  KEY idx_profile_change_status_id (status, id),
  KEY idx_profile_change_user_id (user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
