-- Migration 058: each team's rows apart (docs/HOSTING.md §2, slice 3). Every team-owned table gets
-- a team_id; everything already here is the home team's (migration 055 made it). One server can
-- then hold many teams; a self-hosted one still holds one, so nothing else changes for it.
-- SQLite keeps its unique rules server-wide (one team per SQLite file, so the same thing) and adds
-- the per-team twins the code's ON CONFLICT clauses name. push_subscriptions (web push, unused
-- since the TypeScript server) goes.

ALTER TABLE acknowledgements ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE agent_pairings ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE agents ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE app_views ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE approvals ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE apps ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE card_blocks ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE card_fields ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE card_refs ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE card_tags ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE derivatives ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE embed_types ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE embeds ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE group_subscriptions ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE media ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE message_deliveries ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE message_mentions ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE messages ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE notifications ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE oauth_clients ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE oauth_grants ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE oauth_tokens ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE owner_devices ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE project_groups ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE project_subscriptions ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE projects ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE reactions ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE read_cursors ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE secret_agents ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE secret_audit ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE secret_grants ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE secrets ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE server_settings ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE sessions ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE tags ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE tasks ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE thread_subscriptions ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE update_shares ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE updates ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';
ALTER TABLE upload_tokens ADD COLUMN team_id TEXT NOT NULL DEFAULT 'home';

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

-- Names that are unique within a team (SQLite keeps its server-wide rules too: one team per file).
CREATE UNIQUE INDEX projects_team_slug ON projects (team_id, slug);
CREATE UNIQUE INDEX project_groups_team_slug ON project_groups (team_id, slug);
CREATE UNIQUE INDEX embed_types_team_slug ON embed_types (team_id, slug);
CREATE UNIQUE INDEX apps_team_uri ON apps (team_id, uri);
CREATE UNIQUE INDEX server_settings_team_key ON server_settings (team_id, key);
CREATE UNIQUE INDEX secrets_team_name ON secrets (team_id, name);

DROP TABLE push_subscriptions;
