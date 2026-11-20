-- Migration 063 for Postgres, the twin of migrations/063-find-a-repeat-send-fast.sql: a retried send
-- with no send key is looked up among that person's recent keyless messages only.
CREATE INDEX messages_repeat ON messages (team_id, user_id, created_at) WHERE send_key IS NULL;
