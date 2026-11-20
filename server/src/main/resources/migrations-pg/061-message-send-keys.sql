-- Migration 061 for Postgres, the twin of migrations/061-message-send-keys.sql: a retried send
-- with the same key gets the first message back instead of making a copy.
ALTER TABLE "messages" ADD COLUMN send_key text COLLATE "C";
CREATE UNIQUE INDEX messages_send_key ON messages (team_id, user_id, send_key) WHERE send_key IS NOT NULL;
