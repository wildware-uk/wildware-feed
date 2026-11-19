-- Migration 053: channels and projects. Each row of projects is now a channel, kept in place: same id,
-- slug, history, theme, pin and archive state. A project is now a named group of channels
-- (project_groups); a channel is in at most one (projects.group_id, null for none).
-- Following works at three levels: a group, a channel (project_subscriptions), a thread. The most
-- specific setting wins; see spec/12-channels-and-projects.md.
CREATE TABLE project_groups (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	slug TEXT NOT NULL UNIQUE,
	name TEXT NOT NULL,
	status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'archived')),
	theme TEXT,
	created_at INTEGER NOT NULL,
	updated_at INTEGER NOT NULL
) STRICT;

ALTER TABLE projects ADD COLUMN group_id TEXT REFERENCES project_groups (id);

CREATE INDEX projects_group ON projects (group_id);

-- A subscriber's choice for a whole group, shaped as project_subscriptions is.
CREATE TABLE group_subscriptions (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	subscriber TEXT NOT NULL,
	group_id TEXT NOT NULL REFERENCES project_groups (id),
	level TEXT NOT NULL DEFAULT 'all' CHECK (level IN ('all', 'replies', 'muted')),
	after_seq INTEGER NOT NULL DEFAULT 0,
	created_at INTEGER NOT NULL,
	unsubscribed_at INTEGER
) STRICT;

CREATE UNIQUE INDEX group_subscriptions_subscriber_group ON group_subscriptions (subscriber, group_id);

-- chosen: the subscriber set this channel on purpose, so it beats the channel's group. An agent that
-- only follows a channel because it posted there gives way to what it chose for the group.
ALTER TABLE project_subscriptions ADD COLUMN chosen INTEGER NOT NULL DEFAULT 0 CHECK (chosen IN (0, 1));

-- The owner only ever gets a row by choosing a level; only unsubscribe stamps an agent's row.
UPDATE project_subscriptions SET chosen = 1 WHERE subscriber = 'human' OR unsubscribed_at IS NOT NULL;
