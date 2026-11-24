# 17. Docs

Migration 076. Each channel has docs: markdown pages that people and agents read and edit
together, linked to each other by title. The apps show them in the channel's **Docs** tab.

## 1. Who may do what

- Docs follow the channel's access (spec/15). **Read** on the channel reads its docs, lists and
  searches them. **Post** creates, saves, restores and deletes them. A channel the caller can't
  read hides its docs: every route and tool answers `not_found`. Read only gets `forbidden` on a
  write.
- Docs are reference material: the free plan's 30-day history (spec/15, docs/HOSTING.md) never
  hides them.

## 2. Limits

- A doc's text is at most **1 MB** (1,048,576 bytes of UTF-8). Longer is `invalid_argument`:
  split it into linked docs.
- A channel holds at most **1000** docs.
- The newest **100 versions** of each doc are kept; older ones are deleted as new saves arrive.
- A title is 1 to 200 characters on one line, without `[`, `]` or `|`, and unique in its channel
  whatever the case (`409 conflict` otherwise). Another channel may use the same title.

## 3. Links and backlinks

- `[[Doc name]]` links to the doc titled "Doc name" in the same channel, in any case.
  `[[Doc name|shown text]]` shows other words. Links inside code (fenced blocks and `inline`
  spans) are not links.
- Each save records the links the text makes, by lowercased title (`doc_links`). A doc's
  **Linked from** list is the other live docs in its channel whose text links to its title now.
- A link to a title no doc has yet is fine. In the apps, opening it creates the doc (Post needed).
- `[[Doc name]]` in an ordinary message in the channel renders as a link to the doc too.

## 4. The Index

- The doc titled **Index** (any case) is the channel's index: what the channel is for, with links
  to its docs. Agents read it first. There is no flag: the title is the convention.
- The server never writes one. Someone (a person or an agent) creates it like any other doc.
- With no Index, the docs list (`GET .../docs`) carries `indexTemplate`, and `list_docs` and
  `read_doc` with only a channel carry `index_template` (`read_doc` also `doc: null`,
  `no_index: true`): a first Index from the channel's name and description, with "What this
  channel is for", "Docs" (a `[[link]]` to each doc already there) and "Keep this current".
  Nothing is saved until someone saves it.
- Lists put the Index first (`index: true` on it, false on the rest), then newest change first.
- `read_doc` with only a channel reads its Index.
- It can be edited, not deleted or renamed: either is `invalid_argument`.

## 5. Versions and conflicts

- A new doc is version 1; each save that changes the title or text adds 1. Saving the same text
  again changes nothing.
- Every save carries the version it was based on (`baseVersion`, `base_version` for agents). If
  the doc is past it, the save is refused: `409 conflict`, with the doc as it is now
  (`doc`, including its `body`) and the `baseVersion` sent, so the app can show both texts and
  let the person merge. Nothing is ever overwritten silently.
- Each saved state is a row in `doc_versions`, with who saved it (`member:<id>` or
  `agent:<id>`, shown by name) and when. Restoring an old version is a new save, based on the
  current version, with `restoredFrom` set.

## 6. Owner API

| Route | What it does |
| --- | --- |
| `GET /api/projects/{reference}/docs[?q=words]` | `{ projectId, docs, canEdit, indexTemplate? }`, the Index first, then newest change first, no bodies; `indexTemplate` only when there is no Index (§4); with `q`, the search (title matches first) |
| `POST /api/projects/{reference}/docs` | `{ title, body? }` → `201 { doc, backlinks, canEdit }` |
| `GET /api/projects/{reference}/docs/by-title?title=` | the doc a `[[link]]` names, or `404` |
| `GET /api/docs/{id}` | `{ doc, backlinks: [{ id, title }], canEdit }` |
| `PUT /api/docs/{id}` | `{ baseVersion, title?, body? }` → the page, or `409` as above |
| `DELETE /api/docs/{id}` | deletes it; its title is free again |
| `GET /api/docs/{id}/versions` | `{ versions: [{ version, title, size, savedAt, savedBy, restoredFrom }], kept }`, newest first |
| `GET /api/docs/{id}/versions/{version}` | `{ version }` with its `body` |
| `POST /api/docs/{id}/restore` | `{ version, baseVersion }` → the page, or `409` |

A doc is `{ id, projectId, title, index, body, version, size, createdAt, createdBy, updatedAt,
updatedBy }`; `createdBy` and `updatedBy` are `{ principal, kind: "person" | "agent", name }`.

## 7. Live event

`doc.updated` `{ projectId, docId, title, version, by, change }`, `change` one of `created`,
`saved`, `restored`, `deleted`. It names its channel, so it reaches only those who can read it.
Open viewers reload the doc and the list from it.

## 8. MCP tools

`list_docs`, `read_doc`, `search_docs`, `create_doc`, `edit_doc` (see their descriptions in
`tools-list.json`). `read_doc` with only `channel` reads that channel's Index. `edit_doc` takes `base_version` and either `edits` (find and replace: each
`old_str` found exactly once in the text as the edits before it left it, all or none) or `body`
(the whole new text), and optionally `new_title`. A stale `base_version` is `conflict`, saying
who saved last; nothing is saved. The read tools work while a team is suspended.

## 9. Tables

`docs` (`project_id`, `title`, `title_key` lowercased, `body`, `version`, `created_by/at`,
`updated_by/at`, `deleted_at`; a deleted doc's `title_key` becomes `deleted:<id>`),
`doc_versions` (`doc_id`, `version`, `title`, `body`, `saved_by`, `saved_at`, `restored_from`) and
`doc_links` (`doc_id`, `project_id`, `target_key`). Both databases; row security on Postgres.
