-- Migration 073: who let each agent in (agents.created_by) and who allowed each OAuth connection
-- (oauth_grants.created_by), a membership id, so removing that person, deleting their account or
-- making them a member again revokes what they let in. Rows from before stay NULL: nobody knows.
ALTER TABLE agents ADD COLUMN created_by TEXT;
ALTER TABLE oauth_grants ADD COLUMN created_by TEXT;
