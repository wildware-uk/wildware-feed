-- Migration 056: a card can point at other cards. An agent replies on a card with a video, then
-- asks a question that points at that reply, so the owner sees the video inside the question.
-- The card that points is an update, a message or a request: exactly one of the three is set, as
-- on card_tags. What it points at is a kind and an id, not a foreign key, so a card removed later
-- keeps its place in the list and reads as removed.
CREATE TABLE card_refs (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	update_id TEXT REFERENCES updates (id),
	message_id TEXT REFERENCES messages (id),
	request_id TEXT REFERENCES approvals (id),
	ref_kind TEXT NOT NULL CHECK (ref_kind IN ('update', 'message', 'request')),
	ref_id TEXT NOT NULL,
	created_at INTEGER NOT NULL,
	CHECK ((update_id IS NOT NULL) + (message_id IS NOT NULL) + (request_id IS NOT NULL) = 1)
) STRICT;

-- The reads the feed makes: every ref on the cards it is rendering, in the order they were given.
CREATE INDEX card_refs_update ON card_refs (update_id);
CREATE INDEX card_refs_message ON card_refs (message_id);
CREATE INDEX card_refs_request ON card_refs (request_id);
