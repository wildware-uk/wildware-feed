
UPDATE read_cursors
   SET last_seen_message_seq = (
	SELECT MAX(messages.seq)
	  FROM messages
	  JOIN tasks ON tasks.id = messages.id
	 WHERE messages.project_id = read_cursors.project_id
   )
 WHERE last_seen_message_seq < (
	SELECT COALESCE(MAX(messages.seq), 0)
	  FROM messages
	  JOIN tasks ON tasks.id = messages.id
	 WHERE messages.project_id = read_cursors.project_id
   );
