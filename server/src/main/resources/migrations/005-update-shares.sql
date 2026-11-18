
CREATE TABLE update_shares (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL,
	update_id TEXT NOT NULL,
	token_hash TEXT NOT NULL,
	created_at INTEGER NOT NULL,
	revoked_at INTEGER,
	views INTEGER NOT NULL DEFAULT 0,
	last_viewed_at INTEGER
) STRICT;

CREATE UNIQUE INDEX update_shares_id ON update_shares (id);

-- The lookup every public request makes, and unique because a token is a
-- capability: two rows answering to one token would be a bug nobody could see.
CREATE UNIQUE INDEX update_shares_token_hash ON update_shares (token_hash);

-- At most one live share per update. Partial, so a revoked share stays as a
-- record and does not block sharing that card again.
CREATE UNIQUE INDEX update_shares_live ON update_shares (update_id)
	WHERE revoked_at IS NULL;
