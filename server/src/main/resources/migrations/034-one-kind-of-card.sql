
-- Pinning: the owner's, on anything they want kept at the top of the feed.
ALTER TABLE messages ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0;
ALTER TABLE approvals ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tasks ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0;

-- A task's words can be corrected, as every other card's can (migration 033).
ALTER TABLE tasks ADD COLUMN edited_at INTEGER;

-- Acknowledgements on a card and on a question, not only on a message and a
-- task: "I have seen this" is worth saying about anything an owner posts.
ALTER TABLE acknowledgements ADD COLUMN update_id TEXT REFERENCES updates (id);
ALTER TABLE acknowledgements ADD COLUMN request_id TEXT REFERENCES approvals (id);

-- One live acknowledgement per agent per thing, the same partial shape
-- migration 013 gave the first two anchors.
CREATE UNIQUE INDEX acknowledgements_agent_update ON acknowledgements (agent_id, update_id)
	WHERE update_id IS NOT NULL;
CREATE UNIQUE INDEX acknowledgements_agent_request ON acknowledgements (agent_id, request_id)
	WHERE request_id IS NOT NULL;

CREATE INDEX acknowledgements_update ON acknowledgements (update_id);
CREATE INDEX acknowledgements_request ON acknowledgements (request_id);

-- Reactions on any card.
CREATE TABLE reactions_rebuilt (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	message_id TEXT REFERENCES messages (id),
	update_id TEXT REFERENCES updates (id),
	request_id TEXT REFERENCES approvals (id),
	task_id TEXT REFERENCES tasks (id),
	actor TEXT NOT NULL,
	emoji TEXT NOT NULL,
	created_at INTEGER NOT NULL
) STRICT;

INSERT INTO reactions_rebuilt (seq, id, message_id, actor, emoji, created_at)
SELECT seq, id, message_id, actor, emoji, created_at FROM reactions;

DROP TABLE reactions;
ALTER TABLE reactions_rebuilt RENAME TO reactions;

-- One of each per reactor per card, per anchor: partial, because NULL is not
-- equal to itself and one index across the four would enforce nothing.
CREATE UNIQUE INDEX reactions_message_actor_emoji ON reactions (message_id, actor, emoji)
	WHERE message_id IS NOT NULL;
CREATE UNIQUE INDEX reactions_update_actor_emoji ON reactions (update_id, actor, emoji)
	WHERE update_id IS NOT NULL;
CREATE UNIQUE INDEX reactions_request_actor_emoji ON reactions (request_id, actor, emoji)
	WHERE request_id IS NOT NULL;
CREATE UNIQUE INDEX reactions_task_actor_emoji ON reactions (task_id, actor, emoji)
	WHERE task_id IS NOT NULL;

-- The read a card makes: every reaction on the things it is rendering.
CREATE INDEX reactions_message ON reactions (message_id);
CREATE INDEX reactions_update ON reactions (update_id);
CREATE INDEX reactions_request ON reactions (request_id);
CREATE INDEX reactions_task ON reactions (task_id);
