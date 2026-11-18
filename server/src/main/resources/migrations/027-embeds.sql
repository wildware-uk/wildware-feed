
CREATE TABLE embed_types (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	-- What an agent names in post_update: stable, lowercase, its own handle.
	slug TEXT NOT NULL UNIQUE,
	name TEXT NOT NULL,
	-- Who registered it. NULL for the ones this deployment ships, which is also
	-- what makes them unownable: no agent matches NULL.
	agent_id TEXT REFERENCES agents (id),
	-- The look, as JSON: blocks and a palette, never markup. See the note above.
	spec TEXT NOT NULL,
	created_at INTEGER NOT NULL,
	updated_at INTEGER NOT NULL
) STRICT;

CREATE TABLE embeds (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	type_id TEXT NOT NULL REFERENCES embed_types (id),
	-- Exactly one of these two, enforced in the domain as media's are.
	update_id TEXT REFERENCES updates (id),
	message_id TEXT REFERENCES messages (id),
	agent_id TEXT REFERENCES agents (id),
	-- "human" or "agent:<id>", the vocabulary messages already use (design §3).
	author TEXT NOT NULL,
	-- The values this one shows, as a flat JSON object of strings.
	data TEXT NOT NULL,
	-- Where it was copied from, if the agent said. Shown as the card's link.
	source_url TEXT,
	created_at INTEGER NOT NULL
) STRICT;

-- A card's embeds, as it renders.
CREATE INDEX embeds_update ON embeds (update_id);
-- A reply's embeds, read whenever a thread renders.
CREATE INDEX embeds_message ON embeds (message_id);

INSERT INTO embed_types (id, slug, name, spec, created_at, updated_at) VALUES
	('01M0EMBED00000000000000001', 'slack-message', 'Slack message', '{"theme":{"accent":"#611f69","background":"#ffffff","foreground":"#1d1c1d","muted":"#616061","border":"#e3e3e3","font":"sans","radius":"md"},"chrome":{"label":"Slack","icon":"💬"},"blocks":[{"type":"header","avatar":"{{avatar}}","title":"{{username}}","subtitle":"{{channel}}","meta":"{{time}}"},{"type":"markdown","value":"{{text}}"},{"type":"chips","value":"{{reactions}}"},{"type":"footer","value":"View in Slack","url":"{{permalink}}"}]}', 0, 0),
	('01M0EMBED00000000000000002', 'github-comment', 'GitHub comment', '{"theme":{"accent":"#0969da","background":"#ffffff","foreground":"#1f2328","muted":"#59636e","border":"#d1d9e0","font":"sans","radius":"md"},"chrome":{"label":"GitHub","icon":"🐙"},"blocks":[{"type":"header","avatar":"{{avatar}}","title":"{{username}}","subtitle":"{{repo}} {{issue}}","meta":"{{time}}"},{"type":"markdown","value":"{{body}}"},{"type":"chips","value":"{{labels}}"},{"type":"footer","value":"View on GitHub","url":"{{url}}"}]}', 0, 0),
	('01M0EMBED00000000000000003', 'jira-issue', 'Jira issue', '{"theme":{"accent":"#0052cc","background":"#ffffff","foreground":"#172b4d","muted":"#626f86","border":"#dfe1e6","font":"sans","radius":"sm"},"chrome":{"label":"Jira","icon":"🟦"},"blocks":[{"type":"header","avatar":"{{avatar}}","title":"{{key}} {{summary}}","subtitle":"{{project}}","meta":"{{status}}"},{"type":"fields","items":[{"label":"Status","value":"{{status}}"},{"label":"Assignee","value":"{{assignee}}"},{"label":"Reporter","value":"{{reporter}}"},{"label":"Priority","value":"{{priority}}"}]},{"type":"markdown","value":"{{description}}"},{"type":"chips","value":"{{labels}}"},{"type":"footer","value":"View in Jira","url":"{{url}}"}]}', 0, 0),
	('01M0EMBED00000000000000004', 'confluence-page', 'Confluence page', '{"theme":{"accent":"#1868db","background":"#ffffff","foreground":"#172b4d","muted":"#626f86","border":"#dfe1e6","font":"serif","radius":"sm"},"chrome":{"label":"Confluence","icon":"📄"},"blocks":[{"type":"header","title":"{{title}}","subtitle":"{{space}}","meta":"{{updated}}"},{"type":"text","value":"{{author}}"},{"type":"markdown","value":"{{body}}"},{"type":"footer","value":"Open the page","url":"{{url}}"}]}', 0, 0),
	('01M0EMBED00000000000000005', 'linked-page', 'Linked page', '{"theme":{"accent":"#5b6672","background":"#ffffff","foreground":"#1f2328","muted":"#59636e","border":"#d7dbe0","font":"sans","radius":"md"},"chrome":{"label":"{{site}}","icon":"🔗"},"blocks":[{"type":"header","avatar":"{{image}}","title":"{{title}}","subtitle":"{{site}}"},{"type":"text","value":"{{description}}"},{"type":"footer","value":"Open","url":"{{url}}"}]}', 0, 0);
