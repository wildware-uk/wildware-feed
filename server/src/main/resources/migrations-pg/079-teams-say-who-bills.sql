-- Migration 079 for Postgres, the twin of migrations/079-teams-say-who-bills.sql: who takes a
-- hosted team's money, 'web' or 'apple', or NULL for nobody.
ALTER TABLE "teams" ADD COLUMN billing text COLLATE "C" CHECK (billing IN ('web', 'apple'));
