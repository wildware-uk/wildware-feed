
ALTER TABLE messages ADD COLUMN reply_to TEXT REFERENCES messages (id);

-- The read a card makes: every reply under one post, oldest first.
CREATE INDEX messages_reply_to ON messages (reply_to);
