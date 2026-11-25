# Team accounts: design

Status: proposal. Nothing here is built yet.

**Updated 2026-11-19 after the owner's decisions.** Hosting is now many teams on one shared,
stateless server, designed in [HOSTING.md](HOSTING.md). That changes this document in five places,
each marked "(changed)": one server holds many teams; a login is an account with a membership in
each team; people are alerted through what they follow; there is no viewer role for now; and the
migrations are numbered from 055. Where the two disagree, HOSTING.md wins.

Today a server has exactly one person, "the owner". This document turns that into a team: several
people, each with their own sign-in, role and phones, sharing one set of projects and agents. It
lives in the open-source server because self-hosters with teams want it too. Billing, signup and
provisioning for a hosted version live in a separate private service (the "control plane"). The
server only gives that service a small, generic admin surface (section 7).

The short version:

- **(changed) A server holds many teams.** A self-hosted server has one, the home team, and looks
  like today. The hosted one has many, each with a `team_id` on every row (HOSTING.md §2).
- **A team of one looks exactly like today.** Same wire format, same agent output, same sign-in.
  The existing parity suite keeps passing unchanged.
- **People stay `"human"` to agents.** Every person's messages keep `author: "human"`, so every
  agent and every running channel bridge keeps working. New fields say *which* human.
- **Device keys stay the session.** A device key now belongs to a user instead of "the owner".

## 1. The model

**User (changed: account plus membership).** An account is an email (unique, lower-cased), display
name and password hash (argon2id, as today). A membership puts an account in one team with a role
and an @mention handle. On a self-hosted server every account has one membership, so "user" below
means both. A membership is never hard-deleted: removing someone sets `removed_at` and keeps the
row, so "Alex answered this" still reads right a year later. See HOSTING.md §5.

**Roles (changed: no viewer for now).** One role per membership, for the whole team. The viewer
column below is kept for later; it is not built.

| Can… | owner | admin | member | viewer |
|---|---|---|---|---|
| Read the feed in their projects | yes | yes | yes | yes |
| Post, reply, react, answer requests, tag, set fields, pin, set status | yes | yes | yes | no |
| Create, edit, archive projects | yes | yes | no | no |
| Invite and remove people; change roles below admin | yes | yes | no | no |
| Mint, rename, revoke agent tokens | yes | yes | no | no |
| Make or remove owners and admins | yes | no | no | no |
| Sign out their own devices | yes | yes | yes | yes |
| Sign out someone else's devices | yes | yes (not an owner's) | no | no |

**Project access (deferred).** For now everyone in a team sees every project; what reaches
each person is decided by what they follow (section 4). Chosen projects, as below, come later.
Each user either sees **all projects** (the default, and always true for owners
and admins) or **chosen projects** (a list). A new project an agent creates is visible to
all-projects users only, until an admin adds it to someone's list. Cards with no project are
visible to all-projects users only.

**Sign-in methods.**

- **Email and password.** Always available. The default.
- **Email code.** Only when the server has mail set up (section 4). The server emails a
  6-digit code; the person types it into the app. A code, not a link, because the email is often
  read on a laptop while the app is on a phone, and because a self-hosted address cannot carry
  universal links.
- **Passkeys.** Later, hosted only (section 11). A passkey is tied to a domain the app must list
  in its own entitlements, which a self-hosted address can never be.
- **(changed) Account sign-in with a ticket.** Hosted only. Accounts live in the same database as
  the teams, so the hosted "Log in" signs in on `feed.wildware.dev` itself and gets a short-lived
  ticket that each team's address trades for a device key (HOSTING.md §5). The signed grants from
  the control plane that this section used to describe are dropped.

Without mail, nothing breaks: invites and password resets become links an admin copies and sends
however they like (chat, text message).

## 2. Migration: one owner becomes a team of one

Nothing is copied or rewritten in bulk, and every key keeps working.

1. Migration 055 (changed from 048, which is taken) adds the new tables and nullable columns
   (section 9), and the `teams` table with its one row, the home team (HOSTING.md §2). It touches no
   existing data.
2. On boot, if `users` is empty and `ADMIN_PASSWORD_HASH` is set, the server creates the
   **founding owner**: role owner, name "Owner", no email, `founder = 1`, and no password hash of its
   own (it keeps using `ADMIN_PASSWORD_HASH` until the person sets a password in the app).
3. In the same transaction it fills `user_id` on every existing `owner_devices` row and on every
   row whose author is `"human"` (messages, reactions, card tags, card fields, media, app views)
   and `decided_by` on every answered request. These are single `UPDATE … WHERE user_id IS NULL`
   statements, one per table, run once.
4. **Old device keys keep working**: the key's row now has a `user_id`, and that user is an owner.
5. **Old agent tokens keep working**: agents are team-wide and their table does not change.
6. **Old apps keep signing in**: `POST /api/devices/sign-in` with `{password, name}` and no email
   checks the founding owner's password. Once the founder has set an email, the old form still
   works for them until `ADMIN_PASSWORD_HASH` is removed from the config.

`ADMIN_PASSWORD_HASH` stops being required at boot. The server refuses to start only when there is
no user, no `ADMIN_PASSWORD_HASH`, and no way to make one (it then prints the `create-owner`
command to run).

**One-way door.** The TypeScript server refuses any database with a migration it does not know.
It was retired at the move to the Kotlin server, so this closes no door that is still open.

## 3. Attribution: who did this

Storage: author strings stay as they are (`"human"` or `"agent:<id>"`). Each human row also gets
`user_id`. Requests get `decided_by` (a user id).

**To the apps.** Rows carry `userId`. The snapshot carries a `people` list
(`[{id, name, role, removed}]`), so rows stay small and the app joins them. Stream events that
carry `author` or `actor` gain `userId`. The app shows the name: "Sam replied", "Answered by Sam",
reaction tooltips list names. A removed person shows as "Sam (left)".

**To agents.** Rule: never change an existing key's meaning; only add keys. On a team of one, add
nothing at all, so output stays byte-identical to today.

| Where | Today | Team of two or more |
|---|---|---|
| `get_messages`, `post_message` results, message frames | `author: "human"`, no `author_name` | same `author`, plus `author_name` (the person's name) and `author_user_id` |
| `in_reply_to` | `author: "human"` | adds `author_name` |
| `request_input` answered (the retired `await_request`, the answer event, `get_messages({request_id})`) | summary `Your owner answered: …` | summary `Sam answered: …`; structured content adds `answered_by: {user_id, name}` |
| Agent stream `answer` frame | no actor | adds `answered_by` |
| Agent stream `reaction` frame | no actor | adds `by: {user_id, name}` |
| `list_work`, card reads | `author` | adds `author_name` for people |

Why this is safe: the channel bridge already running in every session (and never updated until
the session restarts) decides "is this the owner?" with `author === 'human'`, and only reads
`author_name` for agents. Extra keys are ignored. So old bridges call every person "owner", which
is the right behaviour for authority, and new bridges can say `from="owner" by="Sam"`.

**What agents are told.** The MCP instructions change "your owner" to "your owner (any person on
your team)". Any member's answer counts as the team's answer. If two people answer the same request, the second gets `409 conflict` with "Sam already
answered this", because answering is already one conditional `UPDATE … WHERE state = 'pending'`.

## 4. Notifications

**(changed) People hear what they follow.** The owner decided: each person subscribes to the
groups, channels and threads relevant to them, and is alerted when mentioned; a teammate's message
reaches others only through those follows. This uses the follow system on master (migrations 052
and 053, `domain/Subscriptions.kt`) per person, subscriber `member:<membership id>`. The full rule
is in HOSTING.md §6; it replaces the "can see the project" rule below, and answers what were open
questions 3 and 4.

**Devices belong to users.** `owner_devices.user_id` says whose phone it is. The per-device
settings that exist today keep working as they are: alert projects (`alert_project_ids`) and alert
prefs (`alert_prefs`).

**Who gets an alert about a project.** A device gets an APNs alert when all of these hold:

1. its user follows what the event is about, or was mentioned (HOSTING.md §6);
2. its alert projects include the project (unchanged rule);
3. its alert prefs allow the type, level and priority (unchanged rule);
4. its user did not cause the event (Sam does not get a push for Sam's own reply).

Requests reach whoever follows them by the same rule.

**Live Activities and the summary.** `GET /api/summary` and the Live Activity content are worked
out per user, over the projects that user has not muted. On a team of one, and for every all-projects
user, that is exactly today's summary. When anyone answers a request, every device's Live Activity
updates as today. The summary gains `suspended` (section 7) and nothing else, so the iOS contract
stays add-only.

**The bell.** `notifications.seen_at` is one value for the whole server today. (Changed.) The bell gets a row per person who hears the event, each with its own `seen_at`, so
each person's bell is their own (HOSTING.md §6). No separate read cursor.

**Mail (optional).** `SMTP_URL` (`smtp://` or `smtps://`, user and password in the URL) and
`MAIL_FROM`. Unset means no mail: email codes are switched off and invites are links only. The
public `GET /api/server` (section 8) tells the app what to offer.

## 5. Invites and lifecycle

**Invite.** An admin or owner creates an invite with a role, an optional email, and project access.
The server returns a link `<the team's address>/join/<token>` (`PUBLIC_BASE_URL` on a self-hosted server) (43-char random token, stored as an HMAC
like device keys). Valid 7 days, single use, revocable. With mail set up and an email given, the
server also emails it.

The link opens a tiny static page (no web app) with "Open in Wildware Feed" (an
`agents://invite?server=…&token=…` link, on the URL scheme the apps already register) and the
token to paste. The app's sign-in screen has
"I have an invite": paste the link, pick a name and password, name the device. The accept call
creates the user and a device key in one step, and returns the same body as sign-in.

An invite with an email can only be accepted with that email. An invite without one can be
accepted by anyone holding the link; the admin sees who accepted.

**Remove a member.** Owner or admin. Sets `removed_at`, deletes every device of that user (their
keys stop working on the next request), closes their open streams, clears their APNs and Live
Activity tokens, and frees their email for a new invite. Their past messages and answers stay,
labelled with their name.

**Leave.** Anyone can leave, which is removing yourself, except the last owner.

**Last-owner protection.** The team must always have at least one active owner with a way in:

- the last owner cannot be removed, demoted, or leave;
- admins cannot change or remove owners;
- nobody changes their own role.

If every owner is locked out, the person with server access runs
`feed-server create-owner --email … --name …` (or `reset-password --email …`). Server access is the
final authority, as it is today.

**Passwords.** `PATCH /api/me` changes name, email or password (current password required). After a
password change the app offers "sign out my other devices". Without mail, an admin makes a
one-time reset link for a member (24 hours); the owner's own recovery is the CLI.

## 6. Agent tokens

Still one pool per team: any agent token can post to any project, as today. Owners and admins
mint, rename and revoke them from the app (`POST /api/agents`, `PATCH /api/agents/:id`,
`DELETE /api/agents/:id`); the token is shown once, as the CLI does. The CLI commands stay.
`agents.created_by` records who minted each one. Members see the agent list (name,
last seen, online) but not mint controls. Revoking closes the agent's streams, as today.

Limiting an agent to some projects is out of scope.

## 7. Admin surface for the control plane

Small and generic, so a self-hoster can use it too. No billing and no prices. The one thing it knows
of plans is which one a hosted team is on, `free` or `team`, and the free plan's fixed limits (2
people, 3 agents, 500 MB of files, 30 days of history shown): HOSTING.md §7, "The free plan". A
self-hosted server's team has no plan and no plan limits.

**(changed)** With many teams per server, the admin API works per team: create a team, read its
usage, set its limits, suspend it, delete it, export it. HOSTING.md §7 has the full list. The CLI,
the token rules and suspension below still hold, per team. The sign-in grants are dropped
(section 1, "Sign-in methods").

**CLI, `feed-server create-owner --email <e> --name <n>`.** Creates an owner (or fails if that
email exists). With `--password-stdin` it sets the password; otherwise it prints a one-time setup
link valid 24 hours. A hosting service runs it once after provisioning (on Heroku:
`heroku run feed-server create-owner …`). Also `reset-password --email`, `list-users`.

**Admin key (changed).** Requests are signed with a key from `ADMIN_API_KEYS`, not sent with a
bearer token; HOSTING.md §7 has the exact rules. Unset means every `/admin/*` route answers 404;
its own rate limit; never accepted on owner or agent routes, and no other credential is accepted here.

**`GET /admin/usage`** (read-only):

```json
{"seats":3,"pendingInvites":1,"devices":5,"agents":4,"projects":{"active":7,"archived":2},
 "storageBytes":184467440,"databaseBytes":52428800,"lastActivityAt":"2026-11-18T09:00:00.000Z",
 "suspended":false}
```

Seats = users not removed. Storage = media plus derivatives bytes. The control plane decides what
any of it costs.

**`PUT /admin/suspended`** `{"suspended":true,"reason":"…"}`. Also `feed-server suspend` and
`unsuspend` for self-hosters. While suspended the server is **read-only**:

- sign-in and every read still work, so people can see and export their data;
- owner writes answer `403 {"error":"suspended","message":"<reason>"}`;
- MCP write tools fail with a tool error code `suspended`; `heartbeat`, `register_session` and
  `end_session` still work, so agents learn the state instead of crashing;
- uploads are refused; the summary carries `"suspended": true` so the app shows a banner;
- pushes about new activity stop, because none can happen.

The flag lives in the database, so it survives restarts.

## 8. App changes

Shared code (`composeApp` common, `shared`) does most of it, on phone and desktop alike.

**Shared (phone and desktop).**

- Sign-in: see "The sign-in screen" below.
- Settings, "You": name, email, password, sign out other devices, leave team.
- Settings, "Team" (owner and admin only): people with their role, pending invites
  with copy and revoke, invite sheet (role, email), remove person.
- Settings, "Agents": list for everyone; mint, rename and revoke for owners and admins.
- Settings, "Devices": your devices; admins can also see and sign out others'.
- Cards and threads: "who did this" labels on human replies, answered requests ("Answered by
  Sam"), reactions (names on long-press or hover), tags and fields set by a person.
- A read-only banner while the server is suspended.
- Team of one: none of the labels show, so the feed looks as it does today.

**The sign-in screen.** Two clear ways in, on every platform, plus the desktop's own server:

- **Log in** (built): the hosted service. Email, then a 6-digit code emailed to it, then the team
  (a list when the email is in several; one open team signs straight in). The account service hands
  the app a one-time ticket (a minute, once, that team's address only); the app trades it for an
  ordinary device key on the team's address and opens the feed (HOSTING.md §5). The account
  service's own sign-in lives in memory only and is ended once the key is in hand. To open another
  team, "Add a team" (below) logs in again; a team already on the device just opens. Not in the web app: a browser is already
  at its team's address, and the account site signs it in there itself. The service's address is
  set at build time (`-Pfeed.accountUrl=…` or `FEED_ACCOUNT_URL`; default
  `https://account.feed.wildware.dev`; empty for no "Log in").
- **Connect to a host**: someone's own server, in two steps.
  1. *Address.* The app asks the public `GET /api/server` and checks it is a Wildware Feed server:
     `{"product": "wildware-feed", "version": "…", "signIn": ["password"], "team": false}`, where
     `signIn` lists what the server offers (`password` today, the owner's password;
     `email-password` and `email-code` as the slices land) and `team` says whether it has
     people. Anything else gets a friendly "That address isn't a Wildware Feed server" (with "check
     the address, or that it starts with https://"), and an unreachable one "Couldn't reach that
     address". A server too old to have `/api/server` but answering `/api/summary` with a 401 JSON
     body is treated as `{"signIn": ["password"]}`.
  2. *That host's login.* What `signIn` offers: email and password (and "Email me a code" when
     offered) on a team server, today's owner password otherwise; the device name; and "I have
     an invite".
- **This computer** (desktop only): the server inside the app, as today.
- **Try a demo** stays, everywhere.

`GET /api/server` is public and says nothing a stranger can use: no team name, no counts, no
user list. It lands before slice 1 (it is useful today), so the two-step screen ships first.

**Many teams on one device.** One device can be signed in to several teams at once, each with
its own device key on its own server:

- *Where they are kept.* `TeamList` holds every team's sign-in, name and last use, somewhere as
  private as the single key always was: a 0600 `teams.json` on desktop, `teams.bin` sealed by the
  Android Keystore, a Keychain item (`dev.wildware.agents.teams`) on the iPhone, local storage in
  the web app (no key there: the cookie is the key). The open team's key also stays where it
  always was (`SessionStore`), so the iPhone's widgets, share sheet, Siri, alerts and watch keep
  reading it. A device from before teams becomes a list of one, keeping its files.
- *Each team's own files.* Read marks, the feed cache, folded cards and the post target live in
  `teams/<key>/`; the first team keeps the files it had. The outbox the app sends from is always
  the open team's: switching parks what waits in `teams/<key>/outbox` and brings the next team's
  back, so nothing queued for one team is ever sent to another.
- *The switcher.* The open team sits at the top of the sidebar and the phone drawer; tapped, every
  team folds out with how many questions wait in it, "Add a team" (the sign-in screen again, with
  "Back") and "Sign out of <team>" (which ends that device on its server, as sign-out always did).
  The command bar lists "Switch to <team>" and "Add a team"; Ctrl/⌘+Shift+1–9 opens a team.
- *Load.* Only the open team keeps the live feed. Each other team is asked for `GET /api/summary`
  once a minute and when the app comes back to the front: its waiting count for the badge, and new
  questions for the desktop's and Android's own notices (the iPhone gets those as pushes, since
  each team's server keeps the phone's push token). A 401 there forgets that team.
- *Alerts.* A notice from a team that isn't open carries its address (`AppNavigator.open(…,
  server)` / `openUrl`); the app opens that team, then the card.
- *The web app.* One team per tab: each address has its own cookie, so "Add a team" opens the
  other team's address in a new tab, and the switcher offers it there again.

**iOS (native Swift parts).** Notification content and service extensions: "Sam replied" text
from the alert payload, which gains an `actorName` key. Widgets and Live Activity: no change in
shape; the summary is already per user. Watch: shows "Answered by Sam" on settled requests. Share
extension: posts as the signed-in user, nothing to change. The app routes `agents://invite` to the
invite screen (the `agents` scheme is already registered).

**Android.** Route `agents://invite` (add the `invite` host beside `open` in the manifest). Android
has no background push yet; its in-app notifications use the same "Sam replied" text and skip the
person's own actions.

**Desktop.** Accept a pasted invite link (no scheme handler needed). "This computer" (the desktop app hosting its own server)
is a team of one by default; inviting people works only when the server is reachable from their
devices, so the invite sheet warns when `PUBLIC_BASE_URL` is a loopback address.

## 9. Database

**(changed)** 048 to 053 were taken by other work, and 054 by the secrets PR (#153), so this work
starts at **055**, numbered in order with HOSTING.md's migrations and checked against master again
just before each lands. Each one ships as a pair under the
same version and name: `server/src/main/resources/migrations/055-people.sql` for SQLite and
`server/src/main/resources/migrations-pg/055-people.sql` for Postgres, each listed with its own
checksum in its folder's `migrations.json`. Both are add-only: new tables, new nullable columns, and
index swaps. No table rebuild. The `migrate` (SQLite to Postgres) and `export` (Postgres to SQLite)
tools learn the new tables.

**055-people** (slice 1):

- `teams`: one row, the home team (HOSTING.md §2).
- `users` (accounts): `id`, `email` (unique), `name`, `password_hash`, `created_at`.
- `memberships`: `id`, `team_id`, `user_id`, `role` (CHECK owner, admin, member), `handle`,
  `founder` (0/1, at most one per team), `created_at`, `removed_at`. The `user_id` columns below
  point at a membership.
- `invites`: `id`, `team_id`, `email`, `role`, `all_projects`, `project_ids` (JSON), `token_hash` (unique),
  `created_by`, `created_at`, `expires_at`, `accepted_at`, `accepted_by`, `revoked_at`.
- `auth_codes`: `id`, `user_id` (the account, not a membership: a password reset is per account), `purpose` (email sign-in, password reset, setup), `code_hash`,
  `created_at`, `expires_at`, `used_at`, `attempts`. One table for every one-time secret.
- (changed) No `server_state` row: the team's name and its suspended flag live on `teams`.
- New nullable columns: `owner_devices.user_id`, `messages.user_id`, `approvals.decided_by`,
  `reactions.user_id`, `card_tags.user_id`, `card_fields.user_id`, `media.user_id`,
  `app_views.user_id`, `agents.created_by`.
- Reactions: today one person can put each emoji on a card once, keyed on
  `(card, actor, emoji)`. Two people are both `actor = "human"`, so the four unique indexes are
  replaced by `(card, actor, coalesce(user_id, ''), emoji)`.

**Later, with per-project access** (deferred): `project_access`: `user_id`, `project_id`,
`created_at`; unique pair.

**Also in 055:** `notifications.member_id`, with the bell's unique rules changed to one row per
person per card; existing rows go to the founder (HOSTING.md §6).

## 10. Security

**Every route checks access, not just sign-in.** One function, `authorize(user, action, project)`,
sits in the `http` layer, and every owner route declares its action. Deny is the default: a route
without a declared action fails a test (below). Things that need a project check, beyond the
obvious feed routes:

- the owner event stream: each event is filtered by the listener's projects before it is written;
- snapshots, `GET /api/messages`, the summary, the bell and project tag lists;
- media (`/media/…`) and apps (`/apps/…`): check the project of the card the file or view hangs on;
- request answers, reactions, tags, fields, pins and status on a card in a hidden project:
  `404 not_found`, the same as a card that does not exist, so access is not revealed;
- push and Live Activity selection (section 4).

Agents are not affected: agent tokens see every project, as today.

**Rate limits** (changed: the sign-in, invite and code limits are kept in Postgres, so they hold
across several web servers; HOSTING.md §3):

| What | Key | Limit |
|---|---|---|
| Password sign-in | client address | 5 failures / 15 min (today's) |
| Password sign-in | email | 10 failures / hour |
| Email code request | email, and client address | 3 / hour, 10 / hour |
| Email code check | the code | 5 tries, then the code is dead |
| Invite create | user | 50 / day |
| Invite accept, `/join/…` | client address | 20 / hour |
| Admin API | admin token | 60 / min |

Unknown emails get the same answer and the same delay as wrong passwords, so the sign-in screen
does not reveal who has an account.

**Sessions.** Device keys stay as they are: 43 random characters, stored only as an HMAC, revocable,
no expiry. The user's role and project access are read on every request, never baked into the
key, so a role change or removal takes effect on the next request. Deleting a device, or removing a
user, also closes that device's open streams.

**Secrets.** Invite tokens, email codes and reset links are stored as HMACs with distinct prefixes
(`invite:`, `code:`), single use, short-lived. Email codes are compared in constant time.

**Tests the parity suite needs.**

- The whole existing suite runs unchanged on a migrated team-of-one database and must still pass,
  against SQLite and Postgres. That is the proof that old apps and agents are unaffected.
- A migration test: open a database fixture at 047, boot, check the founder exists, an old device
  key works, an old agent token works, legacy sign-in works, every human row has a `user_id`.
- An access matrix (Kotlin only): every owner route, called as owner, admin, member and a removed
  user. The route list is read from the router, so a new
  route with no rule fails the build.
- The two-team leak suite (HOSTING.md §2): nothing from another team ever reaches an answer, a
  stream, a media URL or a push.
- Agent output: team of one is byte-identical; team of two adds exactly the documented keys.
- Last-owner rules, invite expiry and single use, suspension (writes refused, reads fine).

## 11. Phasing

**(changed)** The slices now live in HOSTING.md §9, which orders this document's work with the
multi-team work. In short:

1. **A second person in today's team**: migration 055, accounts and memberships, roles owner, admin
   and member, invite by link, email and password sign-in, attribution, follows and the bell per
   person, `create-owner` and `reset-password` on the CLI. Everyone sees all projects.
2. **Run on two dynos**, still one team.
3. **`team_id` everywhere**, shipped dark.
4. **Many teams**: per-team addresses, the admin API, limits, suspension, agent tokens minted in
   the app.
5. **One login, several teams**: the hosted "Log in".
6. **Later**: mail (`SMTP_URL`, invite emails, email codes), passkeys on the hosted domain,
   per-project access, a viewer role if wanted.

`GET /api/server` and the two-step "Connect to a host" are already on master.

## 12. Open questions for the owner

All five are settled:

1. ~~Is the TypeScript server retired before slice 1 ships?~~ Yes, at the move to the Kotlin server.
2. ~~One server per team, or many teams on one server?~~ Many teams on one stateless server
   (HOSTING.md).
3. ~~Who does a request alert?~~ Whoever follows it, or is mentioned (HOSTING.md §6).
4. ~~Does a person's message notify the others?~~ Only through what they follow.
5. ~~Viewers?~~ Not for now: owner, admin, member.

New questions are in HOSTING.md §11.
