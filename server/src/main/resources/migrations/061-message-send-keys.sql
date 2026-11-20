-- Migration 061: an app names each send once, with a key it keeps while it tries again. A second
-- POST with the same key, from the same person in the same team, gets the first message back
-- instead of making a copy. Apps that send no key behave as before (NULLs never collide).
ALTER TABLE messages ADD COLUMN send_key TEXT;
CREATE UNIQUE INDEX messages_send_key ON messages (team_id, user_id, send_key) WHERE send_key IS NOT NULL;
