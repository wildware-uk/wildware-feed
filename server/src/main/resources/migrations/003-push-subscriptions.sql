
CREATE TABLE push_subscriptions (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL,
	endpoint TEXT NOT NULL,
	p256dh TEXT NOT NULL,
	auth TEXT NOT NULL,
	label TEXT,
	created_at INTEGER NOT NULL,
	last_sent_at INTEGER,
	failures INTEGER NOT NULL DEFAULT 0
) STRICT;

CREATE UNIQUE INDEX push_subscriptions_id ON push_subscriptions (id);

-- The endpoint is what a re-subscribe collides on, which is what keeps one
-- browser one row (and one notification) however often it re-subscribes.
CREATE UNIQUE INDEX push_subscriptions_endpoint ON push_subscriptions (endpoint);
