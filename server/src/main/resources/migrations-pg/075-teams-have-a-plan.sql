-- Migration 075 for Postgres, the twin of migrations/075-teams-have-a-plan.sql: the plan the
-- control plane puts a hosted team on, 'free' or 'team', or NULL for none.
ALTER TABLE "teams" ADD COLUMN plan text COLLATE "C" CHECK (plan IN ('free', 'team'));
