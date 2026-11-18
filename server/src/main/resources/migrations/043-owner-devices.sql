
CREATE TABLE owner_devices (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL,
	name TEXT NOT NULL,
	token_hash TEXT NOT NULL,
	apns_token TEXT,
	apns_environment TEXT CHECK (apns_environment IN ('sandbox', 'production')),
	live_activity_token TEXT,
	push_to_start_token TEXT,
	created_at INTEGER NOT NULL,
	last_seen_at INTEGER,
	failures INTEGER NOT NULL DEFAULT 0
) STRICT;

CREATE UNIQUE INDEX owner_devices_id ON owner_devices (id);
CREATE UNIQUE INDEX owner_devices_token_hash ON owner_devices (token_hash);
