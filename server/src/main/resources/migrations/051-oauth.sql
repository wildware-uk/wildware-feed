-- Migration 051: OAuth 2.1 for MCP clients (spec/11-oauth.md). A client registers itself, the owner
-- allows it in the browser as one agent, and it gets short-lived access tokens and a rotating refresh
-- token for that agent. Only hashes of codes and tokens are kept.
CREATE TABLE oauth_clients (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	name TEXT NOT NULL,
	client_uri TEXT,
	redirect_uris TEXT NOT NULL,
	address TEXT,
	created_at INTEGER NOT NULL,
	approved_at INTEGER
) STRICT;

CREATE TABLE oauth_grants (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	client_id TEXT NOT NULL REFERENCES oauth_clients (id),
	agent_id TEXT NOT NULL REFERENCES agents (id),
	resource TEXT NOT NULL,
	scope TEXT NOT NULL,
	redirect_uri TEXT NOT NULL,
	code_hash TEXT NOT NULL UNIQUE,
	code_challenge TEXT NOT NULL,
	code_expires_at INTEGER NOT NULL,
	code_used_at INTEGER,
	created_at INTEGER NOT NULL,
	revoked_at INTEGER
) STRICT;

CREATE INDEX oauth_grants_client ON oauth_grants (client_id);

CREATE TABLE oauth_tokens (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	token_hash TEXT NOT NULL UNIQUE,
	grant_id TEXT NOT NULL REFERENCES oauth_grants (id),
	kind TEXT NOT NULL CHECK (kind IN ('access', 'refresh')),
	created_at INTEGER NOT NULL,
	expires_at INTEGER NOT NULL,
	used_at INTEGER
) STRICT;

CREATE INDEX oauth_tokens_grant ON oauth_tokens (grant_id);
