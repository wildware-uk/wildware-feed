-- Migration 058 for Postgres, the twin of migrations/058-teams-apart.sql. The same columns, and a
-- row-security policy per table that shows a connection only the rows of the team it was told (`app.team`; '*' is every team, for sweeps and the admin API).
-- The policies do nothing until the server switches row security on for a table (TeamTables.kt).
-- Adding a column with a constant default rewrites nothing on Postgres 11 and later.

ALTER TABLE "acknowledgements" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "agent_pairings" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "agents" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "app_views" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "approvals" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "apps" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "card_blocks" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "card_fields" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "card_refs" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "card_tags" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "derivatives" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "embed_types" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "embeds" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "group_subscriptions" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "media" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "message_deliveries" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "message_mentions" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "messages" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "notifications" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "oauth_clients" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "oauth_grants" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "oauth_tokens" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "owner_devices" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "project_groups" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "project_subscriptions" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "projects" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "reactions" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "read_cursors" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "secret_agents" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "secret_audit" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "secret_grants" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "secrets" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "server_settings" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "sessions" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "tags" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "tasks" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "thread_subscriptions" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "update_shares" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "updates" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';
ALTER TABLE "upload_tokens" ADD COLUMN team_id text COLLATE "C" NOT NULL DEFAULT 'home';

-- Each team's lists, newest first.
CREATE INDEX agents_team ON agents (team_id, seq);
CREATE INDEX approvals_team ON approvals (team_id, seq);
CREATE INDEX media_team ON media (team_id, seq);
CREATE INDEX messages_team ON messages (team_id, seq);
CREATE INDEX notifications_team ON notifications (team_id, seq);
CREATE INDEX owner_devices_team ON owner_devices (team_id, seq);
CREATE INDEX projects_team ON projects (team_id, seq);
CREATE INDEX project_groups_team ON project_groups (team_id, seq);
CREATE INDEX sessions_team ON sessions (team_id, seq);
CREATE INDEX tasks_team ON tasks (team_id, seq);
CREATE INDEX updates_team ON updates (team_id, seq);
CREATE INDEX secrets_team ON secrets (team_id, seq);
CREATE INDEX agent_pairings_team ON agent_pairings (team_id, seq);
CREATE INDEX oauth_clients_team ON oauth_clients (team_id, seq);
CREATE INDEX tags_team ON tags (team_id, seq);
CREATE INDEX apps_team ON apps (team_id, seq);
CREATE INDEX embed_types_team ON embed_types (team_id, seq);

-- Names that are unique within a team. Projects, groups and secrets lose their server-wide rule
-- now (no code names it); apps, embed types and settings keep theirs one more release, because the
-- code before this one names it in ON CONFLICT.
CREATE UNIQUE INDEX projects_team_slug ON projects (team_id, slug);
CREATE UNIQUE INDEX project_groups_team_slug ON project_groups (team_id, slug);
CREATE UNIQUE INDEX embed_types_team_slug ON embed_types (team_id, slug);
CREATE UNIQUE INDEX apps_team_uri ON apps (team_id, uri);
CREATE UNIQUE INDEX server_settings_team_key ON server_settings (team_id, key);
CREATE UNIQUE INDEX secrets_team_name ON secrets (team_id, name);
ALTER TABLE "projects" DROP CONSTRAINT projects_slug_key;
ALTER TABLE "project_groups" DROP CONSTRAINT project_groups_slug_key;
ALTER TABLE "secrets" DROP CONSTRAINT secrets_name_key;

-- Row security, dormant until switched on: a connection sees and writes only its own team's rows.
CREATE POLICY team_rows ON "acknowledgements" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "agent_pairings" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "agents" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "app_views" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "approvals" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "apps" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "card_blocks" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "card_fields" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "card_refs" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "card_tags" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "derivatives" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "embed_types" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "embeds" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "group_subscriptions" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "media" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "message_deliveries" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "message_mentions" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "messages" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "notifications" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "oauth_clients" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "oauth_grants" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "oauth_tokens" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "owner_devices" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "project_groups" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "project_subscriptions" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "projects" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "reactions" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "read_cursors" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "secret_agents" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "secret_audit" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "secret_grants" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "secrets" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "server_settings" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "sessions" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "tags" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "tasks" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "thread_subscriptions" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "update_shares" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "updates" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "upload_tokens" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "memberships" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');
CREATE POLICY team_rows ON "invites" USING (team_id = current_setting('app.team', true) OR current_setting('app.team', true) = '*');

DROP TABLE "push_subscriptions";
