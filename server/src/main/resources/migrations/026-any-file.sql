
CREATE TABLE media_rebuilt (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	agent_id TEXT REFERENCES agents (id),
	author TEXT NOT NULL,
	update_id TEXT REFERENCES updates (id),
	message_id TEXT REFERENCES messages (id),
	kind TEXT NOT NULL CHECK (kind IN ('image', 'video', 'file')),
	mime TEXT NOT NULL,
	bytes INTEGER NOT NULL,
	sha256 TEXT NOT NULL,
	width INTEGER,
	height INTEGER,
	duration_ms INTEGER,
	-- What the uploader called it, for a file. Text on a card and a download
	-- name; never a path, and never looked up by.
	filename TEXT,
	status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'ready', 'failed')),
	created_at INTEGER NOT NULL
) STRICT;

INSERT INTO media_rebuilt
	(seq, id, agent_id, author, update_id, message_id, kind, mime, bytes, sha256,
	 width, height, duration_ms, filename, status, created_at)
SELECT seq, id, agent_id, author, update_id, message_id, kind, mime, bytes, sha256,
	 width, height, duration_ms, NULL, status, created_at
FROM media;

DROP TABLE media;
ALTER TABLE media_rebuilt RENAME TO media;

CREATE INDEX media_sha256 ON media (sha256);
-- The sweeper's query: ready media attached to nothing, older than an hour.
CREATE INDEX media_status_created ON media (status, created_at);
-- A card's attachments, as it renders. Every index on the old table has to be
-- recreated: one quietly missed is a table scan nobody sees.
CREATE INDEX media_update ON media (update_id);
-- A message's attachments, read whenever a thread renders.
CREATE INDEX media_message ON media (message_id);
