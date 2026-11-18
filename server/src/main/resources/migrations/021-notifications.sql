
CREATE TABLE notifications (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	kind TEXT NOT NULL CHECK (kind IN ('update', 'reply', 'request')),
	project_id TEXT REFERENCES projects (id),
	update_id TEXT REFERENCES updates (id),
	message_id TEXT REFERENCES messages (id),
	request_id TEXT REFERENCES approvals (id),
	agent_id TEXT REFERENCES agents (id),
	title TEXT NOT NULL,
	body TEXT NOT NULL,
	created_at INTEGER NOT NULL,
	seen_at INTEGER
) STRICT;

-- The read the bell makes: newest first, and how many are still unseen.
CREATE INDEX notifications_seq ON notifications (seq DESC);
CREATE INDEX notifications_unseen ON notifications (seq DESC) WHERE seen_at IS NULL;
-- One notification per thing, so a replay or a double publish cannot double the
-- count. Partial, because only one target column is ever set.
CREATE UNIQUE INDEX notifications_update ON notifications (update_id) WHERE update_id IS NOT NULL;
CREATE UNIQUE INDEX notifications_message ON notifications (message_id) WHERE message_id IS NOT NULL;
CREATE UNIQUE INDEX notifications_request ON notifications (request_id) WHERE request_id IS NOT NULL;
