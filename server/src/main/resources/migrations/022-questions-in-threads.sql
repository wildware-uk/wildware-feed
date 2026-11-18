
ALTER TABLE approvals ADD COLUMN message_id TEXT REFERENCES messages (id);

-- The read a thread makes: every question asked in it.
CREATE INDEX approvals_message ON approvals (message_id);
