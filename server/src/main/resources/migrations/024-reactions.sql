
CREATE TABLE reactions (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	message_id TEXT NOT NULL REFERENCES messages (id),
	actor TEXT NOT NULL,
	emoji TEXT NOT NULL,
	created_at INTEGER NOT NULL
) STRICT;

-- One of each per reactor per message: reacting twice with the same emoji is
-- the same reaction, and a second row would double a count somebody reads.
CREATE UNIQUE INDEX reactions_message_actor_emoji ON reactions (message_id, actor, emoji);
-- The read a thread makes: every reaction on the messages it is rendering.
CREATE INDEX reactions_message ON reactions (message_id);
