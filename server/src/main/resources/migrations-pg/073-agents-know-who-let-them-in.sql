-- Migration 073 for Postgres, the twin of migrations/073-agents-know-who-let-them-in.sql: who let
-- each agent in, and who allowed each OAuth connection, a membership id.
ALTER TABLE "agents" ADD COLUMN created_by text COLLATE "C";
ALTER TABLE "oauth_grants" ADD COLUMN created_by text COLLATE "C";
