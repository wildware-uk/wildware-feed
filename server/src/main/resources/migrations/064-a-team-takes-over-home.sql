-- Migration 064: a team can take over the server's home team (feed-server move-home). Its rows
-- become that team's, its media files stay where they are, at the root of the store, and the
-- server's own address (PUBLIC_BASE_URL) answers as it, so every key and token handed out keeps
-- working. At most one team does.
ALTER TABLE teams ADD COLUMN took_over_home INTEGER NOT NULL DEFAULT 0 CHECK (took_over_home IN (0, 1));
CREATE UNIQUE INDEX teams_took_over_home ON teams (took_over_home) WHERE took_over_home = 1;
