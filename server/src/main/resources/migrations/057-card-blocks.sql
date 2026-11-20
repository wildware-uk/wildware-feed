-- Migration 057: blocks on a card (docs/BLOCKS.md). A block is a typed piece of a card the apps draw
-- themselves: a checklist, steps, a diff, a chart, a table, a log or a poll. The card is an update,
-- a message or a request: exactly one of the three is set, as on card_refs. data is the block as
-- JSON, with what only the server knows (who ticked a line, a poll's votes). rev counts its writes:
-- a write names the rev it read, so two at once never lose one another.
CREATE TABLE card_blocks (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	update_id TEXT REFERENCES updates (id),
	message_id TEXT REFERENCES messages (id),
	request_id TEXT REFERENCES approvals (id),
	block_id TEXT NOT NULL,
	type TEXT NOT NULL CHECK (type IN ('checklist', 'steps', 'diff', 'chart', 'table', 'log', 'poll')),
	data TEXT NOT NULL,
	rev INTEGER NOT NULL DEFAULT 1,
	created_at INTEGER NOT NULL,
	updated_at INTEGER NOT NULL,
	CHECK ((update_id IS NOT NULL) + (message_id IS NOT NULL) + (request_id IS NOT NULL) = 1)
) STRICT;

-- One block of each id on a card, and the reads the feed makes: every block on the cards it shows.
CREATE UNIQUE INDEX card_blocks_update ON card_blocks (update_id, block_id) WHERE update_id IS NOT NULL;
CREATE UNIQUE INDEX card_blocks_message ON card_blocks (message_id, block_id) WHERE message_id IS NOT NULL;
CREATE UNIQUE INDEX card_blocks_request ON card_blocks (request_id, block_id) WHERE request_id IS NOT NULL;
