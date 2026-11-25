-- Migration 078 for Postgres, the twin of migrations/078-video-playback-copy.sql: when a video's
-- playback copy was tried in the background.
ALTER TABLE "media" ADD COLUMN playback_tried_at bigint;
