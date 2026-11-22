# 04 — Event bus and live streams

Scope: the in-process event bus and replay ring (`src/events/`), the owner stream
`GET /api/stream`, the agent stream `GET /api/agent/stream`, and how snapshots
line up with the stream cursor. Shapes of snapshot bodies are in other files.

All paths are relative to the agent-dashboard repo root.

---

## 1. Event bus

### 1.1 What it is

- One bus per process: `export const bus = new EventBus()` — `src/events/bus.ts:187`.
  Domain code reaches it as `ctx.bus` (`src/domain/context.ts:35`).
- **Memory only. Nothing is saved to disk.** A restart begins again from seq 0 —
  `src/events/bus.ts:57` (`#seq = 0`), `src/events/ring.ts` (a plain array).
- No persistence of subscribers. Durable state (rows) is the authority; events
  only say "go and re-read" (`src/events/types.ts:1-9`).

### 1.2 publish

`publish(type, payload)` — `src/events/bus.ts:85-109`:

1. `seq += 1` (first event is seq **1**) — `:86`.
2. Envelope built in this key order: `{ type, seq, at, payload }` — `:90-95`.
   `at` = `new Date().toISOString()` (UTC, milliseconds, `Z`), e.g.
   `2026-11-18T09:15:02.481Z` — `:93`. Display only; ordering is by `seq`
   (`src/events/types.ts:303-304`).
3. Pushed into the ring **before** any listener runs — `:97`.
4. Every listener is called **synchronously**, over a copy of the listener set,
   in insertion (subscribe) order. A listener removed during dispatch is
   skipped — `:100-101`.
5. A listener that throws is caught and logged
   (`event subscriber threw on <type>`), and dispatch continues — `:102-106`, `:66`.
6. Returns the stamped envelope (callers quote its `seq`) — `:108`.

`lastSeq` = last seq handed out, `0` before any publish — `src/events/bus.ts:75-77`.

### 1.3 Replay ring

- Capacity **500** (`RING_CAPACITY`) — `src/events/bus.ts:10`, `:63`.
- Fixed array; once full, the oldest slot is overwritten (FIFO eviction) —
  `src/events/ring.ts:65-72`.
- `since(cursor)` — `src/events/ring.ts:88-97`:
  - `newest` = newest retained seq, or `0` if empty.
  - `oldest` = oldest retained seq, or `newest + 1` if empty.
  - `cursor > newest` → miss, reason `ahead`.
  - `cursor < oldest - 1` → miss, reason `expired`.
  - else → hit, events with `seq > cursor`, oldest first.
- Consequences (exact):
  - Empty ring: cursor `0` = hit with no events; any cursor ≥ 1 = `ahead`.
  - After `L` events with `L ≤ 500`: any cursor `0..L` is a hit (cursor `0`
    replays **all** `L`).
  - After `L > 500`: oldest = `L-499`; cursors `L-500..L` hit; below → `expired`.
  - After restart: a client's old cursor bigger than the new `lastSeq` → `ahead`.

### 1.4 waitFor (used by `await_request`)

`src/events/bus.ts:142-178`. Parks until a matching event, a timeout, or an
abort; always resolves (`undefined` on no match) and always unsubscribes. If
`since` is given and the ring still covers it, the ring is scanned first
(`:153-159`). Used only by `src/domain/requests.ts:789` (types
`['request.answered']`, `where` requestId). Callers read `bus.lastSeq` *before*
reading the row (`src/domain/requests.ts:299`).

### 1.5 In-process subscribers (order matters, see §1.7)

Subscribed at server start, in this order (`src/hooks.server.ts:63-76`):

1. Web push — `src/domain/push.ts:629` (`startRequestPusher`)
2. APNs — `src/domain/apns.ts:471`
3. Live Activity — `src/domain/live-activity.ts:214`
4. Notification recorder — `src/domain/notifications.ts:238`

Then one listener per open `/api/stream` and `/api/agent/stream` connection, in
connect order (`src/http/stream/stream.ts:123`, `src/http/stream/agent.ts:667`).
Plus short-lived `waitFor` listeners.

### 1.6 Every event type

Payload key order below is the **wire order** (JSON.stringify keeps insertion
order), taken from the publishing call, not from the type declaration. Type
map: `src/events/types.ts:61-290`.

| type | payload (wire order) | published by |
|---|---|---|
| `project.created` | `projectId, slug` | `createProject` — `src/domain/projects.ts:172`. Not on idempotent re-create (`:163-164`). Surfaces: MCP `create_project`, owner actions (`src/http/owner/actions.ts`). |
| `project.updated` | `projectId, slug` | `updateProject` — `src/domain/projects.ts:285`. Surfaces: owner actions, MCP `set_project_theme`. |
| `tags.changed` | `projectId` | `defineTag` `src/domain/tags.ts:143` (not when tag already exists, `:125-126`); `renameTag` `:197`; `retireTag` `:215`; `tagCard` `:288` (after a card event, `:287`; nothing on a no-op, `:261-265`). |
| `fields.changed` | `projectId` (nullable) | `setField` — `src/domain/fields.ts:107` (clear) and `:138` (set), each right after a card event (`:106`, `:137`). No event when value unchanged (`:124-126`). |
| `apps.changed` | `projectId` (nullable) | `setAppData` — `src/domain/apps.ts:374`, right after a card event (`:373`). MCP `set_app_data`. |
| `update.created` | `updateId, projectId, agentId` | `postUpdate` — `src/domain/updates.ts:151`. MCP `post_update`. |
| `update.updated` | `updateId, projectId, pinned` | `editUpdate` `src/domain/updates.ts:369`; share mint/revoke `src/domain/shares.ts:146` (via `:120`, `:139`; revoke of nothing is quiet `:136`); card announce `src/domain/cards.ts:120-124` with `projectId: row.projectId ?? ''` (pin, set_status, tag, field, app on an update card). |
| `update.deleted` | `updateId, projectId` | `deleteUpdate` — `src/domain/updates.ts:237`. Quiet if already deleted (`:234`). |
| `media.ready` | `mediaId, updateId, messageId, requestId, kind` | derivative worker — `src/media/derive.ts:144` (kind `file`) and `:207` (image/video/audio). Not when run with `force`. `kind` ∈ `image,video,audio,file`. |
| `task.created` / `task.updated` | `taskId, projectId, agentId, state` (declared) | **Nobody.** Declared in `src/events/types.ts:108-119` and listened for by the browser (`src/web/stream.ts:79-80`) but the tasks module is deleted. Never on the wire. |
| `message.created` | `messageId, projectId, author, updateId, replyTo, requestId, taskId` | `postMessage` — `src/domain/messages.ts:360-365` (thread keys from `threadKeys`, `:1208-1217`). Surfaces: owner `src/http/owner/messages.ts`, MCP `post_message`, MCP `register_app`. |
| `message.updated` | edit: `messageId, projectId, author, updateId, replyTo, requestId, taskId` | `editMessage` — `src/domain/messages.ts:425-430`. MCP `edit_message`. |
| `message.updated` | card announce: `messageId, projectId, author` with `author: ""`, **no thread keys** | `src/domain/cards.ts:128-132` (pin, set_status, tag, field, app on a message card). |
| `message.deleted` | `messageId, projectId, replies, updateId, replyTo, requestId, taskId` | `deleteMessage` — `src/domain/messages.ts:497-502`. `replies` = number of replies soft-deleted with a root post (`:493-495`). Quiet if already deleted (`:480`). |
| `message.delivered` | `messageId, agentId, projectId, updateId, replyTo, requestId, taskId` | `markMessagesDelivered` — `src/domain/messages.ts:555-560`. Only called by the agent stream (`src/http/stream/agent.ts:344`). One event per **new** delivery row, keyed (message, agent, client) — `src/db/deliveries.ts:52-87`. |
| `messages.read` | `agentId, cursor` | `readMessages` (MCP `get_messages`) — `src/domain/messages.ts:802`, only when `moved > 0`. `cursor` is a **message seq** (the highest per-project read cursor touched), not a bus seq — `src/domain/messages.ts:1050-1051`. |
| `request.created` | `requestId, agentId, projectId, kind` | `createRequest` — `src/domain/requests.ts:367`. MCP `request_input`. `kind` ∈ `text,confirm,buttons,choice,multi_choice,form`. |
| `request.updated` | edit: `requestId, agentId, projectId` | `editRequest` — `src/domain/requests.ts:576`. MCP `edit_request`. |
| `request.updated` | card announce: same keys with `agentId: ""` | `src/domain/cards.ts:135-139` (pin, tag, field, app on a request card). |
| `request.answered` | `requestId, agentId, state, settledAt` | `settle` — `src/domain/requests.ts:827-832`. `state`: `answered` (`:408`), `cancelled` (`:423`), `withdrawn` (`:455`), `timeout` (sweeper `:657`, or lazily on any read via `expireIfDue` `:849`). `settledAt` = ISO of `answeredAt ?? now`. |
| `ack.updated` | `ackId, agentId, card{kind,id}, messageId, taskId, state` | `acknowledgeCard` — `src/domain/cards.ts:432-439`. **Every** call publishes, even when the state did not change. MCP `acknowledge`. |
| `agent.renamed` | `agentId, name` | `renameAgent` — `src/domain/agents.ts:259`. Every call, even same name. Owner actions, CLI. |
| `notification.created` | `notificationId, kind, projectId` | `recordNotification` — `src/domain/notifications.ts:141`, only for a new row (`:138`). Called from the recorder subscriber (`:238-256`) on every live `update.created`, on `message.created` only when an agent wrote it (`:175-178`), and on `request.created`. `kind` ∈ `update,reply,request`. |
| `reaction.updated` | `card{kind,id}, messageId, projectId, actor, emoji, on` | `reactToCard` — `src/domain/cards.ts:370-377`, only when a row was added/removed (`:366`). `messageId` = card id for a message card, else `null`. `actor` = `human` or `agent:<id>`. |
| `notifications.seen` | `count` | `markSeen` — `src/domain/notifications.ts:110`, only when `count > 0`. |
| `subscriptions.updated` | `subscriber` | Kotlin server only (migration 052): MCP `subscribe` / `unsubscribe`, the owner's follow and level routes, and the owner's first post in a thread, when something changed. The agent stream recounts on it (§4.8). |
| `notifications.cleared` | `tags` | Kotlin addition (spec/08 §6.9). Alerts the owner has dealt with, by the tag each carried: `update-<id>` and `message-<id>` when their bell rows become seen (`/seen`, `/cards-seen`, or the line or update deleted), `request-<id>` when the request settles (any `request.answered`), `pairing-<id>` when a `pairing.updated` leaves `pending`. Published right after `notifications.seen` where both apply; never with an empty list. |
| `agent.presence` | `agentId, sessionId, online` | `announce` — `src/domain/sessions.ts:432`. `online:true` from `registerSession` (`:130`) / `heartbeat` (`:166`) only when the agent was not already online; `online:false` from `endSession` (`:194-195`) when no other session keeps it online, and from the sweeper with `sessionId: null` (`:316`). Going quiet past the 90 s window (`PRESENCE_WINDOW_MS`, `:71`) publishes **nothing**. |

`card.kind` ∈ `update, message, request` (`src/db/cards.ts:30`). Card objects
are built `{ kind, id }`.

### 1.7 Nested publishes (affects live order)

A listener that publishes during dispatch runs a full inner dispatch before the
outer one continues (§1.2 step 4). Two places do this:

- The notification recorder (subscriber #4, before every stream) publishes
  `notification.created` while handling `update.created` / `message.created` /
  `request.created` — `src/domain/notifications.ts:238-256`. So **every** stream
  connection receives `notification.created` (seq N+1) **before** the event that
  caused it (seq N).
- The agent stream handler publishes `message.delivered` while handling
  `message.created` (via `messageFrame`) — `src/http/stream/agent.ts:704` → `:344`.
  Owner streams that connected **after** that agent stream see seq N+1 before N;
  ones that connected before see N then N+1.

The ring always holds and replays events in seq order (§1.2 step 3).

---

## 2. Wire format (shared by both streams)

From `src/http/stream/frames.ts`. Line ending is LF only. Every frame ends in a
blank line (`\n\n`).

| frame | exact bytes | source |
|---|---|---|
| retry | `retry: <ms>\n\n` (ms = `max(0, round(ms))`) | `frames.ts:80` |
| comment | `: <text>\n\n` (CR/LF in text → space) | `frames.ts:75`, `:95` |
| event | `id: <seq>\nevent: <type>\ndata: <json(envelope)>\n\n` | `frames.ts:54` |
| resync | `id: <seq>\n` (only if seq > 0) + `event: resync\ndata: <json>\n\n` | `frames.ts:66-67` |

- `data:` is a single line: `JSON.stringify(...)` then any CR/LF → space
  (`frames.ts:90-96`). JSON is JS-style: no spaces, non-ASCII raw UTF-8, `/`
  not escaped, `undefined` fields omitted.
- The event `data` is the **whole envelope** `{"type","seq","at","payload"}`,
  not just the payload (`frames.ts:47-54`).
- Resync data: `{"type":"resync","reason":"expired"|"ahead","from":<cursor>,"seq":<lastSeq>}`
  — `frames.ts:67`, `src/http/stream/stream.ts:165`.

Response headers, both streams — `src/http/stream/frames.ts:28-33`:

```
content-type: text/event-stream; charset=utf-8
cache-control: no-cache, no-store, no-transform
connection: keep-alive
x-accel-buffering: no
```

Status 200, no `content-length` (chunked). Not compressed: the compressor's
type allowlist has no `text/event-stream` (`src/http/compress.ts:64-73`).

---

## 3. `GET /api/stream` — owner stream

Mount: `src/http/routes/api/stream/+server.ts:39-41`. Handler:
`src/http/stream/stream.ts:70-149`.

### 3.1 Auth

Checked twice, both at connect only:

1. Hook — `src/http/auth/handle.ts:43-53`: if `Authorization: Bearer <x>` is
   present it alone decides (owner device key); else the session cookie.
2. Handler — `ownerAuthenticated`, same rule — `src/http/stream/owner.ts:60-72`.
   Bearer parse: `/^bearer\s+(\S+)$/i` on the trimmed header —
   `src/http/auth/bearer.ts:13-17`. Fails closed if auth config is missing (`:70`).

Refusal (either lock): `401`, `content-type: application/json`, body
`{"error":"unauthenticated"}` — `src/http/stream/owner.ts:78-83`,
`src/http/auth/handle.ts:22-25`. No bus subscription is made
(`src/http/stream/stream.test.ts:331-333`).

### 3.2 Cursor

`readCursor` — `src/http/stream/stream.ts:175-186`:

- Header `Last-Event-ID` first; if absent **or invalid**, query
  `?last_event_id=` (`LAST_EVENT_ID_PARAM`, `:41`).
- Valid = trimmed, `^\d+$`, safe integer. Anything else = no cursor (never an
  error) — `:180-186`.

### 3.3 Connect sequence (all in one synchronous tick)

1. One chunk: `retry: 2000\n\n: connected\n\n` — `stream.ts:117`
   (`RETRY_MS = 2000`, `:29`).
2. If a cursor was given — `stream.ts:122`, `:159-166`:
   - hit → each missed event as an event frame, oldest first (none if up to date);
   - miss → exactly one resync frame (§2), `from` = the cursor, `seq` = `bus.lastSeq`.
   - No cursor → no replay at all; live only.
3. Subscribe to the bus; every event after that becomes an event frame —
   `stream.ts:123`. Replay-then-subscribe with no await between, so no gap and
   no duplicate (`:119-121`).
4. Heartbeat every **15 000 ms** (`HEARTBEAT_MS`, `:26`), first one 15 s after
   connect: `: heartbeat <ISO now>\n\n` — `:125-133`.

After a resync the stream simply carries on live. It does not close.

### 3.4 What it carries

**Everything.** Every bus event, unfiltered, unprojected, to every owner
connection (`stream.ts:123`). No per-audience logic. Event name = `type`.
Includes `messages.read`, `message.delivered`, `agent.presence`, etc.

### 3.5 Close

The server never ends the stream by itself. Teardown (unsubscribe, stop timer,
close) runs on request abort, body cancel, or a failed enqueue — `stream.ts:95`,
`:99-111`, `:139-145`. Auth is not re-checked: a session that expires or a
device key revoked mid-stream keeps receiving.

### 3.6 Presence

Connecting has no presence effect. Presence is only heartbeats/sessions
(`src/domain/sessions.ts:116-200`).

---

## 4. `GET /api/agent/stream` — agent stream

Mount: `src/http/routes/api/agent/stream/+server.ts:21-23`. Handler:
`src/http/stream/agent.ts:558-728`. Exempt from the session guard by prefix
`/api/agent` (`src/http/auth/guard.ts:33`).

### 4.1 Auth and refusals (in this order)

All refusals: JSON `{"error":<code>,"message":<text>}`, `content-type: application/json`
— `agent.ts:544-549`.

1. No `TOKEN_SECRET` → `503 misconfigured`, message
   `this deployment has no TOKEN_SECRET, so no token can be verified` — `agent.ts:571-578`.
2. `authenticateMcpRequest` — `src/mcp/auth.ts:119-163`:
   - header empty → `401 missing_token`
     `missing Authorization header: send Authorization: Bearer <agent token>`;
     non-bearer scheme → `401 unsupported_scheme`
     `unsupported Authorization scheme "<scheme>": this server accepts Bearer tokens only`;
     `Bearer` with nothing → `401 missing_token`
     `empty bearer token: send Authorization: Bearer <agent token>` — `src/mcp/auth.ts` (`readBearerToken`).
   - not token-shaped → `401 malformed_token`
     `malformed bearer token: not a token this server issued`.
   - rate limit (120 per 60 s sliding window per token HMAC,
     `src/mcp/rate-limit.ts:19-22`) → `429 rate_limited`
     `rate limit exceeded for this token: retry after <n>s`. **No `Retry-After`
     header** on this route (`agent.ts:587` drops `retryAfterMs`).
   - unknown → `401 unknown_token` `unknown bearer token: no agent holds it`;
     revoked → `401 revoked_token`
     `revoked bearer token: ask the dashboard owner to mint a new one`
     — `src/domain/agents.ts:143-148`, `:168-184`.
   - success stamps `agents.last_seen_at` — `src/mcp/auth.ts:160`.
3. `?project=` subscription (§4.2) → `404 not_found` `no such project: <ref>`
   or `400 invalid_argument`
   `* means every project, so it cannot be listed beside others` — `agent.ts:598-610`.
   The Kotlin server ignores the list instead (migration 060), so it refuses none.
   The rate unit and `last_seen_at` stamp are already spent by then.

### 4.2 Query parameters

- `project` — `agent.ts:175-192`. All `project` values, each split on `,`,
  trimmed, empties dropped. Resolved once at connect via `findProject` (id or
  slug, case-insensitive slug; archived projects resolve) —
  `src/domain/projects.ts:195-203`.
  - none → `undefined` = "derive from history" (`projectsForAgent`,
    `src/domain/messages.ts:853-856`; no history = everything). **Kotlin server:**
    what the agent follows on the server (§4.8), read again on every event.
  - exactly `*` → `null` = every project.
  - list → those project ids only.
- `client` — first value, trimmed, cut to 200 chars; empty/absent → `null` —
  `agent.ts:594-596`, `CLIENT_ID_MAX` `:143`. Opaque connection identity for
  delivery dedupe (§4.6). The bridge sends `bridge-<uuid>`, fixed for the bridge
  process (`src/channel/bridge.ts:243-245`, `:643-644`).
- `Last-Event-ID` / `last_event_id`: **ignored**. No cursor on this route
  (`agent.ts:30-38`).

### 4.3 Frames

None carry `id:`. Event names: `work`, `message`, `answer`, `reaction`
(`agent.ts:76`, `:88`, `:104`, `:120`). Data JSON key order exactly as below.

`work` — `agent.ts:250-259`:

```
event: work
data: {"type":"work","unread_messages":N,"open_tasks":N,"pending_approvals":N,"at":"<ISO of ctx.now()>"}
```

`message` — `agent.ts:350-390`: `{"type":"message","messages":[...]}`, each item:

`message_id, project_id, project (slug|null), project_name (|null), update_id,
task_id, reply_to, answers (|null), in_reply_to, request_id, author,
author_name, body, created_at (ISO), media:[{id, kind}]`

- Kotlin, migration 065: a voice note also has `source: "voice"`, and its `body` is the
  words then a blank line and `(Transcribed from a voice note, so it may contain mistakes:
  read it for meaning, and ask if something looks off.)`. The bridge's notice shows that body
  as it is. The stored words never hold that line; people's reads, quotes and edits don't see it.
- Kotlin, §4.9: a line that @mentions this agent in a thread it was not in before (it follows
  the thread only from this line, or not at all; never the post that starts a thread) also has
  `thread: {kind, id}`, and its `body` ends with a blank line and `(You were @mentioned in a
  thread you were not in. To read all of it — the post that started it, every reply, the card's
  details and their files — call get_messages({message_id: "<this line's id>"}).)`. The bridge
  shows it as it is, so even an old bridge tells the agent how to catch up.
- `in_reply_to` = `{id, author, body, truncated}` or `null`; parent chosen as
  `answers ?? reply_to`; body cut to 240 chars + `…`; `null` if parent deleted —
  `src/domain/messages.ts:620-665`.
- `author_name`: agent's name, `null` for the owner — `src/domain/messages.ts:1132`.
- Up to 5 messages (`MESSAGE_LIMIT`, `agent.ts:123`): the **newest** 5 unread in
  scope, then put in ascending message-seq order — `src/db/messages.ts:340-361`.

`answer` — `agent.ts:443-457`:

`type:"answer", request_id, state, kind, question, answer (typed value | null),
project, project_name, update_id, answered_at (ISO | null)`

`reaction` — `agent.ts:519-531`:

`type:"reaction", message_id, emoji, on, project, project_name, update_id,
task_id, body` (message body, whitespace runs → one space, trimmed, first 160 chars).

### 4.4 The three counts

`scopedWork` — `agent.ts:278-294` (the `countWork` result is fully overwritten):

- `unread_messages` = `countUnreadMessagesInScope` — `src/domain/messages.ts:867-890`.
  Messages past this agent's per-project read cursor, not deleted, not written by
  the agent itself, not `@`-addressed only to others; in the subscribed projects
  **or** naming this agent (`mentionsAnywhere`) — `src/db/messages.ts:331-337`, `:398-423`.
  With a project list, messages with **no project** are excluded unless they
  mention the agent (`m.project_id IN (...)`).
- `open_tasks` = cards (updates + messages, not deleted) with status
  `todo|in_progress|blocked` in scope — **any author, not only this agent's** —
  `src/domain/cards.ts:226-233`, `src/db/cards.ts:214-232`. `*` = whole deployment.
- `pending_approvals` = this agent's requests with `state='pending'` (expiry not
  checked) in the listed projects **plus** project-less ones; `*` or no
  subscription = all of the agent's — `src/domain/requests.ts:637-648`,
  `src/db/approvals.ts:178-203`.

### 4.5 Connect sequence

1. `retry: 2000\n\n: connected\n\n` in one chunk — `agent.ts:650` (`AGENT_RETRY_MS`, `:229`).
2. One `work` frame with current counts, always — `agent.ts:663-664`.
3. If `unread_messages > 0`: one `message` frame of what this client has not been
   handed, less what another session of the agent already had (§4.6, backlog
   across sessions; may be empty → nothing written) — `agent.ts:665`.
4. Subscribe to the bus — `agent.ts:667`.
5. Heartbeat `: heartbeat <ISO>\n\n` every 15 000 ms (`AGENT_HEARTBEAT_MS`, `:226`) — `:707-714`.

A test harness reads exactly 4 blocks on connect with one unread message: retry,
comment, work, message (`src/http/stream/agent.test.ts:654-655`).

### 4.6 Live behaviour

Only these bus types are looked at (`WATCHED`, `agent.ts:201-223`):
`message.created, message.deleted, messages.read, update.updated,
message.updated, reaction.updated, request.created, request.answered`
(Kotlin server: and `subscriptions.updated`, §4.8).
Anything else is ignored (e.g. `update.deleted`, `request.updated`).

Per watched event (`agent.ts:667-705`):

1. Recompute the three counts. If that throws, drop this event silently.
2. `request.answered` → `answer` frame if: the request is this agent's; its
   project is in scope (no project = always in scope); state is not
   `withdrawn` — `agent.ts:425-441`, `:416-423`.
3. `reaction.updated` → `reaction` frame if: actor is `human`; it is on a
   message (`messageId` not null); the message exists and is not deleted; it
   "concerns" this agent (agent wrote the line, or wrote the update it hangs on,
   or spoke anywhere in that thread); in scope — `agent.ts:499-516`, `:475-486`.
4. If the counts equal the last ones sent → stop.
5. Else write a `work` frame; and if `unread_messages` **rose**, a `message`
   frame — `agent.ts:697-704`.

Frame order per event: answer/reaction first, then work, then message.

Message de-dupe (`agent.ts:318-348`): a message is skipped if a delivery row
exists for (agent, client id) — only when `client` was given — or if this
connection already sent it (in-memory set). Sent ones are recorded as delivery
rows **before** the frame is written, which publishes `message.delivered` for
each new row — `src/domain/messages.ts:534-564`, `src/db/deliveries.ts:52-129`.
Delivery never marks a message read.

Backlog across sessions (Kotlin server): a restarted session's bridge connects
with a new client id, and many agents never call `get_messages`, so their
unread lines would come back on every restart. So a line written **before this
stream opened** is also skipped when any session of the same agent (any client
id, or none) was handed it more than 2 minutes ago (`HANDOVER_MS`,
`http/AgentStream.kt`). A line handed over in the last 2 minutes still comes,
so a bridge that dropped and reconnected catches what it may have missed; a
line no session was ever handed comes too (real catch-up), unless it was
written more than a day before the stream opened (`AgentWork.Backlog.MAX_AGE_MS`):
old history is never caught up on, handed or not, and `get_messages` still
reaches it. Nothing handed over more than a day ago is ever replayed either.
Lines written after the stream opened are live: only this connection's own
de-dupe applies, so every session sharing one token hears each new line. The
filter is in the unread query, so the 5-line limit is spent on lines the agent
has not had. Unread counts and `get_messages` (read cursors) are unchanged.

### 4.7 Close, presence

Same teardown as §3.5 (`agent.ts:633-645`, `:716-723`). Never closed by the
server; token revocation mid-stream does not cut it. Connecting does **not**
register a session or change `agent.presence`; the only side effects are the
`last_seen_at` stamp, one rate-limit unit, and delivery rows.

---

### 4.8 Subscriptions (Kotlin server, migration 052)

The server keeps what each subscriber follows: an agent (which messages reach it, so a
bridge need not be told its projects) and the owner (which alerts are raised).

- **Tables.** `project_subscriptions (subscriber, project_id, level)` and
  `thread_subscriptions (subscriber, kind, thread_id)`. `subscriber` is a principal spelled
  as a message author is: `agent:<id>`, or `human` for the owner (team members will be
  `member:<id>`). Each has `after_seq` (only messages with a higher seq reach an agent) and
  `unsubscribed_at` (unsubscribing keeps the row). `level` is the owner's (below); an
  agent's is always `all`. A thread is an
  update (`kind = update`, its comments), a request (`request`, its discussion), or a feed
  post and its replies (`message`, keyed by the post). A message's thread: `request_id`,
  else `update_id`, else `reply_to ?? id`; a message on an old task has none.
- **Following happens by itself.** Posting a message follows its thread; a top-level post
  (no `update_id`, `request_id`, `reply_to` or `answers`) also follows its project, from
  now, so the channel's history never turns unread. A reply in a thread never follows the
  channel. `post_update` follows the project and the update's thread; `request_input`
  follows the request's thread; every agent a message @mentions (`@here` / `@everyone`
  included) follows its thread from that message on. What was said before that is never unread; the agent reads it
  with `get_messages({message_id})` (§4.9). Editing a message in a new mention
  does the same. Nothing unfollows except MCP `unsubscribe`, and posting never undoes
  one: only `subscribe` does.
- **Seed.** Migration 052 follows, from seq 0, every project an agent has an update, a
  task or a live message in: exactly the old "worked in" set. No threads are seeded.
- **Reach.** A message reaches an agent when it passes the @mention audience rule and:
  it is in a followed project, or in a followed thread, or names the agent. An agent that
  follows no project hears none: only its threads and mentions. A thread the agent follows
  reaches it even in a channel it doesn't. A `?project=` / `?channel=` list only narrows the
  channels it follows to those named (and messages in no channel), for messages, counts and
  frames alike. It never hides a conversation the agent is in: a thread it follows (posting,
  replying or an @mention follows one) and a mention of it still reach it anywhere, so an
  owner's unmentioned reply in such a thread is pushed on a narrowed stream too. `*` or no
  list is no narrowing, and an unknown name narrows its channels to nothing (the TS server
  used the list instead of the follows, still hearing followed threads and mentions anywhere).
  `get_messages` with `channel` is not a bridge: it reads only what reaches the agent in that
  channel, plus mentions of it. Channels the owner removed the agent from are not heard
  (spec/12, migration 060). The same
  reach drives `get_messages` (without `project`), heartbeat counts, `open_tasks`
  (followed projects) and `list_work`.
- **Live.** The stream reads the subscriptions again on every watched event, so a new
  subscription, an unsubscribe, or the owner adding or removing the agent takes effect without
  reconnecting. On `message.created` it looks for new messages even when the unread count did
  not move (one left the agent's reach as another arrived).
- **MCP.** `subscribe` / `unsubscribe` take exactly one of `project`, `update_id`,
  `message_id` (its thread), `request_id`; `list_subscriptions` lists them.
- **Owner: what raises an alert.** Each project has a level: `all` (no row: the default),
  `replies` or `muted`. The owner follows a thread by posting or replying in it (then
  `subscriptions.updated {subscriber: "human"}`), or by choice, and can unfollow one (a row
  with `unsubscribed_at`). For a new card, an agent's message or a question:
  1. priority `critical` → raised (paging stays each phone's own choice);
  2. a thread the owner follows → raised, even in a muted project; unfollowed → not;
  3. else `all` → raised; `replies` → only questions, and messages that answer the owner
     (a reply to their post, or in a thread they spoke in) or name them (`@you`, `@owner`);
     `muted` → not.
  An agent asking to pair is always raised. This decides whether the bell row is written
  and whether APNs alerts go out at all; each phone's own alert preferences (migration
  047) then decide which phones ring, as before. Alert and Live Activity payloads are
  unchanged.
- **Owner API.**
  - `GET /api/cards/{kind}/{id}/followers` → `{thread: {kind, id}, followers: [{id, name}],
    owner: {following, chosen}}`: the live agents following the thread that card is in,
    and the owner's state (`chosen` false: no choice made, so `following` is the
    project's level being `all`).
  - `PUT /api/cards/{kind}/{id}/follow {following}` → `{thread, owner}`.
  - `PUT /api/projects/{reference}/subscription {level}` → `{projectId, level}`.
  - `GET /api/subscriptions` → `{seq, at, projects: [{projectId, level}], threads: [{kind,
    id, following}]}`: every choice the owner has made, for the app to hold.

### 4.9 A mention opens the whole thread (Kotlin server)

Following a thread hears only what is said after the follow starts (§4.8, `after_seq`). An agent an
@mention brings into a thread therefore never got the post that started it, nor its files: a
root posted with no @mention reaches nobody who doesn't follow its channel. So:

- **Read it.** MCP `get_messages` with exactly one of `message_id` (the thread that message is in),
  `update_id` or `request_id` returns the whole thread instead of what is unread: `thread: {kind,
  id, project_id, channel, card}` (the card it hangs on: an update's title, body, status, fields
  and files; a question's text, kind, detail, state and options; a feed post's status and fields),
  then `messages` — every live line, oldest first (a feed post's root first), each as get_messages
  shows a line, attachments with `download_url` included — and `count`, `withheld`, `earlier`. It
  moves no read cursor and ignores `since`, `channel` and `mark_read`; the lines it hands over are
  marked delivered. At most 200 replies (the newest), plus the root; `earlier` counts the rest.
- **Who may read it.** An agent that follows the thread (an @mention follows it, as does posting
  in it or `subscribe`), is @mentioned anywhere in it, wrote in it, wrote its card, or hears its
  channel. Anyone else gets `forbidden`. Another team's thread is `not_found`, by any id.
- **Which lines.** The @mention audience rule still holds: a line that @names agents is for them
  and its author. One exception, for people: when a person (`human`) @mentioned this agent in the
  thread, every line a person wrote there is shared with it, so "@b please help @a" lets b read
  what the owner told a. A line one agent addressed only to other agents stays theirs. Lines held
  back are counted in `withheld`, never shown. Quotes, titles and work summaries elsewhere are
  unchanged (`Mentions.readable`).
- **Files.** `/api/agent/media/{id}` serves any attached media of the agent's own team to its
  token (§8 of spec/07), so the `download_url`s in the read work as they are.
- **Being told.** The mention line says so: `thread: {kind, id}` and a hint in its body on the
  stream (§4.3), and in `get_messages` the same `thread` key plus a summary line naming the
  message to pass as `message_id`.

## 5. Snapshot cursor

`createSnapshotHandler` — `src/http/stream/snapshot.ts:360-381`. Used by
`/api/snapshot`, `/api/snapshot/updates`, `/api/snapshot/agents`,
`/api/snapshot/requests` (`src/http/routes/api/snapshot/**/+server.ts`).

- `seq = bus.lastSeq` is read **before** the state — `snapshot.ts:369`.
- Body: `{ seq, at, ...state }` — `snapshot.ts:379` (state keys come after).
- Headers: `content-type: application/json; charset=utf-8`, `cache-control: no-store`
  — `snapshot.ts:419-427`.
- Meaning: the state accounts for every event ≤ `seq`. Clients drop frames with
  `seq <= snapshot.seq` and then resume the stream with `?last_event_id=<seq>`
  (`src/web/timeline.svelte.ts:470`, `src/web/stream.ts:231`). An event published
  during the read is applied twice at worst (harmless; payloads are ids).
- Same "lastSeq before read" stamp also on: page load `src/http/routes/dashboard.ts:53`,
  message list `src/http/owner/messages.ts:166`, notification list
  `src/http/owner/notifications.ts:44`.

---

## 6. Clients (confirmation)

- Browser — `src/web/stream.ts`: one `EventSource` (shared across tabs by a
  lock + BroadcastChannel). Reconnects with `?last_event_id=<cursor>` when
  cursor > 0 (`:231`); listens only to the names in `EVENT_TYPES` (`:65-108`;
  `messages.read` is not among them). Seq from `lastEventId`, else `data.seq`
  (`:199-208`). On `resync` the cursor is **set** to its seq, otherwise
  `max(cursor, seq)` (`:293`).
- Native app — `LiveStream.kt`: sends both `Last-Event-ID` header and
  `last_event_id` query when cursor > 0 (`:277-278`); 45 s of no lines = dead
  (`:126`); on `resync` sets cursor to its id or `data.seq` (`:252`), else max
  (`:256`); 401 = signed out, stop.
- Channel bridge / monitor — `src/channel/bridge.ts`: `?project=` repeated per
  slug + `?client=bridge-<uuid>` (`:637-644`); splits on `\n\n` and lines on
  `\n` (**LF only**), keeps last value per field, trims leading space
  (`:519-547`); 90 s byte silence = dead (`:274`); backoff 1,2,5,10,30 s
  (`:248`); acts on `work`, `message`, `answer`, `reaction` (`:840-895`).
  The monitor reuses `runBridge` (`src/channel/monitor.ts:126-139`).

---

## Surprises

1. **Live order is not seq order.** Nested publishes (§1.7) make every owner
   stream get `notification.created` N+1 before `update.created`/`message.created`/
   `request.created` N, and `message.delivered` can come before `message.created`
   depending on connect order. Replay is ordered; live is not. Clients cope
   because they use `max(seq)` and refetch on every frame.
2. **Cursors have no boot epoch.** After a restart seq starts at 1 again. A
   client cursor larger than the new `lastSeq` gets `resync` (`ahead`), but one
   that is *smaller* (e.g. cursor 5, new process already at 40) is a **hit** and
   silently replays the new process's events 6..40 as if continuous — a hidden gap.
3. `task.created` / `task.updated` are declared and listened for, but nothing
   publishes them (tasks module deleted).
4. Card-announce payloads lie a little: `message.updated` with `author: ""` and
   no thread keys; `request.updated` with `agentId: ""`; `update.updated` with
   `projectId: ""` if null (`src/domain/cards.ts:118-140`).
5. `message.delivered` fires once per (message, agent, **client**), not once per
   agent as `src/events/types.ts:171-173` claims.
6. `messages.read.cursor` is a message seq, not a bus seq — same word, different
   number space.
7. The agent stream's rate limiter is its **own** instance (default param,
   `agent.ts:565`; route passes no options). `/mcp` (`src/mcp/server.ts:125`)
   and `/api/agent/media` (`src/http/media/agent.ts:58`) each have another. The
   comment "Shared with `/mcp` in production" (`agent.ts:240`) is false. Its 429
   also has no `Retry-After` header.
8. A refused subscription (404/400) still costs a rate unit and stamps
   `last_seen_at` (checked after auth).
9. Scope rules disagree on project-less items: unread messages with a project
   list **exclude** project-less messages (unless mentioned), but answers,
   reactions and `pending_approvals` **include** project-less ones.
10. `open_tasks` counts every open card in scope by anybody, and with `*` the
    whole deployment; `pending_approvals` counts expired-but-unswept requests
    (sweeper every 30 s, `src/domain/requests.ts:146`).
11. `update.deleted` is not watched by the agent stream, so deleting an open
    update leaves `open_tasks` stale until another watched event.
12. Auth is checked only at connect on both streams; revoking a token or device
    key does not end an open stream.
13. `ack.updated` and `agent.renamed` publish even when nothing changed.
14. A rejected answer to an expired request still publishes
    `request.answered{state:"timeout"}` before the 409 (`expireIfDue`,
    `src/domain/requests.ts:392`, `:843-850`).
15. The agent stream writes an empty chunk when a `message`/`answer`/`reaction`
    frame comes out empty (`write('')`) — harmless, no bytes.
16. HEAD on either stream runs the GET handler (SvelteKit falls back,
    `node_modules/@sveltejs/kit/src/runtime/server/endpoint.js:19-21`), so it
    authenticates, subscribes and (agent) stamps/rate-limits.

## Parity test notes

Black-box checks for the Kotlin server. "Owner" = valid cookie or device bearer;
"agent" = valid agent bearer.

Owner stream:

1. No auth → `401`, `content-type: application/json`, body exactly `{"error":"unauthenticated"}`.
2. Owner connect → `200`, the four headers in §2 exactly; first bytes exactly
   `retry: 2000\n\n: connected\n\n`; no `content-encoding` even with
   `Accept-Encoding: gzip, br`.
3. Idle connection → `: heartbeat <ISO>\n\n` arrives at ~15 s, again at ~30 s.
4. Connect, then create a project via MCP `create_project` → frame
   `id: N\nevent: project.created\ndata: {"type":"project.created","seq":N,"at":"…","payload":{"projectId":"…","slug":"…"}}\n\n`.
   `create_project` again with same name → no frame.
5. Post an update via MCP → `notification.created` (N+1) arrives **before**
   `update.created` (N) (match the TS order, §1.7).
6. Read `lastSeq` = L from `/api/snapshot` `seq`; publish 3 events; reconnect
   with `Last-Event-ID: L` → exactly those 3 frames in seq order, then live.
   Same with `?last_event_id=L` and no header. Header wins when both valid;
   query used when header is garbage (`abc`, `-1`, `1.5`).
7. Cursor = current `lastSeq` → no replay frames, just live.
8. Cursor 0 on a fresh server with < 500 events → replays every event since boot.
9. Push > 500 events, connect with a cursor below `lastSeq - 500` → one
   `id: <lastSeq>\nevent: resync\ndata: {"type":"resync","reason":"expired","from":<c>,"seq":<lastSeq>}\n\n`, then live.
   Cursor `lastSeq - 500` → hit (boundary).
10. Restart server, connect with an old large cursor → `resync` reason `ahead`;
    with zero events since boot the frame has **no `id:` line** and `"seq":0`.
11. Frames for `messages.read`, `agent.presence`, `message.delivered` reach the
    owner stream (no filtering).
12. `agent.presence`: `register_session` for an offline agent → `online:true`
    once; second session → nothing; `end_session` of last session →
    `online:false` with that `sessionId`.
13. Disconnect the client → server drops its listener (no growth in memory /
    timers across many connect/disconnect cycles).

Agent stream:

14. No header → `401 {"error":"missing_token","message":"missing Authorization header: send Authorization: Bearer <agent token>"}`;
    `Basic x` → `401 unsupported_scheme`; bad shape → `401 malformed_token`;
    revoked → `401 revoked_token`.
15. `?project=nope` → `404 {"error":"not_found","message":"no such project: nope"}`;
    `?project=*,a` → `400 invalid_argument`.
16. 121 connects in 60 s → the 121st is `429 rate_limited`, no `Retry-After`;
    `/mcp` calls in the same window do not count against it.
17. Connect `?project=<slug>&client=c1` with nothing waiting → bytes
    `retry: 2000\n\n: connected\n\n` then
    `event: work\ndata: {"type":"work","unread_messages":0,"open_tasks":0,"pending_approvals":0,"at":"…"}\n\n`. No `id:` lines ever.
18. Owner posts a message in that project → `work` (unread 1) then `message`
    frame with all 15 item keys in order; owner stream sees `message.delivered`
    once. Reconnect with the same `client=c1` → work frame (unread 1) and **no**
    message frame. Connect with `client=c2` → message frame again, and a second
    `message.delivered` on the owner stream.
19. Same message, no `client` param, two connections → each gets the message
    frame; only one `message.delivered` event (null-client dedupe).
20. Agent calls `get_messages` → `work` frame with unread 0, no message frame.
21. Message in another project (not subscribed, no `@mention`) → no frame.
    Same with `@agentname` → work + message frames.
22. Agent `request_input`, owner answers → `answer` frame first, then `work`
    with `pending_approvals` dropped. Agent `withdraw_request` → no answer
    frame, only the `work` drop.
23. Owner reacts on the agent's message → one `reaction` frame
    (`on:true`), removes it → `on:false`; no `work` frame. Another agent's
    reaction → nothing.
24. Owner `set_status` on any card in the subscribed project to `todo` →
    `work` with `open_tasks` +1; then `pin` it → no frame (counts unchanged).
25. Idle agent stream → `: heartbeat <ISO>\n\n` at ~15 s.

Snapshot:

26. `GET /api/snapshot` → `seq` equals the `id` of the last frame on an
    already-open owner stream; headers `application/json; charset=utf-8` and
    `cache-control: no-store`.
