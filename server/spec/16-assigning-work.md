# 16. Assigning work

Migration 074. A work card (an update or a post with a work state) can be assigned to people and
agents of the team: who is doing it, said outright rather than guessed from who last wrote in the
thread.

## 1. The rule

- A card has **0 to 5 assignees**. Each is a person (`member:<membership id>`) or an agent
  (`agent:<agent id>`). Requests (questions) are never assigned: they end by being answered.
- **Who may assign:** anyone with **Post** on the card's channel (spec/15), person or agent.
  Agents assign as freely as people: to delegate, to hand over, or to give a person a to-do
  that can wait instead of asking a question with `request_input`. Someone
  who can't read the card gets `not_found`; Read only gets `forbidden`.
- **Who may be assigned:** anyone who can at least **Read** the card's channel. Otherwise the change
  is refused, `403 forbidden`: "Rover can't read #ops, so they can't be assigned work in it…".
- Assigning a card with no work state marks it `todo`.
- A revoked agent or a person who left stops counting as an assignee in every read and filter; their
  row stays.

Table `card_assignees` (both databases, row security on Postgres): `card_kind`, `card_id`,
`principal`, `assigned_by` (same forms), `assigned_at`, `message_id` (the thread line). Unique on
(team, card, principal). Unassigning deletes the row.

## 2. The line in the thread

Every change that adds or removes someone posts one message in the card's thread, written as
whoever did it, with `source: "assignment"`. Its first line is what people see, as a small system
line: `assigned @Rover and Sam`, `unassigned Sam`, or both joined with `; `. Agents get an `@` so the
line @mentions them; people don't.

For the agents it assigns, more lines follow:

    Rover: you were assigned: <card title> — <link>
    Take it with set_status in_progress, say so in this thread, then finish it (set_status done) or hand it back.

and for agents it unassigns, `Rover: you are no longer assigned to this…`. An `@` inside the card
title or a person's name is defused (a zero-width space follows it), so it mentions nobody.

- **Agents:** an assignment line reaches **only the agents it names** (the usual @mention rule), even
  in channels they don't follow, on their stream, `get_messages` and heartbeat counts. A line that
  names no agent (only people) reaches no agent at all. Being named follows the thread (spec/04 §4.9),
  so later replies reach the agent too. No bridge change is needed: it is an ordinary message frame.
- **People:** a person assigned gets a bell row, `<who> assigned you`, body the card title, and a
  phone alert with the same words, honouring their alert settings (it is a `message`-type alert:
  "replies to you"). Nobody else's bell or phone hears an assignment line. A person assigned follows
  the thread from then on. Whoever did it is never told about their own change.

Nothing changed (everyone named was already assigned): no line, no event.

### When assigned work moves

A work-state change on a card that has assignees, by anyone, posts a second kind of line
(`source: "status"`) for the **agents waiting on it**: those that assigned someone to it and
those assigned, never whoever moved it. It reads `marked this done`, then
`@rover: <title> is now done (assigned to Sam). Pick up whatever was waiting on it.` It reaches
only those agents, on their stream, and rings nobody's bell. No line when no agent is waiting,
so ordinary moves on the board stay quiet. This is how an agent that gave a person a to-do
hears they finished it.

## 3. Events

`card.assigned {card, projectId, by, assignees, added, removed}` (principals), then the card's own
`update.updated` / `message.updated`. Filtered by channel like every event.

## 4. HTTP

- `GET /api/cards/{kind}/{id}/assignees` (member): `{card, assignees: [Who], candidates: [Who +
  canRead], max: 5}`. `Who` is `{principal, kind: "agent"|"person", id, name, handle?}`.
- `POST /api/cards/{kind}/{id}/assignees` (member, Post on the channel): `{assignees: [name or id],
  mode: "add"|"remove"|"set"}` (mode defaults to `set` here). Answers `{card, assignees, added,
  removed, status}`.
- `GET /api/board` cards carry `assignees: [Who]`. `?assigned=me|none|<name or id>` keeps the cards
  assigned to the viewer, to nobody, or to one person or agent.

A name is resolved, case-insensitively: `me`, `agent:<id>` / `member:<id>`, an agent's id or name, a
person's membership id, handle (with or without `@`) or name. A name that fits more than one is
`invalid_argument`, listing the principals to choose from; no match is `not_found`.

## 5. MCP

- `assign_work {update_id | message_id, assignees: [names or ids], mode: add (default) | remove | set}`
  returns `{card, assignees: [{kind, id, name}], added, removed, status, line_id}`. `request_id` is
  refused (`invalid_argument`).
- `post_update` takes `status` and `assignees` too, so a to-do for someone is one call. The
  assignees are checked before the update is posted: a refused one leaves no card.
- `list_work` gains `assigned_to` (`"me"` or a name or id). With it and no channel, it looks in every
  channel the agent can read, followed or not. Each item has `assignees`.
- `get_messages` with a card id: `thread.card.assignees`.
