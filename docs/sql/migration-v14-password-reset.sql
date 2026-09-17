-- Admin-issued password reset codes. A member asks from the login page, an admin issues a one-time code
-- (only its BCrypt hash is stored) and the member sets a new password with it. user-service
-- (UserSchemaMigration) also creates this table on startup.

CREATE TABLE IF NOT EXISTS user_db.password_reset_request (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  username VARCHAR(64) NOT NULL,
  contact VARCHAR(100),
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  code_hash VARCHAR(100),
  code_expires_at DATETIME,
  failed_attempts INT NOT NULL DEFAULT 0,
  handled_by BIGINT,
  note VARCHAR(255),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_reset_status_id (status, id),
  KEY idx_reset_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
