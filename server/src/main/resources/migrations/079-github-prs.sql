-- Migration 079: pull requests on cards (docs/GITHUB.md). github_installations holds which GitHub
-- App installations a team has linked; one installation may serve several teams. card_prs holds a
-- PR an agent linked to an update: owner is that agent ("agent:<id>"), told when CI fails. checks
-- and reviews are JSON kept from the webhook; ci and review are worked out from them. alerted_sha
-- is the head commit last said to be red, red_open 1 until a green line follows it, and
-- review_alerted the last review whose change request was routed.
CREATE TABLE github_installations (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	team_id TEXT NOT NULL DEFAULT 'home',
	id TEXT NOT NULL UNIQUE,
	installation_id INTEGER NOT NULL,
	account TEXT NOT NULL,
	added_by TEXT NOT NULL,
	added_at INTEGER NOT NULL
) STRICT;

CREATE UNIQUE INDEX github_installations_team ON github_installations (team_id, installation_id);
CREATE INDEX github_installations_installation ON github_installations (installation_id);

CREATE TABLE card_prs (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	team_id TEXT NOT NULL DEFAULT 'home',
	id TEXT NOT NULL UNIQUE,
	update_id TEXT NOT NULL,
	repo TEXT NOT NULL,
	number INTEGER NOT NULL,
	url TEXT NOT NULL,
	owner TEXT NOT NULL,
	linked_at INTEGER NOT NULL,
	title TEXT,
	state TEXT,
	head_sha TEXT,
	checks TEXT NOT NULL DEFAULT '{}',
	ci TEXT NOT NULL DEFAULT 'none',
	reviews TEXT NOT NULL DEFAULT '{}',
	review TEXT NOT NULL DEFAULT 'none',
	alerted_sha TEXT,
	red_open INTEGER NOT NULL DEFAULT 0,
	review_alerted INTEGER,
	updated_at INTEGER NOT NULL
) STRICT;

CREATE UNIQUE INDEX card_prs_card ON card_prs (team_id, update_id, repo, number);
CREATE INDEX card_prs_pr ON card_prs (team_id, repo, number);
