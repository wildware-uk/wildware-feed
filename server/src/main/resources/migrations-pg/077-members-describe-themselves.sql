-- Migration 077 for Postgres, the twin of migrations/077-members-describe-themselves.sql: each
-- person's (per membership) and each agent's role line, description and picture.
ALTER TABLE "memberships" ADD COLUMN profile_role text COLLATE "C";
ALTER TABLE "memberships" ADD COLUMN profile_description text COLLATE "C";
ALTER TABLE "memberships" ADD COLUMN avatar_media_id text COLLATE "C";
ALTER TABLE "agents" ADD COLUMN profile_role text COLLATE "C";
ALTER TABLE "agents" ADD COLUMN profile_description text COLLATE "C";
ALTER TABLE "agents" ADD COLUMN avatar_media_id text COLLATE "C";
