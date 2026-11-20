-- Migration 060: the owner adds an agent to a channel or a project (a group of channels), or
-- removes it. removed_at: the owner took the agent out, so posting there does not follow it again
-- and its own subscribe is refused, until the owner adds it back. by_owner: the owner made the row
-- for an agent that had no row of its own there. Such a row is not the agent's own choice, so an
-- agent with only these rows still hears every other channel, as a new agent does.
ALTER TABLE project_subscriptions ADD COLUMN removed_at INTEGER;
ALTER TABLE project_subscriptions ADD COLUMN by_owner INTEGER NOT NULL DEFAULT 0 CHECK (by_owner IN (0, 1));
ALTER TABLE group_subscriptions ADD COLUMN removed_at INTEGER;
ALTER TABLE group_subscriptions ADD COLUMN by_owner INTEGER NOT NULL DEFAULT 0 CHECK (by_owner IN (0, 1));
