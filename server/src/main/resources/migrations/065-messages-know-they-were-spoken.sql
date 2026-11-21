-- Migration 065: a message says how it was written. "voice" is a voice note, transcribed on the
-- phone and sent as said; NULL is typed. Agents read a voice note with a line saying it may hold
-- transcription mistakes; people see a small mic. The words themselves stay exactly as said.
ALTER TABLE messages ADD COLUMN source TEXT;
