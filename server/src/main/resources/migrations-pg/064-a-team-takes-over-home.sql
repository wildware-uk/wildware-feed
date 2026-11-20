-- Migration 064 for Postgres, the twin of migrations/064-a-team-takes-over-home.sql.
ALTER TABLE "teams" ADD COLUMN took_over_home bigint NOT NULL DEFAULT 0 CHECK (took_over_home IN (0, 1));
CREATE UNIQUE INDEX teams_took_over_home ON teams (took_over_home) WHERE took_over_home = 1;
