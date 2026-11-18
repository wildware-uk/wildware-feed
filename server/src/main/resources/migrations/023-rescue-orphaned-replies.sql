
UPDATE messages AS reply
SET
	update_id = (SELECT parent.update_id FROM messages AS parent WHERE parent.id = reply.reply_to),
	task_id = (SELECT parent.task_id FROM messages AS parent WHERE parent.id = reply.reply_to),
	answers = COALESCE(reply.answers, reply.reply_to),
	reply_to = NULL
WHERE reply.update_id IS NULL
  AND reply.task_id IS NULL
  AND reply.reply_to IS NOT NULL
  AND EXISTS (
    SELECT 1 FROM messages AS parent
     WHERE parent.id = reply.reply_to
       AND (parent.update_id IS NOT NULL OR parent.task_id IS NOT NULL)
  );
