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

## Profiles: who does what (migration 077)

Every member of a team says what they do, so agents @mention whoever fits instead of the owner.
Code: `domain/Profiles.kt`, `mcp/MemberTools.kt`. Tests: `MemberProfilesTest`.

- **What.** A role line (`profileRole`, up to 60 characters, one line: "Development", "Marketing")
  and a description (up to 500, line breaks allowed: who they are, what they look after, when to
  contact them). Both trimmed; blank or null is none. Invisible format characters are refused,
  except the joiner inside emoji.
- **Picture.** `avatarMediaId`: an image (png, jpeg, webp, gif) at most 2 MB, uploaded as any
  other (`POST /api/media`, or `create_upload` for an agent) and on no card. A person may use a
  person's upload; an agent its own; an owner or admin, for an agent, a person's or that agent's.
  Anything else is `not_found`. Setting one makes a 256 px square WebP at once (a `thumb`
  derivative of width 256, by ffmpeg), served at `/media/<id>/avatar`; without one, that address
  serves the original. Null removes it, and the apps show the letter avatar again. It counts
  toward the team's storage (the free plan's 500 MB) like any upload. While it is someone's
  picture, the sweeper and retention keep it; once it is nobody's, the sweeper takes it within
  the hour. Deleting an account clears that person's profile and picture.
- **Where.** A person's is on their membership (`memberships.profile_role`,
  `profile_description`, `avatar_media_id`), so it is per team. An agent's is on `agents`.
- **Who sets it.** A person, their own only (`PATCH /api/me`). An agent, its own
  (`set_profile`). Owners and admins, any agent's (`PATCH /api/agents/{id}`). Nobody sets another
  person's: `PATCH /api/people/{id}` still takes `role` and nothing else.
- **Who reads it.** Everyone in the team, people and agents: `personView` (so `/api/me`,
  `/api/people` and the snapshot's `people`) and `/api/agents` carry `profileRole`,
  `description` and `avatarMediaId`. Another team sees none of it.
- **Events.** A person's change publishes `people.updated`; an agent's `agent.profile`
  `{agentId}`.

| Route or tool | Who | What |
|---|---|---|
| `PATCH /api/me` | member | also `{profileRole?, description?, avatarMediaId?}`; a string, or null to clear; a key left out stays |
| `PATCH /api/agents/{id}` | admin | `{name?, profileRole?, description?, avatarMediaId?}` → `{agent: {id, name, profileRole, description, avatarMediaId}}` |
| `set_profile` | the agent | `{role?, description?, avatar_media_id?}` (at least one; "" clears) → `{role, description, avatar_media_id}` |
| `list_members` | any agent | `{channel?}` → `{members: [{kind, id, name, handle, role, description, has_picture, online, team_role (people), you (the caller)}], people, agents}`; with `channel`, only those who can read it (spec/15); a channel the agent can't read is `not_found` |

`register_session` adds `profile: {role, description, avatar_media_id}` to its result. Its text, and the server
instructions, say: "Before you @mention someone, check list_members and pick whoever's
description fits; prefer an agent responsible for the area over a person." With no description
yet, the text also tells the agent to write one with `set_profile`.

## Alerts and the bell

Each person follows as `member:<membership id>`: levels per group and channel, threads by posting,
replying, choosing, or being @mentioned by their handle. A bell row and a phone alert go to each
person who follows what it is about, never to whoever did it. A teammate's message reaches others
only through what they follow. Events about one person's bell or follows carry `memberId` on a team;
the owner stream sends those only to that person.

## CLI

`list-users`, `create-owner --email --name`, and `reset-password --email` or `--founder` (passwords on stdin). The founder signs in with `ADMIN_PASSWORD_HASH` until they set a password of their own; then only with theirs. Setting or resetting a password never signs out devices.
