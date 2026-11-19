# Hosting many teams on one server: design

Status: proposal. Nothing here is built yet.

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
`projects.slug`, `project_groups.slug`, `embed_types.slug`, `apps.uri`, `server_settings.key`, and
`oauth_clients` registration. Two teams can both have a project called `web`. Rules on random
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
4. **Row-level security on Postgres**, as a backstop in production. Each team-owned table gets
   `ENABLE` and `FORCE ROW LEVEL SECURITY` and a policy
   `team_id = current_setting('feed.team_id', true)`. A connection taken from the pool sets
   `feed.team_id` for its request and resets it when returned. A forgotten filter then returns
   nothing instead of another team's rows; an insert into the wrong team fails. Background jobs that
   work across teams use a separate setting the policy also accepts (`feed.all_teams = on`), set
   only by the job runner. `FORCE` matters: Heroku's database user owns the tables, and owners skip
   row security without it. SQLite has no such feature; it never holds more than one team.

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

Old device keys, agent tokens and OAuth tokens keep working: their rows are now in the home team.

## 3. Statelessness: everything the server holds in memory today

Today one process holds a lot that a second process would not see. Heroku also restarts every dyno
at least once a day, and the server forgets all of it then. Here is all of it, found by grepping
for maps, timers, executors and `@Volatile`, and where each piece goes.

| What | Where today | What goes wrong with two dynos | Where it goes |
|---|---|---|---|
| **The one database connection.** All requests share one JDBC connection behind a lock. | `db/Database.kt` (`connection`, `lock`) | Every request on a dyno waits in one line. | A pool of connections (HikariCP) on Postgres. SQLite keeps one connection. The "never publish while holding the lock" check (`App.kt`, `bus.beforePublish`) becomes "never publish inside a transaction". |
| **The event bus**: seq counter, boot id, a ring of the last 500 events, the listeners. | `events/Bus.kt` | An event published on dyno 1 never reaches a stream on dyno 2. Every restart changes the boot id, so every client resyncs. | An `events` table (team, seq, type, payload, time) plus Postgres `LISTEN/NOTIFY`. See "The event bus" below. |
| **Waits on the bus**: `await_request`/`request_input` holding for an answer, an agent's pairing poll. | `domain/Requests.kt` `waitForSettle`, `domain/Pairings.kt` `await` | The answer lands on another dyno; the wait runs to its timeout. | Unchanged code, on the new bus: they hear events from every dyno. |
| **Owner and agent streams.** | `http/StreamRoutes.kt`, `http/AgentStream.kt` | Same as above. | The new bus. Each stream's own "already sent" set (`AgentStream.kt`, `sent`) is per connection and stays. |
| **Revocation checks**: each open stream asks the database every 5 s whether its key still works. | `http/Sse.kt` (`checkEveryMs = 5_000`) | Not wrong, but at 1000 teams × 5 streams that is 1000 queries a second doing nothing. | Revoking a key publishes `device.revoked` / `agent.revoked`; streams close on it. The poll stays as a slow backstop (60 s). |
| **Side effects of events**: the bell recorder, iOS alerts, Live Activity updates, all bus listeners. | `domain/Notifications.kt` `startRecorder`, `push/Apns.kt` `start`, `push/LiveActivity.kt` `start` | If every dyno hears every event, every dyno sends the push: duplicates. | They stop being listeners. The `events` table is also a work queue: one dyno claims each event (`FOR UPDATE SKIP LOCKED`) and runs its effects once. If that dyno dies first, another claims it later. |
| **Live Activity memory**: `lastSent` and `startedAt` maps, `wasBusy`, `idleSince`, `newRequestId`, the 2 s coalescing timer and the 1 min refresh. | `push/LiveActivity.kt` | Two dynos push the same update, or neither knows what was sent; every restart forgets and resends. | A row per device (`live_activity_sent_hash`, `push_to_start_at`) and per team (`la_busy`, `la_idle_since`, `la_due_at`). An update is sent only if `UPDATE … SET sent_hash = ? WHERE sent_hash IS DISTINCT FROM ?` changed a row, so two dynos can't both send it. The timers become one job (below) that runs due teams. |
| **iOS sender threads and the signed APNs token.** | `push/Apns.kt` (`sender`, `ProviderToken.cached`) | Nothing: each dyno signs its own token every 50 minutes, which Apple allows. | Stays. |
| **Scheduled jobs**: presence sweep (1 min), request sweep (30 s), pairing sweep (30 s), OAuth sweep (10 min), media derive (1 s), media sweep (15 min), media retention (10 min). | `App.kt` `startJobs`, `every` | Every dyno runs every job. Retention deletes files twice; presence announces twice. | Each job takes a Postgres advisory lock (`pg_try_advisory_lock`) for its run; whoever doesn't get it skips. Sweeps then work across all teams in one statement, and publish each row's event into its own team. |
| **Media derive queue**: a set of ids being worked on. | `media/Derive.kt` (`queued`, `pool`) | Two dynos transcode the same video. | Claim in the database: `UPDATE media SET derive_claimed_at = now … WHERE id = (SELECT … FOR UPDATE SKIP LOCKED)`. A claim older than 15 minutes is taken as dead and retried. |
| **Rate limits**: ten sliding-window limiters (sign-in, MCP, agent stream, upload, pairing start and poll, three OAuth, agent media). | `App.kt`, `auth/SlidingLimiter.kt` | Each dyno counts alone: N dynos let N times as much through. For sign-in lockout that is a security hole. | Split. **Security limits** (sign-in, pairing start, OAuth register, invite accept) move to a Postgres counter table, one fixed window per key: low traffic, must be exact. **Throughput limits** (MCP, streams, uploads) stay in memory per dyno: they are about fairness, and a little loose is fine. Per-team limits are added (section 7). |
| **Presence and heartbeats.** | `domain/Sessions.kt` | Nothing: already in the `sessions` table. | Stays. Only its sweep needs the job lock. |
| **Caches**: S3 object sizes (LRU, 20,000 entries). | `media/MediaStore.kt` (`S3MediaStore.sizes`) | Nothing: objects never change once written. | Stays. Keys gain a team prefix (below). |
| **Local disk**: upload temp files, S3 fetch area, ffmpeg work files under `DATA_DIR/tmp`; on-disk media store. | `media/Uploads.kt` (`tempRoot`, `workRoot`), `App.kt` (`tmp/fetch`), `media/MediaStore.kt` (`LocalMediaStore`) | Temp files: nothing, each lives for one request or one job. The on-disk store: a second dyno can't see the files. | Temp stays. Multi-team mode refuses to start without `MEDIA_STORE=s3`. |
| **Media dedupe**: an upload whose bytes match an earlier one links to it. | `media/Uploads.kt` `place` (`WHERE sha256 = ?`) | Across teams this is a leak: the answer's `deduped` flag tells team A whether team B holds a given file. | Look for twins within the team only. |
| **Media keys**: `<id[0:2]>/<id>/<name>` in the bucket. | `media/Uploads.kt` `originalKey`, `media/MediaStore.kt` | Can't list, measure or delete one team's files. | New teams' media go under `t/<team id>/…`; the home team keeps today's keys, so self-hosters move nothing. |
| **Settings**: retention days, and the one bell "seen" time. | `media/Retention.kt` (`server_settings`), `domain/Notifications.kt` | One value for the whole server. | Per team (`server_settings.team_id`), and the bell's read cursor per person ([TEAMS.md](TEAMS.md)). |
| **Id order**: ULIDs are "each the last plus one" within a millisecond, per process. | `db/Ulid.kt` | Two dynos' ids in one millisecond aren't in order. | Nothing to do: no query orders by id (they order by `seq`). Write it down as a rule. |
| **Migrations at boot**, with no lock. | `db/Database.kt` (`Migrations.run`, `PostgresMigrations.run`) | Two dynos booting together both try to migrate. | Run them once per release in Heroku's release phase, and take an advisory lock in `run` for everyone else. |
| **The owner's password** in config. | `Config.kt` (`ADMIN_PASSWORD_HASH`), `http/AuthRoutes.kt`, `http/OAuthRoutes.kt` | One password for the whole server. | Accounts in the database ([TEAMS.md](TEAMS.md), section 5 here). |
| **SQLite-only code**: `Dialect.SQLITE` branches, `greatest`, `asciiFold`, the SQLite twin migrations. | `db/Database.kt`, `media/Uploads.kt` (`logoIds`, `embedStrings`), `media/Retention.kt` (`logoIds`) | Nothing: hosted runs Postgres only. | Stays for self-hosting and the desktop app, always one team. Multi-team mode refuses to start on SQLite. Every new migration still ships as a pair. |
| **Static things**: the MCP tool list, the web app's files, the server version, the crash-report switch. | `mcp/Tools.kt`, `http/WebApp.kt`, `http/ServerInfo.kt`, `CrashReports.kt` | Nothing. | Stay. |

### The event bus

The bus is the biggest change, so here is how it works.

- **Publishing** writes a row into `events` and calls `NOTIFY feed_events, '<team>:<seq>'`, in one
  short transaction, right after the write it describes, as today. The seq comes from
  `UPDATE teams SET last_seq = last_seq + 1 … RETURNING last_seq` in the same transaction. That row
  lock makes a team's events commit in seq order, so a reader never sees seq 12 before seq 11.
  Different teams don't wait for each other.
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
  iOS app's contract holds) and a `thread-id` prefixed with the team slug, because two teams can
  both have a project called `web`.
- **The bare address** `feed.wildware.dev` serves the account sign-in (section 5), `/api/server`
  (saying `"teams": true`), and the admin API for the control plane (section 7). Nothing else.

**TLS for wildcard addresses** is the practical catch. Heroku's free certificates (ACM) do not
cover wildcards. Either put Cloudflare in front (it already holds the bucket; a certificate for
`*.feed.wildware.dev` needs its paid Advanced Certificate Manager, about $10 a month, to verify), or
upload a wildcard certificate to Heroku by hand and renew it. Open question 1.

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
- **The bell** gets a row per person who hears the event (`notifications.member_id`), so each
  person's bell is their own.
- **Phones** then apply their own preferences (`alert_project_ids`, `alert_prefs`), as now.
- **Live Activity** shows the requests in channels the person has not muted.

## 7. Limits, fairness, and the admin API

### Per-team limits

Stored on the `teams` row, set by the control plane, enforced by the server:

| Limit | Team plan | How it is enforced |
|---|---|---|
| Logins (seats) | 5 | Active memberships plus pending invites. The sixth invite is refused with `seat_limit`. |
| Storage | 25 GB | Media and derivative bytes, kept as a running total on the team (`teams.storage_bytes`, changed on upload, derive and delete; corrected nightly from the bucket listing under the team's prefix). An upload that would pass the limit is refused at reserve time with `storage_full`. |
| Agents | no limit | Rate limited instead (below). |
| Suspended | – | Read-only, as TEAMS.md §7: reads and exports work, writes answer `403 suspended`. |

### Keeping one team from slowing the rest

- **Requests:** a per-team ceiling on top of the per-token one: 1,200 MCP calls a minute across all
  of a team's agents (in memory, per dyno, so loose; fine for fairness).
- **Open connections:** at most 50 streams and 50 held waits (`request_input`, pairing polls) per
  team per dyno. The 51st gets `429` with `Retry-After`.
- **Database:** `statement_timeout` of 10 s on the pool. A query that slow is a bug, not a workload.
- **Transcoding:** ffmpeg is the heaviest thing the server does (it is why a Basic dyno needs
  `FFMPEG_THREADS=1`, see `server/deploy/HEROKU.md`). The derive job takes the oldest pending media
  **per team in turn**, at most one at a time per team, so one team's 50 videos don't hold up
  everyone's thumbnails. Past ~100 teams it moves to its own `worker` dyno.
- **Bandwidth:** media is served through the server today. Heroku has a soft limit on bandwidth per
  app (2 TB a month, to verify). If that bites, serve large media by redirecting to short-lived
  signed bucket URLs instead.

### The admin API the control plane calls

On the bare address only, behind `ADMIN_API_TOKEN` (as TEAMS.md §7: unset means 404; constant-time
check; its own rate limit; no other credential accepted). It knows teams and numbers, never prices.

| Call | What it does |
|---|---|
| `POST /admin/teams` `{slug, name, owner: {email, name}, limits}` | Creates a team and its owner. Links an existing account by email, or makes one and returns a one-time password link. Takes an `Idempotency-Key` header, so a retried signup doesn't make two teams. |
| `GET /admin/teams/{id}/usage` | Seats, pending invites, agents, devices, projects, storage bytes, an estimate of database bytes, last activity, suspended. |
| `GET /admin/usage?since=` | The same for every team changed since a time, for billing runs. |
| `PUT /admin/teams/{id}/limits` | Seats and storage bytes. |
| `PUT /admin/teams/{id}/suspended` `{suspended, reason}` | Read-only on and off. |
| `PUT /admin/teams/{id}/slug` | Rename the address (old address answers with a redirect for 30 days). |
| `DELETE /admin/teams/{id}` | Marks the team for deletion. A job removes its rows in batches and its bucket prefix. A deleted team's slug can't be reused for 30 days. |
| `POST /admin/teams/{id}/export` | Starts an export: the existing `feed-server export` (Postgres to SQLite, `Export.kt`) gains `--team`, producing a file any self-hosted server can open. A hosted team can always leave. |

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
2. **Run on two dynos.** Still one team. A connection pool; jobs under advisory locks; derive claims
   in the database; migrations in the release phase; security limits in Postgres. Then the
   `events` table with `LISTEN/NOTIFY`, effects claimed from it once, Live Activity state in rows,
   revocation by event. Proven by running the parity suite against two server processes sharing
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
2. **Many dynos change timing.** Today everything happens in one process in one order. Across dynos:
   duplicate pushes, events in the wrong order, waits that miss an answer. The parity suite only
   tests one process, so slice 2 adds a two-process run of it.
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
