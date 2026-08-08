USE message_db;

ALTER TABLE feedback_ticket
  ADD COLUMN assignee_user_id BIGINT NULL AFTER official_reply,
  ADD COLUMN assigned_at DATETIME NULL AFTER assignee_user_id,
  ADD COLUMN closed_at DATETIME NULL AFTER assigned_at,
  ADD KEY idx_feedback_assignee (assignee_user_id, status);
