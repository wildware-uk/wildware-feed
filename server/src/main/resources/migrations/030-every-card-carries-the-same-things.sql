
-- The card this is attached to, when it is an approval or a task. Exactly one
-- of the four is ever set; the domain enforces that, as it always has.
ALTER TABLE media ADD COLUMN request_id TEXT REFERENCES approvals (id);
ALTER TABLE media ADD COLUMN task_id TEXT REFERENCES tasks (id);

ALTER TABLE embeds ADD COLUMN request_id TEXT REFERENCES approvals (id);
ALTER TABLE embeds ADD COLUMN task_id TEXT REFERENCES tasks (id);

-- The reads a card makes as it renders, matching media_update and media_message.
CREATE INDEX media_request ON media (request_id);
CREATE INDEX media_task ON media (task_id);
CREATE INDEX embeds_request ON embeds (request_id);
CREATE INDEX embeds_task ON embeds (task_id);
