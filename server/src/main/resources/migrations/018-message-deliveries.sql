
CREATE TABLE message_deliveries (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	message_id TEXT NOT NULL REFERENCES messages (id),
	agent_id TEXT NOT NULL REFERENCES agents (id),
	delivered_at INTEGER NOT NULL
) STRICT;

-- One delivery per agent per message: the second push of the same message is
-- the same fact, so it conflicts rather than accumulating.
CREATE UNIQUE INDEX message_deliveries_message_agent
	ON message_deliveries (message_id, agent_id);

-- The read the dashboard makes: every delivery on the messages it is about to
-- render.
CREATE INDEX message_deliveries_message ON message_deliveries (message_id);
-- And the read the stream makes: what this agent has already been sent.
CREATE INDEX message_deliveries_agent ON message_deliveries (agent_id, message_id);
