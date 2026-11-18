-- Migration 050: media retention. The owner's server-wide settings, starting with how long media is
-- kept; media removed for its age keeps its row, marked with when and after how many days.
-- (048 and 049 are reserved for teams; the runner applies any it has not, in order.)
CREATE TABLE server_settings (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	key TEXT NOT NULL UNIQUE,
	value TEXT NOT NULL,
	updated_at INTEGER NOT NULL
) STRICT;

ALTER TABLE media ADD COLUMN removed_at INTEGER;
ALTER TABLE media ADD COLUMN removed_after_days INTEGER;

CREATE INDEX media_retention_candidates ON media (created_at) WHERE removed_at IS NULL;
