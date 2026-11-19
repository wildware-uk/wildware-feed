-- Migration 049: a question's priority, as an update has one, so a critical question can page a phone.
ALTER TABLE approvals ADD COLUMN priority TEXT NOT NULL DEFAULT 'medium';
