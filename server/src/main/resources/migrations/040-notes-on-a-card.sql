
CREATE TABLE card_fields (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	update_id TEXT REFERENCES updates (id),
	message_id TEXT REFERENCES messages (id),
	request_id TEXT REFERENCES approvals (id),
	-- What it is called, as it is shown.
	name TEXT NOT NULL,
	-- The same, normalised: what "already set" is judged on.
	slug TEXT NOT NULL,
	-- What it says. Text, always: this is a note, not a type system.
	value TEXT NOT NULL,
	-- Who set it last: the literal 'human', or 'agent:<id>'.
	author TEXT NOT NULL,
	created_at INTEGER NOT NULL,
	updated_at INTEGER NOT NULL
) STRICT;

-- One field of each name per card, per anchor. Partial, because NULL is not
-- equal to itself and one index across the three columns would enforce nothing.
CREATE UNIQUE INDEX card_fields_update_slug ON card_fields (update_id, slug)
	WHERE update_id IS NOT NULL;
CREATE UNIQUE INDEX card_fields_message_slug ON card_fields (message_id, slug)
	WHERE message_id IS NOT NULL;
CREATE UNIQUE INDEX card_fields_request_slug ON card_fields (request_id, slug)
	WHERE request_id IS NOT NULL;

-- The read the feed makes: every field on the cards it is rendering.
CREATE INDEX card_fields_update ON card_fields (update_id);
CREATE INDEX card_fields_message ON card_fields (message_id);
CREATE INDEX card_fields_request ON card_fields (request_id);
