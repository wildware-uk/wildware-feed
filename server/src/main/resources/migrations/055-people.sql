-- Migration 055: people. Today's one owner becomes the first person of a team (docs/TEAMS.md,
-- docs/HOSTING.md). A server holds one team, the home team. An account (users) is who someone is;
-- a membership is what they are in the team: owner, admin or member. The owner of today is the
-- founding membership, 'founder', and every row the owner wrote ('human') is marked as theirs.
-- Nothing else changes for them: agents still see author 'human', and the same password and keys work.
CREATE TABLE teams (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	slug TEXT NOT NULL UNIQUE,
	name TEXT NOT NULL,
	created_at INTEGER NOT NULL
) STRICT;

-- email is lower-cased; the founder has none until they set one. password_hash null: the founder
-- signs in with ADMIN_PASSWORD_HASH until they set their own.
CREATE TABLE users (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	email TEXT UNIQUE,
	name TEXT NOT NULL,
	password_hash TEXT,
	created_at INTEGER NOT NULL
) STRICT;

-- Never deleted: removing someone stamps removed_at, so what they wrote still names them.
CREATE TABLE memberships (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	team_id TEXT NOT NULL REFERENCES teams (id),
	user_id TEXT NOT NULL REFERENCES users (id),
	role TEXT NOT NULL CHECK (role IN ('owner', 'admin', 'member')),
	handle TEXT NOT NULL,
	founder INTEGER NOT NULL DEFAULT 0 CHECK (founder IN (0, 1)),
	created_at INTEGER NOT NULL,
	removed_at INTEGER
) STRICT;

CREATE UNIQUE INDEX memberships_founder ON memberships (team_id) WHERE founder = 1;
CREATE UNIQUE INDEX memberships_handle ON memberships (team_id, handle) WHERE removed_at IS NULL;
CREATE UNIQUE INDEX memberships_account ON memberships (team_id, user_id) WHERE removed_at IS NULL;

-- A link to join the team: the token is kept only as a hash, used once, and expires.
CREATE TABLE invites (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	team_id TEXT NOT NULL REFERENCES teams (id),
	email TEXT,
	role TEXT NOT NULL CHECK (role IN ('owner', 'admin', 'member')),
	token_hash TEXT NOT NULL UNIQUE,
	created_by TEXT NOT NULL REFERENCES memberships (id),
	created_at INTEGER NOT NULL,
	expires_at INTEGER NOT NULL,
	accepted_at INTEGER,
	accepted_by TEXT REFERENCES memberships (id),
	revoked_at INTEGER
) STRICT;

CREATE INDEX invites_team ON invites (team_id, seq);

INSERT INTO teams (id, slug, name, created_at) VALUES ('home', 'home', 'Home', CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER));
INSERT INTO users (id, email, name, password_hash, created_at) VALUES ('founder', NULL, 'Owner', NULL, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER));
INSERT INTO memberships (id, team_id, user_id, role, handle, founder, created_at)
VALUES ('founder', 'home', 'founder', 'owner', 'owner', 1, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER));

-- Whose phone, whose line, who answered. Every existing one is the founder's.
ALTER TABLE owner_devices ADD COLUMN user_id TEXT REFERENCES memberships (id);
UPDATE owner_devices SET user_id = 'founder';
CREATE INDEX owner_devices_user ON owner_devices (user_id);

ALTER TABLE messages ADD COLUMN user_id TEXT REFERENCES memberships (id);
UPDATE messages SET user_id = 'founder' WHERE author = 'human';

ALTER TABLE approvals ADD COLUMN decided_by TEXT REFERENCES memberships (id);
UPDATE approvals SET decided_by = 'founder' WHERE state IN ('approved', 'rejected');

-- Two people are both actor 'human', so each emoji is once per person: '' for an agent.
ALTER TABLE reactions ADD COLUMN user_id TEXT NOT NULL DEFAULT '';
UPDATE reactions SET user_id = 'founder' WHERE actor = 'human';
DROP INDEX reactions_message_actor_emoji;
DROP INDEX reactions_update_actor_emoji;
DROP INDEX reactions_request_actor_emoji;
DROP INDEX reactions_task_actor_emoji;
CREATE UNIQUE INDEX reactions_message_actor_emoji ON reactions (message_id, actor, user_id, emoji) WHERE message_id IS NOT NULL;
CREATE UNIQUE INDEX reactions_update_actor_emoji ON reactions (update_id, actor, user_id, emoji) WHERE update_id IS NOT NULL;
CREATE UNIQUE INDEX reactions_request_actor_emoji ON reactions (request_id, actor, user_id, emoji) WHERE request_id IS NOT NULL;
CREATE UNIQUE INDEX reactions_task_actor_emoji ON reactions (task_id, actor, user_id, emoji) WHERE task_id IS NOT NULL;

-- The bell is each person's own: a row per person per thing, each with its own seen_at.
ALTER TABLE notifications ADD COLUMN member_id TEXT REFERENCES memberships (id);
UPDATE notifications SET member_id = 'founder';
DROP INDEX notifications_update;
DROP INDEX notifications_message;
DROP INDEX notifications_request;
CREATE UNIQUE INDEX notifications_update ON notifications (update_id, member_id) WHERE update_id IS NOT NULL;
CREATE UNIQUE INDEX notifications_message ON notifications (message_id, member_id) WHERE message_id IS NOT NULL;
CREATE UNIQUE INDEX notifications_request ON notifications (request_id, member_id) WHERE request_id IS NOT NULL;
CREATE INDEX notifications_member ON notifications (member_id, seq DESC);

-- What the owner follows is now the founder's: a person follows as 'member:<membership id>'.
UPDATE project_subscriptions SET subscriber = 'member:founder' WHERE subscriber = 'human';
UPDATE group_subscriptions SET subscriber = 'member:founder' WHERE subscriber = 'human';
UPDATE thread_subscriptions SET subscriber = 'member:founder' WHERE subscriber = 'human';
