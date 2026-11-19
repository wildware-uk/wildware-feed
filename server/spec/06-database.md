# 06 — Database (`src/db/**`)

What the Kotlin server must do to open the **same** SQLite file as the TypeScript server, and what is in it.
All paths are relative to the TS repo root. The final schema below was built for real: every migration file
`001`–`045` was applied, in order, to an empty file with the runner's exact logic (pragmas, one transaction
per migration, foreign keys off around rebuilds), then `sqlite_master` was dumped.

## 1. Opening the file

| Item | Value | Source |
|---|---|---|
| Driver | `better-sqlite3` `^13.0.3` (installed 13.0.3), bundles SQLite **3.53.4** | `package.json:65` |
| Only importer of the driver | `src/db/connection.ts` (enforced by a test) | `src/db/connection.ts:1-8`, `src/db/connection.test.ts:141` |
| File name | `agent-dashboard.db` | `src/db/connection.ts:19` |
| Path | `join(DATA_DIR, 'agent-dashboard.db')` | `src/db/connection.ts:34-36` |
| `DATA_DIR` default | `'data'` (relative to the process cwd) | `src/config.ts:46` |
| Parent dir | created with `mkdirSync(..., { recursive: true })` | `src/db/connection.ts:49` |
| `PRAGMA journal_mode = WAL` | file DBs only (not `:memory:`) | `src/db/connection.ts:54` |
| `PRAGMA synchronous = NORMAL` | file DBs only | `src/db/connection.ts:56` |
| `PRAGMA foreign_keys = ON` | always | `src/db/connection.ts:59` |
| `PRAGMA busy_timeout = 5000` | always (ms) | `src/db/connection.ts:61` |
| Migrate on open | yes, unless `migrate: false` | `src/db/connection.ts:63` |
| Connections | **one** for the whole process, opened lazily | `src/db/connection.ts:68-83` |
| Close | `closeDatabase()` (CLI exit, tests) | `src/db/connection.ts:86-89`, `src/cli/index.ts:277` |

Not set: `user_version` (stays `0`), `application_id`, `legacy_alter_table`, `recursive_triggers`, page size,
cache size. No custom SQL functions, collations, triggers or views are registered or created anywhere
(`grep pragma` hits only `connection.ts` and `migrate.ts`; `sqlite_master` holds no trigger or view).

Built-in SQLite features the SQL relies on: `STRICT` tables (≥ 3.37), `RETURNING` (≥ 3.35), `ON CONFLICT … DO
UPDATE` upserts including **partial-index conflict targets**, `json_extract` and `json_tree`
(`src/db/media.ts:403,417`), `COLLATE NOCASE` (`src/db/embeds.ts:97`, `src/db/apps.ts:107`),
`randomblob`/`hex` (migration 025), `strftime('%s','now')` (migration 012), `char(10)` (migration 035).

**Transactions.** better-sqlite3 is synchronous and there is one connection, so every repository call is
atomic on its own and no two requests interleave inside one. Explicit `db.transaction` (plain `BEGIN … COMMIT`)
is used only in: the migration runner (`src/db/migrate.ts:119`), `setMessageMentions`
(`src/db/mentions.ts:20`), `deleteTag` (`src/db/tags.ts:141`), `setOwnerDeviceApns` /
`setOwnerDeviceLiveActivity` (`src/db/owner-devices.ts:160,196`), and media+token creation
(`src/media/upload.ts:229`). Several repositories do read-then-write without a transaction and are safe only
because of the single synchronous connection (see §8).

## 2. Value conventions

| Kind | Stored as | Source |
|---|---|---|
| Row key | `seq INTEGER PRIMARY KEY AUTOINCREMENT` on every table; never reused, used as cursor / order key | `src/db/migrations/001-initial-schema.ts:6-9` |
| Public id | `id TEXT`, a **ULID**: 26 chars, Crockford base32 upper-case, regex `^[0-9A-HJKMNP-TV-Z]{26}$` | `src/db/ids.ts:19,23` |
| ULID generator | `ulid` `^3.0.2`, `monotonicFactory()` — ids minted in the same ms increase, so `id` order = `seq` order | `src/db/ids.ts:1-11`, `package.json:71` |
| Exceptions to ULID | `read_cursors.id` seeded by migration 025 = `lower(hex(randomblob(13)))` (26 lower-case hex); `embed_types` built-ins `01M0EMBED0000000000000000{1..5}` | `src/db/migrations/025-cursor-per-project.ts:43,47`, `027-embeds.ts:92-96` |
| Timestamps | `INTEGER` ms since epoch (`Date.now()`) everywhere | `src/db/migrations/001-initial-schema.ts:11-12`, `src/db/README.md` |
| Timestamp exceptions | built-in `embed_types.created_at/updated_at = 0`; migration 012 wrote `owner_seen_at` at whole-second precision ×1000 | `027-embeds.ts:92-96`, `012-seen-what-is-already-read.ts:20` |
| Booleans | `INTEGER` 0/1 (`flagOf`/`boolOf`, `1` = true) | `src/db/rows.ts:10-17` |
| JSON | `TEXT` written with `JSON.stringify`, read with `JSON.parse`; `null`/`undefined` → SQL `NULL` | `src/db/rows.ts:20-28` |
| Author / actor strings | literal `human` (the owner) or `agent:<agent ULID>` | `src/db/types.ts:321`, `016-owner-media.ts:28-31` |
| Card anchors | one nullable column per card kind: `update_id`, `message_id`, `request_id` (= `approvals.id`); `task_id` legacy | `src/db/cards.ts:63-67` |

JSON-in-TEXT columns and their shapes:

| Column | Shape | Source |
|---|---|---|
| `projects.theme` | `{ background?: "#rrggbb", accent?: "#rrggbb", logoMediaId?: string, logoReplacesName?: boolean }` | `src/db/types.ts:34-50` |
| `projects.board` | `{ columns: [{ title: string, states: ("todo"\|"claimed"\|"done"\|"cancelled")[] }] }` | `src/db/types.ts:59-62` |
| `sessions.meta` | object: `{ host?, cwd?, model?, …any }` | `src/db/types.ts:88` |
| `approvals.options` | `string[]` | `src/db/approvals.ts:60,103` |
| `approvals.config` | `{ placeholder?, multiline?, default?, min?, max?, label?, optionMedia?: {[option]: mediaId} }` | `src/db/types.ts:452-465` |
| `approvals.answer` | `{ kind: RequestKind, value: string \| boolean \| string[] \| {action, text} }` | `src/db/types.ts:430-442` |
| `approvals.decided_value` | **not JSON**: plain scalar text, informational only | `src/db/types.ts:498-504` |
| `push_subscriptions.prefs` | `{ types?: string[], levels?: string[], priorities?: string[] }` | `src/db/push.ts:48-55` |
| `upload_tokens.mime_allow` | `string[]` (always `[mime]` today); `NULL` read as `[]` | `src/db/upload-tokens.ts:34`, `src/media/upload.ts:250` |
| `embed_types.spec` | closed block/palette vocabulary, kept as raw text in db layer; validated in `src/domain/embeds.ts` | `src/db/types.ts:521-536` |
| `embeds.data` | flat object of strings (raw text in db layer) | `src/db/types.ts:555-556` |
| `apps.csp` | `{ connect: string[], resource: string[], frame: string[] }` (raw text) | `src/db/migrations/041-an-app-on-a-card.ts:67-69` |
| `app_views.data`, `app_views.state` | arbitrary JSON (raw text); `state` nullable | `src/db/types.ts:659-662` |
| `owner_devices.alert_project_ids` | `string[]` or `NULL` (= all projects) | `src/db/owner-devices.ts:62-63,181` |

## 3. Migration mechanism (`src/db/migrate.ts`, `src/db/migrations/index.ts`)

- **Bookkeeping table** `schema_migrations` (`src/db/schema.ts:45`), created on every call with
  `CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, name TEXT NOT NULL, checksum TEXT
  NOT NULL, applied_at INTEGER NOT NULL) STRICT;` (`src/db/migrate.ts:29-38`).
- **Row written per migration**: `(version, name, checksum, applied_at)` where `checksum` =
  lower-case hex SHA-256 of the migration's SQL string (UTF-8) and `applied_at = Date.now()`
  (`src/db/migrate.ts:25-27,113-121`).
- **Migration list**: array `MIGRATIONS` of `{ version, name, sql, rebuildsTables? }`
  (`src/db/migrations/index.ts:54-79,81-144`). `sql` is the exact contents of the template literal
  `export const sql = \`…\`` in each file — it starts with a newline and includes tabs, comments and (027)
  emoji. No file contains `\` or `${`, so the literal text **is** the string.
- **Boot check** (`assertUsable`, `src/db/migrate.ts:56-81`), run before anything is applied:
  1. duplicate version in the list → throw;
  2. a version in `schema_migrations` that the build does not know → throw *"Refusing to run against a newer
     database"*;
  3. an applied version whose current SQL checksum differs from the stored one → throw *"Migrations are
     append-only"*.
  Gaps are not checked at boot (only by a test, `src/db/migrate.test.ts:100`). `name` is not compared.
- **Pending** = known versions not in the table, sorted ascending by `version`
  (`src/db/migrate.ts:84-93`). Comparison is by integer `version` only.
- **Apply** (`src/db/migrate.ts:110-149`): for each pending migration, one transaction containing
  `db.exec(sql)` + the `INSERT INTO schema_migrations`. A failure rolls that migration back and throws (later
  ones are not attempted; earlier ones stay committed).
- **`rebuildsTables: true`** (versions **16, 25, 26, 31, 34, 38** — `src/db/migrations/index.ts:97,106,107,
  120-125,128,132`): `PRAGMA foreign_keys = OFF` before the transaction, then after commit
  `PRAGMA foreign_key_check`; any row → throw; `finally` sets `foreign_keys = ON`
  (`src/db/migrate.ts:129-145`).
- Running on an up-to-date DB is a no-op (`src/db/migrate.ts:1-7`).

Migration 047 (`device-alert-prefs`, `ALTER TABLE owner_devices ADD COLUMN alert_prefs TEXT;`) came after
046 the same way: TS b65832d and the Kotlin server ship byte-identical SQL.

**Adding migration 046 so both servers stay compatible.** Each server refuses a DB that has a version it does
not know, and refuses a known version whose SQL text changed. So:
1. Write `src/db/migrations/046-<name>.ts` in the TS repo **and** the byte-identical SQL in the Kotlin
   server, with the same version and name; register it in `src/db/migrations/index.ts`.
2. Kotlin must record `checksum = sha256_hex(utf8(sqlText))` of exactly that text, or the TS server will
   refuse to boot ("has changed since it was applied").
3. Ship both servers before either runs against the live file. Whichever server boots first applies 046;
   the other then accepts it.
4. Add-only DDL (`ALTER TABLE … ADD COLUMN`, `CREATE INDEX`, new tables). A table rebuild needs
   `rebuildsTables` semantics in both runners: FKs off outside the transaction, rebuild as
   *create `X_rebuilt` → copy → drop `X` → rename `X_rebuilt` to `X` → recreate every index*, then
   `foreign_key_check`.

## 4. Migrations 001–045

Rebuild = run with foreign keys off (`rebuildsTables`). SQL starts at the cited line.

| # | File:line | What it did | Data backfill / transform |
|---|---|---|---|
| 001 | `001-initial-schema.ts:19` | Created `projects`, `agents`, `sessions`, `updates`, `media`, `derivatives`, `upload_tokens`, `tasks`, `messages`, `read_cursors` (one per agent), `approvals` + indexes | — |
| 002 | `002-owner-requests.ts:31` | `approvals` + `kind` (default `'confirm'`, no CHECK), `detail`, `config`, `answer`; index `approvals_agent_state` | existing rows get `kind='confirm'` by default |
| 003 | `003-push-subscriptions.ts:28` | `push_subscriptions` + unique `id`, unique `endpoint` | — |
| 004 | `004-update-edits.ts:18` | `updates.edited_at` | — |
| 005 | `005-update-shares.ts:25` | `update_shares` (no FK on `update_id`), unique `id`, unique `token_hash`, partial unique live share per update | — |
| 006 | `006-project-theme.ts:21` | `projects.theme` | — |
| 007 | `007-priority-and-push-prefs.ts:28` | `updates.priority` (default `'medium'`, no CHECK); `push_subscriptions.prefs` | — |
| 008 | `008-updates-on-tasks.ts:20` | `updates.task_id` → `tasks`; partial index `updates_task_seq` | — |
| 009 | `009-project-board.ts:17` | `projects.board` | — |
| 010 | `010-task-broadcast.ts:20` | `tasks.broadcast_at` | — |
| 011 | `011-project-seen.ts:16` | `projects.owner_seen_at` | — |
| 012 | `012-seen-what-is-already-read.ts:18` | data only | `owner_seen_at = strftime('%s','now')*1000` where NULL |
| 013 | `013-acknowledgements.ts:32` | `acknowledgements` (message/task anchors), partial unique per agent+anchor | — |
| 014 | `014-message-replies.ts:23` | `messages.reply_to` → `messages`; index | — |
| 015 | `015-replies-seen.ts:18` | `updates.replies_seen_at` | — |
| 016 | `016-owner-media.ts:39` | **Rebuild** `media`: `agent_id` nullable, + `author` NOT NULL, + `message_id` | `author` = `'agent:'` concatenated with `agent_id`; `message_id = NULL` |
| 017 | `017-message-deletes.ts:20` | `messages.deleted_at`; partial index `messages_live_seq` | — |
| 018 | `018-message-deliveries.ts:25` | `message_deliveries`, unique (message, agent) | — |
| 019 | `019-delivery-per-client.ts:26` | + `client_id`; unique index becomes (message, agent, client); agent index becomes (agent, client, message) | — |
| 020 | `020-answering-a-comment.ts:22` | `messages.answers` → `messages`; index | — |
| 021 | `021-notifications.ts:24` | `notifications` + partial unique per target | — |
| 022 | `022-questions-in-threads.ts:16` | `approvals.message_id` → `messages`; index | — |
| 023 | `023-rescue-orphaned-replies.ts:26` | data only | messages with `reply_to` set, no `update_id`/`task_id`, whose parent has a thread: take parent's `update_id`/`task_id`, `answers = COALESCE(answers, reply_to)`, `reply_to = NULL` |
| 024 | `024-reactions.ts:22` | `reactions` (message only), unique (message, actor, emoji) | — |
| 025 | `025-cursor-per-project.ts:30` | **Rebuild** `read_cursors`: + `project_id NOT NULL DEFAULT ''`, `UNIQUE(agent_id, project_id)` | old cursor copied to **every project** + one `''` row per agent; ids = random hex |
| 026 | `026-any-file.ts:33` | **Rebuild** `media`: kind CHECK adds `'file'`, + `filename` | `filename = NULL` |
| 027 | `027-embeds.ts:53` | `embed_types`, `embeds` + indexes | seeds 5 built-in types (`slack-message`, `github-comment`, `jira-issue`, `confluence-page`, `linked-page`), `agent_id NULL`, timestamps 0 |
| 028 | `028-embeds-look-like-their-sources.ts:52` | data only | rewrites `spec` of the 5 built-ins (`WHERE slug=… AND agent_id IS NULL`) |
| 029 | `029-embeds-carry-their-mark.ts:32` | data only | rewrites the same 5 specs again, adding `chrome.mark` |
| 030 | `030-every-card-carries-the-same-things.ts:34` | `media.request_id`, `media.task_id`, `embeds.request_id`, `embeds.task_id`; 4 indexes | — |
| 031 | `031-an-agent-withdraws-its-own-question.ts:35` | **Rebuild** `approvals`: state CHECK adds `'withdrawn'`; recreates its 4 indexes | straight copy |
| 032 | `032-talking-a-question-over-first.ts:36` | `messages.request_id` → `approvals`; index | — |
| 033 | `033-second-thoughts.ts:25` | `messages.edited_at`, `approvals.edited_at` | — |
| 034 | `034-one-kind-of-card.ts:50` | `pinned` on messages/approvals/tasks; `tasks.edited_at`; `acknowledgements.update_id/request_id` + indexes; **rebuild** `reactions` with 4 anchors + partial unique per anchor | reactions copied (message anchor only) |
| 035 | `035-work-lives-in-the-feed.ts:49` | `updates.status`, `messages.status` (CHECK 5 values) + partial indexes | **every task → a message with the same `id`**: author `human`/`agent:<id>`, body `**title**` (+ `\n\n` + body), status todo/claimed→in_progress/done/cancelled; messages with `task_id` → `reply_to = task_id` (if no `reply_to`) and `task_id = NULL`; acks/media/embeds/reactions `task_id` → `message_id` |
| 036 | `036-old-tasks-are-not-news.ts:22` | data only | each `read_cursors` row moved up to the max `seq` of converted-task messages in its project (if lower) |
| 037 | `037-work-that-moves-comes-back.ts:24` | `updates.status_at`, `messages.status_at` | — |
| 038 | `038-sound-on-a-card.ts:31` | **Rebuild** `media`: kind CHECK adds `'audio'` | straight copy (see Surprises: two indexes lost) |
| 039 | `039-tags-on-a-card.ts:38` | `tags`, `card_tags` + indexes | — |
| 040 | `040-notes-on-a-card.ts:38` | `card_fields` + indexes | — |
| 041 | `041-an-app-on-a-card.ts:52` | `apps`, `app_views` + indexes | — |
| 042 | `042-an-app-that-lives-somewhere-else.ts:32` | `apps.url` | — |
| 043 | `043-owner-devices.ts:14` | `owner_devices` + unique `id`, unique `token_hash` | — |
| 044 | `044-mentions.ts:14` | `message_mentions` + unique (message, agent), index (agent, message) | — |
| 045 | `045-alert-projects.ts:9` | `owner_devices.alert_project_ids` | — |

### Recorded checksums

`schema_migrations` must hold exactly these `(version, name, checksum)` rows on a DB at 045 (computed from the
working-tree SQL with the runner's own hash):

| version | name | sha256 |
|---|---|---|
| 1 | `initial-schema` | `d3e404f27352fa1ed17f206bee219a0d51a5508ac623f196468a73dc4ad9d762` |
| 2 | `owner-requests` | `0e344d13a0730e77a39c34595e263eb01adc6e8b944097b18aee0e9e0a524188` |
| 3 | `push-subscriptions` | `3702be1e8b9f602812504f7deaf4973be3dbe4d8d348df7e58352f994dab75b7` |
| 4 | `update-edits` | `72a4050a7c4754848b1aca7e532d6e9e2cfee4e12b71c50fbffc85c6f41375eb` |
| 5 | `update-shares` | `b9857dea2bc5280a5a1f75c45b53c2df4d243cd6813d07b51281793b96ec7773` |
| 6 | `project-theme` | `92495f30277d13199a8eebb0b20259ce77728784e5b307adae6a8c5c2dadc3ed` |
| 7 | `priority-and-push-prefs` | `924f67c6df72bcd06b4357730b454852ca807e9429156d0dea98dd6cb3893afa` |
| 8 | `updates-on-tasks` | `c40c9a1805655b11b092eafbb442981a46c466eee43f0959d837ca7f591299d0` |
| 9 | `project-board` | `8ccdecb48939c6155d4259f2c612983a7e2e1192a89471072bdde2a169d36050` |
| 10 | `task-broadcast` | `b65d7ed5cf8ae7216a21ff7339e19ca15c545dd88f1d454febd938904791267c` |
| 11 | `project-seen` | `78a8168a3c05a71c0b1d9f07636d30725f069431921134a9e3879f406d92315b` |
| 12 | `seen-what-is-already-read` | `22c1350cc746f3a290580ed9985a170127340b99f98c74eef67aa586830177ba` |
| 13 | `acknowledgements` | `bb7c6c42b0654453f6438ab5bb18edf06deb625fc13e18f02a3425c4a3cfb687` |
| 14 | `message-replies` | `172f8ebdba37aea7bfd0822d4c5adf5e0a7d02826ca9135ed2f1c56eec0b762e` |
| 15 | `replies-seen` | `45636214f58b21e2483862440343f9952ea638e28e105fe03b9c5475c26ac742` |
| 16 | `owner-media` | `b794912e5ac6baa375db5b628676160e69be4177fd3455b6de4429ff4a68746e` |
| 17 | `message-deletes` | `3a1ee58115f2c89bed42dfdb1dda585dc6c1b696d67850b3fa9832e1bd95bc98` |
| 18 | `message-deliveries` | `76dad6711dd8720215a4165a614c6dfda2ef122d7b7c7c30f542a5ad11535586` |
| 19 | `delivery-per-client` | `0a187b86560c3a31d5ae4e5d219436596125ed634e60eb54b5365020e93af4b9` |
| 20 | `answering-a-comment` | `b7f737252b4ea8433c0681594cabc763483cae0c71ec5fa857e595e3d0de95bf` |
| 21 | `notifications` | `2011805ed3e8a12e23a5542dc2362e102b7ad36aed7cd5beba2ec7ebe9ecfc40` |
| 22 | `questions-in-threads` | `d5286ff64dfb8540a7510fa6f7b974b349b6e655707a8d36aa31bd8f9b4d3eef` |
| 23 | `rescue-orphaned-replies` | `bfb84b6dd6d81ebda0991adce4238b75e2f034e40b9f78cca51436663a3c381c` |
| 24 | `reactions` | `22de9b670beb94b80e650b181b5defe748cf0cbe995d57431fc83a37a3bf869e` |
| 25 | `cursor-per-project` | `e6ead86c991eccea0887982ee4310c6e63b20347c668f9da38badeba7302ea17` |
| 26 | `any-file` | `4846c72a13fcf9917b65058137a3036c3c4b0332e633e20e4a6d2a144b41166f` |
| 27 | `embeds` | `c283121648049aaad44c4e0dcb7e4f0fb204405f40f247d026054f2a00dfeff2` |
| 28 | `embeds-look-like-their-sources` | `9381e5c5f66e74030a3accb6b8edfa60d3e9d9d917f59cd31ef8db77e728d087` |
| 29 | `embeds-carry-their-mark` | `326f7a66a6b680982bc1039573a7680f88c4b01ca3f7761f43dfccd67315c1f2` |
| 30 | `every-card-carries-the-same-things` | `0898f54499d0aa9ebe1159ea39f5786e2c64b6674e499321e4dc107d7bdef7f2` |
| 31 | `an-agent-withdraws-its-own-question` | `e77576eda765e7c83f875401aae3fc5fc71cc02581720f2b600dedba77954d3e` |
| 32 | `talking-a-question-over-first` | `8a60af36098bd99c47393a076c3092433e666a0ac1e198ad88148078fa4757a1` |
| 33 | `second-thoughts` | `ff93b64f70c7b5a779943bddbdf9ba689a6a322856b705ba448b9356faafef20` |
| 34 | `one-kind-of-card` | `cf2ce9e8789e75e2287f395dfbd91d9cf928da18f43d2778fcaf4a55f965f2a6` |
| 35 | `work-lives-in-the-feed` | `eab9adce021a9354c5c54bacaaad9a3cf20876dc85e21ac45ed8496d5339034a` |
| 36 | `old-tasks-are-not-news` | `553e1bf41cefe321151ce7a940658788c911785b461b19fdf10e57740c976176` |
| 37 | `work-that-moves-comes-back` | `d6d50310cf46bca3474eb03337da371a592afc097c5afd4f84fd7c4e9bc2ac02` |
| 38 | `sound-on-a-card` | `62803bd919497d2ae31cc367a1a51e8bb24e13a1cc15461d7aaa5826f84f31c2` |
| 39 | `tags-on-a-card` | `25803620b8c6b27399f9f57a5796af561fec79813588f2e47f4e08e11315f264` |
| 40 | `notes-on-a-card` | `23cac4f4cccefd3c15cb358f2dd5a56431a6c08c85d29cb57f1a1ab8ea56c1d6` |
| 41 | `an-app-on-a-card` | `7e0d64de7fdfc7151f1232b8fd2e0726e264098a8cfbe11c259cc308a0346873` |
| 42 | `an-app-that-lives-somewhere-else` | `0303325056000f18002007e41fe84dc7b843afd7f3c7ad03990417b5898ece32` |
| 43 | `owner-devices` | `aa55018a705ad19c3103f7853cc3c25ce945f6b646b48e4199e8308234fc4ee2` |
| 44 | `mentions` | `d88737723f6b8512b1e73e72032677a7cb903675a5afbfd1434362312d526cff` |
| 45 | `alert-projects` | `7cbe2615fe2e5ff45b4604b98a5809431535d2fffae4741a11b19982991f386e` |
| 46 | `media-indexes-come-back` | `357b4342fe37b91bddc580b48db9def109c73f62454ab94afcca05632ecbb683` |
| 47 | `device-alert-prefs` | `e135df3c06ce57b245afb42184c8c23cfec4510f73453f9686ae659195ef49d9` |
| 48 | `media-retention` | `afc20b267c20eacdae209999ac1ecc796ae0a79a039b02ce375d6bdb1925f2c9` (Kotlin only) |
| 49 | `request-priority` | `e0a17823bbfac65b7e582741261d7fe16751d4e0e3bf0e33593994d170316502` (Kotlin only) |

## 5. Final schema after 045 (verbatim `sqlite_master.sql`)

Dumped from a fresh file migrated 001→045, ordered tables → indexes, each group by table then name. The text is
what SQLite stores, so `ALTER TABLE … ADD COLUMN` columns appear appended after the original column list and
migration comments are kept. No triggers, no views. Besides these, SQLite keeps `sqlite_sequence` (from
`AUTOINCREMENT`) and 27 automatic indexes for inline `UNIQUE` constraints: `agents`(id, token_hash),
`app_views`(id), `approvals`(id), `apps`(id, uri), `card_fields`(id), `card_tags`(id), `derivatives`(id),
`embed_types`(id, slug), `embeds`(id), `media`(id), `message_deliveries`(id), `message_mentions`(id),
`messages`(id), `notifications`(id), `projects`(id, slug), `reactions`(id), `read_cursors`(id, (agent_id,
project_id)), `sessions`(id), `tags`(id), `tasks`(id), `updates`(id), `upload_tokens`(id).

Every `REFERENCES` is `ON DELETE NO ACTION` except `derivatives.media_id` and `upload_tokens.media_id`, which
are `ON DELETE CASCADE`. All FKs point at the parent's `id` column (a `UNIQUE` column, not the rowid).

```sql
CREATE TABLE acknowledgements (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL,
	agent_id TEXT NOT NULL REFERENCES agents (id),
	message_id TEXT REFERENCES messages (id),
	task_id TEXT REFERENCES tasks (id),
	state TEXT NOT NULL,
	created_at INTEGER NOT NULL,
	updated_at INTEGER NOT NULL
, update_id TEXT REFERENCES updates (id), request_id TEXT REFERENCES approvals (id)) STRICT;

CREATE TABLE agents (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	name TEXT NOT NULL,
	token_hash TEXT NOT NULL UNIQUE,
	created_at INTEGER NOT NULL,
	revoked_at INTEGER,
	last_seen_at INTEGER
) STRICT;

CREATE TABLE app_views (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	app_id TEXT NOT NULL REFERENCES apps (id),
	-- Exactly one of these, the generic card anchor every card feature uses
	-- (migration 034).
	update_id TEXT REFERENCES updates (id),
	message_id TEXT REFERENCES messages (id),
	request_id TEXT REFERENCES approvals (id),
	agent_id TEXT REFERENCES agents (id),
	-- 'human' or 'agent:<id>', the vocabulary every other card feature uses.
	author TEXT NOT NULL,
	-- What the agent handed this instance, as JSON. Delivered to the frame as the
	-- spec's ui/notifications/tool-result.
	data TEXT NOT NULL,
	-- What the app itself saved, as JSON, or NULL if it never has.
	state TEXT,
	-- A line of text above the frame, so a collapsed app still says what it is.
	title TEXT,
	created_at INTEGER NOT NULL,
	updated_at INTEGER NOT NULL
) STRICT;

CREATE TABLE "approvals" (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	agent_id TEXT NOT NULL REFERENCES agents (id),
	project_id TEXT REFERENCES projects (id),
	update_id TEXT REFERENCES updates (id),
	question TEXT NOT NULL,
	options TEXT,
	-- 'withdrawn' is the new one: the asking agent took the question back.
	state TEXT NOT NULL DEFAULT 'pending'
		CHECK (state IN ('pending', 'approved', 'rejected', 'timeout', 'cancelled', 'withdrawn')),
	expires_at INTEGER NOT NULL,
	decided_at INTEGER,
	decided_value TEXT,
	kind TEXT NOT NULL DEFAULT 'confirm',
	detail TEXT,
	config TEXT,
	answer TEXT,
	message_id TEXT REFERENCES messages (id)
, edited_at INTEGER, pinned INTEGER NOT NULL DEFAULT 0) STRICT;

CREATE TABLE apps (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	-- The spec's own handle, e.g. 'ui://review-board'. Unique, and what an agent
	-- names when it shows one.
	uri TEXT NOT NULL UNIQUE,
	name TEXT NOT NULL,
	description TEXT,
	-- Who registered it. NULL would mean one this deployment ships; there are
	-- none yet, and the column is nullable so there can be.
	agent_id TEXT REFERENCES agents (id),
	-- The document itself: text/html;profile=mcp-app, served into a sandboxed
	-- iframe and never into this page.
	html TEXT NOT NULL,
	-- The origins it declared, as JSON: { connect: [], resource: [], frame: [] }.
	-- Anything not named here is refused by the policy the route sends.
	csp TEXT NOT NULL,
	created_at INTEGER NOT NULL,
	updated_at INTEGER NOT NULL
, url TEXT) STRICT;

CREATE TABLE card_fields (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	update_id TEXT REFERENCES updates (id),
	message_id TEXT REFERENCES messages (id),
	request_id TEXT REFERENCES approvals (id),
	-- What it is called, as it is shown.
	name TEXT NOT NULL,
	-- The same, normalised: what "already set" is judged on.
	slug TEXT NOT NULL,
	-- What it says. Text, always: this is a note, not a type system.
	value TEXT NOT NULL,
	-- Who set it last: the literal 'human', or 'agent:<id>'.
	author TEXT NOT NULL,
	created_at INTEGER NOT NULL,
	updated_at INTEGER NOT NULL
) STRICT;

CREATE TABLE card_tags (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	tag_id TEXT NOT NULL REFERENCES tags (id),
	update_id TEXT REFERENCES updates (id),
	message_id TEXT REFERENCES messages (id),
	request_id TEXT REFERENCES approvals (id),
	-- Who put it on: the literal 'human', or 'agent:<id>'. The vocabulary every
	-- other card feature uses.
	author TEXT NOT NULL,
	created_at INTEGER NOT NULL
) STRICT;

CREATE TABLE derivatives (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	media_id TEXT NOT NULL REFERENCES media (id) ON DELETE CASCADE,
	kind TEXT NOT NULL CHECK (kind IN ('thumb', 'poster', 'mp4')),
	path TEXT NOT NULL,
	bytes INTEGER NOT NULL,
	width INTEGER,
	height INTEGER
) STRICT;

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
, request_id TEXT REFERENCES approvals (id), task_id TEXT REFERENCES tasks (id)) STRICT;

CREATE TABLE "media" (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	agent_id TEXT REFERENCES agents (id),
	author TEXT NOT NULL,
	update_id TEXT REFERENCES updates (id),
	message_id TEXT REFERENCES messages (id),
	kind TEXT NOT NULL CHECK (kind IN ('image', 'video', 'audio', 'file')),
	mime TEXT NOT NULL,
	bytes INTEGER NOT NULL,
	sha256 TEXT NOT NULL,
	width INTEGER,
	height INTEGER,
	duration_ms INTEGER,
	filename TEXT,
	status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'ready', 'failed')),
	created_at INTEGER NOT NULL,
	-- Migration 030: an approval and a task carry attachments too. Carried
	-- through this rebuild rather than reinvented; a column quietly dropped here
	-- would take every question's screenshots with it.
	request_id TEXT REFERENCES approvals (id),
	task_id TEXT REFERENCES tasks (id)
) STRICT;

CREATE TABLE message_deliveries (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	message_id TEXT NOT NULL REFERENCES messages (id),
	agent_id TEXT NOT NULL REFERENCES agents (id),
	delivered_at INTEGER NOT NULL
, client_id TEXT) STRICT;

CREATE TABLE message_mentions (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	message_id TEXT NOT NULL REFERENCES messages (id),
	agent_id TEXT NOT NULL REFERENCES agents (id)
) STRICT;

CREATE TABLE messages (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	project_id TEXT REFERENCES projects (id),
	update_id TEXT REFERENCES updates (id),
	task_id TEXT REFERENCES tasks (id),
	author TEXT NOT NULL,
	body TEXT NOT NULL,
	created_at INTEGER NOT NULL
, reply_to TEXT REFERENCES messages (id), deleted_at INTEGER, answers TEXT REFERENCES messages (id), request_id TEXT REFERENCES approvals (id), edited_at INTEGER, pinned INTEGER NOT NULL DEFAULT 0, status TEXT
	CHECK (status IN ('todo', 'in_progress', 'blocked', 'done', 'cancelled')), status_at INTEGER) STRICT;

CREATE TABLE notifications (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	kind TEXT NOT NULL CHECK (kind IN ('update', 'reply', 'request')),
	project_id TEXT REFERENCES projects (id),
	update_id TEXT REFERENCES updates (id),
	message_id TEXT REFERENCES messages (id),
	request_id TEXT REFERENCES approvals (id),
	agent_id TEXT REFERENCES agents (id),
	title TEXT NOT NULL,
	body TEXT NOT NULL,
	created_at INTEGER NOT NULL,
	seen_at INTEGER
) STRICT;

CREATE TABLE owner_devices (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL,
	name TEXT NOT NULL,
	token_hash TEXT NOT NULL,
	apns_token TEXT,
	apns_environment TEXT CHECK (apns_environment IN ('sandbox', 'production')),
	live_activity_token TEXT,
	push_to_start_token TEXT,
	created_at INTEGER NOT NULL,
	last_seen_at INTEGER,
	failures INTEGER NOT NULL DEFAULT 0
, alert_project_ids TEXT) STRICT;

CREATE TABLE projects (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	slug TEXT NOT NULL UNIQUE,
	name TEXT NOT NULL,
	description TEXT,
	status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'archived')),
	pinned INTEGER NOT NULL DEFAULT 0 CHECK (pinned IN (0, 1)),
	created_at INTEGER NOT NULL,
	updated_at INTEGER NOT NULL
, theme TEXT, board TEXT, owner_seen_at INTEGER) STRICT;

CREATE TABLE push_subscriptions (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL,
	endpoint TEXT NOT NULL,
	p256dh TEXT NOT NULL,
	auth TEXT NOT NULL,
	label TEXT,
	created_at INTEGER NOT NULL,
	last_sent_at INTEGER,
	failures INTEGER NOT NULL DEFAULT 0
, prefs TEXT) STRICT;

CREATE TABLE "reactions" (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	message_id TEXT REFERENCES messages (id),
	update_id TEXT REFERENCES updates (id),
	request_id TEXT REFERENCES approvals (id),
	task_id TEXT REFERENCES tasks (id),
	actor TEXT NOT NULL,
	emoji TEXT NOT NULL,
	created_at INTEGER NOT NULL
) STRICT;

CREATE TABLE read_cursors (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	agent_id TEXT NOT NULL REFERENCES agents (id),
	project_id TEXT NOT NULL DEFAULT '',
	last_seen_message_seq INTEGER NOT NULL DEFAULT 0,
	UNIQUE (agent_id, project_id)
) STRICT;

CREATE TABLE schema_migrations (
			version INTEGER PRIMARY KEY,
			name TEXT NOT NULL,
			checksum TEXT NOT NULL,
			applied_at INTEGER NOT NULL
		) STRICT;

CREATE TABLE sessions (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	agent_id TEXT NOT NULL REFERENCES agents (id),
	started_at INTEGER NOT NULL,
	last_heartbeat_at INTEGER NOT NULL,
	ended_at INTEGER,
	meta TEXT
) STRICT;

CREATE TABLE sqlite_sequence(name,seq);

CREATE TABLE tags (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	project_id TEXT NOT NULL REFERENCES projects (id),
	-- The word as it is written on the card.
	name TEXT NOT NULL,
	-- The same word, normalised: what "already defined" is judged on, so "Bug",
	-- "bug" and " bug " are one tag rather than three that look identical.
	slug TEXT NOT NULL,
	-- One of the palette's names ('rose', 'amber', …), or NULL for the default.
	-- A name rather than a colour value: the dashboard has two themes, and a
	-- stored hex would be legible in exactly one of them.
	colour TEXT,
	-- Who defined it: an agent, or NULL for the owner. The same rule media keeps.
	agent_id TEXT REFERENCES agents (id),
	created_at INTEGER NOT NULL
) STRICT;

CREATE TABLE tasks (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	project_id TEXT NOT NULL REFERENCES projects (id),
	agent_id TEXT REFERENCES agents (id),
	title TEXT NOT NULL,
	body TEXT NOT NULL DEFAULT '',
	state TEXT NOT NULL DEFAULT 'todo' CHECK (state IN ('todo', 'claimed', 'done', 'cancelled')),
	created_at INTEGER NOT NULL,
	claimed_at INTEGER,
	done_at INTEGER,
	result TEXT
, broadcast_at INTEGER, pinned INTEGER NOT NULL DEFAULT 0, edited_at INTEGER) STRICT;

CREATE TABLE update_shares (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL,
	update_id TEXT NOT NULL,
	token_hash TEXT NOT NULL,
	created_at INTEGER NOT NULL,
	revoked_at INTEGER,
	views INTEGER NOT NULL DEFAULT 0,
	last_viewed_at INTEGER
) STRICT;

CREATE TABLE updates (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	project_id TEXT NOT NULL REFERENCES projects (id),
	agent_id TEXT NOT NULL REFERENCES agents (id),
	session_id TEXT REFERENCES sessions (id),
	title TEXT,
	body TEXT NOT NULL,
	level TEXT NOT NULL DEFAULT 'info' CHECK (level IN ('info', 'success', 'warn', 'error')),
	pinned INTEGER NOT NULL DEFAULT 0 CHECK (pinned IN (0, 1)),
	created_at INTEGER NOT NULL,
	deleted_at INTEGER
, edited_at INTEGER, priority TEXT NOT NULL DEFAULT 'medium', task_id TEXT REFERENCES tasks (id), replies_seen_at INTEGER, status TEXT
	CHECK (status IN ('todo', 'in_progress', 'blocked', 'done', 'cancelled')), status_at INTEGER) STRICT;

CREATE TABLE upload_tokens (
	seq INTEGER PRIMARY KEY AUTOINCREMENT,
	id TEXT NOT NULL UNIQUE,
	agent_id TEXT NOT NULL REFERENCES agents (id),
	media_id TEXT NOT NULL REFERENCES media (id) ON DELETE CASCADE,
	max_bytes INTEGER NOT NULL,
	mime_allow TEXT NOT NULL,
	expires_at INTEGER NOT NULL,
	used_at INTEGER
) STRICT;

CREATE UNIQUE INDEX acknowledgements_agent_message ON acknowledgements (agent_id, message_id)
	WHERE message_id IS NOT NULL;

CREATE UNIQUE INDEX acknowledgements_agent_request ON acknowledgements (agent_id, request_id)
	WHERE request_id IS NOT NULL;

CREATE UNIQUE INDEX acknowledgements_agent_task ON acknowledgements (agent_id, task_id)
	WHERE task_id IS NOT NULL;

CREATE UNIQUE INDEX acknowledgements_agent_update ON acknowledgements (agent_id, update_id)
	WHERE update_id IS NOT NULL;

CREATE UNIQUE INDEX acknowledgements_id ON acknowledgements (id);

CREATE INDEX acknowledgements_message ON acknowledgements (message_id);

CREATE INDEX acknowledgements_request ON acknowledgements (request_id);

CREATE INDEX acknowledgements_task ON acknowledgements (task_id);

CREATE INDEX acknowledgements_update ON acknowledgements (update_id);

CREATE INDEX app_views_message ON app_views (message_id);

CREATE INDEX app_views_request ON app_views (request_id);

CREATE INDEX app_views_update ON app_views (update_id);

CREATE INDEX approvals_agent_seq ON approvals (agent_id, seq DESC);

CREATE INDEX approvals_agent_state ON approvals (agent_id, state);

CREATE INDEX approvals_message ON approvals (message_id);

CREATE INDEX approvals_state_expires ON approvals (state, expires_at);

CREATE INDEX card_fields_message ON card_fields (message_id);

CREATE UNIQUE INDEX card_fields_message_slug ON card_fields (message_id, slug)
	WHERE message_id IS NOT NULL;

CREATE INDEX card_fields_request ON card_fields (request_id);

CREATE UNIQUE INDEX card_fields_request_slug ON card_fields (request_id, slug)
	WHERE request_id IS NOT NULL;

CREATE INDEX card_fields_update ON card_fields (update_id);

CREATE UNIQUE INDEX card_fields_update_slug ON card_fields (update_id, slug)
	WHERE update_id IS NOT NULL;

CREATE UNIQUE INDEX card_tags_message ON card_tags (tag_id, message_id)
	WHERE message_id IS NOT NULL;

CREATE INDEX card_tags_message_card ON card_tags (message_id);

CREATE UNIQUE INDEX card_tags_request ON card_tags (tag_id, request_id)
	WHERE request_id IS NOT NULL;

CREATE INDEX card_tags_request_card ON card_tags (request_id);

CREATE INDEX card_tags_tag ON card_tags (tag_id);

CREATE UNIQUE INDEX card_tags_update ON card_tags (tag_id, update_id)
	WHERE update_id IS NOT NULL;

CREATE INDEX card_tags_update_card ON card_tags (update_id);

CREATE INDEX derivatives_media_kind ON derivatives (media_id, kind);

CREATE UNIQUE INDEX derivatives_media_kind_width
	ON derivatives (media_id, kind, coalesce(width, -1));

CREATE INDEX embeds_message ON embeds (message_id);

CREATE INDEX embeds_request ON embeds (request_id);

CREATE INDEX embeds_task ON embeds (task_id);

CREATE INDEX embeds_update ON embeds (update_id);

CREATE INDEX media_message ON media (message_id);

CREATE INDEX media_sha256 ON media (sha256);

CREATE INDEX media_status_created ON media (status, created_at);

CREATE INDEX media_update ON media (update_id);

CREATE INDEX message_deliveries_agent_client
	ON message_deliveries (agent_id, client_id, message_id);

CREATE INDEX message_deliveries_message ON message_deliveries (message_id);

CREATE UNIQUE INDEX message_deliveries_message_agent_client
	ON message_deliveries (message_id, agent_id, client_id);

CREATE INDEX message_mentions_agent ON message_mentions (agent_id, message_id);

CREATE UNIQUE INDEX message_mentions_message_agent ON message_mentions (message_id, agent_id);

CREATE INDEX messages_answers ON messages (answers);

CREATE INDEX messages_live_seq ON messages (seq) WHERE deleted_at IS NULL;

CREATE INDEX messages_project_seq ON messages (project_id, seq);

CREATE INDEX messages_reply_to ON messages (reply_to);

CREATE INDEX messages_request ON messages (request_id);

CREATE INDEX messages_status ON messages (status) WHERE status IS NOT NULL;

CREATE INDEX messages_task ON messages (task_id);

CREATE INDEX messages_update ON messages (update_id);

CREATE UNIQUE INDEX notifications_message ON notifications (message_id) WHERE message_id IS NOT NULL;

CREATE UNIQUE INDEX notifications_request ON notifications (request_id) WHERE request_id IS NOT NULL;

CREATE INDEX notifications_seq ON notifications (seq DESC);

CREATE INDEX notifications_unseen ON notifications (seq DESC) WHERE seen_at IS NULL;

CREATE UNIQUE INDEX notifications_update ON notifications (update_id) WHERE update_id IS NOT NULL;

CREATE UNIQUE INDEX owner_devices_id ON owner_devices (id);

CREATE UNIQUE INDEX owner_devices_token_hash ON owner_devices (token_hash);

CREATE INDEX projects_status_pinned ON projects (status, pinned DESC, seq DESC);

CREATE UNIQUE INDEX push_subscriptions_endpoint ON push_subscriptions (endpoint);

CREATE UNIQUE INDEX push_subscriptions_id ON push_subscriptions (id);

CREATE INDEX reactions_message ON reactions (message_id);

CREATE UNIQUE INDEX reactions_message_actor_emoji ON reactions (message_id, actor, emoji)
	WHERE message_id IS NOT NULL;

CREATE INDEX reactions_request ON reactions (request_id);

CREATE UNIQUE INDEX reactions_request_actor_emoji ON reactions (request_id, actor, emoji)
	WHERE request_id IS NOT NULL;

CREATE INDEX reactions_task ON reactions (task_id);

CREATE UNIQUE INDEX reactions_task_actor_emoji ON reactions (task_id, actor, emoji)
	WHERE task_id IS NOT NULL;

CREATE INDEX reactions_update ON reactions (update_id);

CREATE UNIQUE INDEX reactions_update_actor_emoji ON reactions (update_id, actor, emoji)
	WHERE update_id IS NOT NULL;

CREATE INDEX read_cursors_agent ON read_cursors (agent_id, project_id);

CREATE INDEX sessions_agent_heartbeat ON sessions (agent_id, last_heartbeat_at DESC);

CREATE INDEX sessions_open_heartbeat ON sessions (last_heartbeat_at) WHERE ended_at IS NULL;

CREATE UNIQUE INDEX tags_project_slug ON tags (project_id, slug);

CREATE INDEX tasks_agent_state ON tasks (agent_id, state) WHERE agent_id IS NOT NULL;

CREATE INDEX tasks_project_state ON tasks (project_id, state, seq DESC);

CREATE UNIQUE INDEX update_shares_id ON update_shares (id);

CREATE UNIQUE INDEX update_shares_live ON update_shares (update_id)
	WHERE revoked_at IS NULL;

CREATE UNIQUE INDEX update_shares_token_hash ON update_shares (token_hash);

CREATE INDEX updates_agent_seq ON updates (agent_id, seq DESC);

CREATE INDEX updates_live_seq ON updates (seq DESC) WHERE deleted_at IS NULL;

CREATE INDEX updates_project_seq ON updates (project_id, seq DESC);

CREATE INDEX updates_status ON updates (status) WHERE status IS NOT NULL;

CREATE INDEX updates_task_seq ON updates (task_id, seq DESC) WHERE task_id IS NOT NULL;

CREATE INDEX upload_tokens_expires ON upload_tokens (expires_at) WHERE used_at IS NULL;

CREATE INDEX upload_tokens_media ON upload_tokens (media_id);
```

## 6. Tables: meaning, writers, readers

Every table also has `seq` (row key, cursor) and `id` (ULID) — not repeated below. "R:" = readers, "W:" =
writers, all in `src/db/`. Repositories return camelCased rows (`src/db/types.ts`).

**projects** — a workspace on the wall. `slug` unique handle; `name`; `description?`; `status`
`active|archived` (never deleted); `pinned` 0/1; `created_at`, `updated_at` (set on every patch);
`theme` JSON; `board` JSON (still accepted by `src/domain/projects.ts:268`); `owner_seen_at` (dead, §9).
W: `insertProject` `projects.ts:60`, `updateProject` `projects.ts:134`. R: `findProjectById/BySlug`
`projects.ts:87,92`, `listProjects` (order `pinned DESC, seq DESC`) `projects.ts:103`; `theme.logoMediaId`
also read by `listOrphanedMedia` `media.ts:403`.

**agents** — a bearer-token identity. `name`; `token_hash` = hex HMAC-SHA256 of the token
(`src/domain/agents.ts:71-73`), unique; `created_at`; `revoked_at` (soft revoke); `last_seen_at` (only moves
forward: `max(coalesce(last_seen_at,0), ?)`). W: `insertAgent` `agents.ts:44`, `renameAgent` `:108`,
`revokeAgent` `:115` (only if not revoked), `touchAgent` `:129`. R: `findAgentById` `:63`,
`findAgentByTokenHash` `:74`, `listAgents` `:81` (order `seq`).

**sessions** — one agent run. `agent_id`; `started_at`; `last_heartbeat_at`; `ended_at`; `meta` JSON.
W: `insertSession` `sessions.ts:47`, `heartbeatSession` `:79` (only while `ended_at IS NULL`), `endSession`
`:91`, `endStaleSessions` `:132` (sets `ended_at` where heartbeat older than cutoff, `RETURNING id`).
R: `findSessionById` `:68`, `listLiveSessions` `:100`, `listSessionsForAgent` `:111`.

**updates** — an agent's card in the feed. `project_id`, `agent_id`, `session_id?`; `title?`; `body`
(markdown); `level` `info|success|warn|error`; `priority` `low|medium|high` (no CHECK, `types.ts:107`);
`pinned`; `created_at`; `deleted_at` (soft delete); `edited_at`; `status` (work state, 5 values) and
`status_at`; `task_id` and `replies_seen_at` dead. W: `insertUpdate` `updates.ts:75`, `editUpdate` `:186`
(only live rows, always sets `edited_at`), `softDeleteUpdate` `:158`, `setUpdatePinned` `:218`,
`setCardPinned`/`setCardStatus` `cards.ts:173,190`. R: `findUpdateById` `:105`, `listUpdates` `:125`
(order `seq DESC`, live only unless asked), `countOpenWork`/`countWorkByStatus`/`listWork`
`cards.ts:214,240,325`.

**media** — an uploaded file. `agent_id?` (NULL = owner); `author` (`human`/`agent:<id>`, defaulted from
`agent_id` in `insertMedia` `media.ts:94`); anchors `update_id`, `message_id`, `request_id`, `task_id`
(legacy) — at most one set; `kind` `image|video|audio|file`; `mime`; `bytes`; `sha256` (hex, **`''` until
the bytes arrive**, `src/media/upload.ts:236`); `width`, `height`, `duration_ms`; `filename` (files only);
`status` `pending|ready|failed`; `created_at`. W: `insertMedia` `:89`, `setMediaBytes` `:154`,
`setMediaStatus` `:183` (dims via `coalesce(new, old)`), `attachMedia` `:254` (sets one anchor only where
**all** anchors are NULL and owner column matches), `deleteMedia` `:431` (hard delete; derivatives and
upload_tokens cascade). R: `findMediaById` `:124`, `findMediaBySha256` `:130` (newest), `listMediaFor*`
`:283` (order `seq`), `listMediaByStatus` `:352`, `listOrphanedMedia` `:384` (unattached, old, not a
project logo, not any string inside any `embeds.data` via `json_tree`).

**derivatives** — thumbnails/posters/transcodes of a media row. `media_id` (CASCADE); `kind`
`thumb|poster|mp4`; `path` (relative to media root); `bytes`; `width`, `height`. Unique on
`(media_id, kind, coalesce(width,-1))`. W: `insertDerivative` `derivatives.ts:51`, `upsertDerivative` `:79`
(conflict target is that expression), `deleteDerivatives` `:137`. R: `listDerivatives` `:106`,
`findDerivative` `:116`.

**upload_tokens** — single-use permission to upload bytes into one media row. `agent_id`; `media_id`
(CASCADE); `max_bytes`; `mime_allow` JSON; `expires_at`; `used_at`. W: `insertUploadToken`
`upload-tokens.ts:49`, `consumeUploadToken` `:84` (`used_at IS NULL AND expires_at > now`, `RETURNING`),
`deleteExpiredUploadTokens` `:106`. R: `findUploadTokenById` `:70`.

**tasks** — legacy board tasks, frozen since 035 (each copied into `messages` with the same `id`). No writer
remains (`src/db/tasks.ts` deleted). R: `listAgentProjectIds` `messages.ts:436-450` only.

**messages** — owner posts, replies and agent messages. `project_id?`; thread anchors `update_id`,
`request_id`, `reply_to` (a post; one level), `task_id` (always NULL since 035); `answers` (display-only
"in reply to" inside a thread); `author`; `body`; `created_at`; `deleted_at` (soft); `edited_at`; `pinned`;
`status`, `status_at`. W: `insertMessage` `messages.ts:85`, `editMessage` `:122` (live only),
`softDeleteMessage` `:157`, `setCardPinned`/`setCardStatus`. R: `findMessageById` `:146` (includes
deleted), `listRepliesTo` `:172`, `listMessages` `:227`, `countMessagesAfter` `:267`,
`listUnreadMessages`/`countUnreadMessages` `:340,365` (join `read_cursors` on
`coalesce(m.project_id,'')`), mention audience filter `readerScope` `:398` (a message that names anyone is
visible only to the named agents).

**read_cursors** — how far an agent has read, per project. `agent_id`; `project_id` (`''` = messages with
no project, `read-cursors.ts:34`); `last_seen_message_seq` (a `messages.seq`). Unique `(agent_id,
project_id)`. W: `advanceReadCursor` `read-cursors.ts:80` (upsert, never moves back: `max(old, new)`).
R: `getReadCursor` `:46`, `readCursorSeq` `:60` (0 if none), `listReadCursors` `:65`.

**approvals** — owner requests ("questions"); the card kind `request`. `agent_id`; `project_id?`;
`update_id?`, `message_id?` (where it renders); `kind` `text|confirm|buttons|choice|multi_choice|form`
(no CHECK, `types.ts:421`); `question`; `detail?`; `options` JSON; `config` JSON; `state`
`pending|approved|rejected|timeout|cancelled|withdrawn`; `expires_at`; `decided_at`; `decided_value`
(scalar); `answer` JSON (authoritative); `edited_at`; `pinned`. W: `insertApproval` `approvals.ts:93`,
`editApproval` `:241` and `decideApproval` `:283` (both only `WHERE state = 'pending'`), `expireApprovals`
`:314` (`timeout` where `expires_at <= now`), `setCardPinned`. R: `findApprovalById` `:124`,
`listApprovals` `:147`, `countPendingApprovals` `:178`.

**push_subscriptions** — Web Push endpoints. `endpoint` unique; `p256dh`, `auth` (base64url as the browser
gave); `label`; `created_at`; `last_sent_at`; `failures`; `prefs` JSON. W: `upsertPushSubscription`
`push.ts:108` (on `endpoint`: keys/label replaced, `failures = 0`, `prefs` kept when the new one is NULL),
`markPushSent` `:164`, `markPushFailed` `:176`, `setPushPrefs` `:194`, `deletePushSubscription` `:156`
(hard). R: `listPushSubscriptions` `:141` (order `created_at`), `findPushSubscription` `:148`,
`countPushSubscriptions` `:186`.

**update_shares** — public read-only links to one update. `update_id` (no FK); `token_hash` unique
(`hashShareToken`, `src/domain/shares.ts:78`); `created_at`; `revoked_at`; `views`; `last_viewed_at`. At most
one row with `revoked_at IS NULL` per update. W: `insertUpdateShare` `shares.ts:67`,
`revokeSharesForUpdate` `:124`, `recordShareView` `:136`. R: `findShareByTokenHash` `:93`, `findShareById`
`:100`, `findLiveShareForUpdate` `:108`, `listSharesForUpdate` `:143`.

**acknowledgements** — an agent's "seen / on it / done" on a card. `agent_id`; anchors `message_id`,
`update_id`, `request_id` (`task_id` legacy); `state` `thinking|read|done` (no CHECK, `types.ts:229`);
`created_at`; `updated_at`. One row per (agent, card). W: `upsertAcknowledgement`
`acknowledgements.ts:74` (`ON CONFLICT (agent_id, <anchor>) WHERE <anchor> IS NOT NULL DO UPDATE` state +
`updated_at`). R: `listAcknowledgements` `:116`.

**message_deliveries** — a message pushed onto an agent's live stream. `message_id`; `agent_id`;
`delivered_at`; `client_id?` (bridge connection id). W: `recordDelivery` `deliveries.ts:52`. R:
`deliveredMessageIds` `:112` (per client; empty for NULL client), `listDeliveries` `:139` (first row per
message+agent).

**notifications** — the in-app bell. `kind` `update|reply|request`; `project_id?`; exactly one target of
`update_id`, `message_id`, `request_id` (each partial-unique); `agent_id?` (who caused it); `title`; `body`;
`created_at`; `seen_at`. W: `insertNotification` `notifications.ts:69` (`ON CONFLICT DO NOTHING`, then
returns the existing row), `markNotificationsSeen` `:153`. R: `listNotifications` `:125` (order `seq DESC`),
`countUnseenNotifications` `:139`.

**reactions** — emoji on a card. Anchors `message_id`, `update_id`, `request_id` (`task_id` legacy); `actor`
(`human`/`agent:<id>`); `emoji` (free text); `created_at`. One per (card, actor, emoji). W: `addReaction`
`reactions.ts:61` (insert-or-return-existing), `removeReaction` `:94` (hard delete). R: `listReactions`
`:114`.

**embed_types** — a registered look. `slug` unique; `name`; `agent_id?` (NULL = built-in); `spec` JSON;
`created_at`; `updated_at`. W: `upsertEmbedType` `embeds.ts:125` (on `slug`: name, spec, `updated_at`;
`agent_id` and `id` kept). R: `findEmbedTypeBySlug/ById` `:78,85`, `listEmbedTypes` `:93` (built-ins first,
then `name COLLATE NOCASE`, `slug`).

**embeds** — one thing shown in a look. `type_id`; anchors `update_id`, `message_id`, `request_id`,
`task_id`; `agent_id?`; `author`; `data` JSON; `source_url?`; `created_at`. Insert-only. W: `insertEmbed`
`embeds.ts:167`. R: `listEmbedsFor*` `:207-232` (order `seq`).

**tags** — a project's own vocabulary. `project_id`; `name` (as shown); `slug` (normalised, unique per
project); `colour?` (palette name, no CHECK); `agent_id?` (NULL = owner); `created_at`. W: `insertTag`
`tags.ts:62` (conflict → returns the existing tag), `updateTag` `:111` (read-modify-write), `deleteTag` `:140`
(deletes its `card_tags` first, in a transaction). R: `findTagById/BySlug` `:85,90`, `listTags` `:100`.

**card_tags** — a tag on a card. `tag_id`; anchors `update_id`, `message_id`, `request_id`; `author`;
`created_at`. W: `addCardTag` `tags.ts:186` (no-op on duplicate → `undefined`), `removeCardTag` `:204`.
R: `findCardTag` `:214`, `listCardTags` `:230`.

**card_fields** — named text notes on a card. Anchors `update_id`, `message_id`, `request_id`; `name`;
`slug` (unique per card); `value`; `author` (last setter); `created_at`; `updated_at`. W:
`upsertCardField` `fields.ts:70` (conflict on `(<anchor>, slug)` replaces name, value, author,
`updated_at`), `deleteCardField` `:89`. R: `findCardField` `:99`, `listCardFields` `:115`.

**apps** — interactive UI an agent registers. `uri` unique (`ui://…`); `name`; `description?`; `agent_id?`;
`html` (document, NOT NULL — `''` for a URL app, `src/domain/apps.ts:224`); `url?` (framed page); `csp` JSON;
`created_at`; `updated_at`. W: `upsertApp` `apps.ts:135` (on `uri`; `agent_id` and `id` kept). R:
`findAppByUri/ById` `:92,99`, `listApps` `:105`.

**app_views** — one showing of an app on a card. `app_id`; anchors `update_id`, `message_id`, `request_id`;
`agent_id?`; `author`; `data` JSON; `state` JSON?; `title?`; `created_at`; `updated_at`. W: `insertAppView`
`apps.ts:191`, `updateAppViewData` `:234`, `updateAppViewState` `:254`. R: `findAppView` `:220`,
`listAppViews` `:274`.

**owner_devices** — the owner's phones (iOS app). `name`; `token_hash` unique (`hashAgentToken`,
`src/domain/devices.ts:66`); `apns_token?`; `apns_environment` `sandbox|production`; `live_activity_token?`;
`push_to_start_token?`; `created_at`; `last_seen_at`; `failures`; `alert_project_ids` JSON. W:
`insertOwnerDevice` `owner-devices.ts:74`, `deleteOwnerDevice` `:116` (hard), `touchOwnerDevice` `:125`
(only if `last_seen_at` NULL or ≤ `notBefore`), `setOwnerDeviceApns` `:154` (also `failures = 0`),
`setOwnerDeviceLiveActivity` `:191`, `setOwnerDeviceAlertProjects` `:173`, `clearOwnerDeviceToken` `:234`,
`markOwnerDevicePushed/PushFailed` `:247,252`. Setting a token NULLs the same token on every other device
(`releaseTokenElsewhere` `:141`). R: `findOwnerDeviceById/ByTokenHash` `:93,100`, `listOwnerDevices` `:108`.

**message_mentions** — agents a message names with `@name`. `message_id`; `agent_id`; unique pair. W:
`setMessageMentions` `mentions.ts:13` (delete all for the message, re-insert, one transaction). R:
`listMessageMentions` `:27`, and the `EXISTS` subqueries in `messages.ts:413-415`.

**schema_migrations** — §3.

## 7. Boot-time schema checks, `schema.ts`, `types.ts`

- No schema introspection at boot. The only checks are the migration runner's (unknown version, edited
  checksum, duplicate version, `foreign_key_check` after a rebuild) — `src/db/migrate.ts:56-81,133-139`.
- `TABLES` lists the 26 data tables (incl. legacy `tasks`) and `MIGRATIONS_TABLE = 'schema_migrations'`; used by
  tests only (`src/db/schema.ts:8-45`, `src/db/schema.test.ts:311`).
- `types.ts` row shapes = columns camelCased, booleans decoded, JSON decoded **only** for `projects.theme`,
  `projects.board`, `sessions.meta`, `approvals.options/config/answer`, `push_subscriptions.prefs`,
  `upload_tokens.mime_allow`, `owner_devices.alert_project_ids`. `embed_types.spec`, `embeds.data`,
  `apps.csp`, `app_views.data/state` stay strings (`src/db/types.ts:521-536,555-556,639-662`).
- Card reference derivation when reading anchor rows: acknowledgements and reactions check
  `message_id` → `update_id` → `request_id` (`acknowledgements.ts:35-39`, `reactions.ts:34-38`); card_tags,
  card_fields and app_views check `update_id` → `message_id` → `request_id` (`tags.ts:161-165`,
  `fields.ts:34-38`, `apps.ts:71-75`).
- Card kinds are `update`, `message`, `request` (task removed); work statuses
  `todo|in_progress|blocked|done|cancelled`, open = first three (`src/db/cards.ts:30,39,49`).

## 8. Integrity rules kept in code, not SQL

- **Exactly one anchor** per media / embed / ack / reaction / card_tag / card_field / app_view / notification
  row: enforced by the domain, not by CHECK (`src/db/types.ts:167-171,542-547`, `027-embeds.ts:73`).
  `attachMedia` only claims rows whose four anchors are all NULL (`media.ts:240,274`).
- **Soft deletes**: `updates.deleted_at`, `messages.deleted_at` (reads filter `deleted_at IS NULL`;
  `messages.ts:45`, `updates.ts:144`); `agents.revoked_at`; `sessions.ended_at`; `update_shares.revoked_at`;
  projects are archived, never deleted. Nothing cascades from a soft delete (replies, reactions, media stay).
- **Hard deletes**: `media` (FK cascade to derivatives, upload_tokens), `derivatives`, expired unused
  `upload_tokens`, `reactions`, `card_tags`, `tags` (with manual cascade to `card_tags`, `tags.ts:140-145`),
  `card_fields`, `push_subscriptions`, `owner_devices`, `message_mentions`.
- **Monotonic values**: `agents.last_seen_at` (`agents.ts:132`), `read_cursors.last_seen_message_seq`
  (`read-cursors.ts:98`).
- **Race-sensitive transitions** are one conditional `UPDATE … RETURNING`; the loser gets nothing:
  `decideApproval`, `editApproval`, `expireApprovals`, `consumeUploadToken`, `heartbeatSession`,
  `endSession`, `revokeAgent`, `softDelete*` (`src/db/README.md` "Conventions").
- **Read-then-write without a transaction** (atomic only because one synchronous connection):
  `recordDelivery` NULL-client pre-check (`deliveries.ts:68-76`), `insertTag` fallback find
  (`tags.ts:82`), `updateTag` (`tags.ts:116-128`), `addReaction` fallback select (`reactions.ts:84-89`),
  `insertNotification` fallback select (`notifications.ts:105-112`), `setCardPinned/Status` then
  `findCard` (`cards.ts:173-205`).
- **Orphan sweeping** protects media named by `projects.theme.logoMediaId` or by any string in any
  `embeds.data` (`media.ts:402-419`).
- **Ordering keys**: lists order by `seq` (feeds `seq DESC`, threads `seq ASC`); work list orders by
  `COALESCE(status_at, created_at) DESC, id DESC` (`cards.ts:342,350`).

## 9. Surprises

1. **Two indexes silently lost.** 030 created `media_request` and `media_task`; the 038 rebuild drops the
   table and recreates only `media_sha256`, `media_status_created`, `media_update`, `media_message`
   (`038-sound-on-a-card.ts:68-71`). A fully migrated DB has **no** index on `media.request_id` or
   `media.task_id`. Do not "fix" it outside a migration; add it as 046 in both servers.
2. **Rebuild FK check runs after COMMIT** (`migrate.ts:131-139`): a dangling reference throws at boot, but
   the migration is already recorded, so the next boot will not re-check.
3. **Checksums cover the whole SQL string** — leading newline, tabs, comments, emoji (027). Re-indenting or
   re-commenting a migration in Kotlin makes the TS server refuse the file.
4. **Dead columns and table**: `projects.owner_seen_at`, `updates.replies_seen_at` (nothing in `src/`
   reads or writes them; only `schema.test.ts:42,67`); `updates.task_id` (`types.ts:126-128`);
   `messages.task_id` (NULL since 035); `task_id` on media/embeds/reactions/acknowledgements; table `tasks`.
5. **Converted tasks share ids with `tasks` rows** and got fresh `messages.seq` values at migration time, so
   for them `seq` order ≠ `created_at` order (035, then 036 to mark them read).
6. **Not every enum has a CHECK**: `approvals.kind`, `updates.priority`, `acknowledgements.state`,
   `tags.colour`, `media`/`embeds`/`app_views` author strings. `pinned` on messages/approvals/tasks has no
   `CHECK (pinned IN (0,1))`; on projects/updates it does.
7. **Not all ids are ULIDs**: 025 seeded `read_cursors.id` as 26 lower-case hex; `isId()` rejects them.
8. **Built-in embed types have `created_at = updated_at = 0`** until an upsert touches them.
9. **NULL is distinct in unique indexes**: `message_deliveries` rows with NULL `client_id` never conflict,
   hence the manual pre-check; `derivatives` uses `coalesce(width,-1)` for the same reason.
10. **`notifications` upsert has no conflict target** (`ON CONFLICT DO NOTHING`), so a clash on *any* unique
    index (incl. `id`) is swallowed and then looked up by target.
11. **`update_shares.update_id` has no FK** by design (`005-update-shares.ts:20-23`).
12. **`media.sha256 = ''`** is a real stored value meaning "no bytes yet"; `findMediaBySha256('')` would
    match placeholders.
13. **Table renames rewrite FK text** (SQLite ≥ 3.26, `legacy_alter_table` off). Migrations only ever rename
    the new `*_rebuilt` table, or a table nothing references (025's `read_cursors`). Follow that pattern.
14. **`approvals.state = 'approved'`** is used for every answered non-`confirm` kind; "answered" is a domain
    view (`002-owner-requests.ts:12-17`).
15. `apps.html` is `NOT NULL` even for URL apps; `apps.url` "exactly one" is a domain rule
    (`042-an-app-that-lives-somewhere-else.ts:33-35`).
16. Upsert on `embed_types.slug` / `apps.uri` never changes `agent_id`: ownership checks live in the domain.

## 10. Compatibility notes (Kotlin opening the same file)

- **Driver**: xerial `sqlite-jdbc` ≥ 3.45 (needs `STRICT`, `RETURNING`, JSON1 `json_tree`). Open
  `$DATA_DIR/agent-dashboard.db`; create `DATA_DIR` if missing.
- **Per connection, every connection**: `foreign_keys=ON`, `busy_timeout=5000`, `synchronous=NORMAL`,
  `journal_mode=WAL` (WAL is persistent in the file, set it anyway). With a pool, set these in the
  connection config, not once.
- **One writer.** Use a single connection (or one write connection behind a mutex) so the read-then-write
  sequences in §8 stay atomic. Do not run both servers against the file at the same time: stop one, start
  the other.
- **Migrations**: implement §3 exactly — same table DDL, same `(version, name, sha256-hex-of-UTF-8-SQL,
  ms)` row, same refusal on unknown version or changed checksum, one transaction per migration, FKs off
  around 16/25/26/31/34/38. Ship the 45 SQL texts **byte-identical** (extract the template-literal bodies
  from `src/db/migrations/*.ts` mechanically) and unit-test them against the checksum table in §4.
  The live DB is already at 045, so Kotlin applies nothing on first boot.
- **New schema only via a new numbered migration present in both servers** (§3). Never touch
  `user_version`, `sqlite_sequence`, or DDL outside a migration.
- **Types (STRICT)**: bind timestamps/flags/counts as `Long`/`Int` (a string into an INTEGER column is an
  error); booleans as `0`/`1`; never insert an explicit `seq`.
- **Ids**: upper-case Crockford ULIDs, monotonic within a millisecond (`ORDER BY … id DESC` relies on it).
- **Times**: `System.currentTimeMillis()`.
- **JSON**: write compact `JSON.stringify`-style text, omit absent optional keys (JS drops `undefined`);
  `NULL` column for a null value. Keep `embed_types.spec`, `embeds.data`, `apps.csp`, `app_views.*` as
  opaque strings.
- **Partial-index upserts** must repeat the index predicate in the conflict target, e.g.
  `ON CONFLICT (agent_id, message_id) WHERE message_id IS NOT NULL` (`acknowledgements.ts:88`,
  `reactions.ts:78`, `tags.ts:195`, `fields.ts:78`); the derivatives upsert targets the expression
  `(media_id, kind, coalesce(width, -1))` (`derivatives.ts:94`).
- **Token hashes** stored in `agents.token_hash`, `owner_devices.token_hash` (hex HMAC-SHA256 with the
  configured secret, `src/domain/agents.ts:71-73`) and `update_shares.token_hash`
  (`src/domain/shares.ts:78`) must be computed identically, or every existing agent, phone and share link
  stops authenticating.
