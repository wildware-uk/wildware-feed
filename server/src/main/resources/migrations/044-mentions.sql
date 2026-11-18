
CREATE TABLE message_mentions (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	message_id TEXT NOT NULL REFERENCES messages (id),
	agent_id TEXT NOT NULL REFERENCES agents (id)
) STRICT;

CREATE UNIQUE INDEX message_mentions_message_agent ON message_mentions (message_id, agent_id);
CREATE INDEX message_mentions_agent ON message_mentions (agent_id, message_id);
