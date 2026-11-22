-- Migration 067: feedback and feature requests (server/spec/14-feedback.md). A person sends one from
-- the app and it is sent at once; an agent's waits as a question to its team's owner or admins
-- (request_id), and is sent only once one of them approves it. Sent means passed to Sentry's user
-- feedback (SENTRY_DSN, sentry_event_id); a server with no SENTRY_DSN keeps it here only ('saved').
CREATE TABLE feedback (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	team_id TEXT NOT NULL DEFAULT 'home',
	id TEXT NOT NULL UNIQUE,
	kind TEXT NOT NULL CHECK (kind IN ('feedback', 'feature_request')),
	area TEXT,
	title TEXT NOT NULL,
	body TEXT NOT NULL,
	author_kind TEXT NOT NULL CHECK (author_kind IN ('person', 'agent')),
	author_id TEXT NOT NULL,
	author_name TEXT NOT NULL,
	card_kind TEXT CHECK (card_kind IN ('update', 'message', 'request')),
	card_id TEXT,
	status TEXT NOT NULL CHECK (status IN ('pending', 'discarded', 'sent', 'saved')),
	request_id TEXT,
	approved_by TEXT,
	sentry_event_id TEXT,
	created_at INTEGER NOT NULL,
	sent_at INTEGER,
	updated_at INTEGER NOT NULL
) STRICT;

CREATE INDEX feedback_team ON feedback (team_id, seq);
CREATE INDEX feedback_author ON feedback (team_id, author_kind, author_id, created_at);
CREATE INDEX feedback_request ON feedback (team_id, request_id);

-- A picture sent with feedback is the feedback's: the sweep that clears unattached uploads leaves it.
ALTER TABLE media ADD COLUMN feedback_id TEXT;
