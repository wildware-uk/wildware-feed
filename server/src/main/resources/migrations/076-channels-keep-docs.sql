-- Migration 076: linked markdown docs in each channel (spec/17). A doc belongs to one channel and
-- is read and edited by whoever may read and post there. title_key is the title lowercased, so
-- [[Doc name]] finds it whatever the case and no two live docs in a channel share a title; a
-- deleted doc's key becomes "deleted:<id>", freeing its title. version counts its saves from 1.
-- doc_versions keeps each saved state (the newest ones; see Docs.KEEP_VERSIONS), with who saved it.
-- doc_links holds the [[links]] each doc's text makes, by title key, for "Linked from".
CREATE TABLE docs (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	team_id TEXT NOT NULL DEFAULT 'home',
	id TEXT NOT NULL UNIQUE,
	project_id TEXT NOT NULL,
	title TEXT NOT NULL,
	title_key TEXT NOT NULL,
	body TEXT NOT NULL,
	version INTEGER NOT NULL,
	created_by TEXT NOT NULL,
	created_at INTEGER NOT NULL,
	updated_by TEXT NOT NULL,
	updated_at INTEGER NOT NULL,
	deleted_at INTEGER
) STRICT;

CREATE UNIQUE INDEX docs_title ON docs (team_id, project_id, title_key);
CREATE INDEX docs_project ON docs (team_id, project_id, updated_at);

CREATE TABLE doc_versions (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	team_id TEXT NOT NULL DEFAULT 'home',
	id TEXT NOT NULL UNIQUE,
	doc_id TEXT NOT NULL,
	version INTEGER NOT NULL,
	title TEXT NOT NULL,
	body TEXT NOT NULL,
	saved_by TEXT NOT NULL,
	saved_at INTEGER NOT NULL,
	restored_from INTEGER
) STRICT;

CREATE UNIQUE INDEX doc_versions_doc ON doc_versions (team_id, doc_id, version);

CREATE TABLE doc_links (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	team_id TEXT NOT NULL DEFAULT 'home',
	doc_id TEXT NOT NULL,
	project_id TEXT NOT NULL,
	target_key TEXT NOT NULL
) STRICT;

CREATE UNIQUE INDEX doc_links_pair ON doc_links (team_id, doc_id, target_key);
CREATE INDEX doc_links_target ON doc_links (team_id, project_id, target_key);
