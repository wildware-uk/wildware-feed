
ALTER TABLE messages ADD COLUMN deleted_at INTEGER;

CREATE INDEX messages_live_seq ON messages (seq) WHERE deleted_at IS NULL;
