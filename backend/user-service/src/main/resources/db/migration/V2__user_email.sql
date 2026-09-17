-- Members can bind an email address; verifying it is optional.
-- Only verified addresses must be unique: an unverified binding cannot lock the real owner out, and binding an
-- address says nothing about whether another account uses it.
ALTER TABLE `user`
  ADD COLUMN email VARCHAR(254) NULL,
  ADD COLUMN email_verified_at DATETIME NULL,
  ADD COLUMN verified_email VARCHAR(254) GENERATED ALWAYS AS (IF(email_verified_at IS NULL, NULL, email)) STORED,
  ADD UNIQUE KEY uk_user_verified_email (verified_email),
  ADD KEY idx_user_email (email);

-- The latest verification code sent to a member (one row per account; only a hash of the code is kept).
CREATE TABLE IF NOT EXISTS email_verification (
  user_id BIGINT PRIMARY KEY,
  email VARCHAR(254) NOT NULL,
  code_hash VARCHAR(100) NOT NULL,
  expires_at DATETIME NOT NULL,
  failed_attempts INT NOT NULL DEFAULT 0,
  sent_at DATETIME NOT NULL,
  sent_day DATE NOT NULL,
  sent_count INT NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
