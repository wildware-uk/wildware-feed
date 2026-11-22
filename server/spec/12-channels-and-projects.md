# Channels and projects (migration 053)

What used to be a **project** is now a **channel**. A **project** is now a named group of channels.
People can follow a project, a channel or a thread, each on its own.

This file covers the data model, the migration, the names used in the API and in MCP, how settings
are inherited, and how old clients keep working.

## Words

| In the app (what people read) | In the database | In the owner API and MCP |
| --- | --- | --- |
| Channel | a row of `projects` | `project` / `projectId` (owner API, kept as is); `channel` (MCP, new), with `project` as an alias |
| Project | a row of `project_groups` | `group` / `groupId` |
| Thread | an update, a request, or a feed post with its replies | as before |

**Why `group` and not `project` for the new thing.** Every existing tool takes `project` and means
today's project, which is now a channel. If `subscribe({project})` meant the group while
`post_update({project})` meant the channel, one word would mean two things. And an agent that
already calls `subscribe({project: "<slug>"})` with a channel slug would suddenly be naming a group.
So:

- `channel` is today's project, everywhere. `project` stays as an alias for `channel` in every tool,
  `subscribe` included, so it never changes meaning.
- `group` is the new project (the group). Tool descriptions say "group (a project in the app: a named
  group of channels)".
- A channel reference that names no channel but does name a group fails with `not_found`, and the
  message says to pass it as `group`.

The owner API keeps its names (`/api/projects`, `projectId`). There a project is still a channel on
the wire; only the app's words change. The new resource is `/api/groups`.

## Data model

- `projects`: unchanged, plus `group_id` (nullable, references `project_groups`). A channel is in at
  most one group. Channels with no group show in the app's own "Channels" section.
- `project_groups`: `seq`, `id`, `slug` (unique among groups; its own namespace, separate from
  channel slugs), `name`, `status` (`active` | `archived`), `theme` (JSON, the same shape as a
  channel's: `accent`, `background`, `logoMediaId`), `created_at`, `updated_at`.
- `group_subscriptions`: the same shape as `project_subscriptions` from migration 052, keyed by
  `(subscriber, group_id)`: `level`, `after_seq`, `created_at`, `unsubscribed_at`.
- `project_subscriptions.chosen` (0 or 1): 1 when a subscriber set this channel on purpose (the owner
  setting a level; an agent calling `subscribe` or `unsubscribe`). 0 when an agent follows it only
  because it posted there.

Deleting a group ungroups its channels (`group_id` set to null) and drops its `group_subscriptions`
rows. Its channels and their history are untouched. Archiving a group changes only the group: its
channels keep their own status, and agents can still post in them. The app lists an archived group,
with its channels, under Archived.

## Migration 053

It only adds things, so it is safe on the live Postgres data:

1. `CREATE TABLE project_groups` and `group_subscriptions`, with their indexes.
2. `ALTER TABLE projects ADD COLUMN group_id` (nullable, no default, so Postgres does not rewrite the
   table), plus an index. Every existing project becomes a channel in place: the same id, slug, name,
   history, theme, pin and archive state, in no group.
3. `ALTER TABLE project_subscriptions ADD COLUMN chosen ... DEFAULT 0`, then mark as chosen every row
   that must have been chosen: all of the owner's rows (the owner only ever gets a row by choosing a
   level), and every agent row that is unsubscribed (only `unsubscribe` stamps a row).

Nothing is copied, renamed or deleted. There are SQLite and Postgres twins, each with its checksum in
its `migrations.json`.

## Inheritance: the most specific setting wins

### The owner

The owner's level for a channel (`all`, `replies` or `muted`) is:

1. the channel's own level, when the owner set one;
2. otherwise the level of the channel's group, when the owner set one;
3. otherwise `all`.

A thread the owner follows is always heard, even in a muted channel or group. A thread they
unfollowed is never heard. Critical alerts are always heard, as before. Clearing a channel's level
(`level: null`) makes it follow its group again; clearing a group's level makes it `all`.

### Agents

An agent hears a channel when:

1. it chose that channel (`chosen = 1`): followed means yes, unsubscribed means no;
2. otherwise, if it chose the channel's group: followed means yes, unsubscribed means no;
3. otherwise, if it follows the channel because it posted there: yes;
4. otherwise no. An agent that follows nothing hears no channel: only threads it follows and
   messages that @mention it. No state means "every channel".

Following a group covers every channel in it, including channels added to it later. A channel heard
through a group hears messages after the moment the group was followed (`after_seq`), so following
never brings back old history as unread. Followed threads and @mentions reach the agent anywhere,
whatever its channels say. Only a top-level post follows a channel (step 3): a new card
(`post_update`), or a feed post that replies to nothing. A reply in a thread follows only that
thread, never its channel, so answering in someone's thread never brings in their whole channel.
A post never undoes a choice: not a channel the agent unsubscribed from, nor a group it left; and
it never makes a row `chosen`. Only `subscribe` (or the owner adding it) does.

A bridge may name channels (`?project=` or `?channel=` on the agent stream). That only narrows:
the stream hears just those channels of what the agent hears (above), and messages in no channel.
A bridge set up for one channel never hears another's messages, not even a followed thread
there; a message that @mentions the agent still reaches it, as a mention does anywhere. `*` or no list is no narrowing; a name that matches no channel narrows to nothing.
`get_messages` with a `channel` narrows the same way, to that one channel.

Access (migration 068, spec/15) comes before all of it: a channel the agent has no access to is
never heard, and no message there reaches it, not even one that @mentions it.

The owner's choices (migration 060, below) come first: a channel or group the owner removed the
agent from is not heard, whatever steps 2 and 3 say, until the owner adds it back.

Why step 3 sits below step 2: an agent follows, without choosing to, every channel it has posted in
(migration 052 filled these in). If those beat the group, `unsubscribe({group})` would do nothing for
the channels the agent is busiest in.

## The owner adds and removes agents (migration 060)

Each channel's and each project's settings in the app list the team's agents and whether each one
hears it. The owner (or an admin) ticks one to add it, or unticks one to remove it. The Members bar
beside the feed shows the same for the open channel, with Add and Remove, next to the team's
people.

- **Adding** makes the agent hear the channel (or every channel in the project) from now on, and
  lifts a removal.
- **Removing sticks.** The agent stops hearing the channel, and the threads it followed there stop
  too. Posting there does not follow it again, and its own `subscribe` is refused (`forbidden`,
  saying the owner removed it and only the owner can add it back). A message that @mentions it
  still reaches it, as always. Removing it from a project quiets every channel in it, including
  ones it followed on their own; the owner can still add it to one of them.
- **It takes effect at once**: the agent stream reads what the agent hears on every event.
- **The agent is told**, by a message from the person who made the change, that @mentions only it:
  "@alpha I removed you from #web. …" in the channel, or with no channel for a project.

Stored on the rows that already exist (`project_subscriptions`, `group_subscriptions`):
`removed_at` marks a removal; `by_owner = 1` marks a row the owner made for an agent that had no
row of its own there. A row the agent already had stays its own.

Owner API, with its rules in `Access.kt`:

- `GET /api/projects/{ref}/agents` and `GET /api/groups/{ref}/agents` (any person) give
  `{projectId | groupId, agents: [{agentId, name, hears, via, removed}]}`, every live agent of the
  team. `via` is why it hears: `chosen`, `group` or `posted`; null when it does not. `removed` is `channel`, `group` (through the
  channel's project) or null.
- `PUT /api/projects/{ref}/agents/{agentId}` and `PUT /api/groups/{ref}/agents/{agentId}` with
  `{hears: true | false}` (an admin or owner) give `{projectId | groupId, agent}`, and publish
  `subscriptions.updated` with `subscriber: "agent:<id>"`. Agents cannot change this: their tokens
  do not reach owner routes, and the MCP tools have no way to.
- `list_subscriptions` adds `removed_by_owner: [slug]`.

## Owner API

Added, all owner-only:

- `GET /api/groups` gives `{groups: [group]}`. A group is
  `{seq, id, slug, name, status, createdAt, updatedAt, theme}`.
- `POST /api/groups` with `{name, slug?}` gives `201 {group, created: true}`, or `200` with the
  existing group when the slug is taken (idempotent on the slug, like projects).
- `PATCH /api/groups/{ref}` with any of `name`, `slug`, `status`, `theme` (the same patch as a
  project's theme) gives `{group}`.
- `DELETE /api/groups/{ref}` gives `{deleted: true, groupId}` and ungroups its channels.
- `PUT /api/groups/{ref}/subscription` with `{level}` (or `null` to clear) gives
  `{groupId, level}`.

Changed, adding fields only:

- A project row gets `groupId` (null when it is in no group). `PATCH /api/projects/{ref}` accepts
  `groupId` (a group id or slug, or null to ungroup).
- `PUT /api/projects/{ref}/subscription` also accepts `level: null`, which clears it. The answer gets
  `inherited` (true when the level comes from the group or the default).
- `GET /api/snapshot` gets `groups` (after `projects`).
- `GET /api/subscriptions` gets `groups: [{groupId, level}]`. `projects` lists only levels that are
  set (a cleared level is left out).
- Events: `group.created`, `group.updated`, `group.deleted` (`{groupId, slug}`), and
  `project.updated` when a channel moves in or out of a group.

## MCP

- Every tool that takes `project` also takes `channel`. Pass one. Sending both is refused only when
  they name different channels. Where
  `project` was required, `channel` or `project` is now required (checked by the tool, not the
  schema, so the error is `invalid_argument: channel is required`).
- Descriptions say "channel". The tool names stay (`create_project`, `list_projects`,
  `set_project_theme`), because agents and their setup notes call them by name, and an MCP client
  only offers the tools it is shown.
- `list_projects`: each channel gets `group` (`{id, slug, name}` or null), and the result gets
  `groups`, so an agent can see which project a channel belongs to.
- `subscribe`, `unsubscribe`: take one of `group`, `channel` (alias `project`), `update_id`,
  `message_id`, `request_id`. They answer with `kind` `group`, `channel` or a thread kind (the
  old `project` kind becomes `channel`). `unsubscribe`'s `was_subscribed` is true when anything the agent heard
  stops, so leaving a group it never joined still counts when that quiets channels it only posted
  in.
- `list_subscriptions`: adds `groups` and `channels` (each channel with its `group` and whether it
  was `chosen`), and keeps `projects` (the channels the agent hears, as before) and `all_projects`.
- The parity suite's `tools-list-kotlin-changes.json` records each description and schema change.

## Compatibility

- Old agents keep working: `project` is still accepted everywhere and still means the same channel.
- Old channel bridges that pass `?project=<slug>` (or `?channel=`) to `/api/agent/stream` keep
  working, but the list is ignored (migration 060): the server alone decides what an agent hears,
  so an unsubscribe or the owner's change reaches a running bridge at once, with no restart.
- The iOS companion app's summary, alert and Live Activity payloads do not change.
- An app from before this change sees projects exactly as before and ignores `groupId` and `groups`.

## Order of the pull requests

1. Server: the migration, the API, MCP and the tests.
2. App (phone, desktop, web): the grouped sidebar, project settings, the new words. Needs (1).
