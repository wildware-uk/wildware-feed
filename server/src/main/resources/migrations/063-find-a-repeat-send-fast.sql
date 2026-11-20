-- Migration 063: finding "the same words, from the same person, a moment ago" (a retried send with no
-- send key) reads only that person's recent keyless messages, not every message in the team.
CREATE INDEX messages_repeat ON messages (team_id, user_id, created_at) WHERE send_key IS NULL;
