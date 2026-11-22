-- Migration 068: who may read, post in or manage each channel and project (spec/15). A row in
-- channel_access gives one person (member:<membership id>) or agent (agent:<agent id>) a level on a
-- channel or on a project (a group of channels), which every channel in it follows unless the
-- channel has its own row for them. No row means today's rule: everyone in the team posts, the
-- team's owners and admins manage. private: only those listed can see it. Nobody loses access on
-- the way in: no rows are made, and nothing starts private.
ALTER TABLE projects ADD COLUMN private INTEGER NOT NULL DEFAULT 0 CHECK (private IN (0, 1));
ALTER TABLE project_groups ADD COLUMN private INTEGER NOT NULL DEFAULT 0 CHECK (private IN (0, 1));

CREATE TABLE channel_access (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	team_id TEXT NOT NULL DEFAULT 'home',
	id TEXT NOT NULL UNIQUE,
	target_kind TEXT NOT NULL CHECK (target_kind IN ('channel', 'group')),
	target_id TEXT NOT NULL,
	principal TEXT NOT NULL,
	level TEXT NOT NULL CHECK (level IN ('none', 'read', 'post', 'manage')),
	created_at INTEGER NOT NULL,
	updated_at INTEGER NOT NULL,
	updated_by TEXT NOT NULL
) STRICT;

CREATE UNIQUE INDEX channel_access_target ON channel_access (team_id, target_kind, target_id, principal);
CREATE INDEX channel_access_principal ON channel_access (team_id, principal);

-- Every change to the above, kept for the team's owners and admins: who changed what, from what to what.
CREATE TABLE access_audit (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	team_id TEXT NOT NULL DEFAULT 'home',
	id TEXT NOT NULL UNIQUE,
	at INTEGER NOT NULL,
	actor TEXT NOT NULL,
	target_kind TEXT NOT NULL CHECK (target_kind IN ('channel', 'group')),
	target_id TEXT NOT NULL,
	target_name TEXT NOT NULL,
	principal TEXT,
	change TEXT NOT NULL CHECK (change IN ('level', 'private')),
	old_value TEXT,
	new_value TEXT
) STRICT;

CREATE INDEX access_audit_at ON access_audit (team_id, seq);
