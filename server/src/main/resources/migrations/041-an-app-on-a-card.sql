
CREATE TABLE apps (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	-- The spec's own handle, e.g. 'ui://review-board'. Unique, and what an agent
	-- names when it shows one.
	uri TEXT NOT NULL UNIQUE,
	name TEXT NOT NULL,
	description TEXT,
	-- Who registered it. NULL would mean one this deployment ships; there are
	-- none yet, and the column is nullable so there can be.
	agent_id TEXT REFERENCES agents (id),
	-- The document itself: text/html;profile=mcp-app, served into a sandboxed
	-- iframe and never into this page.
	html TEXT NOT NULL,
	-- The origins it declared, as JSON: { connect: [], resource: [], frame: [] }.
	-- Anything not named here is refused by the policy the route sends.
	csp TEXT NOT NULL,
	created_at INTEGER NOT NULL,
	updated_at INTEGER NOT NULL
) STRICT;

CREATE TABLE app_views (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	app_id TEXT NOT NULL REFERENCES apps (id),
	-- Exactly one of these, the generic card anchor every card feature uses
	-- (migration 034).
	update_id TEXT REFERENCES updates (id),
	message_id TEXT REFERENCES messages (id),
	request_id TEXT REFERENCES approvals (id),
	agent_id TEXT REFERENCES agents (id),
	-- 'human' or 'agent:<id>', the vocabulary every other card feature uses.
	author TEXT NOT NULL,
	-- What the agent handed this instance, as JSON. Delivered to the frame as the
	-- spec's ui/notifications/tool-result.
	data TEXT NOT NULL,
	-- What the app itself saved, as JSON, or NULL if it never has.
	state TEXT,
	-- A line of text above the frame, so a collapsed app still says what it is.
	title TEXT,
	created_at INTEGER NOT NULL,
	updated_at INTEGER NOT NULL
) STRICT;

-- The reads the feed makes: every app on the cards it is rendering.
CREATE INDEX app_views_update ON app_views (update_id);
CREATE INDEX app_views_message ON app_views (message_id);
CREATE INDEX app_views_request ON app_views (request_id);
