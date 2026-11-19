-- Migration 052: subscriptions. A subscriber follows projects and threads. A subscriber is spelled
-- as a message author is: 'agent:<id>' for an agent, 'human' for the owner.
-- For an agent, following decides which messages reach it, for the owner, which alerts are raised.
-- A thread is an update, a request, or a feed post with its replies (kind 'message').
-- level: the owner's choice per project ('all', 'replies' or 'muted'), always 'all' for an agent.
-- after_seq: only messages newer than this reach an agent, so following never replays history.
-- Unsubscribing keeps the row, stamped, so an agent that has followed projects is never mistaken
-- for a new one, which hears every project, and an owner's unfollowed thread stays unfollowed.
CREATE TABLE project_subscriptions (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	subscriber TEXT NOT NULL,
	project_id TEXT NOT NULL REFERENCES projects (id),
	level TEXT NOT NULL DEFAULT 'all' CHECK (level IN ('all', 'replies', 'muted')),
	after_seq INTEGER NOT NULL DEFAULT 0,
	created_at INTEGER NOT NULL,
	unsubscribed_at INTEGER
) STRICT;

CREATE UNIQUE INDEX project_subscriptions_subscriber_project ON project_subscriptions (subscriber, project_id);

CREATE TABLE thread_subscriptions (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	subscriber TEXT NOT NULL,
	kind TEXT NOT NULL CHECK (kind IN ('update', 'message', 'request')),
	thread_id TEXT NOT NULL,
	project_id TEXT REFERENCES projects (id),
	after_seq INTEGER NOT NULL DEFAULT 0,
	created_at INTEGER NOT NULL,
	unsubscribed_at INTEGER
) STRICT;

CREATE UNIQUE INDEX thread_subscriptions_subscriber_thread ON thread_subscriptions (subscriber, kind, thread_id);
CREATE INDEX thread_subscriptions_thread ON thread_subscriptions (kind, thread_id);

-- Every agent keeps the projects it has worked in: its updates, its tasks, its live messages.
INSERT INTO project_subscriptions (id, subscriber, project_id, level, after_seq, created_at)
SELECT w.agent_id || ':' || w.project_id, 'agent:' || w.agent_id, w.project_id, 'all', 0, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)
FROM (
	SELECT agent_id, project_id FROM updates
	UNION
	SELECT agent_id, project_id FROM tasks WHERE agent_id IS NOT NULL
	UNION
	SELECT substr(author, 7) AS agent_id, project_id FROM messages
	WHERE author LIKE 'agent:%' AND project_id IS NOT NULL AND deleted_at IS NULL
) AS w
WHERE w.agent_id IN (SELECT id FROM agents) AND w.project_id IN (SELECT id FROM projects)
ORDER BY w.agent_id, w.project_id;
