
-- Work is a state a card is in, on both kinds of card that stay.
ALTER TABLE updates ADD COLUMN status TEXT
	CHECK (status IN ('todo', 'in_progress', 'blocked', 'done', 'cancelled'));
ALTER TABLE messages ADD COLUMN status TEXT
	CHECK (status IN ('todo', 'in_progress', 'blocked', 'done', 'cancelled'));

-- What the feed asks for constantly: the work that is not finished.
CREATE INDEX updates_status ON updates (status) WHERE status IS NOT NULL;
CREATE INDEX messages_status ON messages (status) WHERE status IS NOT NULL;

-- Every task becomes a post, keeping its own id so that everything pointing at
-- it keeps pointing at it.
INSERT INTO messages (id, project_id, author, body, created_at, status)
SELECT
	tasks.id,
	tasks.project_id,
	CASE WHEN tasks.agent_id IS NULL THEN 'human' ELSE 'agent:' || tasks.agent_id END,
	CASE
		WHEN tasks.body = '' THEN '**' || tasks.title || '**'
		ELSE '**' || tasks.title || '**' || char(10) || char(10) || tasks.body
	END,
	tasks.created_at,
	CASE tasks.state
		WHEN 'todo' THEN 'todo'
		WHEN 'claimed' THEN 'in_progress'
		WHEN 'done' THEN 'done'
		WHEN 'cancelled' THEN 'cancelled'
	END
FROM tasks;

-- A task's thread becomes the post's thread. The id is the same value, so this
-- is a move of the anchor rather than a lookup.
UPDATE messages SET reply_to = task_id, task_id = NULL
	WHERE task_id IS NOT NULL AND reply_to IS NULL;
UPDATE messages SET task_id = NULL WHERE task_id IS NOT NULL;

UPDATE acknowledgements SET message_id = task_id, task_id = NULL WHERE task_id IS NOT NULL;
UPDATE media SET message_id = task_id, task_id = NULL WHERE task_id IS NOT NULL;
UPDATE embeds SET message_id = task_id, task_id = NULL WHERE task_id IS NOT NULL;
UPDATE reactions SET message_id = task_id, task_id = NULL WHERE task_id IS NOT NULL;
