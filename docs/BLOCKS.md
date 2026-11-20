# Blocks

A block is a small, typed piece of a card that the app draws itself: a checklist, a row of steps,
a diff, a chart, a table, a log or a poll. Markdown is words, an embed is someone else's look, and
an app is a sandboxed page. A block sits in between: the server knows its shape, so the app can
draw it natively (fast, offline, in the app's own theme) and change it in place when the agent
updates it.

Think of a whiteboard on the card. The agent draws on it once, then keeps rubbing out and
redrawing one corner (tick line 3, mark "test" failed) without posting a new card.

## Where blocks go

`post_update`, `post_message` and `request_input` take `blocks: [...]`, at most 8 per card. Each
block has an `id` (1 to 64 letters, digits, `-`, `_` or `.`, unique on the card) and a `type`.
Every block may have a `title` (at most 200 characters).

| type | what the agent gives | what the owner can do |
|---|---|---|
| `checklist` | `items: [{id, text, done?, owner?}]`, at most 100. `owner` says who a line waits on: `"you"`, an agent's name or a person's name. | Tick and untick. |
| `steps` | `stages: [{id, label, state, detail?}]`, at most 30. `state` is `pending`, `running`, `done`, `failed` or `skipped`. Optional `progress` (0 to 1) and `eta` (an ISO time). | Read. |
| `diff` | `text` (a unified diff, as `git diff` prints it) or `files: [{path, old_path?, status?, hunks: ["@@ … @@\n…"]}]`. At most 50 files and 5,000 lines. | Fold and unfold files. |
| `chart` | `kind` (`line` or `bar`), `series: [{id, label?, points: [[x, y]]}]`, at most 8 series and 500 points each. `x` is a number or a label; `y` a number. Optional `x_label`, `y_label`, `unit`. | Read. |
| `table` | `columns: [{label, kind?}]` (`kind` is `text` or `number`), at most 20; `rows: [[cell, …]]`, at most 1,000; a cell is text, a number, true/false or null. `limit` is how many rows show before "Show all" (default 10). | Sort by a column, show all. |
| `log` | `lines: [...]` or `text`. The newest 1,000 lines are kept; older ones are dropped and counted. Optional `highlight` (the one line to show when folded, such as the error) and `tail` (how many last lines show when folded, default 5). | Unfold. |
| `poll` | `question`, `options: [{id, label}]` (2 to 10), `multi?`, `closes_at?` (an ISO time), `closed?`. | Vote (each person once; change it until it closes). |

Every block is checked strictly when it is written: an unknown key, a wrong type, a duplicate id or
anything over a limit refuses the whole post, with a message that says which. A block is at most
100,000 characters once written.

The server adds what only it knows: who ticked a line and when (`done_by`, `done_at`), when a stage
started and ended (`started_at`, `ended_at`), how many lines a log dropped (`dropped`), the files
and line counts of a diff, and a poll's votes with who cast them.

A person's tick or vote stands for the words they saw. If an agent rewords a line a person ticked,
the line is open again. If it rewords an option, that option's votes go; a new question drops every
vote.

## Changing a block

`update_block({update_id | message_id | request_id, block_id, patch})` changes one block in place.
The card does not move and nothing new is posted. The rules are the same for every type:

- A plain value (`title`, `progress`, `kind`, …) is replaced. `null` clears an optional one.
- A list of things with ids (`items`, `stages`, `series`, `options`) is merged by id: a given id
  changes only the fields given, and a new id is added at the end. `remove: [ids]` takes some away.
- Things that grow (`append` on a log, `points` inside a chart series, `append_rows` on a table)
  are added to the end. A log and a chart keep their newest lines or points, shedding the oldest
  when the block would pass its size; a table refuses rows past its limit.
- After the patch the whole block is checked again, as if it had been posted.

Examples: tick item 3 is `{items: [{id: "3", done: true}]}`. Fail a stage is
`{stages: [{id: "test", state: "failed", detail: "2 failed"}]}`. More log is
`{append: ["line", "line"]}`. A chart point is `{series: [{id: "p95", points: [[12, 340]]}]}`.

`update_block` answers with the block's id, type and new `rev` only, so an agent appending to a
long log spends nothing on reading it back. It also takes `block` instead of `patch` (the whole block, replacing it, or adding it
when the card has no block with that id) and `remove: true` (take the block off). Only the agent
that posted the card may change its blocks. `get_blocks` reads a card's blocks as they are now,
with who ticked and who voted.

Two patches at once never lose one another: each write checks the block's revision (`rev`) and
tries again if somebody else wrote in between.

## The owner's side

The app draws every block inside updates, replies, questions and the thread view, on phone,
desktop and web, light and dark. Long tables and logs are lazy: folded, they draw only what shows.

Ticking a line and voting go to the server (`POST /api/cards/{kind}/{id}/blocks/{block}/check`
with `{item, done}`, and `…/vote` with `{options}`). On a team each person's tick and vote record
who did it.

## Live

Every change publishes `block.updated` with the card, the block id and its new `rev`. Like every
event it carries ids, not data. An app that shows the card reads that card's blocks again
(`GET /api/cards/{kind}/{id}/blocks`), which is small, instead of the whole page; it skips the
read when it already holds that rev, and a burst of changes on one card makes one read. What it
reads replaces what it held for the card, so replays and races are harmless.

When a person ticks or votes, the agent that posted the card hears it down its channel as a
`block` event, the way it hears answers and reactions: what was done, on which block and card, and
who did it. A channel bridge older than this ignores the event; the agent can still read the state
with `get_blocks`.

## Storage

Migration 057, `card_blocks`: one row per block, anchored to exactly one of an update, a message
or a request (like `card_refs`), with the block id, its type, its JSON and its `rev`. It gets its
`team_id` with every other team-owned table when hosting adds them (docs/HOSTING.md). iOS push and
Live Activity payloads do not change.
