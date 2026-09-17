-- Removed chat messages, so a conversation that is open elsewhere can drop them (GET /message/sync). A row names
-- one deleted message, or every message up to cleared_through_id when a conversation was cleared. Rows older than
-- a week are pruned as new ones are written.
CREATE TABLE IF NOT EXISTS chat_message_removal (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  message_id BIGINT NULL,
  cleared_through_id BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_removal_session (session_id, id),
  KEY idx_removal_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
