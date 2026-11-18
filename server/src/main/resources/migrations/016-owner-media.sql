
CREATE TABLE media_rebuilt (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	agent_id TEXT REFERENCES agents (id),
	author TEXT NOT NULL,
	update_id TEXT REFERENCES updates (id),
	message_id TEXT REFERENCES messages (id),
	kind TEXT NOT NULL CHECK (kind IN ('image', 'video')),
	mime TEXT NOT NULL,
	bytes INTEGER NOT NULL,
	sha256 TEXT NOT NULL,
	width INTEGER,
	height INTEGER,
	duration_ms INTEGER,
	status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'ready', 'failed')),
	created_at INTEGER NOT NULL
) STRICT;

INSERT INTO media_rebuilt
	(seq, id, agent_id, author, update_id, message_id, kind, mime, bytes, sha256,
	 width, height, duration_ms, status, created_at)
SELECT seq, id, agent_id, 'agent:' || agent_id, update_id, NULL, kind, mime, bytes, sha256,
	 width, height, duration_ms, status, created_at
FROM media;

DROP TABLE media;
ALTER TABLE media_rebuilt RENAME TO media;

CREATE INDEX media_sha256 ON media (sha256);
-- The sweeper's query: ready media attached to nothing, older than an hour.
CREATE INDEX media_status_created ON media (status, created_at);
-- A card's images, as it renders. Rebuilt with the rest: every index on the old
-- table has to be recreated, and one quietly missed is a table scan nobody sees.
CREATE INDEX media_update ON media (update_id);
-- A message's images, read whenever a thread renders.
CREATE INDEX media_message ON media (message_id);
