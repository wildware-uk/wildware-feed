
CREATE TABLE approvals_rebuilt (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	agent_id TEXT NOT NULL REFERENCES agents (id),
	project_id TEXT REFERENCES projects (id),
	update_id TEXT REFERENCES updates (id),
	question TEXT NOT NULL,
	options TEXT,
	-- 'withdrawn' is the new one: the asking agent took the question back.
	state TEXT NOT NULL DEFAULT 'pending'
		CHECK (state IN ('pending', 'approved', 'rejected', 'timeout', 'cancelled', 'withdrawn')),
	expires_at INTEGER NOT NULL,
	decided_at INTEGER,
	decided_value TEXT,
	kind TEXT NOT NULL DEFAULT 'confirm',
	detail TEXT,
	config TEXT,
	answer TEXT,
	message_id TEXT REFERENCES messages (id)
) STRICT;

INSERT INTO approvals_rebuilt
	(seq, id, agent_id, project_id, update_id, question, options, state, expires_at,
	 decided_at, decided_value, kind, detail, config, answer, message_id)
SELECT seq, id, agent_id, project_id, update_id, question, options, state, expires_at,
	 decided_at, decided_value, kind, detail, config, answer, message_id
FROM approvals;

DROP TABLE approvals;
ALTER TABLE approvals_rebuilt RENAME TO approvals;

-- The sweeper's query: pending approvals whose expires_at has passed (design §5).
CREATE INDEX approvals_state_expires ON approvals (state, expires_at);
CREATE INDEX approvals_agent_seq ON approvals (agent_id, seq DESC);
-- What one agent has outstanding, which the stream counts on every event.
CREATE INDEX approvals_agent_state ON approvals (agent_id, state);
-- A question asked inside a thread, read whenever that thread renders.
CREATE INDEX approvals_message ON approvals (message_id);
