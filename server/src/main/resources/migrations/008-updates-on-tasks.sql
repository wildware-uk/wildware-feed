
ALTER TABLE updates ADD COLUMN task_id TEXT REFERENCES tasks (id);

-- The task page's own query: this task's updates, newest first.
CREATE INDEX updates_task_seq ON updates (task_id, seq DESC) WHERE task_id IS NOT NULL;
