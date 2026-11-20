-- Migration 059: many teams on one server (docs/HOSTING.md §4, §7, slice 4). A team gets what the
-- admin API sets (limits, a suspension, a reference of the control plane's own) and a state for
-- deleting. auth_codes holds one-time secrets that sign someone in or let them set a password,
-- only as hashes. admin_requests remembers admin calls by their idempotency key for a day.
-- A self-hosted server's one team has no limits and is never suspended, so nothing changes for it.
ALTER TABLE teams ADD COLUMN external_ref TEXT;
ALTER TABLE teams ADD COLUMN seat_limit INTEGER;
ALTER TABLE teams ADD COLUMN storage_limit INTEGER;
ALTER TABLE teams ADD COLUMN suspended_at INTEGER;
ALTER TABLE teams ADD COLUMN suspended_reason TEXT;
ALTER TABLE teams ADD COLUMN suspended_message TEXT;
ALTER TABLE teams ADD COLUMN state TEXT NOT NULL DEFAULT 'active' CHECK (state IN ('active', 'deleting', 'deleted'));
ALTER TABLE teams ADD COLUMN deleted_at INTEGER;
ALTER TABLE teams ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0;
CREATE INDEX teams_external_ref ON teams (external_ref);

-- When the control plane last proved this account owns its email (an emailed code or link). An
-- email typed into the server itself proves nothing: across teams, only a proved one is trusted.
ALTER TABLE users ADD COLUMN email_verified_at INTEGER;

-- kind: 'setup' (set a password, 24 hours) or 'ticket' (sign in once, 60 seconds).
CREATE TABLE auth_codes (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	team_id TEXT NOT NULL REFERENCES teams (id),
	kind TEXT NOT NULL CHECK (kind IN ('setup', 'ticket')),
	code_hash TEXT NOT NULL UNIQUE,
	membership_id TEXT NOT NULL REFERENCES memberships (id),
	created_at INTEGER NOT NULL,
	expires_at INTEGER NOT NULL
) STRICT;

CREATE INDEX auth_codes_membership ON auth_codes (team_id, membership_id, kind);

-- state: 'running' while the first call works, then 'done' with what it answered (no secrets kept).
CREATE TABLE admin_requests (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	idem_key TEXT NOT NULL UNIQUE,
	fingerprint TEXT NOT NULL,
	state TEXT NOT NULL CHECK (state IN ('running', 'done')),
	status INTEGER,
	body TEXT,
	created_at INTEGER NOT NULL
) STRICT;
