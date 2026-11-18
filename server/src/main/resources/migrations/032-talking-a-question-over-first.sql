
ALTER TABLE messages ADD COLUMN request_id TEXT REFERENCES approvals (id);

-- One request's discussion, read every time the card renders.
CREATE INDEX messages_request ON messages (request_id);
