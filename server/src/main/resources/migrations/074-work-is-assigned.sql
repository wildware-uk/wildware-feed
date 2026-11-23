-- Migration 074: who a piece of work is assigned to (spec/16). A row gives one card (an update or
-- a post marked as work) one assignee: a person (member:<membership id>) or an agent
-- (agent:<agent id>). A card has at most a handful. assigned_by is whoever did it, in the same
-- form, and message_id is the line in the card's thread that said so. Unassigning deletes the row;
-- the thread keeps the line saying who did it.
CREATE TABLE card_assignees (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	team_id TEXT NOT NULL DEFAULT 'home',
	id TEXT NOT NULL UNIQUE,
	card_kind TEXT NOT NULL CHECK (card_kind IN ('update', 'message')),
	card_id TEXT NOT NULL,
	principal TEXT NOT NULL,
	assigned_by TEXT NOT NULL,
	assigned_at INTEGER NOT NULL,
	message_id TEXT
) STRICT;

CREATE UNIQUE INDEX card_assignees_card ON card_assignees (team_id, card_kind, card_id, principal);
CREATE INDEX card_assignees_principal ON card_assignees (team_id, principal);
