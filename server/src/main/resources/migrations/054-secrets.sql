-- Migration 054: secrets the owner and agents share. A value is sealed with AES-256-GCM under its
-- own data key, and the data key under a key made from SECRETS_KEY (key_id says which); neither is
-- ever stored in the clear. Grants are single-use URLs, kept only as hashes. The audit outlives
-- the secret it is about, so it names the secret rather than pointing at its row.
CREATE TABLE secrets (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	name TEXT NOT NULL UNIQUE,
	note TEXT,
	all_agents INTEGER NOT NULL DEFAULT 0 CHECK (all_agents IN (0, 1)),
	key_id TEXT NOT NULL,
	wrapped_key TEXT NOT NULL,
	sealed TEXT NOT NULL,
	bytes INTEGER NOT NULL,
	created_by TEXT NOT NULL,
	created_at INTEGER NOT NULL,
	updated_at INTEGER NOT NULL,
	paused_at INTEGER,
	last_used_at INTEGER
) STRICT;

CREATE TABLE secret_agents (
	secret_id TEXT NOT NULL REFERENCES secrets (id) ON DELETE CASCADE,
	agent_id TEXT NOT NULL REFERENCES agents (id) ON DELETE CASCADE,
	PRIMARY KEY (secret_id, agent_id)
) STRICT;

CREATE INDEX secret_agents_agent ON secret_agents (agent_id);

CREATE TABLE secret_grants (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	token_hash TEXT NOT NULL UNIQUE,
	kind TEXT NOT NULL CHECK (kind IN ('read', 'write')),
	secret_id TEXT REFERENCES secrets (id) ON DELETE CASCADE,
	name TEXT NOT NULL,
	note TEXT,
	agent_id TEXT NOT NULL REFERENCES agents (id) ON DELETE CASCADE,
	created_at INTEGER NOT NULL,
	expires_at INTEGER NOT NULL,
	used_at INTEGER
) STRICT;

CREATE INDEX secret_grants_expiry ON secret_grants (expires_at);

CREATE TABLE secret_audit (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	secret_id TEXT,
	secret_name TEXT NOT NULL,
	action TEXT NOT NULL,
	actor TEXT NOT NULL CHECK (actor IN ('owner', 'agent', 'server')),
	actor_id TEXT,
	actor_name TEXT,
	detail TEXT,
	at INTEGER NOT NULL
) STRICT;

CREATE INDEX secret_audit_secret ON secret_audit (secret_id, seq);
