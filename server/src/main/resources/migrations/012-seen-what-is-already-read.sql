
UPDATE projects
   SET owner_seen_at = CAST(strftime('%s', 'now') AS INTEGER) * 1000
 WHERE owner_seen_at IS NULL;
