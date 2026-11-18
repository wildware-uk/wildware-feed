
ALTER TABLE read_cursors RENAME TO read_cursors_old;

CREATE TABLE read_cursors (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	agent_id TEXT NOT NULL REFERENCES agents (id),
	project_id TEXT NOT NULL DEFAULT '',
	last_seen_message_seq INTEGER NOT NULL DEFAULT 0,
	UNIQUE (agent_id, project_id)
) STRICT;

INSERT INTO read_cursors (id, agent_id, project_id, last_seen_message_seq)
SELECT lower(hex(randomblob(13))), old.agent_id, projects.id, old.last_seen_message_seq
  FROM read_cursors_old AS old, projects;

INSERT INTO read_cursors (id, agent_id, project_id, last_seen_message_seq)
SELECT lower(hex(randomblob(13))), old.agent_id, '', old.last_seen_message_seq
  FROM read_cursors_old AS old;

DROP TABLE read_cursors_old;

CREATE INDEX read_cursors_agent ON read_cursors (agent_id, project_id);
