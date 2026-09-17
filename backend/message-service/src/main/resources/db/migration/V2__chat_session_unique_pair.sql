-- One conversation per pair of users. The service always stores the smaller user id first and looks the pair up
-- before creating one, but two first messages at the same moment could still create two conversations.

-- Older rows may hold the pair in the other order. MySQL applies SET assignments left to right, so this swaps them.
UPDATE chat_session
SET user_a_id = user_a_id + user_b_id, user_b_id = user_a_id - user_b_id, user_a_id = user_a_id - user_b_id
WHERE user_a_id > user_b_id;

-- Duplicates are merged into the oldest conversation: messages and notification links move there, the rest go.
CREATE TEMPORARY TABLE chat_session_keep AS
SELECT user_a_id, user_b_id, MIN(id) AS keep_id, MAX(updated_at) AS last_update
FROM chat_session GROUP BY user_a_id, user_b_id HAVING COUNT(*) > 1;

UPDATE chat_message m
JOIN chat_session s ON s.id = m.session_id
JOIN chat_session_keep k ON k.user_a_id = s.user_a_id AND k.user_b_id = s.user_b_id AND s.id <> k.keep_id
SET m.session_id = k.keep_id;

UPDATE notification n
JOIN chat_session s ON n.target_type = 'CHAT' AND n.target_id = s.id
JOIN chat_session_keep k ON k.user_a_id = s.user_a_id AND k.user_b_id = s.user_b_id AND s.id <> k.keep_id
SET n.target_id = k.keep_id;

UPDATE chat_session s
JOIN chat_session_keep k ON s.id = k.keep_id
SET s.updated_at = k.last_update;

DELETE s FROM chat_session s
JOIN chat_session_keep k ON k.user_a_id = s.user_a_id AND k.user_b_id = s.user_b_id AND s.id <> k.keep_id;

DROP TEMPORARY TABLE chat_session_keep;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_session' AND INDEX_NAME = 'uk_session_pair') = 0, 'ALTER TABLE `chat_session` ADD UNIQUE KEY `uk_session_pair` (user_a_id, user_b_id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

-- The unique key serves every lookup the old pair index did.
SET @ddl = IF((SELECT COUNT(*) = 0 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_session' AND INDEX_NAME = 'idx_session_pair') = 0, 'ALTER TABLE `chat_session` DROP INDEX `idx_session_pair`', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_session' AND CONSTRAINT_NAME = 'chk_session_pair_order') = 0, 'ALTER TABLE `chat_session` ADD CONSTRAINT `chk_session_pair_order` CHECK (user_a_id <= user_b_id)', 'DO 0');
PREPARE ddl_statement FROM @ddl;
EXECUTE ddl_statement;
DEALLOCATE PREPARE ddl_statement;
