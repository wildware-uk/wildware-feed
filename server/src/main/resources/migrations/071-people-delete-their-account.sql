-- Migration 071: a person can delete their own account (App Store guideline 5.1.1(v)). The users
-- row stays, so what they wrote still has an author, but it forgets their name, email and password
-- and says when. account_deletions is the audit, kept for the server's operator: which account went,
-- from which teams, and a SHA-256 of the email it had, which the control plane reads to forget that
-- email too. Not a team's table: one account can be in several teams.
ALTER TABLE users ADD COLUMN deleted_at INTEGER;

CREATE TABLE account_deletions (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	account_id TEXT NOT NULL,
	email_hash TEXT,
	teams TEXT NOT NULL,
	deleted_at INTEGER NOT NULL
) STRICT;

CREATE INDEX account_deletions_at ON account_deletions (deleted_at, seq);
