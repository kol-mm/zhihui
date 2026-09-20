-- What administrators changed: account decisions, moderation, deletions and platform settings.
-- Every service reports its own actions; entries are only ever added (and removed after the retention period).
CREATE TABLE IF NOT EXISTS admin_audit_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  created_at DATETIME NOT NULL,
  actor_id BIGINT NOT NULL,
  actor_name VARCHAR(64) NULL,
  action VARCHAR(64) NOT NULL,
  category VARCHAR(32) NOT NULL,
  target_type VARCHAR(32) NULL,
  target_id VARCHAR(64) NULL,
  target_label VARCHAR(200) NULL,
  subject_user_id BIGINT NULL,
  summary VARCHAR(500) NULL,
  detail TEXT NULL,
  source VARCHAR(32) NULL,
  client_ip VARCHAR(64) NULL,
  KEY idx_audit_created (created_at),
  KEY idx_audit_category_id (category, id),
  KEY idx_audit_actor_id (actor_id, id),
  KEY idx_audit_subject_id (subject_user_id, id),
  KEY idx_audit_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
