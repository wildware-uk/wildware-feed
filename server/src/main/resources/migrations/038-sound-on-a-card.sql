
CREATE TABLE media_rebuilt (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	agent_id TEXT REFERENCES agents (id),
	author TEXT NOT NULL,
	update_id TEXT REFERENCES updates (id),
	message_id TEXT REFERENCES messages (id),
	kind TEXT NOT NULL CHECK (kind IN ('image', 'video', 'audio', 'file')),
	mime TEXT NOT NULL,
	bytes INTEGER NOT NULL,
	sha256 TEXT NOT NULL,
	width INTEGER,
	height INTEGER,
	duration_ms INTEGER,
	filename TEXT,
	status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'ready', 'failed')),
	created_at INTEGER NOT NULL,
	-- Migration 030: an approval and a task carry attachments too. Carried
	-- through this rebuild rather than reinvented; a column quietly dropped here
	-- would take every question's screenshots with it.
	request_id TEXT REFERENCES approvals (id),
	task_id TEXT REFERENCES tasks (id)
) STRICT;

INSERT INTO media_rebuilt
	(seq, id, agent_id, author, update_id, message_id, kind, mime, bytes, sha256,
	 width, height, duration_ms, filename, status, created_at, request_id, task_id)
SELECT seq, id, agent_id, author, update_id, message_id, kind, mime, bytes, sha256,
	 width, height, duration_ms, filename, status, created_at, request_id, task_id
FROM media;

DROP TABLE media;
ALTER TABLE media_rebuilt RENAME TO media;

-- Every index on the old table has to be recreated: one quietly missed is a
-- table scan nobody sees.
CREATE INDEX media_sha256 ON media (sha256);
CREATE INDEX media_status_created ON media (status, created_at);
CREATE INDEX media_update ON media (update_id);
CREATE INDEX media_message ON media (message_id);
