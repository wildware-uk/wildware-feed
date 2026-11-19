# Team accounts: design

Status: proposal. Nothing here is built yet.

Today a server has exactly one person, "the owner". This document turns that into a team: several
people, each with their own sign-in, role and phones, sharing one set of projects and agents. It
lives in the open-source server because self-hosters with teams want it too. Billing, signup and
provisioning for a hosted version live in a separate private service (the "control plane"). The
server only gives that service a small, generic admin surface (section 7).

The short version:

- **One server is one team.** No multi-tenancy: there is no `team_id` on every row.
- **A team of one looks exactly like today.** Same wire format, same agent output, same sign-in.
  The existing parity suite keeps passing unchanged.
- **People stay `"human"` to agents.** Every person's messages keep `author: "human"`, so every
  agent and every running channel bridge keeps working. New fields say *which* human.
- **Device keys stay the session.** A device key now belongs to a user instead of "the owner".

## 1. The model

**User.** Email (unique, lower-cased), display name, password hash (argon2id, as today), role, and
whether they see all projects. A user is never hard-deleted: removing someone sets `removed_at`
and keeps the row, so "Alex answered this" still reads right a year later.

**Roles.** One role per user, for the whole team.

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

**Project access.** Each user either sees **all projects** (the default, and always true for owners
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
- **A grant from the control plane.** Hosted only, slice 2 (section 7): the hosted "Log in" signs
  the person in on the control plane, which hands the app a one-time grant for their team's server.

Without mail, nothing breaks: invites and password resets become links an admin copies and sends
however they like (chat, text message).

## 2. Migration: one owner becomes a team of one

Nothing is copied or rewritten in bulk, and every key keeps working.

1. Migration 048 adds the new tables and nullable columns (section 9). It touches no existing data.
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
After 048 runs, it can no longer open the file. Ship teams only after the Kotlin cut-over, or port
048 to the TypeScript server first (open question 1).

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
| `await_request` / `request_input` answered | summary `Your owner answered: …` | summary `Sam answered: …`; structured content adds `answered_by: {user_id, name}` |
| Agent stream `answer` frame | no actor | adds `answered_by` |
| Agent stream `reaction` frame | no actor | adds `by: {user_id, name}` |
| `list_work`, card reads | `author` | adds `author_name` for people |

Why this is safe: the channel bridge already running in every session (and never updated until
the session restarts) decides "is this the owner?" with `author === 'human'`, and only reads
`author_name` for agents. Extra keys are ignored. So old bridges call every person "owner", which
is the right behaviour for authority, and new bridges can say `from="owner" by="Sam"`.

**What agents are told.** The MCP instructions change "your owner" to "your owner (any person on
your team)". Any member's answer counts as the team's answer. Viewers cannot answer, so that is
safe. If two people answer the same request, the second gets `409 conflict` with "Sam already
answered this", because answering is already one conditional `UPDATE … WHERE state = 'pending'`.

## 4. Notifications

**Devices belong to users.** `owner_devices.user_id` says whose phone it is. The per-device
settings that exist today keep working as they are: alert projects (`alert_project_ids`) and alert
prefs (`alert_prefs`).

**Who gets an alert about a project.** A device gets an APNs alert when all of these hold:

1. its user can see the project (section 1);
2. its alert projects include the project (unchanged rule);
3. its alert prefs allow the type, level and priority (unchanged rule);
4. its user did not cause the event (Sam does not get a push for Sam's own reply).

Requests go to everyone who can see the project. Whether a request should go only to some people
is open question 3.

**Live Activities and the summary.** `GET /api/summary` and the Live Activity content are worked
out per user, over the projects that user can see. On a team of one, and for every all-projects
user, that is exactly today's summary. When anyone answers a request, every device's Live Activity
updates as today. The summary gains `suspended` (section 7) and nothing else, so the iOS contract
stays add-only.

**The bell.** `notifications.seen_at` is one value for the whole server today. It becomes a
per-user read cursor (`notification_reads`, section 9). The bell lists only notifications in
projects the user can see.

**Mail (optional).** `SMTP_URL` (`smtp://` or `smtps://`, user and password in the URL) and
`MAIL_FROM`. Unset means no mail: email codes are switched off and invites are links only. The
public `GET /api/server` (section 8) tells the app what to offer.

## 5. Invites and lifecycle

**Invite.** An admin or owner creates an invite with a role, an optional email, and project access.
The server returns a link `PUBLIC_BASE_URL/join/<token>` (43-char random token, stored as an HMAC
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
`agents.created_by` records who minted each one. Members and viewers see the agent list (name,
last seen, online) but not mint controls. Revoking closes the agent's streams, as today.

Limiting an agent to some projects is out of scope.

## 7. Admin surface for the control plane

Small and generic, so a self-hoster can use it too. No billing, no plans, no prices.

**CLI, `feed-server create-owner --email <e> --name <n>`.** Creates an owner (or fails if that
email exists). With `--password-stdin` it sets the password; otherwise it prints a one-time setup
link valid 24 hours. A hosting service runs it once after provisioning (on Heroku:
`heroku run feed-server create-owner …`). Also `reset-password --email`, `list-users`.

**Admin token.** `ADMIN_API_TOKEN` (at least 32 chars). Unset means every `/admin/*` route answers
404. Checked in constant time; its own rate limit (60 a minute); never accepted on owner or agent
routes, and no other credential is accepted here.

**`GET /admin/usage`** (read-only):

```json
{"seats":3,"pendingInvites":1,"devices":5,"agents":4,"projects":{"active":7,"archived":2},
 "storageBytes":184467440,"databaseBytes":52428800,"lastActivityAt":"2026-11-18T09:00:00.000Z",
 "suspended":false}
```

Seats = users not removed. Storage = media plus derivatives bytes. The control plane decides what
any of it costs.

**Sign-in grants** (slice 2). The hosted service's "Log in" happens on the control plane, not on
the team's server: the person signs in there (email and password, or a magic link), and the
control plane answers the app with their team server's address and a **grant**, a short-lived
statement it signs: "this person may sign in to this server once". The app then trades the grant
for a device key on the team server, exactly as a password sign-in would. The open server needs
nothing hosted-specific beyond checking that signature:

- `GRANT_PUBLIC_KEY`: the control plane's Ed25519 public key (base64 of the 32 raw bytes). Unset
  means grants are off and the route below answers 404, as on any self-hosted server.
- The grant is `<base64url payload>.<base64url signature>`, the signature over the payload bytes.
  The payload is JSON `{"aud": "<this server's PUBLIC_BASE_URL>", "email": "…", "jti": "<random>",
  "exp": <unix seconds>}`.
- `POST /api/devices/grant` `{grant, name}` → 201 with the same body as password sign-in. Refused
  (401 `grant_refused`, one message for every reason) when the signature is bad, `aud` is not this
  server, it has expired (at most 5 minutes after issue, small clock skew allowed), its `jti` was
  used before, or no current user has that email. A grant never creates a user: the control plane
  provisions people through `create-owner` and invites, like anyone else.
- One use: the `jti` is stored in `auth_codes` (purpose `grant`, hashed, kept until `exp`), the
  same table as every other one-time secret, so no extra table.
- The same rate limits as password sign-in, keyed on the client address.

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
- Settings, "Team" (owner and admin only): people with role and project access, pending invites
  with copy and revoke, invite sheet (role, email, all or chosen projects), remove person.
- Settings, "Agents": list for everyone; mint, rename and revoke for owners and admins.
- Settings, "Devices": your devices; admins can also see and sign out others'.
- Cards and threads: "who did this" labels on human replies, answered requests ("Answered by
  Sam"), reactions (names on long-press or hover), tags and fields set by a person.
- Hide controls a viewer cannot use (reply box, answer buttons, react) rather than letting them
  fail.
- A read-only banner while the server is suspended.
- Team of one: none of the labels show, so the feed looks as it does today.

**The sign-in screen.** Two clear ways in, on every platform, plus the desktop's own server:

- **Log in**: the hosted service. Email and password or a magic link on the control plane, which
  answers with the team server's address and a grant (section 7); the app trades the grant for a
  device key and opens the feed. Until the control plane exists it is hidden behind a build flag, or
  shown as "coming soon".
- **Connect to a host**: someone's own server, in two steps.
  1. *Address.* The app asks the public `GET /api/server` and checks it is a Wildware Feed server:
     `{"product": "wildware-feed", "version": "…", "signIn": ["password"], "team": false}`, where
     `signIn` lists what the server offers (`password` today, the owner's password;
     `email-password`, `email-code` and `grant` as the slices land) and `team` says whether it has
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

Migrations use the numbers **048** and **049**, reserved for this work. (**050** is already taken by
media retention; the runner applies any version it has not applied yet, in order, so 048 and 049
can still land after it.) Each one ships as a pair under the
same version and name: `server/src/main/resources/migrations/048-people.sql` for SQLite and
`server/src/main/resources/migrations-pg/048-people.sql` for Postgres, each listed with its own
checksum in its folder's `migrations.json`. Both are add-only: new tables, new nullable columns, and
index swaps. No table rebuild. The `migrate` (SQLite to Postgres) and `export` (Postgres to SQLite)
tools learn the new tables.

**048-people** (slice 1):

- `users`: `id`, `email` (unique among not-removed rows), `name`, `password_hash`, `role`
  (CHECK owner, admin, member, viewer), `all_projects` (0/1, default 1), `founder` (0/1, at most one),
  `created_at`, `removed_at`.
- `invites`: `id`, `email`, `role`, `all_projects`, `project_ids` (JSON), `token_hash` (unique),
  `created_by`, `created_at`, `expires_at`, `accepted_at`, `accepted_by`, `revoked_at`.
- `auth_codes`: `id`, `user_id`, `purpose` (email sign-in, password reset, setup), `code_hash`,
  `created_at`, `expires_at`, `used_at`, `attempts`. One table for every one-time secret.
- `server_state`: a single row: `team_name`, `suspended_at`, `suspended_reason`.
- New nullable columns: `owner_devices.user_id`, `messages.user_id`, `approvals.decided_by`,
  `reactions.user_id`, `card_tags.user_id`, `card_fields.user_id`, `media.user_id`,
  `app_views.user_id`, `agents.created_by`.
- Reactions: today one person can put each emoji on a card once, keyed on
  `(card, actor, emoji)`. Two people are both `actor = "human"`, so the four unique indexes are
  replaced by `(card, actor, coalesce(user_id, ''), emoji)`.

**049-project-access** (slice 3):

- `project_access`: `user_id`, `project_id`, `created_at`; unique pair.
- `notification_reads`: `user_id` (unique), `seen_through_seq`. The old `notifications.seen_at`
  stays and is read only as the founder's starting point.

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

**Rate limits** (in memory, sliding windows, like today's limiter):

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
- An access matrix (Kotlin only): every owner route, called as owner, admin, member, viewer, a
  member without the project, and a removed user. The route list is read from the router, so a new
  route with no rule fails the build.
- Stream and media leak tests: a chosen-projects member must never receive an event, media file,
  summary count or push about a hidden project.
- Agent output: team of one is byte-identical; team of two adds exactly the documented keys.
- Last-owner rules, invite expiry and single use, suspension (writes refused, reads fine).

## 11. Phasing

**Slice 1: a second person with full access.** The smallest thing worth having.

- Migration 048; founding owner on boot; email and password sign-in; legacy sign-in kept.
- Roles owner, admin and member; everyone sees all projects.
- Invite by link (no mail); accept in the app; people list; remove a person (devices revoked,
  streams closed); last-owner rules; `create-owner` and `reset-password` on the CLI.
- Attribution in the app and to agents (section 3).
- Push to every user's devices, minus the person who acted.
- App: the host's email-and-password login (step 2 of "Connect to a host"), invite accept, "You"
  and "Team" settings, "who did this" labels.

**Before slice 1: the two-step sign-in.** `GET /api/server`, and the sign-in screen's "Log in"
(hidden) and "Connect to a host" (address, then the host's login). Small, and useful on its own.

**Slice 2: the hosted hooks.** `ADMIN_API_TOKEN`, `/admin/usage`, suspension, agent tokens minted
and revoked in the app, admin reset links, and sign-in grants (`GRANT_PUBLIC_KEY`,
`POST /api/devices/grant`).

**Slice 3: chosen projects and viewers.** Migration 049, the viewer role, per-project access, the
filtered stream, media, summary, bell and pushes, and the leak tests. This is the riskiest slice.

**Slice 4: mail.** `SMTP_URL`, invite emails, email-code sign-in and its rate limits.

**Slice 5: passkeys** on the hosted domain only.

**The riskiest part** is slice 3: one missed read path (an event on the stream, a media URL, a
count in the summary, a push) leaks a hidden project. The deny-by-default access matrix and the
leak tests exist for that. Slice 1 avoids the risk by giving everyone every project.

## 12. Open questions for the owner

1. ~~Is the TypeScript server retired before slice 1 ships?~~ Settled: it was retired at the move
   to the Kotlin server, so migration 048 closes no door that is still open.
2. Hosted: one server per team (this design), or many teams on one server? Many teams means a
   team id on every row, which is a much bigger change; better decided now.
3. Should a request alert everyone who can see its project, or only some people (for example,
   whoever is "on call" for that project)?
4. Should a person's message notify the other people on the team (the bell and a push), or only
   agent messages, as today?
5. Do viewers earn their place, or are three roles enough for now?
