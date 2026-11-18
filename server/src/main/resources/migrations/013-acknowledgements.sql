
CREATE TABLE acknowledgements (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL,
	agent_id TEXT NOT NULL REFERENCES agents (id),
	message_id TEXT REFERENCES messages (id),
	task_id TEXT REFERENCES tasks (id),
	state TEXT NOT NULL,
	created_at INTEGER NOT NULL,
	updated_at INTEGER NOT NULL
) STRICT;

CREATE UNIQUE INDEX acknowledgements_id ON acknowledgements (id);

-- One live acknowledgement per agent per thing. Partial, because only one of
-- the two target columns is ever set and NULLs would otherwise collide.
CREATE UNIQUE INDEX acknowledgements_agent_message ON acknowledgements (agent_id, message_id)
	WHERE message_id IS NOT NULL;
CREATE UNIQUE INDEX acknowledgements_agent_task ON acknowledgements (agent_id, task_id)
	WHERE task_id IS NOT NULL;

-- The reads the dashboard makes: every acknowledgement on a thing it is about
-- to render.
CREATE INDEX acknowledgements_message ON acknowledgements (message_id);
CREATE INDEX acknowledgements_task ON acknowledgements (task_id);
