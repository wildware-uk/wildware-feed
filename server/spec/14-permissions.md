# 14. Permissions: who may read, post in and manage each channel and project

Migration 067. Code: `domain/ChannelAccess.kt` (the rules), `db/Viewer.kt` (who a piece of work is
shown to), `http/AccessRoutes.kt` (the Permissions panel). Tests: `ChannelAccessTest`,
`ChannelAccessLeakTest`.

## Levels

Each person (`member:<membership id>`) and each agent (`agent:<agent id>`) has one level on each
channel:

| Level | People | Agents |
| --- | --- | --- |
| `none` | the channel doesn't exist for them | the same |
| `read` | read it, follow it, acknowledge | read it, follow it, acknowledge |
| `post` | read, post, react, answer, tag, set fields, pin, mark work, tick and vote | read, post, react, ask, tag, set fields |
| `manage` | post, and rename, move, archive, theme, which agents hear it, and its permissions | as `post` (agents never change permissions) |

The level is the most specific of:

1. a **team owner** always has `manage`, whatever any row says; no row can be set for them;
2. a row for them on the **channel**;
3. else a row for them on the channel's **project** (group);
4. else, when the channel **or its project is private**, `none`;
5. else **today's rule**: admins `manage`; members and agents `post`.

With no rows and nothing private, that is exactly the server before migration 067: nobody gains or
loses anything on deploy. Content in no channel (a feed post outside any channel) is everyone's.

A project's own level (its panel) is its row, else `none` when it is private, else the default. A
project is hidden from someone only when they have `none` on it and on every channel in it.

Moving a channel out of a project, or deleting the project, keeps the access the channel had
through it: the project's rows are copied onto the channel where it has none, and a channel leaving
a private project becomes private itself. Nothing hidden comes into view by being moved.

## What hidden means

A channel someone can't read is left out, or answers as if it did not exist (`not_found`, the same
message as a channel that never was), everywhere data leaves the server:

- **Owner API**: `/api/snapshot` (projects, groups, updates, messages, requests, tags), `/api/summary`
  (projects, requests, work counts, working cards), `/api/messages`, `/api/notifications` and the
  unseen count, `/api/subscriptions`, `/api/groups`, every card route (`/api/cards/{kind}/{id}/…`,
  blocks, followers, follow), tags, app views (`/apps/{view}`, app state), `/media/{id}/{variant}`,
  shared secrets on a card, reach summaries.
- **Streams**: `GET /api/stream` drops every event about a channel (its `projectId`, or the card it
  names) or project (`groupId`) the person can't read. The agent stream hears nothing there: no
  messages, answers, reactions or block events, and its counts leave it out.
- **Phones**: no alert (not even a critical one) and no bell row for a channel the person can't
  read; the Live Activity is worked out per person.
- **MCP**: `list_projects`, `list_subscriptions`, `list_work`, `list_tags`, `get_messages` (a
  channel, a thread, or what is unread), `get_card_fields`, `get_blocks`, card references, and every
  write. An @mention in a channel the agent can't read does not reach it.
- **Agent files** (`GET /api/agent/media/{id}`, issue #304): the same rule as reading the card the
  file is on: the channel's access, then the thread rule of `get_messages({message_id})` (it follows
  the thread, hears the channel, wrote there, or was @mentioned there), and on a message that very
  line (a line @addressed only to other agents stays theirs, except that a person @mentioning this
  agent in the thread shares what people wrote there). Any refusal is the same 404.

Writes check the level: below `read` is `not_found`, `read` is `403 forbidden` ("you can read this
channel but not post in it"). A slug is unique whoever can see its channel: making a channel whose
slug a hidden channel holds is `409 conflict` and says only that.

What is not per channel: agents, people, devices, secrets, pairings, registered apps and embed
types are the team's, as before. There is no search route.

## How it is enforced

Every owner request runs with its caller as the viewer (`Viewer`, set by the route wrapper next to
the role check); every MCP call and the agent stream run as their agent. Bus listeners run as the
server's own work (no viewer) and decide per reader. Reads that hand out channel content add
`ChannelAccess.notHidden(column)` to their SQL, and the lookups by id (`Projects`, `Groups`,
`Cards`, `Updates`, `Messages`, `Requests`, `Tags`, `Reads`, `Subscriptions.threadAt`) return
nothing for a hidden channel, so a route that forgets a check still finds nothing. What one viewer
may not see is worked out once and kept for a second (`KEEP_MS`), and at once after any change on
this server.

Row security was considered for this and left out: it is keyed to the connection, and the server's
own work inside a request (the next message `seq`, fan-out to other readers) must see every row, so
it would have miscounted silently. SQLite needs the same rule in the queries anyway. The two new
tables carry `team_id` and the team row policy like every other.

## Data (migration 067)

- `projects.private`, `project_groups.private` (0 or 1, default 0): only those listed see it.
- `channel_access`: `team_id`, `id`, `target_kind` (`channel` | `group`), `target_id`, `principal`,
  `level` (`none` | `read` | `post` | `manage`), `created_at`, `updated_at`, `updated_by`; unique
  on `(team_id, target_kind, target_id, principal)`.
- `access_audit`: every change: `at`, `actor`, `target_kind`, `target_id`, `target_name`,
  `principal` (null for the private switch), `change` (`level` | `private`), `old_value`,
  `new_value`.

Project and group JSON gain `private` as their last key.

## Owner API

| Route | Who | What |
| --- | --- | --- |
| `GET /api/projects/{ref}/access` | anyone who can read it | `{projectId, private, groupPrivate, yourLevel, canManage, entries}` |
| `PUT /api/projects/{ref}/access` | Manage | `{private}`; the person making it private is listed as managing it |
| `PUT /api/projects/{ref}/access/{member\|agent}/{id}` | Manage | `{level}`: `none`, `read`, `post`, `manage`, or null to inherit again |
| `GET`/`PUT /api/groups/{ref}/access…` | the same, for a project | `{groupId, private, yourLevel, canManage, entries}` |
| `GET /api/people/{id}/access` | yourself; anyone for an admin | `{memberId, role, channels: [{projectId, slug, name, groupId, level, via}]}` |
| `GET /api/agents/{id}/access` | any person | `{agentId, channels: […]}` |
| `GET /api/access/audit` | admin | `{entries: [{seq, id, at, actor, actorName, kind, targetId, targetName, principal, principalName, change, from, to}], nextCursor}`; `?before=<seq>&limit=` |

An entry: `{principal, kind (member | agent), id, name, role, level, via, own, inherited, editable}`.
`via` is `owner`, `channel`, `group`, `private` or `default`; `own` is the row on this channel or
project, `inherited` the row on its project. A team owner's entry is not `editable`; setting one is
`409 conflict`. Reach summaries list only channels the caller can see.

`PATCH /api/projects/{ref}` and `PATCH /api/groups/{ref}` now need Manage on that channel or
project (the route is open to members; admins manage by default, so nothing changes until someone
is given Manage). Moving a channel into a project needs Manage on the project too. Which agents hear
a channel (`PUT …/agents/{agentId}`) is Manage too, and adding an agent that has no access to the
channel is `409`, saying to give it access first. An agent's `set_project_theme` needs only Post,
as it always could.

Every change publishes `access.updated` with an empty payload: the apps read again what they may
see, and nothing about the change leaks to anyone.
