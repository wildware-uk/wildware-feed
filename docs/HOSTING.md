# Hosting many teams on one server: design

Status: slices 1, 3 and 4 are built, on **one** server process (the owner chose one small dyno
for now, so slice 2's pool, `LISTEN/NOTIFY` and job claims wait). What differs from the text below:

- One process with one database connection already runs writes one at a time, so there is no
  `team.write` lock. A request, a job or a bus listener says its team once (`db/Tenant.kt`); SQL
  names it as `team_id = :team`.
- Row security tells the connection its team with a session setting, changed only when the team
  changes. With one connection behind one lock that is as safe as `SET LOCAL`, and saves a round
  trip per statement.
- The bus keeps a seq and a replay ring per team, in memory, as before.
- Migrations 058 (every row gets a `team_id`; row-security policies, off until switched on) and
  059 (team limits, suspension, deletion; `auth_codes`; `admin_requests`).
- Settings: `TEAM_DOMAIN` (many teams; needs Postgres; turns row security on), `ADMIN_API_KEYS`,
  `CONTROL_PLANE_ORIGIN`, and `ROW_SECURITY` for a single team. Unset, a server is as before.

[TEAMS.md](TEAMS.md) adds people to a server: several logins sharing one feed. This document goes one
step further: **many teams on one shared, stateless server**, so the hosted service at
`feed.wildware.dev` can run every customer on a few machines and one large database, instead of one
Heroku app per team.

Code paths below are under `server/src/main/kotlin/dev/wildware/feed/server/` unless they start
with `server/` or `docs/`.

The short version:

- **No fork.** Teams go into the open AGPL server. A self-hoster runs one team and sees no change.
  Billing, signup and plans stay in the separate private service, as already decided.
- **A `team_id` on every team-owned row**, scoped in code on every query, checked by a lint and a
  two-team leak test, with Postgres row-level security as a backstop.
- **Stateless web servers.** Events, timers, limits and job schedules move out of memory and into
  Postgres, so any number of web servers can serve any team.
- **Each team has its own address**, `<team>.feed.wildware.dev`. To the app, a hosted team looks
  exactly like a self-hosted server. A key also names its team, and the two must agree.
- **One login, several teams.** An account (email and password) is separate from a membership (this
  account is an admin in that team). Device keys belong to one membership.
- **People hear what they follow.** Alerts use the follows already on master (groups, channels,
  threads) plus mentions, per person.
- **Roles: owner, admin, member.** No viewer for now.
- **First slice:** invite a second person to today's single team. Useful to self-hosters at once.

## 1. Fork or not

The owner asked whether the paid version could be a fork. Two ways to do it:

**(a) Tenancy in the open server.** The open server learns about teams. A self-hosted server has one
team, made for it by a migration, and behaves as today. The hosted service runs the same server with
multi-team mode switched on, plus the private control plane for signup and billing.

**(b) A private fork for the hosted version.** The open server stays single-team. A private copy
adds teams and is what runs at `feed.wildware.dev`.

How they compare:

| | (a) In the open server | (b) Private fork |
|---|---|---|
| Merge cost | Paid once. | Paid forever. Tenancy touches nearly every query: about 326 SQL call sites in 34 files today (`grep db.query/one/update`). Every feature added upstream would conflict with the fork, on exactly those lines. |
| AGPL | Nothing to think about: the source is public already. | Section 13 says: if you run a modified AGPL program for users over a network, you must offer **those users** its source. Every hosted customer could ask for the fork and publish it. So the fork is not private to anyone who matters. |
| The one way out of (b)'s AGPL problem | – | A copyright holder is not bound by their own licence. While Wildware owns **every** line of the server, it may run a closed fork. One outside contribution taken without a contributor agreement ends that. |
| Self-hosters | Get teams (several logins) for free. | Get nothing. |
| What competitors can do | Run their own multi-team host, but must publish their changes. | The same, from the public single-team server, with more work. |
| What stays private | Billing, signup, plans, prices, the marketing site: the control plane. | The same, plus tenancy. Tenancy is not the hard part to copy; the service is. |

**Recommendation: (a).** Put tenancy in the open server and keep the control plane as the only
private part. A fork buys a secret that the AGPL would not keep, and costs a merge on every feature
forever. Separately, decide whether outside contributions need a contributor agreement (open
question 3): that keeps the option of other licences later, whichever way this goes.

What keeps billing separate under (a): the open server has no idea what a plan, price or invoice is.
It has a few generic limits per team (seats, storage bytes, a suspended flag) and a small admin API
to set them and read usage (section 7). The control plane decides what any of it means.

## 2. Tenancy model

### Choices

| | Team id on every row | Schema per team | Database per team |
|---|---|---|---|
| How | One set of tables; a `team_id` column on every team-owned table. | One Postgres schema (namespace) per team, each with all ~35 tables. | One database per team. |
| At 1000 teams | One set of tables. | ~35,000 tables. Every migration runs 1000 times; a failure halfway leaves teams on different versions. | 1000 databases: the cost of one app per team, which is what we are avoiding. |
| Connection pool | One pool. | A pool that switches `search_path` per request; a missed switch reads another team. | A pool per team. |
| Leak risk | A query that forgets `team_id`. | A connection with the wrong `search_path`. | Lowest. |
| Admin queries (usage, sweeps) | One query. | One per schema. | One per database. |
| SQLite (self-host) | Same code, one team. | No schemas in SQLite: a second code path. | – |

**Chosen: a team id on every row.** It is the only one that keeps one code path for SQLite and
Postgres and one migration run. Its weakness, a forgotten filter, gets four guards below.

### Which tables get `team_id`

Every table on master except the migration log. Team-owned: `projects`, `project_groups`, `updates`,
`messages`, `approvals`, `tasks`, `reactions`, `card_tags`, `card_fields`, `tags`, `embeds`,
`embed_types`, `apps`, `app_views`, `media`, `derivatives`, `upload_tokens`, `update_shares`,
`notifications`, `read_cursors`, `acknowledgements`, `message_deliveries`, `message_mentions`,
`project_subscriptions`, `thread_subscriptions`, `group_subscriptions`, `agents`, `sessions`,
`agent_pairings`, `owner_devices`, `oauth_clients`, `oauth_grants`, `oauth_tokens`,
`server_settings`, `push_subscriptions` (unused by the Kotlin server; drop it instead), and the
`secrets` table when PR #153 lands. New tables from [TEAMS.md](TEAMS.md) (`memberships`, `invites`)
carry it from birth. `users` (accounts) does not: an account can be in several teams (section 5).

Every id is a random ULID, unique across the whole database, so ids never clash between teams. But a
lookup by id alone is still a leak: `SELECT … FROM approvals WHERE id = ?` must become
`WHERE team_id = ? AND id = ?`, or team A can answer team B's request by guessing nothing more than
an id it saw in a link.

**Unique rules that change** (today unique across the server, then unique within a team):
`projects.slug`, `project_groups.slug`, `embed_types.slug`, `apps.uri`, `server_settings.key`,
`oauth_clients` registration, and agent names (unique only in code today: `domain/Pairings.kt`,
the OAuth consent page). The bell's three unique indexes (`notifications_update`, `_message`,
`_request`: one row per card) become one row per person per card (section 6). Two teams can both have a project called `web`. Rules on random
tokens (`token_hash`, `code_hash`, `secret_hash`) stay server-wide: a key must find exactly one row.

**Links between rows** get the team in them where it is cheap: `messages (team_id, project_id)`
refers to `projects (team_id, id)`, and so on for project and group links. Then the database itself
refuses a message in team A that points at a project in team B.

### How every query gets scoped

1. **In code.** A request resolves its team once (section 4) and gets a `Team` handle. Domain
   classes (`domain/Reads.kt`, `domain/Messages.kt`, …) are built per request from that handle
   instead of living as one instance on `App` (`App.kt`). Every SQL statement names
   `team_id = ?` itself: plain, greppable SQL, no hidden rewriting.
2. **A lint, in the test build.** `Database.query/one/update` (`db/Database.kt`) checks each
   statement while tests run: if it names a team-owned table and does not mention `team_id`, the
   test fails, naming the statement. A short allow-list covers the deliberate cross-team queries
   (sweeps, admin usage), each with a comment saying why.
3. **A two-team leak suite.** The parity suite (`server/parity`) runs as team A while a team B with
   look-alike data (same project slugs, same agent names, same media bytes) sits in the same
   database. Afterwards: B's rows are unchanged, and no B id, slug, name or byte appeared in any
   answer, stream frame or push A received.
4. **Row-level security on Postgres**, as a backstop in multi-team mode. Each team-owned table gets
   `ENABLE` and `FORCE ROW LEVEL SECURITY` and a policy
   `team_id = current_setting('feed.team_id', true)`. A connection taken from the pool sets
   `feed.team_id` for its request and resets it when returned. A forgotten filter then returns
   nothing instead of another team's rows; an insert into the wrong team fails. Both settings are set
   with `SET LOCAL`, inside the request's transaction, so they vanish when it ends and can't leak
   to the next user of a pooled connection. Code that must read across teams says so with
   `db.allTeams { … }`, which sets `feed.all_teams = on` the same way. The full list: the job
   runner, the event dispatcher and effects runner (section 3), account sign-in on the bare address
   (section 5) and the admin API (section 7). The lint's allow-list is exactly the `allTeams`
   blocks. Sweeps that run inline in a request today (for example `pairings.expireDue()` inside the
   pairing poll, `domain/Pairings.kt`) become scoped to that request's team. `FORCE` matters: Heroku's database user owns the tables, and owners skip
   row security without it. SQLite has no such feature; it never holds more than one team.
   Row security is switched on by the last tenancy migration, after the data has moved; any later
   migration that changes data sets `feed.all_teams` for itself.

### Moving today's data into one team

Migrations stay gapless, each as a SQLite and Postgres pair (`server/src/main/resources/migrations`
and `migrations-pg`). Master's last is 053; 054 is taken by the secrets PR (#153). So this work
starts at **055**, and each number is checked against master again just before it lands.

1. A `teams` table and one row, the **home team**: slug `home`, name from the founder's choice
   later. Its id is fixed (`home`) so SQL and tests can name it.
2. `ALTER TABLE … ADD COLUMN team_id text NOT NULL DEFAULT 'home'` on every team-owned table. On
   Postgres 11 and later, adding a column with a constant default rewrites nothing: it is instant
   even on a big table. SQLite does the same.
3. Swap the unique rules above to include `team_id`, and add the team-aware links.
4. A later migration, after the code always writes `team_id`, **drops the default on Postgres** and
   turns on row security. Without that step a forgotten `team_id` on insert would quietly land a
   row in the home team. SQLite keeps the default: it has one team forever.

**SQLite does less.** Its unique rules are written into the table definitions, and it can't add a
composite foreign key with `ALTER TABLE`; both would need a table rebuild. It doesn't need them:
a SQLite database holds one team, so "unique in the server" and "unique in the team" are the same.
So the SQLite twin of each migration adds the `team_id` columns and skips the unique swaps and the
team-aware links. The two schemas then differ only in constraints, never in columns, so `migrate`
(SQLite to Postgres) and `export` (Postgres to SQLite), which copy column by column, keep working.

**Before or after the deploy.** Heroku runs migrations in the release phase while the old dynos are
still serving, so every step is labelled:

- *Safe before the deploy* (old code still works): new tables, new nullable or defaulted columns,
  new indexes.
- *Only after every dyno runs the new code*: swapping a unique rule that old code names in
  `ON CONFLICT (key)` (`media/Retention.kt`, `domain/EmbedTypes.kt`, `domain/Apps.kt`), dropping
  defaults, turning on row security. These go in the *next* release's migration, never the same
  one.

Old code also refuses a database with a migration it doesn't know (`db/Database.kt`), so a rollback
past a migration means restoring a backup. Releases that add a migration say so in their notes.

Old device keys, agent tokens and OAuth tokens keep working: their rows are now in the home team.

## 3. Statelessness: everything the server holds in memory today

Two things change at once: a **pool of connections** instead of one, and **several processes**
instead of one. The first breaks things even on a single dyno, so it comes first.

Today one process holds a lot that a second process would not see. Heroku also restarts every dyno
at least once a day, and the server forgets all of it then. Here is all of it, found by grepping
for maps, timers, executors and `@Volatile`, and where each piece goes.

| What | Where today | What goes wrong with two dynos | Where it goes |
|---|---|---|---|
| **The one database connection.** All requests share one JDBC connection behind a lock. | `db/Database.kt` (`connection`, `lock`) | Every request on a dyno waits in one line. | A pool of connections (HikariCP) on Postgres. SQLite keeps one connection. See "Transactions on a pool" below: this is the hardest part of the list. |
| **Transactions lean on that one connection.** Code inside `db.transaction { _ -> … }` ignores the connection it is handed and calls `db.update` / `db.one`, which only works because there is one connection. | e.g. `domain/Pairings.kt` (approve), `domain/AgentWork.kt` (`recordDelivery`), `domain/OAuth.kt`, `domain/Devices.kt`, `domain/Mentions.kt`, `domain/Tags.kt`, `media/Uploads.kt`, `media/Retention.kt` | With a pool, those statements run on other connections, outside the transaction. | The transaction's connection is carried in the coroutine context, and `db.update` / `db.one` use it when there is one. |
| **Every write is serialised by that lock**, so "look, then write" code never races. | e.g. `domain/AgentWork.kt` `recordDelivery` (a select, then an insert keyed on a nullable `client_id`), `domain/Sessions.kt` (online before, write, announce) | Two connections both look, both write: double deliveries, double presence announcements. | A per-team write lock (below). |
| **`seq` order is taken as commit order.** Readers page with `WHERE seq > ?` and remember the highest seen. | `domain/Messages.kt` (agent read cursors), `domain/AgentWork.kt`, `domain/Subscriptions.kt` (`now()` = `max(seq)`, `after_seq`) | On Postgres, with two connections, seq 12 can commit before seq 11. A reader that saw 12 never sees 11: an agent silently misses a message. | The per-team write lock makes a team's writes commit one at a time, so within a team seq order is commit order again. Reads never need cross-team order. |
| **The event bus**: seq counter, boot id, a ring of the last 500 events, the listeners. | `events/Bus.kt` | An event published on dyno 1 never reaches a stream on dyno 2. Every restart changes the boot id, so every client resyncs. | An `events` table (team, seq, type, payload, time) plus Postgres `LISTEN/NOTIFY`. See "The event bus" below. |
| **Waits on the bus**: `await_request`/`request_input` holding for an answer, an agent's pairing poll. | `domain/Requests.kt` `waitForSettle` (takes `bus.lastSeq`, replays from the ring), `domain/Pairings.kt` `await` | The answer lands on another dyno; the wait runs to its timeout. | The new bus, with `lastSeq` and replay read from the team's events, not the process. |
| **Snapshot stamps**: snapshots carry the bus's current seq so a stream can resume from them. | `http/ReadRoutes.kt`, `http/FeedRoutes.kt`, `http/PairingRoutes.kt` (`bus.lastSeq`) | Each dyno has its own seq. | The team's `last_seq`. |
| **Owner and agent streams.** | `http/StreamRoutes.kt`, `http/AgentStream.kt` | Same as above. | The new bus. Each stream's own "already sent" set (`AgentStream.kt`, `sent`) is per connection and stays. |
| **Revocation checks**: each open stream asks the database every 5 s whether its key still works, and an agent's check also **writes** `last_seen_at`, unthrottled, as does every MCP call. | `http/Sse.kt` (`checkEveryMs = 5_000`), `domain/Agents.kt` `authenticate` | Not wrong, but at 1000 teams × 5 streams that is 1000 queries a second, many of them writes. | Revoking a key publishes `device.revoked` / `agent.revoked`; streams close on it. The poll stays as a slow backstop (60 s). `last_seen_at` is written at most once a minute, as device keys already do (`domain/Devices.kt`). |
| **Listeners run on the dispatching thread**, and some query and publish there (the agent stream counts work per event per stream; delivery and the bell publish more events). | `http/AgentStream.kt`, `domain/AgentWork.kt` `markDelivered`, `domain/Notifications.kt` `record` | With one dispatcher per dyno, one slow team holds up every team's events. | One dispatch queue per team, so teams don't wait on each other. The agent stream's recount is coalesced (at most one per stream per 250 ms). |
| **Side effects of events**: the bell recorder, iOS alerts, Live Activity updates, all bus listeners. | `domain/Notifications.kt` `startRecorder`, `push/Apns.kt` `start` (sends on a thread pool), `push/LiveActivity.kt` `start` | If every dyno hears every event, every dyno sends the push: duplicates. | They stop being listeners and become **effects**, run from the `events` table. See "Effects" below. |
| **Live Activity memory**: `lastSent` and `startedAt` maps, `newRequestId` (one slot: a second request inside the 2 s window overwrites the first), the 2 s coalescing timer and the 1 min refresh. | `push/LiveActivity.kt` | Two dynos push the same update, or neither knows what was sent; every restart forgets and resends. | Rows: per device `la_sent_hash`, `la_push_to_start_at`; per team `la_due_at`, and `la_new_requests` (a list, not one slot). A `request.created` effect sets `la_due_at = now + 2 s` if it is unset. A job runs due teams, **one dyno per team at a time** (a transaction-scoped advisory lock on the team): it works out the state, sends, and records `la_sent_hash` only after Apple says 200, as today. So a failed send is retried next run, and an older state can never be sent after a newer one. |
| **iOS sender threads and the signed APNs token.** | `push/Apns.kt` (`sender`, `ProviderToken.cached`) | Nothing: each dyno signs its own token every 50 minutes, which Apple allows. | Stays. |
| **Scheduled jobs**: presence sweep (1 min), request sweep (30 s), pairing sweep (30 s), OAuth sweep (10 min), media derive (1 s), media sweep (15 min), media retention (10 min). | `App.kt` `startJobs`, `every` | Every dyno runs every job. Retention deletes files twice; presence announces twice. | A `job_runs` table: one row per job with `next_run_at`. A dyno runs a job only if `UPDATE job_runs SET next_run_at = now + period WHERE name = ? AND next_run_at <= now` changed the row, so no lock has to outlive a transaction. Sweeps work across all teams in batches of 500 (inside `allTeams`, with their own 5-minute statement limit), and publish each row's event into its own team. |
| **Retention's last-run time** is kept in `server_settings`. | `media/Retention.kt` | Server-wide job state in a table that becomes per team. | Moves to `job_runs`. |
| **Media derive queue**: a set of ids being worked on. | `media/Derive.kt` (`queued`, `pool`) | Two dynos transcode the same video. | Claim in the database: `UPDATE media SET derive_claimed_at = now … WHERE id = (SELECT … FOR UPDATE SKIP LOCKED)`. A claim older than 15 minutes is taken as dead and retried. The claim is the only guard derive needs; it doesn't also take the job lock. |
| **Rate limits**: ten sliding-window limiters (sign-in, MCP, agent stream, upload, pairing start and poll, three OAuth, agent media). | `App.kt`, `auth/SlidingLimiter.kt` | Each dyno counts alone: N dynos let N times as much through. For sign-in lockout that is a security hole. | Split. **Security limits** (sign-in, pairing start, OAuth register, invite accept) move to a Postgres table of hit times per key, read and written in one statement: low traffic, must be exact. They keep today's rules, including sign-in counting only failures and clearing on success (`http/AuthRoutes.kt`). **Throughput limits** (MCP, streams, uploads) stay in memory per dyno: they are about fairness, and a little loose is fine. Per-team limits are added (section 7). |
| **Presence and heartbeats.** | `domain/Sessions.kt` | The data is already in the `sessions` table, but "online before? write; announce if changed" races across connections: double or missing announcements. | The per-team write lock covers it; the sweep runs as a job. |
| **Caches**: S3 object sizes (LRU, 20,000 entries). | `media/MediaStore.kt` (`S3MediaStore.sizes`), read by `media/Serve.kt` | Objects do change: a delete clears only the deleting dyno's cache, and re-deriving rewrites the same keys (`media/Derive.kt`). Other dynos then serve a stale size. | Cache only originals (written once, never rewritten); ask the bucket for derivatives. A delete removes the media row first, so a stale cached size never gets served. |
| **The OAuth consent cookie** (`__Host-feed_owner`): proves the owner signed in on the consent page. Signed with `SESSION_SECRET` over a hash of `ADMIN_PASSWORD_HASH`. | `http/OAuthRoutes.kt` (`OwnerSession`) | With accounts and teams, the value says nothing about which person or team: a member of team A could replay it on team B's consent page. | It signs the membership id and the team id, and is refused on any other team's address or once that membership is removed. |
| **Local disk**: upload temp files, S3 fetch area, ffmpeg work files under `DATA_DIR/tmp`; on-disk media store. | `media/Uploads.kt` (`tempRoot`, `workRoot`), `App.kt` (`tmp/fetch`), `media/MediaStore.kt` (`LocalMediaStore`) | Temp files: nothing, each lives for one request or one job. The on-disk store: a second dyno can't see the files. | Temp stays. Multi-team mode refuses to start without `MEDIA_STORE=s3`. |
| **Media dedupe**: an upload whose bytes match an earlier one links to it. | `media/Uploads.kt` `place` (`WHERE sha256 = ?`) | Across teams this is a leak: the answer's `deduped` flag tells team A whether team B holds a given file. | Look for twins within the team only. |
| **Media keys**: `<id[0:2]>/<id>/<name>` in the bucket. | `media/Uploads.kt` `originalKey`, `media/MediaStore.kt` | Can't list, measure or delete one team's files. | New teams' media go under `t/<team id>/…`; the home team keeps today's keys, so self-hosters move nothing. |
| **Settings**: retention days; the bell's `seen_at` on each row. | `media/Retention.kt` (`server_settings`), `domain/Notifications.kt` | One owner's values. | Retention per team (`server_settings.team_id`). The bell gets a row per person, each with its own `seen_at` (section 6). |
| **Id order**: ULIDs are "each the last plus one" within a millisecond, per process. | `db/Ulid.kt` | Two dynos' ids in one millisecond aren't in order. | Harmless: queries order by `seq` or a time, and use `id` only to break ties (`domain/Cards.kt`, `domain/Reads.kt`). Write down the rule: never order by id alone. |
| **Migrations at boot**, with no lock. | `db/Database.kt` (`Migrations.run`, `PostgresMigrations.run`) | Two dynos booting together both try to migrate. | A new `feed-server migrate-schema` command (plain `migrate` already means "copy SQLite to Postgres", `Main.kt`) run in Heroku's release phase (a `release` process type in `heroku.yml`, beside `web` and `worker`). `run` also takes a transaction advisory lock, for anyone who boots without the release phase. See "Before or after the deploy" in section 2. |
| **The owner's password** in config. | `Config.kt` (`ADMIN_PASSWORD_HASH`), `http/AuthRoutes.kt`, `http/OAuthRoutes.kt` | One password for the whole server. | Accounts in the database ([TEAMS.md](TEAMS.md), section 5 here). |
| **SQLite-only code**: `Dialect.SQLITE` branches, `greatest`, `asciiFold`, the SQLite twin migrations. | `db/Database.kt`, `media/Uploads.kt` (`logoIds`, `embedStrings`), `media/Retention.kt` (`logoIds`) | Nothing: hosted runs Postgres only. | Stays for self-hosting and the desktop app, always one team. Multi-team mode refuses to start on SQLite. Every new migration still ships as a pair. |
| **Static things**: the MCP tool list, the web app's files, the server version, the crash-report switch. | `mcp/Tools.kt`, `http/WebApp.kt`, `http/ServerInfo.kt`, `CrashReports.kt` | Nothing. | Stay. |

### Transactions on a pool

Today the one connection's lock means every write in the server happens one at a time. Plenty of
code quietly relies on that (the table above names the places found so far). Rather than hunt down
every "look, then write" and hope, keep the guarantee where it matters:

- **Every write runs in `team.write { … }`.** It opens a transaction, takes the team's row lock
  (`SELECT … FROM teams WHERE id = ? FOR UPDATE`), sets `feed.team_id`, runs the block, and
  publishes the block's events in the same transaction. Writes in one team happen one at a time, as
  today; different teams don't wait for each other. One team's writes are a handful a second, so
  the queue is short.
- **Reads run in `team.read { … }`**: a transaction with `feed.team_id` set, no lock, in parallel.
- **The transaction's connection travels in the coroutine context**, so code inside the block that
  calls `db.update` / `db.one` uses it. Outside a block, those calls fail in tests (another lint).
- **SQLite** keeps one connection; `team.write` is just its transaction.

This also makes seq order commit order within a team (the table above), and lets events be written
in the same transaction as the change they describe, so a crash can no longer save the change and
lose its event.

### Effects

Effects are what used to be bus listeners with outside consequences: the bell, iOS alerts, Live
Activity scheduling.

- Each `events` row has an `effects_done_at` column. Every dyno runs an effects loop, woken by the
  same `NOTIFY` and by a 5 s poll for anything missed. It claims up to 50 pending events with
  `FOR UPDATE SKIP LOCKED`, so no two dynos take the same one.
- An event's effects run in a fixed order (bell rows, then alerts, then the Live Activity mark).
  The claim is held until all of them finish, **including the Apple sends**: no "fire and forget"
  to a thread pool as `push/Apns.kt` does now. Then it sets `effects_done_at` and commits.
- If a dyno dies mid-way, the claim lapses and another dyno runs the event again. Bell rows are
  idempotent (unique per person per card). A repeated alert is possible and harmless: alerts carry
  a collapse id, so the phone shows one.
- **Every event carries its actor** (`actor: {kind: "member" | "agent", id}`), so effects can skip
  the person who acted (section 6).
- **Read-your-own-writes:** the bell row now appears a moment after the post, not in the same
  instant. The parity suite gets a short "wait until" for bell checks, and the app already learns
  of bell rows from `notification.created` on its stream.

### The event bus

The bus is the biggest change, so here is how it works.

- **Publishing** writes a row into `events` and calls `NOTIFY feed_events, '<team>:<seq>'`. The seq
  comes from `UPDATE teams SET last_seq = last_seq + 1 … RETURNING last_seq`. Because the team's
  row is locked for the whole write, a team's events commit in seq order, so a reader never sees
  seq 12 before seq 11. Different teams don't wait for each other.
- **Inside the write**: the event row is written in the same `team.write` transaction as the change,
  after taking the team lock, so the seq and the commit order agree.
- **Listening**: each dyno holds one extra connection doing `LISTEN feed_events`. On a notice for a
  team that has a stream or a wait on this dyno, it reads that team's new rows in order and hands
  them to its local listeners. If the listening connection drops, it reconnects and reads what it
  missed from the table, so nothing is lost.
- **Replay** (a stream reconnecting with `Last-Event-ID`) reads the table instead of the 500-event
  ring. A job deletes events older than 24 hours.
- **The boot id** becomes the team's `epoch`, changed only when its data is restored. Today every
  dyno restart (at least daily on Heroku) makes every client resync; after this, they just resume.
- **Limits**: a `NOTIFY` payload must be under 8000 bytes; ours is under 60. Postgres takes one
  global lock at commit for transactions that notify, which is fine up to a few hundred events a
  second across all teams. Past that, swap the notice for Redis pub/sub (Heroku Key-Value Store);
  the table stays the source of truth. `LISTEN` needs a direct connection, never one through
  PgBouncer in transaction mode.
- **Self-hosted on SQLite**, and the desktop app, keep today's in-memory bus behind the same
  interface. One process needs nothing more.

## 4. Routing: how a request finds its team

**Each team has a subdomain: `<team>.feed.wildware.dev`.** The server reads the team from the
`Host` header when multi-team mode is on (`TEAM_DOMAIN=feed.wildware.dev`). Unset, every request is
the home team, as on any self-hosted server.

Why a subdomain and not a token lookup alone:

- **The app needs no new idea.** It already treats one address as one feed ("Connect to a host").
  A hosted team is just an address. Signing in to two teams is two addresses.
- **Cookies stay apart.** The web app's cookie (`http/OwnerCookie.kt`) has no `Domain`, so each
  team's site keeps its own.
- **OAuth for MCP works as written.** `http/OAuthRoutes.kt` builds the issuer and the resource from
  one base address. A team's address is that base.
- **Agents get one URL per team** (`https://acme.feed.wildware.dev/mcp`), like any self-hosted one.

**The rule: a credential names its team, and the address must agree.** Device keys, agent tokens,
OAuth tokens, upload tokens and share links are all looked up by a hash that is unique across the
server, so each finds exactly one row, and that row has a team. A key from team A sent to team B's
address gets `401`, the same answer as a wrong key. A request with no credential (sign-in, pairing,
`/api/server`, OAuth pages) uses the address's team.

What changes, piece by piece:

- **`PUBLIC_BASE_URL`** is used to build links in about 16 places (OAuth, pairing words, upload
  URLs, push links, MCP attachments). Each becomes the team's own address,
  `https://<slug>.<TEAM_DOMAIN>`. The home team keeps `PUBLIC_BASE_URL`.
- **The web app's cookie** is named `__Host-feed_device` in multi-team mode. Any page on one team's
  subdomain can otherwise set a cookie for all of `.feed.wildware.dev` and push it onto another
  team's site; the `__Host-` prefix makes browsers refuse that. The same-origin check
  (`pageOrigin` in `OwnerCookie.kt`) must keep asking for `same-origin`, never `same-site`, because
  every team's subdomain is the same "site" to a browser. (Putting `feed.wildware.dev` on the
  Public Suffix List would make each team its own site, like `github.io`; worth doing later.)
- **Agent pairing** (`/api/pair`) and **OAuth sign-in** happen on the team's address, so they join
  that team. On the bare `feed.wildware.dev` they answer 404.
- **iOS alerts.** A device row belongs to one membership, so one phone signed in to two teams has
  two rows with the same APNs token, one per team. Each alert gains a `team` key (add-only, so the
  iOS app's contract holds); the app routes a tapped alert by that key, falling back to the link's
  host. The `thread-id` gets a team-slug prefix, because two teams can both have a project called
  `web`, **except for the home team**, so self-hosters' notification grouping doesn't change.
- **Live Activity on a phone in two teams.** One phone shows one Live Activity. The app registers
  its Live Activity and push-to-start tokens only with the team it is showing, and clears them on
  the others, so two teams never write over each other on the lock screen.
- **The bare address** `feed.wildware.dev` serves the account sign-in (section 5), `/api/server`,
  and the admin API for the control plane (section 7). Nothing else. Its `/api/server` says
  `"signIn": ["account"]` and `"directory": true`, so the app knows this address lists teams and
  is not itself a feed. A team's address answers as in TEAMS.md §8, with `"team": true`.
- **An unknown subdomain** answers every path with `404 {"error":"no_such_team"}`. A team that is
  being deleted answers the same.
- **New public routes** (`/api/account/sign-in`, `/api/devices/ticket`, `/auth/ticket`, `/join/…`) join the guard's
  public list (`http/Guard.kt`). `/admin` has its own token check and is public to the guard.
- **Today's live server** becomes the home team at its current address: `PUBLIC_BASE_URL` stays the
  home team's address, and nothing about it moves to a subdomain. Hosted customers' teams get
  subdomains.
- **Renaming a team's address** keeps the old one as a permanent alias of the same team, not a
  redirect: device keys, agent configs, MCP URLs, OAuth tokens and open streams keep working on it.
  The new one is used for new links. A slug can't be taken by another team while it is an alias.

**TLS for wildcard addresses** is the practical catch. Heroku's free certificates (ACM) do not
cover wildcards. Either put Cloudflare in front (it already holds the bucket; a certificate for
`*.feed.wildware.dev` needs its paid Advanced Certificate Manager, about $10 a month, to verify), or
upload a wildcard certificate to Heroku by hand and renew it. Open question 1.

Cloudflare in front changes three things, all to verify:

- **Media must not be cached at the edge.** The server checks access, then answers media with
  `Cache-Control: public, max-age=31536000, immutable` (`http/MediaRoutes.kt`). A shared cache
  would then hand that file to anyone with the URL. It becomes `private, max-age=31536000,
  immutable` (browsers and the apps still cache it), plus a Cloudflare rule to bypass the cache
  for `/media`, `/apps`, `/api` and `/mcp`.
- **Request bodies over 100 MB are refused** on Cloudflare's Free and Pro plans, and video uploads
  allow 200 MB (`Config.kt`, `MAX_VIDEO_BYTES`). Either cap hosted videos at 100 MB or let large
  uploads go straight to the bucket with a signed upload URL.
- **The client address** comes from `CF-Connecting-IP`, so `ADDRESS_HEADER` changes with it, or
  every sign-in lockout counts Cloudflare's addresses instead of the real ones.

## 5. Identity: accounts and memberships

[TEAMS.md](TEAMS.md) has one `users` table per server. With many teams, one person can be in
several (a contractor in two clients' teams), so it splits in two:

- **Account** (`users`): email (unique across the server, lower-cased), name, password hash. This
  is who you are. It never holds a role.
- **Membership** (`memberships`): account, team, role (owner, admin or member), handle for
  @mentions, `removed_at`. This is what you are in one team.
- **Device key**: belongs to one membership, so one key opens one team. A stolen key leaks one team.

On a self-hosted server with one team this is the same as TEAMS.md: every account has exactly one
membership, in the home team.

**Hosted "Log in" in the app**, with no control plane in the middle (accounts live in the same
database as the teams, so the sign-in grants of TEAMS.md §7 are not needed):

1. The app sends email and password to `POST https://feed.wildware.dev/api/account/sign-in`.
2. The answer lists the account's teams (`slug`, `name`, `address`, `role`) and a **ticket**, good
   for 5 minutes, stored hashed in `auth_codes` like every other one-time secret.
3. The app picks a team (or asks, if there are several) and sends the ticket to
   `POST https://<team>.feed.wildware.dev/api/devices/ticket` with a device name. It gets a device
   key, the same answer as a password sign-in, and opens the feed.
4. Signing in to another team later: the same two steps.

The founding owner of a migrated server has no email (TEAMS.md §2) until they set one in the app;
account sign-in can't find them before that, and the app asks for one at their next sign-in.

**Hosted "Log in" through the control plane.** The app can also sign in with no password at all:
the control plane emails a code to prove the email, asks the admin API which teams that email is in
(`POST /admin/memberships/lookup`) and for a ticket to the chosen one
(`POST /admin/teams/{id}/sign-in-tickets`, section 7), and hands the ticket to the app, which
redeems it at step 3 above. The server makes and checks the ticket either way.

Sign-in on the team's own address ("Connect to a host" → `acme.feed.wildware.dev` → email and
password) also works and goes straight to step 3's result. Same rate limits as today's sign-in, by
client address and by email (section 3, security limits).

**Signup** happens on the control plane. It calls the admin API to create the team and its owner
(section 7), and the server answers with a one-time link for the owner to set a password. The
control plane never sees a password.

## 6. Who gets alerted

The owner's decisions: people subscribe to what is relevant to them, or get mentioned; a teammate's
message reaches others only through what they follow. This is the follow system already on master
(migrations 052 and 053, `domain/Subscriptions.kt`), extended from one owner to each person.

- **Subscriber names.** Today the owner is `human` and agents are `agent:<id>`. Each person becomes
  `member:<membership id>` (the name `domain/Subscriptions.kt` already reserves). The migration
  rewrites the owner's `human` rows to the founder's membership.
- **Levels**, per person, unchanged: group, channel (`all`, `replies`, `muted`), and threads followed
  by posting, replying or choosing.
- **Mentions** of a person (`@sam`, by membership handle) alert them and follow the thread for them,
  whatever their channel level. `@here` and `@everyone` stay agent-only for now.
- **People and agents are treated alike.** Sam's post in a channel reaches Alex if Alex follows that
  channel at `all`, or the thread. Sam never hears his own post.
- **Requests** follow the same per-person rule as today's owner (`ownerHearsRequest`). Anyone in
  the team may answer; the first answer wins (TEAMS.md §3).
- **The bell** gets a row per person who hears the event (`notifications.member_id`, unique per
  person per card), each with its own `seen_at` as today's rows have. This replaces the
  per-person read cursor TEAMS.md §9 once proposed (`notification_reads`).
- **Phones** then apply their own preferences (`alert_project_ids`, `alert_prefs`), as now.
- **Live Activity** shows the requests in channels the person has not muted, so its content is
  worked out per device (per person), while "is anything busy" stays per team.

## 7. Limits, fairness, and the admin API

### Per-team limits

Stored on the `teams` row, set by the control plane, enforced by the server:

| Limit | Team plan | How it is enforced |
|---|---|---|
| Logins (seats) | 5 | Active memberships plus pending invites, checked inside `team.write`, so two invites at once can't both take the last seat. The sixth is refused with `seat_limit`. |
| Storage | 25 GB | Media and derivative bytes, kept as a running total on the team (`teams.storage_bytes`), changed in one statement: `UPDATE teams SET storage_bytes = storage_bytes + ? WHERE id = ? AND storage_bytes + ? <= storage_limit`. No row changed means `storage_full`. Reserve adds the declared size; landing corrects it to the real size; delete subtracts. Corrected nightly from the media rows (the home team's keys sit at the bucket root, so its total comes from rows, not a listing). |
| Agents | no limit | Rate limited instead (below). |
| Suspended | – | Read-only, as TEAMS.md §7: reads and exports work, writes answer `403 suspended`. |

### Keeping one team from slowing the rest

- **Requests:** a per-team ceiling on top of the per-token one: 1,200 MCP calls a minute across all
  of a team's agents (in memory, per dyno, so loose; fine for fairness).
- **Open connections:** at most 50 streams and 50 held waits (`request_input`, pairing polls) per
  team per dyno, so about 50 × the number of dynos in all, since the router spreads connections at
  random. The 51st gets `429` with `Retry-After`, which the apps and the channel bridge must honour
  with a growing delay, not an instant reconnect.
- **Database:** `statement_timeout` of 10 s for requests. A query that slow is a bug, not a
  workload. Jobs that sweep every team get 5 minutes and work in batches.
- **Transcoding:** ffmpeg is the heaviest thing the server does (it is why a Basic dyno needs
  `FFMPEG_THREADS=1`, see `server/deploy/HEROKU.md`). The derive job takes the oldest pending media
  **per team in turn**, at most one at a time per team, so one team's 50 videos don't hold up
  everyone's thumbnails. Past ~100 teams it moves to its own `worker` dyno.
- **Bandwidth:** media is served through the server today. Heroku has a soft limit on bandwidth per
  app (2 TB a month, to verify). If that bites, serve large media by redirecting to short-lived
  signed bucket URLs instead.

### The admin API the control plane calls

This is a fixed contract (version 1): the control plane is built against exactly this, so slice 4
builds exactly this. It knows teams, people and numbers, never prices or plans.

**Where.** Under `/admin/` on any address that is not a team's (the bare address in multi-team mode;
on a self-hosted server, its own address, with the one team called `home`, so a self-hoster can use
it too). Unset keys mean every `/admin/*` route answers `404`. JSON in and out; times ISO 8601 UTC
with milliseconds. Errors are `{"error": "<code>", "message": "<words>"}`. Clients ignore fields they
don't know, so answers may grow.

**Signed requests, not a bearer token.** `ADMIN_API_KEYS` holds one or two keys (comma-separated, so
a key can be rotated), each at least 32 random bytes in base64url. The key never travels. Each request
carries `Feed-Admin-Timestamp: <unix seconds>` and `Feed-Admin-Signature: v1=<hex>`, where the hex is
`HMAC-SHA256(key, message)`, the key is the key string's UTF-8 bytes, and the message is five lines
joined by `\n` with no trailing newline:

```
v1
<timestamp>
<METHOD>
<path and query exactly as sent>
<hex SHA-256 of the raw body; for no body e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855>
```

A test vector the server must accept (at that time): key `test-key-0123456789abcdef0123456789abcdef`,
`POST /admin/teams` with body `{"slug":"acme"}` at `1763629200` hashes the body to
`0e671cb77c0d46e2a648694df49ee50c8c3d0ef1345e9e835175c8647589eb99` and signs to
`v1=02819ba998415d8cc7379718c4a0a382f6c92281c7d7eeed16f092ad07309ab0`.

A timestamp more than 300 s off is `401 stale_request`. No key matching (compared in constant time),
or a missing header, is `401 bad_signature`. No other credential works on `/admin/*`, and the signature
works nowhere else. Its own rate limit: 600 a minute, then `429` with `Retry-After`. Admin calls should
be audit-logged (call, team, time; never bodies).

**Each signed write is taken once.** The server remembers the signatures of calls other than `GET`
and `HEAD` while their time is good; the same signed call again is `401 stale_request` (a plain
refusal, nothing is locked). So the control plane signs every attempt afresh, retries included,
and moves the timestamp on by a second if it would send the very same call twice in one second.
Otherwise someone who saw one call in transit could send it again: a replayed sign-up would revoke
the owner's setup link and hand the replayer a new one.

**Idempotency.** `POST /admin/teams` and `POST /admin/teams/{id}/invites` require an
`Idempotency-Key` header (1 to 255 printable ASCII). The server keeps it 24 hours with a fingerprint
(method, path, SHA-256 of the body). Same key and request: the first status and body again, with
`Idempotent-Replayed: true`, and nothing made twice; any one-time link in the answer (`setupUrl`,
invite `url`) is freshly made and the earlier one revoked, so no secret is ever stored for replay.
Same key, different request: `422 idempotency_key_reused`. Same key still running:
`409 idempotency_in_progress`. Other writes are idempotent by nature (`PUT` sets; `DELETE` repeats
`202`).

**The team object:**

```json
{"id": "01JD…", "slug": "acme", "name": "Acme", "address": "https://acme.feed.wildware.dev",
 "externalRef": "tm_01JD…", "limits": {"seats": 5, "storageBytes": 26843545600},
 "suspended": false, "suspendedReason": null, "state": "active", "ownerSetupDone": false,
 "createdAt": "2026-11-20T09:00:00.000Z"}
```

`state` is `active` or `deleting`; once the purge is done the team is `404 no_such_team`.
`externalRef` is the control plane's own id (up to 64 characters), stored and echoed, never
interpreted. `ownerSetupDone` says whether the team's first owner has a password: `true` once their
setup link is used (or when they had a password already), `false` while it is waiting. Reading it
makes no new link.

| Call | Body | Answer | Errors |
|---|---|---|---|
| `POST /admin/teams` | `{slug, name, owner: {email, name}, limits: {seats, storageBytes}, externalRef}` | `201 {team, owner: {accountId, membershipId, email, newAccount, setupUrl, setupExpiresAt}}`. Links an existing account by email (lower-cased) or makes one with no password. `setupUrl` is a one-time password link, 24 h, `null` if the account has a password. | `400 invalid_request`, `422 invalid_slug` (3–30 of `a-z0-9-`, starts with a letter, no trailing `-`, no `--`), `409 slug_taken` (team or alias), `409 slug_reserved` (reserved, or a team deleted in the last 30 days) |
| `GET /admin/teams/{id}` | – | `200 {team}` | `404 no_such_team` |
| `GET /admin/teams?externalRef=` | – | `200 {teams: […]}` (empty if none) | – |
| `GET /admin/teams/{id}/usage` | – | `200 {teamId, seats: {used, pendingInvites, limit}, storage: {bytes, limit}, agents, devices, projects: {active, archived}, databaseBytes, lastActivityAt, suspended}` | `404 no_such_team` |
| `GET /admin/usage?since=&cursor=&limit=` | – | `200 {usage: […], nextCursor}`, teams changed since `since`, oldest first, `limit` 1–500 (100) | – |
| `PUT /admin/teams/{id}/limits` | `{seats?, storageBytes?}` | `200 {team}`. A limit below use is accepted; new seats or uploads are refused until under it. | `404` |
| `PUT /admin/teams/{id}/suspended` | `{suspended, reason?, message?}` (`reason` a code ≤ 64; `message` ≤ 280, shown to the team) | `200 {team}`; setting the current state is fine | `404` |
| `DELETE /admin/teams/{id}` | – | `202 {team}` with `state: "deleting"`; the address answers `404 no_such_team` at once; a job removes rows and the bucket prefix; repeats answer `202` until done, then `404`. Slug blocked 30 days. | – |
| `POST /admin/teams/{id}/invites` | `{email, role}` (`owner`, `admin`, `member`) | `201 {inviteId, url, expiresAt}`: the TEAMS.md §5 invite | `409 seat_limit`, `409 already_member`, `404` |
| `POST /admin/teams/{id}/setup-links` | `{email}` | `201 {url, expiresAt}` for a member with no password yet; earlier setup links revoked | `404 no_such_member`, `409 already_set_up` |
| `POST /admin/memberships/lookup` | `{email}` (a `POST`, so the email stays out of URLs and logs) | `200 {memberships: [{membershipId, role, team: {id, slug, name, address, suspended}}]}`: active memberships in teams not being deleted, of an account whose email was proved (below) | – |
| `POST /admin/teams/{id}/sign-in-tickets` | `{email}` | `201 {ticket, expiresAt, address, membershipId}` | `404 no_such_member`, `404 no_such_team` |

**Only proved emails count across teams.** An email someone typed into the server (a profile
change, accepting an invite) proves nothing: anyone can type anyone's address. An account's email
is proved when the control plane made it (`POST /admin/teams`) or its setup link was used, and
`memberships/lookup` and `sign-in-tickets` see only those. When `POST /admin/teams` finds an
account with the owner's email that was never proved, the email comes off that account (it keeps
its teams and devices) and the owner gets a new account (`newAccount: true`). Accepting an invite
will count as proof once the control plane emails invite links itself; it doesn't yet, so invited
people sign in with their password at their team's address until slice 5.

**Sign-in tickets** let the control plane sign a person in to their team after it has checked their
email itself (by an emailed code), without ever seeing a password. A ticket is 32 random bytes
(43 base64url characters), stored only hashed in `auth_codes`, valid **60 seconds**, **single use**
(deleted in the same transaction that checks it), bound to one membership, and taken only on that
team's address. It works while the team is suspended. Not idempotent on purpose: a retry makes a new
ticket. Redeemed, with no admin key, on the team's address:

- **App:** `POST /api/devices/ticket {ticket, deviceName, platform}` answers exactly what a password
  sign-in does. A used, expired, unknown or other team's ticket: `401 invalid_ticket`, all alike.
  Rate limited like sign-in.
- **Browser:** `POST /auth/ticket` with form body `ticket=…` sets the web app's cookie and answers
  `200` with a tiny page that moves itself to `/` (a meta refresh). Not a `303`: the cookie is
  `SameSite=Strict`, and a browser won't send it on a redirect that began on another site, so the
  person would land signed out. A bad ticket's page moves to the sign-in page with an error. The form is posted from the
  control plane's page, so `Origin` must equal the control plane's origin exactly (setting
  `CONTROL_PLANE_ORIGIN`; unset means this route answers `404`); any other or missing `Origin` is
  `403`. Otherwise any website could post the attacker's own ticket and sign a visitor in to the
  attacker's team.

The control plane uses these as follows: sign-up sends `POST /admin/teams` with
`Idempotency-Key: create-<its team id>` and `externalRef` set to that id; a lapsed team is suspended,
a paid one resumed, extra logins become `PUT …/limits`; 30 days after lapsing it sends `DELETE` and
reads until `404`; the app's hosted "Log in" is `memberships/lookup` then `sign-in-tickets`.

Error codes in one place: `400 invalid_request`; `401 bad_signature`, `stale_request`,
`invalid_ticket`; `403 suspended` (team routes only); `404 no_such_team`, `no_such_member`;
`409 slug_taken`, `slug_reserved`, `seat_limit`, `already_member`, `already_set_up`,
`idempotency_in_progress`; `422 invalid_slug`, `idempotency_key_reused`; `429 rate_limited`.

Still to come, not used by the control plane yet: `PUT /admin/teams/{id}/slug` (rename; the old
address stays an alias, section 4) and `POST /admin/teams/{id}/export` (the existing
`feed-server export` gains `--team`, rewriting `team_id` to `home` and copying the team's media from
`t/<team id>/…` to the root layout, so a hosted team can always leave).

## 8. Cost

All prices are **estimates to verify** against Heroku's and Cloudflare's current price lists.
Assumed per team: 5 people, 2 GB of media (the cap is 25 GB), 100 MB of database rows.

Heroku pieces used: dynos **Basic** (about $7 a month; one dyno per process type, so it cannot
scale out), **Standard-1X** (about $25, 512 MB), **Standard-2X** (about $50, 1 GB); Postgres
**Essential-1** (about $9, 10 GB), **Standard-0** (about $50, 64 GB, 120 connections),
**Standard-2** (about $200, 256 GB, 400 connections). R2: about $0.015 per GB a month after 10 GB
free, no charge for traffic out, small per-operation charges.

**Shared (this design):**

| Teams | Web | Worker | Postgres | R2 | Total a month | Per team |
|---|---|---|---|---|---|---|
| 10 | 2 × Standard-1X ($50) | none (jobs run on web) | Essential-1 ($9) | 20 GB (~$0) | **~$60** | ~$6 |
| 100 | 2 × Standard-2X ($100) | 1 × Standard-2X ($50) | Standard-0 ($50) | 200 GB (~$5) | **~$205** | ~$2 |
| 1000 | 4 × Standard-2X ($200) | 2 × Standard-2X ($100) | Standard-2 ($200) | 2 TB (~$40) | **~$540** | ~$0.55 |

Add about $10 a month for the wildcard certificate (section 4). Two web dynos from the start, so a
deploy or a crash doesn't take everyone down.

**One app per team** (Basic dyno + Essential-0, about $12): ~$120 at 10 teams, ~$1,200 at 100,
~$12,000 at 1000, plus a deploy and a migration per team on every release, and a 1 GB database cap
per team.

**For scale:** revenue at $29 a team is $290, $2,900 and $29,000 a month. Even if every team filled
its 25 GB, 1000 teams would be 25 TB, about $375 a month in R2.

## 9. Slices

Each slice ships on its own, keeps the parity suite green on SQLite and Postgres, and leaves
self-hosters working.

**Already done:** `GET /api/server` and the two-step "Connect to a host" (`http/ServerInfo.kt`).

1. **A second person in today's team.** TEAMS.md slice 1, re-shaped by this design: migration 055
   adds `teams` (one row, the home team), `users` (accounts), `memberships`, `invites`, `auth_codes`
   and the `user_id` columns. Roles owner, admin, member. Invite by link, email and password
   sign-in, remove a person, attribution ("Sam replied"). Follows and the bell per person
   (section 6). Self-hosters get teams of people; nothing hosted yet.
2. **Run on two dynos.** Still one team. First the pool: transactions in the coroutine context,
   `team.write` and `team.read`, the lints. Then jobs in `job_runs`; derive claims; migrations in
   the release phase; security limits in Postgres. Then the `events` table with `LISTEN/NOTIFY`,
   events written inside the write, effects claimed once, Live Activity state in rows, revocation by
   event, `private` media caching. Proven by running the parity suite against two server processes sharing
   one Postgres. Pays off at once: the live server scales to two dynos, deploys stop dropping
   everyone, and restarts stop forcing a resync.
3. **`team_id` everywhere, switched off.** Migration adding the column with the home team as
   default; unique rules and links swapped; every query scoped; the lint; the two-team leak suite;
   media dedupe per team; settings per team. There is still no way to make a second team, so this
   ships dark. A follow-up migration drops the Postgres default and turns on row security.
4. **Many teams.** `TEAM_DOMAIN` routing, per-team addresses, the `__Host-` cookie, per-team OAuth,
   the `team` key on alerts, the admin API, seat and storage limits, fair derive, per-team rate
   limits, wildcard TLS. The control plane (private) can now create teams.
5. **One login, several teams.** Account sign-in on the bare address with tickets; the app's
   "Log in" (no longer behind a flag) and a team switcher; `export --team`.
6. **Later:** mail (invites and codes by email, TEAMS.md slice 4), passkeys on the hosted domain,
   per-project access, a viewer role if wanted.

## 10. Risks

1. **A missed filter shows one team another team's data.** About 326 SQL call sites today, plus
   media keys, dedupe, events, pushes and links. The lint, the two-team suite and row security each
   catch what the others miss; slice 3 ships dark until all three pass.
2. **A pool and many dynos change timing.** Today every write happens one at a time, in one
   process. With a pool and several dynos: agents missing messages (seq order), double deliveries,
   duplicate pushes, waits that miss an answer. The per-team write lock keeps today's order within a
   team; the parity suite only tests one process, so slice 2 adds a two-process run of it.
3. **One team slows everyone.** Shared Postgres, shared ffmpeg, shared bandwidth through the dynos.
   Per-team limits and fair derive help; a heavy team may need its own worker or an upgrade to a
   bigger database sooner than the cost table says.

Smaller ones: wildcard TLS on Heroku needs Cloudflare or a hand-renewed certificate; the iOS app's
contract gains add-only keys (`team`, prefixed thread ids) and must ship before teams do; deleting a
team must also delete its bucket prefix and its backups age out on Heroku's schedule.

## 11. Open questions for the owner

1. **Team addresses:** `<team>.feed.wildware.dev`, with Cloudflare in front for the wildcard
   certificate (about $10 a month)? The alternative, one address and the team found from the key
   alone, saves that but makes OAuth and the web app's cookie harder.
2. **Several teams per login in the app from day one** (a team switcher), or one team per sign-in
   at first and the switcher later?
3. **Outside contributions:** ask contributors to sign an agreement letting Wildware relicense, or
   accept that the server stays AGPL-only for good?
4. **Leaving and lapsing:** how long does a cancelled or unpaid team stay read-only before it is
   deleted? 30 days?
5. **Trial:** a free trial of the Team plan, and if so how long, and with what limits?
