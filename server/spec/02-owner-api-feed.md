# 02 — Owner HTTP API: the feed

What the owner (the browser or the native app) can do to projects, updates, messages and cards.
Source of truth: the working tree of `agent-dashboard`. Every rule cites `file:line`.

Routes covered:

| Method | Path | Handler |
| --- | --- | --- |
| POST | `/api/projects` | `createProjectHandler` `src/http/owner/actions.ts:76` |
| PATCH | `/api/projects/[reference]` | `patchProjectHandler` `src/http/owner/actions.ts:88` |
| GET | `/api/projects/[reference]/tags` | `listTagsHandler` `src/http/owner/tags.ts:16` |
| POST | `/api/projects/[reference]/tags` | `defineTagHandler` `src/http/owner/tags.ts:31` |
| PATCH | `/api/updates/[id]` | `patchUpdateHandler` `src/http/owner/actions.ts:97` |
| DELETE | `/api/updates/[id]` | `deleteUpdateHandler` `src/http/owner/actions.ts:113` |
| GET | `/api/messages` | `listMessagesHandler` `src/http/owner/messages.ts:162` |
| POST | `/api/messages` | `postMessageHandler` `src/http/owner/messages.ts:98` |
| DELETE | `/api/messages/[id]` | `deleteMessageHandler` `src/http/owner/messages.ts:114` |
| POST | `/api/messages/[id]/reactions` | `reactHandler` `src/http/owner/messages.ts:135` |
| POST | `/api/cards/[kind]/[id]/pin` | `pinCardHandler` `src/http/owner/cards.ts:48` |
| POST | `/api/cards/[kind]/[id]/status` | `setCardStatusHandler` `src/http/owner/cards.ts:67` |
| POST | `/api/cards/[kind]/[id]/reactions` | `reactToCardHandler` `src/http/owner/cards.ts:86` |
| POST | `/api/cards/[kind]/[id]/tags` | `tagCardHandler` `src/http/owner/cards.ts:115` |
| POST | `/api/cards/[kind]/[id]/fields` | `setCardFieldHandler` `src/http/owner/cards.ts:146` |
| PATCH | `/api/tags/[id]` | `renameTagHandler` `src/http/owner/tags.ts:53` |
| DELETE | `/api/tags/[id]` | `retireTagHandler` `src/http/owner/tags.ts:81` |
| PATCH | `/api/agents/[id]` | `renameAgentHandler` `src/http/owner/actions.ts:127` (lives in `actions.ts`) |

There is **no** endpoint at `/api/cards/[kind]` or `/api/cards/[kind]/[id]` themselves; only the five sub-routes above exist (`src/http/routes/api/cards/[kind]/[id]/*/+server.ts`). There is no `GET /api/projects` (the project list comes from `GET /api/snapshot`, another file).

Route files are one-line mounts: each exports only the methods in the table (e.g. `src/http/routes/api/tags/[id]/+server.ts:13-14`).

---

## 1. Common behaviour (every route above)

### 1.1 Auth

Two locks, same rule.

1. The hook (`src/hooks.server.ts:193`, `src/http/auth/handle.ts:39-56`) runs for every matched route whose path is not exempt (`src/http/auth/guard.ts:33,39`: `/mcp`, `/api/upload`, `/s`, `/api/agent`, `/login`, `/logout`, `/api/devices/sign-in`). All routes here need auth.
2. The handler wrapper `ownerAction` = `handle` (`src/http/owner/actions.ts:145-163`) checks again with `ownerAuthenticated` (`src/http/stream/owner.ts:60-72`).

Accepted credentials:

- `Authorization: Bearer <token>` matching `/^bearer\s+(\S+)$/i` after trim (`src/http/auth/bearer.ts`). The token must be a live owner device key (`environmentDevices`, `src/http/stream/owner.ts:32-40`). **If a bearer is present it decides alone**: a bad bearer plus a valid cookie is refused (`src/http/stream/owner.ts:66-67`, `src/http/auth/handle.ts:45-47`).
- Otherwise the signed session cookie `ad_session` (`src/http/auth/session.ts:17`).

Refusal (both locks, identical body):

```
401
content-type: application/json
{"error":"unauthenticated"}
```

(`src/http/auth/handle.ts:22-25`, `src/http/stream/owner.ts:78-83`). No `cache-control` on this one.

### 1.2 Success and domain-error responses

All handler responses go through `json()` (`src/http/owner/actions.ts:295-303`):

```
content-type: application/json; charset=utf-8
cache-control: no-store
body: JSON.stringify(body)   // compact, no spaces, key order = object build order
```

Domain errors (`src/domain/errors.ts:12-53`) map by code (`src/http/owner/actions.ts:52,159-161`):

| code | HTTP |
| --- | --- |
| `invalid_argument` | 400 |
| `not_found` | 404 |
| `conflict` | 409 |

Body: `{"error":"<code>","message":"<text>"}` — exactly two keys, in that order. Message texts below are exact.

Any other thrown error is re-thrown (`src/http/owner/actions.ts:160`) to SvelteKit: 500. With `Accept: application/json` the body is `{"message":"Internal Error"}`, otherwise an HTML error page (`node_modules/@sveltejs/kit/src/runtime/server/utils.js:75-93`; no `handleError` hook is defined).

### 1.3 Reading the body

Two modes. Which one a route uses matters for error text.

- **Strict** `readJson` (`src/http/owner/actions.ts:172-184`): unparseable → 400 `body must be JSON`; `null`, array or a scalar → 400 `body must be a JSON object`. Used by: POST/PATCH projects, PATCH updates, POST messages, PATCH agents.
- **Lenient** `readOwnerJson(...).catch(() => ({}))`: any bad body becomes `{}`, so the caller gets the first field error instead. Used by: all `/api/cards/...` routes, tag routes, `POST /api/messages/[id]/reactions` (`src/http/owner/cards.ts:50,69,88,117,148`; `src/http/owner/tags.ts:34,56`; `src/http/owner/messages.ts:137`).
- Content-Type is not checked by the handlers (`request.json()` parses anything). But see SvelteKit CSRF (§9).
- Unknown body keys are ignored, except where noted (PATCH update, PATCH agent refuse them).

### 1.4 Other framework behaviour

- Wrong method on an existing route (after auth passes): `405`, `text/plain`, body `"<METHOD> method not allowed"`, header `allow:` listing exported methods in order `GET, POST, PUT, PATCH, DELETE, OPTIONS, HEAD` (HEAD added when GET exists) (`node_modules/@sveltejs/kit/src/runtime/server/utils.js:17-36`). Unauthenticated wrong-method → 401 first (hook runs first).
- Unmatched path (e.g. `/api/cards/update/X`): `route.id` is null so the hook skips auth (`src/http/auth/handle.ts:43`); SvelteKit 404.
- Responses ≥1400 bytes may be compressed (br/gzip) by `src/http/compress.ts` (covered elsewhere).

### 1.5 Events

Every write publishes on the in-process bus (`src/events/bus.ts:85-97`): envelope `{type, seq, at, payload}`; `seq` is a per-process counter starting at 0 on boot, `at` is `new Date().toISOString()`. Payload types: `src/events/types.ts`. Consumers relevant here: browser/app stream (`src/web/stream.ts:66-106`), agent stream (`src/http/stream/agent.ts:201-221`), Live Activity pusher (`src/domain/live-activity.ts:40-52`).

**Push / notifications:** none of the owner writes here trigger Web Push, APNs alerts or a notification row. All three listen to `message.created`, but bail out when the author is not an agent (`src/domain/push.ts:566-567`, `src/domain/notifications.ts:175`, `src/domain/apns.ts:396` via `replyMessage`). The Live Activity pusher does refresh on `update.updated`, `update.deleted`, `message.created|updated|deleted`, `project.created|updated`, `request.updated`, `agent.renamed` (`src/domain/live-activity.ts:40-52`).

### 1.6 Shared JSON shapes (exact key order)

Times are epoch ms (integers). Ids are 26-char ULIDs (`src/db/ids.ts`, `isId` = `/^[0-9A-HJKMNP-TV-Z]{26}$/`). `seq` is the row's SQLite autoincrement.

**Project** (`src/db/projects.ts:29-43`):
`seq, id, slug, name, description, status, pinned, createdAt, updatedAt, theme, board`
- `description`: string|null. `status`: `"active"|"archived"`. `pinned`: bool.
- `theme`: object|null, parsed from stored JSON text, keys in stored order; subset of `background, accent, logoMediaId, logoReplacesName` (`src/db/types.ts:34-49`).
- `board`: `{columns:[{title, states:[...]}]}`|null.

**Update** (`src/db/updates.ts:38-57`):
`seq, id, projectId, agentId, sessionId, title, body, level, pinned, createdAt, deletedAt, editedAt, priority, taskId, status, statusAt`

**Message** (`src/db/messages.ts:47-66`):
`seq, id, projectId, updateId, taskId, author, body, createdAt, replyTo, deletedAt, answers, requestId, editedAt, pinned, status, statusAt`
- `author`: `"human"` or `"agent:<agentId>"` (`src/domain/messages.ts:78-91`).

**CardRef**: `{"kind": "update"|"message"|"request", "id": "<id>"}`.

**Reaction** (`src/db/reactions.ts:33-49`):
`seq, id, card, messageId, actor, emoji, createdAt`. `messageId` is the card id when kind is message, else null. `card` is chosen from the row's anchor columns in the order message → update → request. A row with none of those three (a legacy task reaction) reads `{"kind":"request","id":null}`; acknowledgements, card tags, fields and app views fall back the same way.

**Tag** (`src/db/tags.ts:32-43`): `seq, id, projectId, name, slug, colour, agentId, createdAt`.

**CardField** (`src/db/fields.ts:33-52`): `seq, id, card, name, slug, value, author, createdAt, updatedAt`. `card` from anchors update → message → request.

**Acknowledgement** (`src/db/acknowledgements.ts:34-54`): `seq, id, agentId, card, messageId, taskId, updateId, requestId, state, createdAt, updatedAt`. `state`: `thinking|read|done`.

**Delivery** (`src/db/deliveries.ts:36-45`): `seq, id, messageId, agentId, deliveredAt, clientId`.

**MediaAttachment** (`src/domain/media.ts:556-573`): `id, updateId, messageId, requestId, taskId, kind, mime, status, bytes, width, height, durationMs, filename, variants`. `variants` from `derivativesFor` (`src/media/serve.ts:224`), media spec covers it.

**EmbedView** (`src/domain/embeds.ts:783-795`): `id, updateId, messageId, requestId, taskId, type, typeName, spec, data, sourceUrl, createdAt`. `type` = embed type slug, `typeName` = its name. `spec` = stored spec re-parsed by `parseEmbedSpec`; `data` = stored data re-parsed by `parseEmbedData`, or `{}` if that throws. An embed whose type row is missing or whose spec fails to parse is **dropped** (`src/domain/embeds.ts:770-775,809-813`). Embeds are never written by these routes; they only appear in `GET /api/messages`.

---

## 2. Projects

### 2.1 `POST /api/projects`

Body (strict JSON object, `src/http/owner/actions.ts:187-192`):

| field | type | rule |
| --- | --- | --- |
| `name` | string, required | else 400 `name must be a string` |
| `description` | string or null, optional | non-string non-null → 400 `description must be a string` |
| `slug` | string, optional | **`null` is refused**: 400 `slug must be a string` |

Domain `createProject` (`src/domain/projects.ts:158-175`), in order:
1. `name` trimmed; blank → 400 `name is required`; >200 UTF-16 units → 400 `name must be at most 200 characters` (`src/domain/text.ts:14-40`, `src/domain/projects.ts:29`).
2. slug = `slugFor(name, slug)` (§7.1). Errors: bad explicit slug → 400 `slug must be lowercase letters, digits and single hyphens, at most 64 characters`; nothing derivable → 400 `could not derive a slug from name; pass slug explicitly`.
3. `description` trimmed; blank → null; >2000 → 400 `description must be at most 2000 characters`.
4. Existing project with that slug → return it **untouched**, `created:false`, no event.
5. Else insert (status `active`, pinned false, `createdAt = updatedAt = now`, theme/board null) and publish `project.created {projectId, slug}`.

Response: `201` if created, else `200` (`src/http/owner/actions.ts:83`):

```json
{"project":{"seq":7,"id":"01JD...","slug":"fee-service","name":"Fee service","description":null,"status":"active","pinned":false,"createdAt":1763470000000,"updatedAt":1763470000000,"theme":null,"board":null},"created":true}
```

Idempotent on slug. Validation (incl. description length) still runs on a repeat.

### 2.2 `PATCH /api/projects/[reference]`

`reference` = slug or id (§7.2). Body (strict, `src/http/owner/actions.ts:201-213`); only these keys are read:

| field | type | handler error |
| --- | --- | --- |
| `name` | string | `name must be a string` |
| `description` | string or null | `description must be a string` |
| `slug` | string | `slug must be a string` |
| `status` | `"active"` or `"archived"` | `status must be active or archived` |
| `pinned` | boolean | `pinned must be true or false` |
| `theme` | object or null | `theme must be an object, or null to clear it` |
| `board` | anything; checked in domain | — |

Inside `theme` (`src/http/owner/actions.ts:223-239`): `background`, `accent`, `logoMediaId` each string or null (`<field> must be a string`); `logoReplacesName` boolean or null (`logoReplacesName must be true or false`). Other theme keys are ignored.

Body shape errors fire **before** the project lookup. Then `updateProject` (`src/domain/projects.ts:242-288`), in order:
1. Resolve reference → 400 `project is required` (blank), 404 `no such project: <ref trimmed>`.
2. `name`: as create. `description`: as create (blank → null).
3. `status`, `pinned`: stored as given.
4. `theme`: `null` clears the whole theme; an object is merged (§7.3).
5. `board`: `assertBoard` (§7.4); `null` stores null (the default board).
6. `slug`: `assertSlug`; held by another project → **409** `slug already in use: <slug>`.
7. No recognised field given → 400 `update requires at least one field to change`. (So `{}` and `{"foo":1}` both give this, but only after the project resolved.)
8. Write all fields plus `updatedAt = now`, publish `project.updated {projectId, slug}` (new slug).

Not quiet: a patch that changes nothing (same name) still bumps `updatedAt` and publishes.

Response `200 {"project": Project}`.

### 2.3 `GET /api/projects/[reference]/tags`

No body. Resolves project (404/400 as above). Response `200 {"tags":[Tag...]}` ordered by `seq` ascending (`src/domain/tags.ts:148-151`, `src/db/tags.ts:90-98`).

### 2.4 `POST /api/projects/[reference]/tags`

Body (lenient): `name` string (else 400 `name must be a string`); `colour` optional, string or null (else 400 `colour must be a palette name, or null`) (`src/http/owner/tags.ts:31-49`).

`defineTag` (`src/domain/tags.ts:109-145`), in order:
1. Resolve project (404 `no such project: <ref>`).
2. Name trimmed; empty → 400 `a tag needs a word`; >32 → 400 `a tag may be at most 32 characters`.
3. Slug = `tagSlug(name)` (§7.5); empty → 400 `<JSON.stringify(original name)> has no letters or digits in it`, e.g. `"!!!" has no letters or digits in it`.
4. Colour not null/undefined and not one of `grey, rose, amber, green, blue, violet` → 400 `colour must be one of grey, rose, amber, green, blue, violet`.
5. Tag with that slug exists in the project → return it as-is (colour **not** updated), no event.
6. Project already has ≥50 tags → 400 `this project already has 50 tags, which is as many as a picker can show`.
7. Insert (`agentId` null for owner), publish `tags.changed {projectId}`.

Response always `200 {"tag": Tag}` (never 201).

### 2.5 `PATCH /api/tags/[id]`

Body (lenient): `name` absent or a string (else 400 `name must be a string`); `colour` absent, null or a string (else 400 `colour must be a palette name, or null`) (`src/http/owner/tags.ts:53-73`).

`renameTag` (`src/domain/tags.ts`), in order:
1. Unknown id → 404 `no such tag: <id>`.
2. Colour not null/absent and not a palette name → 400 `colour must be one of grey, rose, amber, green, blue, violet`.
3. `name` given: trimmed; the same three checks as §2.4 step 2-3; another tag in the project with that slug → **400** (not 409) `this project already has a tag called <JSON.stringify(its name)>`.
4. Write `name`/`slug` (when given) and `colour` (when given; `null` clears it), publish `tags.changed {projectId}`. Always: `{}` writes the same row and still publishes.

Response `200 {"tag": Tag}`.

### 2.6 `DELETE /api/tags/[id]`

No body. Unknown id → 404 `no such tag: <id>` (so a second delete is a 404). Deletes the tag's `card_tags` rows and the tag in one transaction, publishes `tags.changed {projectId}`. Response `200 {"tag":{"id":"<id>","projectId":"<project id>"}}`.

---

## 3. Updates

### 3.1 `PATCH /api/updates/[id]`

Body (strict) must have **exactly one key, `pinned`**; anything else (extra keys, `{}`) → 400 `an update patch changes pinned and nothing else`. `pinned` non-boolean → 400 `pinned must be true or false` (`src/http/owner/actions.ts:249-255`).

`setUpdatePinned` → `pinCard({kind:'update', id})` (`src/domain/updates.ts:259-262`, §6.1): unknown or soft-deleted → 404 `no such update: <id>`. Same value → no write, no event. Else write and publish `update.updated {updateId, projectId, pinned}`.

Response `200 {"update": Update}` (raw row: no media, embeds, share or reactions).

### 3.2 `DELETE /api/updates/[id]`

No body read. `deleteUpdate` (`src/domain/updates.ts:231-240`):
- Unknown → 404 `no such update: <id>`.
- Already deleted → `200` with the row, no event (idempotent).
- Else set `deleted_at = now` (`src/db/updates.ts:158-164`), publish `update.deleted {updateId, projectId}`.

Nothing cascades: its thread messages, reactions, media, fields, tags stay.

Response `200 {"update": Update}` with `deletedAt` set.

---

## 4. Messages

### 4.1 `GET /api/messages`

Query params (`src/http/owner/messages.ts:258-278`); empty values are ignored except `limit`:

| param | meaning |
| --- | --- |
| `update` | only messages with `update_id` = this |
| `request` | only messages with `request_id` = this |
| `task` | only messages with `task_id` = this (legacy; never set now) |
| `project` | slug or id; resolved, 404 `no such project: <ref>` if unknown |
| `replyTo` | one feed post and its replies (overrides all others) |
| `limit` | must match `/^[0-9]+$/` else 400 `limit must be a positive integer`; `0`, or digits so long that `Number()` gives `Infinity`, → same 400 from the domain; capped at 200 |

`listThread` (`src/domain/messages.ts:966-1008`):
- `limit` default **200** (`MAX_MESSAGE_LIMIT`), not 50 (`src/domain/messages.ts:967`).
- With `replyTo`: the post, if it exists and is live, then its live replies (`reply_to = post`, by seq), keeping the last `limit-1` replies. Missing/deleted post → `[]`. No project or other filter applies.
- Otherwise: the **newest** `limit` live messages matching all given filters (AND), returned oldest first. With no filter: every live message in the deployment, project-less ones included.
- Then `withParents` (`src/domain/messages.ts:1010-1028`): any `replyTo` head not in the window is fetched and added if live; result sorted by `seq` ascending.

Response `200` (`src/http/owner/messages.ts:171-205`), key order:

```json
{
  "seq": 1234,
  "at": "2026-11-18T13:01:02.345Z",
  "messages": [Message...],
  "acks": [Acknowledgement...],
  "media": {"<messageId>": [MediaAttachment...]},
  "embeds": {"<messageId>": [EmbedView...]},
  "deliveries": [Delivery...],
  "reactions": [Reaction...]
}
```

- `seq` = `bus.lastSeq` read **before** the query (`src/http/owner/messages.ts:166`).
- `acks`: all acknowledgements whose `message_id` is in the page, by seq (`src/db/acknowledgements.ts:116-136`).
- `media`/`embeds`: only keys for messages that have some; each list by seq.
- `deliveries`: the earliest delivery per (message, agent), by seq (`src/db/deliveries.ts:139-154`).
- `reactions`: all reactions on the page's messages, by seq.
- No write, no event.

### 4.2 `POST /api/messages`

The owner posts. Author is always `"human"`; any `author` key is ignored.

Body (strict; `readReply`, `src/http/owner/messages.ts:210-248`). Every key, if **present**, must be a string — `null` is refused:

| key | maps to | error if wrong type |
| --- | --- | --- |
| `body` | text, required | `body must be a string` |
| `update` | `updateId` | `update must be a string` |
| `replyTo` | `replyTo` | `replyTo must be a string` |
| `project` | `project` (slug or id) | `project must be a string` |
| `answers` | `answers` | `answers must be a string` |
| `request` | `requestId` | `request must be a string` |
| `mediaIds` | list of strings | `mediaIds must be a list` / `mediaIds[<i>] must be a string` |
| `source` | `source` (Kotlin, migration 065): `"voice"` for a transcribed voice note, else left out | `source must be a string`; any other value: `source must be "voice" or left out` |

`task` is **not read** (silently ignored). Owners cannot send embeds or apps.

`postMessage` (`src/domain/messages.ts:186-367`), checks in this exact order:

1. `body` trimmed; blank → 400 `body is required`; >10000 → 400 `body must be at most 10000 characters` (`:187`, `:70`). **Stored trimmed.**
2. `answers` (if non-empty): missing or deleted → 404 `no such message: <answers>` (`:192-195`).
3. `replyTo` and `update` both present (even `""`) → 400 `a reply answers a message, or hangs off an update, not both` (`:200-202`).
4. `requestId = request ?? answered.requestId ?? null` (`:208`). `request` present together with `update` or `replyTo` → 400 ``a message belongs to a request`s discussion, or to a card, not both`` (literal backtick in the text) (`:209-211`). Request not found → 404 `no such request: <id>` (`:212-213`). Request state is not checked.
5. `replyTo` named but not found → 404 `no such message: <id>` (`:217-219`). A **deleted** target is accepted.
6. Thread resolution (§7.6).
7. `project` (if non-empty) resolved: 400 `project is required` (whitespace only), 404 `no such project: <ref>` (`:283`).
8. Anchor project: request's project, else parent post's project, else the update's (update missing or deleted → 404 `no such update: <id>`, `:1180-1188`), else the answered message's. Anchor and named project both non-null and different → 400 `that update, task or message belongs to a different project` (`:289-296`). Final `projectId = anchored ?? named` — **may be null** (no project, no anchor is allowed).
9. `mediaIds` non-empty → `assertAttachableToMessage` (`src/domain/media.ts:338-393`): de-duplicated; >24 → 400 `an update may carry at most 24 media items`; non-ULID → 400 `not a media id: "<id>"`; then per id: 404 `no such media: <id>`; uploaded by someone else (author ≠ `human`) → 400 `media <id> was uploaded by somebody else`; already attached → 400 `media <id> is already on an update|another message|an approval`; no bytes → 400 `media <id> has no bytes yet: upload the file first`. Empty list skips this.
10. `answers` given and not in the resolved thread → 400 `that message is in a different thread` (`:313-316`, §7.6).
11. Insert; then `recordMentions` (§7.7); attach media (`src/db/media.ts:297-307`); publish:

`message.created {messageId, projectId, author:"human", updateId, replyTo, requestId, taskId}` (`:360-366`).

Response always `201 {"message": Message}` (the inserted row). Not idempotent: each call is a new row.

```json
{"message":{"seq":88,"id":"01JD...","projectId":"01JC...","updateId":null,"taskId":null,"author":"human","body":"deploy it","createdAt":1763470000000,"replyTo":null,"deletedAt":null,"answers":null,"requestId":null,"editedAt":null,"pinned":false,"status":null,"statusAt":null}}
```

Downstream: the agent stream recounts unread for agents in scope (`src/http/stream/agent.ts:201`). No push (§1.5).

### 4.3 `DELETE /api/messages/[id]`

No body. `deleteMessage` with `by: human` (`src/domain/messages.ts:477-507`):
- Unknown → 404 `no such message: <id>`.
- Already deleted → `200` with the row, no event.
- Owner may delete anyone's message.
- If the message's own `replyTo` is null, every live message with `reply_to = id` is soft-deleted too, same timestamp (`:493-495`).
- Publish `message.deleted {messageId, projectId, replies:<count>, updateId, replyTo, requestId, taskId}`.

Reactions, acks, media, mentions, fields and tags on it are left alone.

Response `200 {"message": Message}` (re-read, `deletedAt` set).

### 4.4 `POST /api/messages/[id]/reactions`

Body (lenient): `emoji` string (else 400 `emoji must be a string`), `on` optional boolean (anything non-boolean = toggle) (`src/http/owner/messages.ts:135-151`). Then exactly as §6.3 with card `{kind:"message", id}`.

Response `200 {"reactions":[Reaction...], "on": bool}`.

---

## 5. Agents (`PATCH /api/agents/[id]`)

Served by `actions.ts`, so listed for completeness. Body (strict) must have exactly one key `name` else 400 `an agent patch changes name and nothing else`; non-string → 400 `name must be a string` (`src/http/owner/actions.ts:265-271`). `renameAgent` (`src/domain/agents.ts:252-261`): 404 `no such agent: <id>`; name trimmed, blank → 400 `name is required`, >100 → 400 `name must be at most 100 characters`. Always writes and publishes `agent.renamed {agentId, name}`.

Response `200 {"agent":{"id":"...","name":"..."}}` — only those two keys (`src/http/owner/actions.ts:136`).

---

## 6. Cards: `/api/cards/[kind]/[id]/...`

### 6.0 Path and kinds

`cardFrom` (`src/http/owner/cards.ts:32-39`): `kind` must be one of `update`, `message`, `request` (`src/db/cards.ts:30`) else 400 `no such kind of card: <kind>`. **`task` is not a kind** (400). Empty id → 400 `which card?` (unreachable via routing).

Order inside each handler: body field checks → kind check → domain.

`resolveCard` (`src/domain/cards.ts:100-104`): row missing, or `deleted_at` set → 404 `no such <kind>: <id>`. Requests (`approvals` table) have no deleted column, so they always resolve if they exist (`src/db/cards.ts` `DELETED` map).

Card change event (`announce`, `src/domain/cards.ts:118-140`), by kind:
- update → `update.updated {updateId, projectId: row.projectId ?? "", pinned}`
- message → `message.updated {messageId, projectId, author: ""}` (no thread keys)
- request → `request.updated {requestId, agentId: "", projectId}`

### 6.1 `POST .../pin`

Body (lenient): `pinned` boolean, else 400 `pinned must be true or false`. `pinCard` (`src/domain/cards.ts:153-165`): resolve; same value → no write, no event; else `UPDATE <table> SET pinned` and announce. Any kind.

Response `200 {"card":{"kind","id"},"pinned":bool}`.

### 6.2 `POST .../status`

Body (lenient): `status` must be `null` or one of `todo, in_progress, blocked, done, cancelled` (`src/db/cards.ts:39`); missing or other → 400 `status must be one of the five work states, or null`.

`setStatus` (`src/domain/cards.ts:190-209`):
1. kind `request` → 400 `a request cannot be marked as work` (only `update`, `message`, `:65`).
2. Resolve (404).
3. Same as current → no write, no event.
4. Else `SET status = ?, status_at = now` (also when clearing to null) (`src/db/cards.ts:190-205`), announce. Since migration 070 the same write clears `board_hidden_at` and `board_kept_at` (§6.6): a new state puts the card back on the board.

Anyone may move any card. Response `200 {"card":{...},"status": string|null}`.

The native app's "create ticket" posts a message then calls this with `{"status":"todo"}` on `/api/cards/message/<id>/status`.

### 6.3 `POST .../reactions`

Body (lenient): `emoji` string else 400 `emoji must be a string`; `on` optional boolean (`src/http/owner/cards.ts:86-103`).

`reactToCard` (`src/domain/cards.ts:349-385`):
1. Resolve card (404) — before the emoji is checked.
2. `assertEmoji` (§7.8).
3. `already` = a row exists for (card, actor `human`, emoji). `wanted = on ?? !already`.
4. Add (`INSERT ... ON CONFLICT DO NOTHING`) or delete as needed.
5. Only if something changed, publish `reaction.updated {card:{kind,id}, messageId: id-if-message-else-null, projectId, actor:"human", emoji, on: wanted}`.

Response `200 {"reactions":[all reactions on the card, by seq], "on": wanted}`. `on` explicit is idempotent; omitted toggles.

The agent stream forwards `reaction.updated` to agents (`src/http/stream/agent.ts:693-695`).

### 6.4 `POST .../tags`

Body (lenient): `tag` non-blank string (else 400 `tag must be a tag id, or the word itself`); `on` optional boolean; `create` (only literal `true` counts) (`src/http/owner/cards.ts:115-136`).

`tagCard` (`src/domain/tags.ts:251-291`):
1. Resolve card (404). Card `projectId` null → 400 `this card does not belong to a project, so it cannot be tagged`.
2. Find tag (`resolveTag`, `:294-321`): by id first — wrong project → 400 `that tag belongs to another project`; then by `tagSlug(tag)` in the card's project; else if `create` → `defineTag` (all §2.4 rules and its own `tags.changed`); else 404 `this project has no tag called "<tag as sent>"`.
3. `wanted = on ?? !already`. Already in that state → return, no event.
4. Adding when the card has ≥8 tags → 400 `a card may carry at most 8 tags`.
5. Add (author `human`) or remove; then announce the card **and** publish `tags.changed {projectId}`.

Response `200 {"card":{...},"tag":Tag,"on":bool}`.

### 6.5 `POST .../fields`

Body (lenient): `name` string (else 400 `name must be a string`); `value` string or null, **must be present** (else 400 `value must be a string, or null to remove the field`).

`setField` (`src/domain/fields.ts:90-140`):
1. Resolve card (404). Any kind, including request.
2. Name trimmed; empty → 400 `a field needs a name`; >40 → 400 `a field name may be at most 40 characters`.
3. Slug = `fieldSlug(name)` (§7.5); empty → 400 `<JSON name> has no letters or digits in it`.
4. `value: null` → delete by (card, slug); none → 404 `this card has no field called "<trimmed name>"`; else announce + `fields.changed {projectId}` → `set:false`.
5. Value trimmed; empty → 400 `a field needs a value, or null to remove it`; >500 → 400 `a field value may be at most 500 characters`.
6. New slug and card already has ≥20 fields → 400 `a card may carry at most 20 fields`.
7. Same value **and** same name as stored → no write, no event.
8. Upsert on (card, slug): new row, or update `name, value, author, updated_at` keeping `id, seq, createdAt` (`src/db/fields.ts:70-84`). Announce + `fields.changed`.

Response `200 {"card":{...},"fields":[all fields on the card, by seq],"set":bool}`.

### 6.6 The board (migration 070, Kotlin server only)

`GET /api/board?channel=<ref>|group=<ref>&removed=1&column=<state>&after=<cursor>&limit=<n>` (member). Cards with a work state, a column per state: `todo`, `in_progress`, `blocked`, `done` (`done` also holds `cancelled`). `channel` is one channel, `group` every channel of a project, neither every channel the viewer can read; a hidden channel or project answers 404, and hidden channels never appear. Each column lists its newest move first (`COALESCE(status_at, created_at)`, then id), `limit` (default 50, at most 200) at a time, with `more` and an `after` cursor; `column` + `after` reads one column's next page. A Done card leaves the board when it was removed (`board_hidden_at`) or when `COALESCE(board_kept_at, status_at, created_at)` is older than the team's auto-remove age; `removed=1` lists those too, marked `removed` with `removedBy` `"someone"` or `"age"`.

Response `200 {"columns":[{"status","cards":[Card],"more","after"}],"autoRemoveDays": int|null}`. A card: `card {kind,id}`, `projectId`, `channel {slug,name}`, `status`, `statusAt`, `createdAt`, `movedAt`, `title` (an update's title, else the first line), `author`, `on` (who is on it: agents with a `thinking` acknowledgement, else the update's agent, the last agent to reply, or the person who wrote it; `{kind,id,name}`), `replies`, `lastReplyAt`, `waiting` (pending requests on it or in its thread), `removed`, `removedBy`, `canMove` (Post on its channel).

`POST /api/cards/[kind]/[id]/board` (member, Post on the channel): body `removed` boolean (else 400). Only `update`/`message` cards whose status is `done` or `cancelled` (else 400). `true` sets `board_hidden_at = now`; `false` clears it and sets `board_kept_at = now`, so the age counts again from then. Announces the card. Response `200 {"card":{...},"removed":bool}`. Nothing is deleted.

`GET /api/settings/board` (member) answers `{"autoRemoveDays": int|null}`; `PUT` (admin) takes `autoRemoveDays` 1, 3, 7 or 30, or `null` for never (else 400), kept per team in `server_settings` (`board.auto_remove_days`), and publishes `board.settings {autoRemoveDays}`. The default is 7.

---

## 7. Domain rules to copy exactly

### 7.1 Project slug (`src/domain/slug.ts`)

- `SLUG_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/`, max 64 (`:13-16`).
- `assertSlug(v)`: `v.trim().toLowerCase()`, must match pattern and length ≤64 (`:47-55`).
- `slugify(v)` (`:24-33`): NFKD normalise → remove `[̀-ͯ]` → lowercase → every run of `[^a-z0-9]+` becomes `-` → trim leading/trailing `-` → take first 64 chars → trim `-` again.
- `slugFor(name, slug)` (`:58-64`): if slug is a non-blank string use `assertSlug(slug)`; else `slugify(name)`; empty → error.
- Examples: `"Café Déjà Vu!"` → `cafe-deja-vu`; `"  --Hello__World--  "` → `hello-world`.

### 7.2 Project reference (`src/domain/projects.ts:195-210`)

Trim; blank → 400 `project is required`. If it looks like a ULID: id first, then slug (`ref.toLowerCase()`). Otherwise slug (lowercased) first, then id (exact). Miss → 404 `no such project: <trimmed>`.

### 7.3 Theme merge (`src/domain/projects.ts:306-364`)

- Start from a copy of the current theme (stored key order kept); new keys are appended in the order processed: `background`, `accent`, `logoMediaId`, `logoReplacesName`.
- Field absent = keep. Field `null` = delete it. `logoMediaId: null` also deletes `logoReplacesName`.
- Colour: trimmed, must match `/^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/`, else 400 `<field> must be a hex colour like #1a2b3c`. Stored as lowercase `#rrggbb` (3-digit doubled).
- Logo: media must exist (404 `no such media: <id>`), be `kind: image` (400 `a logo must be an image`), `status: ready` (400 `that image is still being processed`).
- `logoReplacesName`: stored only when `true`; false/null delete it. True with no logo → 400 `logoReplacesName needs a logo to show instead of the name`.
- Result `{}` becomes `null`.

### 7.4 Board (`src/domain/projects.ts:79-120`)

`null`/missing → null. Errors in check order: not an object → `board must be an object, or null for the default columns`; `columns` not a list → `board.columns must be a list`; empty → `a board needs at least one column`; >6 → `at most 6 columns`; per column i: not an object → `columns[i] must be an object`; `states` not a non-empty list → `columns[i].states must name at least one task state`; a state not in `todo, claimed, done, cancelled` → `columns[i].states must be any of: todo, claimed, done, cancelled`; repeated anywhere → `<state> is already in another column`; title = `requiredText(String(title ?? ''), 'columns[i].title', 40)` → `columns[i].title is required` / `columns[i].title must be at most 40 characters`. Stored `{columns:[{title, states}]}`, title trimmed.

### 7.5 Tag and field slugs

- `tagSlug` (`src/domain/tags.ts:79-87`): trim → lowercase → `\s+`→`-` → drop `[^a-z0-9-]` → collapse `-+` → strip one leading and one trailing `-` (regex `/^-|-$/g`).
- `fieldSlug` (`src/domain/fields.ts:55-63`): trim → lowercase → `\s+`→`-` → drop `[^a-z0-9._-]` → collapse `-+` → strip all leading/trailing `[-.]`.
- Note: no NFKD folding here, so `"café"` → `caf`.

### 7.6 Message threading (`src/domain/messages.ts:208-321`)

Where a message lives:

| shape | columns set | what it is |
| --- | --- | --- |
| feed post | none of updateId/replyTo/requestId | a card of its own |
| reply under a post | `replyTo = <post id>` | one level only |
| update thread | `updateId` | comment on an update |
| request discussion | `requestId` (replyTo and updateId forced null) | talk about a question |
| task thread | `taskId` | legacy; `taskId` is always written null now (`:228`) |

`answers` is a label ("this answers that line"), not nesting.

Resolution, given `request`, `update`, `replyTo` (target), `answers` (answered):

```
requestId = input.request ?? answered.requestId ?? null
updateId  = request ? null : (input.update ?? answered.updateId ?? target.updateId ?? null)
inThread  = target != null && target.updateId != null
parent    = inThread ? null : (target.replyTo ? find(target.replyTo) : target)
anchor    = parent?.id ?? (answered && !inThread ? (answered.replyTo ?? (answered.updateId == null ? answered.id : null)) : null)
replyTo   = request ? null : anchor          // stored
answers   = answered?.id ?? (inThread ? target.id : null)
```

So:
- `replyTo` = a post → filed under it.
- `replyTo` = a reply → flattened under that reply's post; `answers` stays null unless sent.
- `replyTo` = a line in an update thread → joins that update thread (`updateId` set, `replyTo` null, `answers` = the line).
- `answers` alone inherits the answered line's thread.

Same-thread check (`sameThread`, `:1151-1163`), only when `answers` given: true if the new replyTo is the answered message; else if either side has a requestId they must be equal; else `answered.updateId == updateId && (answered.replyTo ?? null) == replyTo`.

### 7.7 Mentions (`src/domain/mentions.ts`)

Run on insert (and edit) of every message (`src/domain/messages.ts:334`). Stored in `message_mentions` (replaced in one transaction, `src/db/mentions.ts`).
- Skip entirely if body has no `@`.
- Candidate names: every **non-revoked** agent's `name` (`src/db/agents.ts:81-91`).
- Remove code first: replace every match of `` /```[\s\S]*?(?:```|$)|~~~[\s\S]*?(?:~~~|$)|`[^`\n]*`/g `` with a single space (`:18,22`). Unclosed fences run to end of text.
- For each non-blank name, regex-escape it and test `(?<![\w@])@<name>(?![\w-])` with flags `iu` (`:30`). Match → all agents whose `name.toLowerCase()` equals that name lowercased are mentioned (names are not unique).
- Effect: a message with ≥1 mention is delivered only to the mentioned agents (and reaches them outside their projects); with none, it reaches every agent in scope (`src/db/messages.ts` `readerScope`).
- Kotlin server only: that narrowing holds only for a message an agent wrote. A person's @name
  reaches that agent as well as everyone in the channel; an agent's @name reaches only the agents it
  names (`Mentions.directs`, used by `Subscriptions.audienceSql` and `Mentions.readable`). Either
  way the agents named get it outside the channels they follow, and channel access still rules.
- Kotlin server only (the TS server is retired): two groups, matched the same way as names. `@everyone`
  mentions every non-revoked agent; `@here` every non-revoked agent with an open session that beat
  within `PRESENCE_WINDOW_MS` (5 min). A group never mentions the agent that wrote the message (its
  own messages never reach it anyway). An agent named `here` or `everyone` is still mentioned by name.
  Offline agents mentioned this way see the message as unread when they next connect.

### 7.8 Emoji (`src/domain/reactions.ts:96-120`)

1. Not a string → 400 `emoji must be a string`.
2. Trim; empty → 400 `emoji is required`.
3. Strip leading/trailing `:` runs, lowercase; if in `SHORTCODES` return that character: `eyes 👀, tick ✅, check ✅, white_check_mark ✅, done ✅, +1 👍, thumbsup 👍, -1 👎, thumbsdown 👎, tada 🎉, rocket 🚀, heart ❤️, thinking 🤔, thinking_face 🤔, warning ⚠️, x ❌, fire 🔥, pray 🙏, smile 😄` (`:52-72`).
4. Length (UTF-16 units) >24 → 400 `an emoji is at most 24 characters`.
5. Contains JS `\s` → 400 `an emoji has no spaces in it`.
6. All code points ≤0x7F → 400 `"<trimmed>" is not an emoji. Send the character itself, or one of: eyes, tick, check, white_check_mark, done, +1, thumbsup, -1`.
7. Else store the trimmed string as-is. No allow-list.

### 7.9 Acknowledgements (read-only here)

Written only by agents (`acknowledgeCard`, `src/domain/cards.ts:414-442`): states `thinking|read|done`; one row per (agent, card), upsert updates `state` and `updatedAt`; publishes `ack.updated`. The owner API only returns them in `GET /api/messages` `acks`.

### 7.10 Text and markdown

- `body`/`name`/`description` are stored as trimmed raw text (markdown, untrusted). No server-side rendering or sanitising in these routes. Raw HTML is disabled by the renderers.
- `markdownToPlainText` (`src/domain/plain-text.ts`) is used only for push text (`src/domain/push.ts:453`), not here.
- Length limits count UTF-16 code units (JS `.length`).

---

## 8. Native client usage (`DashboardClient.kt`)

Uses from this file: `GET /api/messages` (params `project, update, request, replyTo, task`), `POST /api/messages` (keys `body, project, update, replyTo, answers, request, mediaIds`; nulls omitted because `explicitNulls = false`), `DELETE /api/messages/{id}`, `DELETE /api/updates/{id}`, `POST /api/cards/{kind}/{id}/reactions` (`emoji`, optional `on`; reads `reactions`), `POST /api/cards/message/{id}/status` (`{"status":"todo"}`). Errors are read as `{error, message}`. Auth is always the bearer device key.

---

## Surprises

1. **`task` is not a card kind.** `CARD_KINDS` is `update, message, request` (`src/db/cards.ts:30`); the client's `CardKinds.Task` (`FeedModels.kt:383`) gets 400 `no such kind of card: task`.
2. **`POST /api/messages` ignores `task`.** `NewMessage.task` (`FeedModels.kt:371`) is dropped by `readReply`; a body with only `task` becomes a project-less feed post.
3. **Messages can have no project.** No anchor + no `project` → `projectId: null`, allowed and returned by unscoped `GET /api/messages`.
4. **`null` is refused for optional message keys** (`"update": null` → 400 `update must be a string`), and for `slug: null` on project create. Clients must omit keys.
5. **Reply to a deleted message is accepted** via `replyTo` (only `answers` checks `deletedAt`) (`src/domain/messages.ts:192-219`).
6. **`replyTo` pointing at a request-discussion line** sets `reply_to` and drops the request (requestId is only inherited via `answers`), so the line lands outside the discussion.
7. **`replyTo` + `update` both present, even `""`,** is refused (`!= null` check), but `""` for `answers`/`project` is ignored (truthiness check).
8. **`GET /api/messages?replyTo=X&limit=1`** returns the post and **all** replies: `slice(-(1-1))` = `slice(-0)` = everything (`src/domain/messages.ts:972`).
9. **Default page size is 200**, not the 50 that `DEFAULT_MESSAGE_LIMIT` suggests (`:967`).
10. **Lenient vs strict bodies:** card/tag routes turn a malformed body into `{}`, so the error is the first field's, not `body must be JSON`.
11. **Card reactions check the card before the emoji**: bad emoji on a missing card → 404, not 400.
12. **Tag rename clash is 400, project slug clash is 409.**
13. **Tag define is idempotent but ignores the new colour** when the word exists; **tag rename with `{}` still publishes** `tags.changed`; **tag delete is not idempotent** (second → 404) and its response is `{"tag":{"id","projectId"}}`, not a full Tag.
14. **Project PATCH is not quiet**: same values still bump `updatedAt` and publish. Unknown project + empty patch → 404 (lookup first); bad field type + unknown project → 400 (body first).
15. **`announce` sends placeholders**: `message.updated.author` is `""` and has no thread keys; `request.updated.agentId` is `""`; `update.updated.projectId` falls back to `""`.
16. **Board title coerced with `String()`**: `{"title":123}` is accepted as `"123"`; `{"title":null}` gives `columns[0].title is required`.
17. **Request cards can't be deleted, so they always resolve**; they can be pinned, reacted to, tagged (if they have a project) and given fields, but not given a status.
18. **Lowercase ULID as a project reference misses**: it is treated as a slug, and the id fallback is exact-case.
19. **Owner messages and reactions never push or notify**; only the Live Activity and agent streams react.
20. **Regex dialects:** the mention pattern uses JS `\w` with `iu` flags (under `iu`, `\w` also matches U+017F and U+212A); emoji whitespace uses JS `\s` (includes U+FEFF, U+00A0, U+2028/9); `.trim()` is JS trim. Kotlin's `trim()`, `\s` and `\w` differ — use explicit character sets.
21. **SvelteKit CSRF**: a POST/PUT/PATCH/DELETE with `Content-Type` `text/plain`, `application/x-www-form-urlencoded` or `multipart/form-data` and an `Origin` other than the server's own (or no Origin) gets **403 before auth**: `Cross-site <METHOD> form submissions are forbidden` (text, or `{"message":...}` if `Accept` is exactly `application/json`) (`node_modules/@sveltejs/kit/src/runtime/server/respond.js:81-100`). JSON clients are unaffected.
22. **`bus.seq` resets on restart**, so `GET /api/messages` `seq` can go backwards across deploys.

## Parity test notes

Run each against both servers with a fresh DB and the same bearer `$K`. Compare status, headers (`content-type`, `cache-control`), and body bytes (after replacing ids, seq and times).

1. Auth: `curl -i -X POST /api/messages -d '{"body":"x"}'` (no auth) → `401`, `content-type: application/json`, body `{"error":"unauthenticated"}`. Same with a bad bearer plus a valid cookie → 401.
2. Create project: `POST /api/projects {"name":"Café Déjà Vu!"}` → 201, `project.slug == "cafe-deja-vu"`, `created:true`. Repeat → 200, `created:false`, same `id`, `updatedAt` unchanged.
3. `POST /api/projects {"name":"x","slug":null}` → 400 `{"error":"invalid_argument","message":"slug must be a string"}`.
4. `POST /api/projects` body `[1]` → 400 `body must be a JSON object`; body `nope` → 400 `body must be JSON`.
5. `PATCH /api/projects/cafe-deja-vu {"theme":{"accent":"#ABC"}}` → theme `{"accent":"#aabbcc"}`. Then `{"theme":{"accent":null}}` → `theme: null`. `{"theme":{"logoReplacesName":true}}` → 400 `logoReplacesName needs a logo to show instead of the name`.
6. `PATCH /api/projects/cafe-deja-vu {}` → 400 `update requires at least one field to change`; `PATCH /api/projects/nope {}` → 404 `no such project: nope`.
7. Slug clash: create `a` and `b`, `PATCH /api/projects/b {"slug":"a"}` → 409 `{"error":"conflict","message":"slug already in use: a"}`.
8. Board: `{"board":{"columns":[{"title":"A","states":["todo"]},{"title":"B","states":["todo"]}]}}` → 400 `todo is already in another column`.
9. Post: `POST /api/messages {"project":"a","body":"  hi @scout  "}` → 201, `message.body == "hi @scout"`, `author == "human"`, all anchors null. If an agent named `Scout` exists, a `message_mentions` row exists for it.
10. Post with null: `{"project":"a","body":"x","update":null}` → 400 `update must be a string`.
11. Reply flattening: post P; `{"replyTo":P,"body":"r1"}` → R1 with `replyTo:P`; `{"replyTo":R1,"body":"r2"}` → `replyTo:P`, `answers:null`.
12. Two anchors: `{"replyTo":P,"update":U,"body":"x"}` → 400 `a reply answers a message, or hangs off an update, not both`.
13. Request + card: `{"request":Q,"update":U,"body":"x"}` → 400 ``a message belongs to a request`s discussion, or to a card, not both``.
14. Project mismatch: update U in project a, `{"update":U,"project":"b","body":"x"}` → 400 `that update, task or message belongs to a different project`.
15. Threads: `GET /api/messages?replyTo=P` → `[P,R1,R2]`, top-level keys in order `seq,at,messages,acks,media,embeds,deliveries,reactions`, `media` and `embeds` `{}`. `GET /api/messages?replyTo=P&limit=1` → still all three (surprise 8). `?limit=0` and `?limit=-1` → 400 `limit must be a positive integer`.
16. Delete post: `DELETE /api/messages/P` → 200, `deletedAt` set; R1 and R2 also deleted; event `message.deleted` has `replies:2`. Repeat → 200, same row, no event.
17. React: `POST /api/cards/message/P2/reactions {"emoji":":tick:"}` → 200 `{"reactions":[{...,"emoji":"✅","actor":"human"}],"on":true}`; repeat → `{"reactions":[],"on":false}`; `{"emoji":"✅","on":true}` twice → second is `on:true`, no new event.
18. Emoji refusals: `{"emoji":"ok"}` → 400 `"ok" is not an emoji. Send the character itself, or one of: eyes, tick, check, white_check_mark, done, +1, thumbsup, -1`; `{"emoji":"👍 👍"}` → 400 `an emoji has no spaces in it`; `{}` → 400 `emoji must be a string`.
19. Kinds: `POST /api/cards/task/X/pin {"pinned":true}` → 400 `no such kind of card: task`; `POST /api/cards/request/Q/status {"status":"todo"}` → 400 `a request cannot be marked as work`.
20. Status: `POST /api/cards/message/P2/status {"status":"todo"}` → `{"card":{"kind":"message","id":P2},"status":"todo"}`; `{}` → 400 `status must be one of the five work states, or null`; `{"status":null}` → `status:null`.
21. Pin bad body: `POST /api/cards/update/U/pin` with body `nope` → 400 `pinned must be true or false` (lenient).
22. Update patch: `PATCH /api/updates/U {"pinned":true,"x":1}` → 400 `an update patch changes pinned and nothing else`; `{"pinned":true}` → 200 `{"update":{...,"pinned":true}}`. `DELETE /api/updates/U` twice → both 200; the second publishes nothing. Then `PATCH` → 404 `no such update: U`.
23. Tags: `POST /api/projects/a/tags {"name":"High Priority","colour":"rose"}` → 200 tag `slug:"high-priority"`. Again with `"colour":"blue"` → same tag, colour still `rose`. `{"name":"!!!"}` → 400 `"!!!" has no letters or digits in it`. `PATCH /api/tags/<id> {"name":"bug"}` when `bug` exists → 400 `this project already has a tag called "bug"`. `DELETE /api/tags/<id>` → `{"tag":{"id":...,"projectId":...}}`; again → 404 `no such tag: <id>`.
24. Tag a card: `POST /api/cards/message/P2/tags {"tag":"wontfix"}` → 404 `this project has no tag called "wontfix"`; with `"create":true` → 200 `on:true`; a project-less message → 400 `this card does not belong to a project, so it cannot be tagged`; 9th tag → 400 `a card may carry at most 8 tags`.
25. Fields: `POST /api/cards/update/U/fields {"name":" Branch ","value":"main"}` → `fields[0]` `{name:"Branch",slug:"branch",value:"main",author:"human"}`, `set:true`; same again → no event; `{"name":"branch","value":"dev"}` → same `id`/`seq`/`createdAt`, name now `branch`; `{"name":"nope","value":null}` → 404 `this card has no field called "nope"`; `{"name":"x"}` → 400 `value must be a string, or null to remove the field`.
26. Agent rename: `PATCH /api/agents/<id> {"name":" Scout "}` → `{"agent":{"id":...,"name":"Scout"}}`; `{"name":"x","tokenHash":"y"}` → 400 `an agent patch changes name and nothing else`.
27. Method: `GET /api/projects` (authorised) → 405, body `GET method not allowed`, header `allow: POST`.
28. CSRF: `curl -X POST -H 'content-type: text/plain' -H 'Origin: https://evil' -d '{"body":"x"}' /api/messages` → 403 `Cross-site POST form submissions are forbidden`, even with a valid bearer.
