-- Migration 065 for Postgres, the twin of migrations/065-messages-know-they-were-spoken.sql: a
-- message says how it was written ("voice" for a transcribed voice note, NULL for typed).
ALTER TABLE "messages" ADD COLUMN source text COLLATE "C";
