
ALTER TABLE messages ADD COLUMN answers TEXT REFERENCES messages (id);

-- Reading a thread means resolving every "in reply to" in it at once.
CREATE INDEX messages_answers ON messages (answers);
