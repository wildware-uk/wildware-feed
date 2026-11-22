-- Migration 070 for Postgres, the twin of migrations/070-the-board.sql: a finished card taken off
-- the board (board_hidden_at), or put back on it (board_kept_at), for everyone in the team.
ALTER TABLE "updates" ADD COLUMN board_hidden_at bigint;
ALTER TABLE "updates" ADD COLUMN board_kept_at bigint;
ALTER TABLE "messages" ADD COLUMN board_hidden_at bigint;
ALTER TABLE "messages" ADD COLUMN board_kept_at bigint;
