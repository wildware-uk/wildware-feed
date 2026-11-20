# 13. People: a team on one server

Migration 055. Design: `docs/TEAMS.md` and `docs/HOSTING.md` (slice 1). A server holds one team,
the home team. Today's owner is its founding member, `founder`.

## Rules

- **Roles.** `owner`, `admin`, `member`. Every owner route names the least role it needs in
  `http/Access.kt`; a route without a line answers `403 forbidden` to everyone, and `AccessTest`
  fails for it. Members read and take part; admins also run channels, projects, tags, retention,
  agents, pairings and secrets, and invite and remove members; owners do everything, and only
  owners change roles.
- **Nobody changes their own role. The last owner can't be removed, demoted or leave.** Admins never
  touch owners or other admins.
- **Device keys belong to a membership** (`owner_devices.user_id`). Removing someone deletes their
  keys; a key whose membership is removed also stops working. Roles are read on every request.
- **A team of one reads as before.** Rows carry `userId` (messages, reactions) and `answeredBy`
  (requests) only for a person who is not the founder. The snapshot carries `people` only once more
  than one person has been in the team. Agents see new keys only then too (`author_name`,
  `author_user_id`, `answered_by`, `by`); `author` stays `"human"` for everyone.
- **Who answered.** On a team, `GET /api/snapshot/requests` adds `answered`: questions answered in
  the last 7 days, newest answer first, at most 50, each with `answeredBy` (left out for the
  founder). The apps keep them in the feed as "Answered by Sam · No". A team of one has no `answered`.

## Routes

| Route | Who | What |
|---|---|---|
| `GET /api/me` | member | `{person, team}` |
| `PATCH /api/me` | member | `{name?, email?, password?, currentPassword}`; email and password need `currentPassword` |
| `GET /api/people` | member | everyone, removed people too; emails for admins and yourself; `lastSeenAt` (any of their devices) and `online` (within the 5 minutes that make an agent online) |
| `PATCH /api/people/{id}` | owner | `{role}` |
| `DELETE /api/people/{id}` | member | leave (your own id); remove others by role |
| `GET /api/invites` | admin | waiting invites |
| `POST /api/invites` | admin | `{role?, email?}` → `{invite, token, link}`; admins invite members only; 50 a day each |
| `DELETE /api/invites/{id}` | admin | revoke |
| `POST /api/invite/peek` | public | `{token}` (the code or the link) → `{invite: {role, email, invitedBy, expiresAt}}` |
| `POST /api/invite/accept` | public | `{token, name, email, password, deviceName, cookie?}` → as sign-in, plus `person` |
| `GET /join/{token}` | public | a page with "Open in Wildware Feed" (`agents://invite?server=…&token=…`), the code to paste, and, where this server serves the web app, "Or join in this browser" (`/#invite=<code>`; the code stays in the fragment) |
| `POST /api/devices/sign-in` | public | as before; with `email`, that person's sign-in |

Invite tokens are 43 random characters, stored as an HMAC (`invite:` prefix), single use, 7 days.
Peek and accept are limited to 20 an hour per address. Sign-in is limited to 5 failures in 15
minutes per address and 10 an hour per email; an unknown email costs the same as a wrong password.

`GET /api/server` lists `email-password` in `signIn` once any account has an email, and `team` is
true with more than one person.

## Alerts and the bell

Each person follows as `member:<membership id>`: levels per group and channel, threads by posting,
replying, choosing, or being @mentioned by their handle. A bell row and a phone alert go to each
person who follows what it is about, never to whoever did it. A teammate's message reaches others
only through what they follow. Events about one person's bell or follows carry `memberId` on a team;
the owner stream sends those only to that person.

## CLI

`list-users`, `create-owner --email --name`, and `reset-password --email` or `--founder` (passwords on stdin). The founder signs in with `ADMIN_PASSWORD_HASH` until they set a password of their own; then only with theirs. Setting or resetting a password never signs out devices.
