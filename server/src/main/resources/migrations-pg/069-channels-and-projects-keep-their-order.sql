-- Migration 069 for Postgres, the twin of migrations/069-channels-and-projects-keep-their-order.sql:
-- a channel's place in its project, and a project's place in the list (NULL for not arranged yet).
ALTER TABLE "projects" ADD COLUMN position bigint;
ALTER TABLE "project_groups" ADD COLUMN position bigint;
