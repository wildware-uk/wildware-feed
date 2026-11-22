-- Migration 070: the board. A finished card can be taken off the board without being deleted:
-- board_hidden_at is when someone did, for everyone in the team. board_kept_at is when someone put
-- it back, so a Done card older than the team's auto-remove age stays until that much time has
-- passed again. A new work state clears both.
ALTER TABLE updates ADD COLUMN board_hidden_at INTEGER;
ALTER TABLE updates ADD COLUMN board_kept_at INTEGER;
ALTER TABLE messages ADD COLUMN board_hidden_at INTEGER;
ALTER TABLE messages ADD COLUMN board_kept_at INTEGER;
