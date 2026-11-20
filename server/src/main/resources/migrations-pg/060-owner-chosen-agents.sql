-- Migration 060 for Postgres, the twin of migrations/060-owner-chosen-agents.sql: the owner adds
-- an agent to a channel or a project, or removes it, and a removal sticks.
ALTER TABLE "project_subscriptions" ADD COLUMN removed_at bigint;
ALTER TABLE "project_subscriptions" ADD COLUMN by_owner bigint NOT NULL DEFAULT 0 CHECK (by_owner IN (0, 1));
ALTER TABLE "group_subscriptions" ADD COLUMN removed_at bigint;
ALTER TABLE "group_subscriptions" ADD COLUMN by_owner bigint NOT NULL DEFAULT 0 CHECK (by_owner IN (0, 1));
