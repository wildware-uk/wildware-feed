-- Migration 081 for Postgres, the twin of migrations/081-messages-carry-quick-replies.sql: quick-reply
-- buttons or a small form on a message, and on each answer which message it answers and what was chosen.
ALTER TABLE "messages" ADD COLUMN replies text COLLATE "C";
ALTER TABLE "messages" ADD COLUMN reply_for text COLLATE "C";
ALTER TABLE "messages" ADD COLUMN reply_value text COLLATE "C";
CREATE UNIQUE INDEX messages_one_reply ON messages (team_id, reply_for, user_id) WHERE reply_for IS NOT NULL AND deleted_at IS NULL;
