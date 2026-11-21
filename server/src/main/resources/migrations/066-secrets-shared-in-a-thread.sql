-- Migration 066: an agent attaches a secret to a post (post_message / post_update `secrets`), and
-- the people reading it open it in place (docs/SECRETS.md, "Sharing in a thread"). A row names the
-- card, the secret and the agent that shared it, never the value. It points at no other row, so a
-- deleted secret leaves its share behind to say "no longer available" instead of vanishing.
CREATE TABLE card_secrets (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	team_id TEXT NOT NULL DEFAULT 'home',
	card_kind TEXT NOT NULL CHECK (card_kind IN ('update', 'message')),
	card_id TEXT NOT NULL,
	secret_id TEXT NOT NULL,
	secret_name TEXT NOT NULL,
	agent_id TEXT NOT NULL,
	created_at INTEGER NOT NULL
) STRICT;

CREATE UNIQUE INDEX card_secrets_card ON card_secrets (team_id, card_kind, card_id, secret_name);
