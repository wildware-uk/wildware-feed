-- Migration 062 for Postgres, the twin of migrations/062-codes-know-their-email.sql: a one-time code
-- remembers the email it was sent to, and the codes made before it go.
ALTER TABLE "auth_codes" ADD COLUMN email text COLLATE "C";
DELETE FROM "auth_codes";
