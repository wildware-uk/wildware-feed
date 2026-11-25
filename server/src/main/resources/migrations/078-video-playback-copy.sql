-- Migration 078: every video gets a playback copy, an mp4 of at most 1280x720 (spec/07 §5). Videos
-- from before it get theirs in the background, once each: this is when that was tried, so a video
-- that can't be converted isn't tried again. NULL is not tried yet.
ALTER TABLE media ADD COLUMN playback_tried_at INTEGER;
