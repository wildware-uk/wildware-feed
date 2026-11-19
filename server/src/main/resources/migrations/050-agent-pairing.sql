-- Migration 050: agent pairing. An agent asks to join under a name it picks; the owner allows or
-- denies it. Only hashes are kept: the poll secret's here, and the token's on the agent, which is
-- made when the agent collects it, so no token is ever stored.
CREATE TABLE agent_pairings (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	name TEXT NOT NULL,
	host TEXT,
	cwd TEXT,
	address TEXT,
	asked_by TEXT REFERENCES agents (id),
	code TEXT NOT NULL,
	secret_hash TEXT NOT NULL UNIQUE,
	state TEXT NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'approved', 'denied', 'expired', 'collected')),
	agent_id TEXT REFERENCES agents (id),
	created_at INTEGER NOT NULL,
	expires_at INTEGER NOT NULL,
	decided_at INTEGER,
	collected_at INTEGER
) STRICT;

CREATE INDEX agent_pairings_open ON agent_pairings (expires_at) WHERE state IN ('pending', 'approved');
