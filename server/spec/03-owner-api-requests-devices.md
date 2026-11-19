# 03 — Owner API: requests, devices, push, notifications, summary, agents, apps, snapshots

Source: the `agent-dashboard` working tree. All paths below are under `src/`.
Route files under `http/routes/api/**/+server.ts` are thin mounts; the work is in `http/owner/*.ts`,
rules in `domain/*.ts`, storage in `db/*.ts`.

## 0. Shared rules (apply to every route in this file)

### 0.1 Auth — two locks

1. **Hook** `http/auth/handle.ts:31` (installed `hooks.server.ts:110-112`, after `compressHandle`).
   Runs only when `event.route.id !== null` (a matched route) and `requiresSession(path)`
   (`http/auth/guard.ts:52`). Path is normalised by stripping trailing `/` (`guard.ts:47`).
   - Exempt prefixes (whole-segment match `guard.ts:42`): `/mcp`, `/api/upload`, `/s`, `/api/agent`
     (`guard.ts:33`), `/login`, `/logout`, `/api/devices/sign-in` (`guard.ts:36-39`).
   - If `Authorization` matches `/^bearer\s+(\S+)$/i` after trim (`http/auth/bearer.ts:13`), the
     bearer alone decides: valid device key → pass, else refuse. A cookie is then ignored
     (`handle.ts:45-48`).
   - Else the `ad_session` cookie (`http/auth/session.ts:17`) must verify under `SESSION_SECRET`.
   - Refusal (`handle.ts:18`): path under `/api` → `401`, header `content-type: application/json`,
     body `{"error":"unauthenticated"}`. Any other path (e.g. `/apps/<id>`) → `303`,
     `location: /login?redirectTo=<encodeURIComponent(path+search)>` (`guard.ts:66`).
2. **Handler** re-checks with `ownerAuthenticated` (`http/stream/owner.ts:60`): same bearer-first
   rule; config load failure → not authenticated. Refusal → `401`, `content-type: application/json`,
   body `{"error":"unauthenticated"}` (`owner.ts:78`). No `cache-control` header on this 401.

Device key check (`domain/devices.ts:78`): token trimmed, must match `/^[A-Za-z0-9_-]{43}$/`
(`domain/agents.ts:58`), `TOKEN_SECRET` non-empty; HMAC-SHA256(token, TOKEN_SECRET) lowercase hex
looked up in `owner_devices.token_hash`, compared constant-time. Side effect on every successful
check: `UPDATE owner_devices SET last_seen_at=now WHERE id=? AND (last_seen_at IS NULL OR
last_seen_at <= now-60000)` (`db/owner-devices.ts:125`, `DEVICE_SEEN_RESOLUTION_MS=60_000`
`domain/devices.ts:40`). The check runs twice per request (hook + handler); the second is a no-op.
`TOKEN_SECRET` unloadable → every bearer refused (`owner.ts:32-39`).

### 0.2 Owner handler wrapper `ownerAction` (`http/owner/actions.ts:145`)

- Auth (above) → run → serialise.
- Success: status from handler, body `JSON.stringify(body)`, headers
  `content-type: application/json; charset=utf-8`, `cache-control: no-store` (`actions.ts:295`).
- `DomainError` → `{ "error": <code>, "message": <text> }` with `invalid_argument`→400,
  `not_found`→404, `conflict`→409 (`actions.ts:52`, `:159-161`). Same headers as success.
- Any other thrown error propagates → SvelteKit 500 (no `handleError` hook exists).
- Body parsing `readOwnerJson` (`actions.ts:172`): `request.json()` fails (empty/invalid) →
  400 `{"error":"invalid_argument","message":"body must be JSON"}`; parsed `null`/non-object/array →
  400 `{"error":"invalid_argument","message":"body must be a JSON object"}`. Content-Type is not
  checked. Unknown fields are ignored.

### 0.3 Other cross-cutting behaviour

- Compression (`http/compress.ts`): JSON responses ≥1400 bytes (`:47`) and ≤8 000 000 (`:56`) are
  brotli (q4) or gzip encoded when `Accept-Encoding` offers `br`/`gzip` with q>0; br preferred.
- SvelteKit default CSRF (`checkOrigin` on; no override in `vite.config.ts`): a cross-origin
  POST/PUT/PATCH/DELETE with a form content type (`application/x-www-form-urlencoded`,
  `multipart/form-data`, `text/plain`) is refused 403 before hooks run.
- Wrong method on a matched route: SvelteKit 405 (after the hook, so unauthenticated still gets 401).
- Unmatched path: `route.id === null` → hook does not guard → SvelteKit 404.
- Time formats are mixed: requests/notifications/projects/updates use **epoch ms numbers**;
  devices, summary, snapshot `at` use **`Date#toISOString()`** = `YYYY-MM-DDTHH:mm:ss.sssZ`
  (always 3 ms digits).
- String rules: `requiredText`/`optionalText` (`domain/text.ts:14,26`) use JS `trim()` and
  `.length` (UTF-16 code units). Errors: `"<field> is required"`,
  `"<field> must be at most <n> characters"`.
- IDs are ULIDs, 26 chars Crockford base32 (`db/ids.ts:22`).

---

## 1. Owner requests (`request_input`) — the model

### 1.1 Kinds (`domain/requests.ts:91-98`)

| kind | options | answer `value` | notes |
|---|---|---|---|
| `text` | refused | string | textual |
| `confirm` | refused | boolean | yes/no |
| `buttons` | required | one option string | pictured |
| `choice` | required | one option string | pictured |
| `multi_choice` | required | array of option strings | pictured |
| `form` | required (= action buttons) | `{action, text}` | textual |

`LISTED` = buttons, choice, multi_choice, form (`:101`). `TEXTUAL` = text, form (`:104`).
`PICTURED` = buttons, choice, multi_choice (`:965`) — **form can't have option pictures**.

### 1.2 Limits (`domain/requests.ts:107-146`)

`QUESTION_MAX_LENGTH=500`, `DETAIL_MAX_LENGTH=4000`, `OPTION_MAX_LENGTH=200` (also placeholder,
label), `OPTIONS_MAX=25`, `TEXT_ANSWER_MAX_LENGTH=10000` (also default length),
`DEFAULT_TIMEOUT_S=3600`, `MIN_TIMEOUT_S=5`, `MAX_TIMEOUT_S=86400`, `DEFAULT_HOLD_MS=55000`,
`POLL_AFTER_MS=1000`, `PENDING_REQUEST_LIMIT=200`, `REQUEST_SWEEP_INTERVAL_MS=30000`.
Media per request: `MEDIA_PER_UPDATE_MAX=24` including option pictures (`domain/media.ts:72`,
`requests.ts:337-341`).

### 1.3 States

Domain states: `pending | answered | timeout | cancelled | withdrawn` (`requests.ts:149`).
DB column `approvals.state`: `pending | approved | rejected | timeout | cancelled | withdrawn`
(`db/types.ts:417`). Mapping `toRequest` (`requests.ts:892`): `approved`/`rejected` → `answered`;
others pass through. `confirm` false writes `rejected`, every other answer writes `approved`
(`requests.ts:400`).

Transitions — all via `decideApproval` = `UPDATE approvals SET state, decided_value, answer,
decided_at WHERE id=? AND state='pending' RETURNING …` (`db/approvals.ts:283`); only one writer wins.

| from | to | by | where |
|---|---|---|---|
| pending | answered | owner `POST /api/requests/:id/answer` | `requests.ts:388` |
| pending | cancelled | owner `DELETE /api/requests/:id` | `requests.ts:416` |
| pending | withdrawn | asking agent, MCP `withdraw_request` | `requests.ts:444` |
| pending | timeout | sweeper every 30 s (`expires_at <= now`) | `requests.ts:655`, `db/approvals.ts:314` |
| pending | timeout | lazily, on any answer/cancel/withdraw/edit/await of a due row (`now >= expiresAt`) | `requests.ts:843` |

Every settle publishes exactly one `request.answered`
`{requestId, agentId, state: "answered"|"timeout"|"cancelled"|"withdrawn", settledAt: ISO(decidedAt)}`
(`requests.ts:821-834`). Timeout via sweeper sets only `state`, `decided_at` (`db/approvals.ts:320`).

Settled-already error (`requests.ts:870`): 409 `conflict`, message
`request <id> has already been answered` | `timed out` | `was dismissed` | `was withdrawn`.

Legacy: a row with no `answer` JSON but state approved/rejected reads as
`{kind:"confirm", value: state==="approved"}` (`requests.ts:929`).

### 1.4 Creation (agent side, MCP `request_input`; `mcp/tools/request-input.ts:22-160,262-290`)

Args → domain: `kind, question, detail, options, placeholder, multiline, label, default, min, max,
project, update, message_id→message, timeout_s→timeoutS, media_ids→mediaIds,
option_media→optionMedia ({option: media_id}), embeds[{type,data,source_url}]`.

Validation order in `createRequest` (`requests.ts:308-343`) — first failure wins:
1. kind in list → else `kind must be one of text, confirm, buttons, choice, multi_choice, form` (`:935`).
2. question required ≤500; detail optional ≤4000 (blank→null).
3. options (`:949`): non-listed kind with non-empty options → `a <kind> request takes no options`;
   listed: each `requiredText(option, "options[<i>]", 200)` (trimmed), empty list →
   `a <kind> request needs at least one option`, >25 → `at most 25 options`, duplicates (after trim) →
   `options must be distinct`.
4. config (`:997`), built in this key order: `placeholder` (textual only, else
   `placeholder is for a text or form request`), `multiline:true` (textual only, else
   `multiline is for a text or form request`; false is dropped), `label` (form only, else
   `label names a form request’s field` — U+2019), `default` (≤10000; multi_choice →
   `a multi_choice takes no default`; confirm must be `"true"`/`"false"` →
   `the default for a confirm is "true" or "false"`; buttons/choice must be an option →
   `the default must be one of the options`), `min`/`max` (only text/form/multi_choice →
   `min and max are for a text, form or multi_choice request`; integer ≥0 →
   `<f> must be a whole number`; ceiling 10000 for textual, `options.length` for multi_choice →
   `<f> must be at most <c>`; `min must not exceed max`). Empty config → `null`.
5. anchor (`:1077`): `message` first (must exist, not deleted → 404 `no such message: <id>`;
   conflicting project → `the message named belongs to a different project`; takes projectId
   (fallback: named project), updateId from the message, messageId); else `update` (404
   `no such update: <id>`; `the update named belongs to a different project`); else project only
   (404 `no such project: <ref>`).
6. timeout (`:1060`): integer 5..86400 else
   `timeout_s must be a whole number of seconds between 5 and 86400`. `expiresAt = now + s*1000`.
7. media_ids (`domain/media.ts:374`): deduped, 1..24, ULID shape, exist (404), author is
   `agent:<id>`, unattached, has bytes.
8. embeds (`domain/embeds.ts`).
9. option pictures (`requests.ts:971`): empty map → none; non-pictured kind →
   `pictures on options are for a buttons, choice or multi_choice request`; every key must equal a
   (trimmed) option → `<key> is not one of the options`; ids distinct → `each picture may be used once`;
   attachable as in 7; each media `kind === "image"` → `media <id> must be an image to go on an option`;
   a picture also in media_ids → `a picture on an option cannot also be in media_ids`; total >24 →
   `a request may carry at most 24 media items, pictures included`.
   Pictures are appended to the request's media (attached to the request row) and stored as
   `config.optionMedia` (appended last in config).

Side effects: insert `approvals` row; attach media/embeds; publish
`request.created {requestId, agentId, projectId, kind}` (`:367`). Bus subscribers then:
notification row (§5), Web Push (§4.5), APNs alert (§6.1), Live Activity refresh (§6.2), agent
stream counts.

### 1.5 Edit (agent side, MCP `edit_request`; `requests.ts:498`)

Fields: `question?`, `detail?` (null clears), `options?`, `default?` (null clears). Own request only
(`request belongs to another agent` 400); pending only (lazy-expire first, then 409).
- options re-validated with the row's kind (`:511`).
- default: same rules as create against new-or-current options; merged into existing config
  (`placeholder/label/min/max/optionMedia` kept); null deletes `default`; empty config → null
  (`:518-537`).
- options changed but default not given: old default must still be an option (except form) (`:538`).
- option pictures whose option was removed are dropped from `config.optionMedia` (`:548-559`).
  **The media rows stay attached to the request** (see Surprises).
- Nothing given → `an edit must change the question, the detail, the options or the default`.
- Always sets `edited_at = now`. Publishes `request.updated {requestId, agentId, projectId}` (`:576`).
- Kind can never change.

### 1.6 Answer validation per kind (`validateAnswer`, `requests.ts:703`)

`config` = row config or `{}`; `options` = row options or `[]`. All errors are 400 `invalid_argument`.

| kind | rule | error message |
|---|---|---|
| confirm | `typeof value === "boolean"` | `a confirm is answered true or false` |
| text | string | `a text request is answered with a string` |
| text | `trim()`; len ≥ `min ?? 1` | `the answer must be at least <min> characters` |
| text | len ≤ `max ?? 10000` | `the answer must be at most <max> characters` |
| multi_choice | array | `a multi_choice is answered with a list of options` |
| multi_choice | each item a string in options (exact, untrimmed) | `<JSON.stringify(item)> is not one of the options offered` |
| multi_choice | no duplicates | `an option was chosen twice` |
| multi_choice | count ≥ `min ?? 0` / ≤ `max ?? options.length` | `choose at least <n>` / `choose at most <n>` |
| form | non-null, non-array object | `a form is answered with { action, text }` |
| form | `action` string in options | `<JSON.stringify(action)> is not one of the actions offered` |
| form | `text` string | `a form’s text is a string` (U+2019) |
| form | `trim()` len ≥ `min ?? 0`, ≤ `max ?? 10000` | `the text must be at least <n> characters` / `at most <n>` |
| buttons, choice | string in options (exact) | `<JSON.stringify(value)> is not one of the options offered` |

`JSON.stringify` of a missing value is `undefined`, so a missing form action reads
`undefined is not one of the actions offered`. Stored answer: `{kind, value}`; text/form text is the
trimmed string. `decided_value` scalar: boolean→`"true"/"false"`, array→null, form→action, string→itself
(`requests.ts:882`).

### 1.7 OwnerRequest JSON (key order from `toRequest`, `requests.ts:901-919`)

```json
{
  "id": "01JD3M5X9Q8W7E6R5T4Y3V2K1N",
  "seq": 42,
  "agentId": "01JD…",
  "projectId": "01JD…" ,
  "updateId": null,
  "messageId": null,
  "kind": "choice",
  "question": "Which colour?",
  "detail": null,
  "options": ["Red", "Blue"],
  "config": {"default": "Red", "optionMedia": {"Red": "01JD…"}},
  "state": "pending",
  "expiresAt": 1763474000000,
  "answeredAt": null,
  "answer": null,
  "editedAt": null,
  "pinned": false
}
```

`answer` once answered: `{"kind":"choice","value":"Red"}`; confirm `{"kind":"confirm","value":true}`;
multi `{"kind":"multi_choice","value":["a","b"]}`; form
`{"kind":"form","value":{"action":"Send","text":"hi"}}`. `answeredAt` = decided_at (also set for
timeout/cancelled/withdrawn). `config` keys appear in stored-JSON order (create order §1.4 step 4,
then `optionMedia`).

### 1.8 Pending list (`listPendingRequests`, `requests.ts:586`)

State `pending`, oldest 200 by seq, then drops `expiresAt <= now` (no expiry write, no event), sorted
by seq ascending. Used by summary, requests snapshot, full snapshot card-scope.

---

## 2. Request routes

### `POST /api/requests/[id]/answer` (`http/routes/api/requests/[id]/answer/+server.ts:14`, handler `http/owner/requests.ts:36`)

- Auth: cookie or device bearer.
- Body: JSON object with key `value` (any JSON, incl. `null`). Missing key → 400
  `{"error":"invalid_argument","message":"an answer must carry a value"}` (`requests.ts:39`).
- Flow (`domain/requests.ts:388`): find (404 `no such request: <id>`) → lazy expire (if due:
  writes `timeout`, publishes `request.answered{state:"timeout"}`, **then** 409
  `request <id> timed out`) → not pending → 409 → validate (§1.6) → `decideApproval` (lost race → 409).
- Success 200: `{"request": <OwnerRequest>}` with `state:"answered"` (no media/embeds/reactions).
- Side effects: row updated (`state`, `decided_value`, `answer`, `decided_at`); publishes
  `request.answered {requestId, agentId, state:"answered", settledAt}`. Agent stream writes an answer
  frame (`http/stream/agent.ts:686`); Live Activity refresh (§6.2). No push, no notification change.

### `DELETE /api/requests/[id]` (`.../requests/[id]/+server.ts:13`, `http/owner/requests.ts:57`)

- Auth: cookie or device bearer. No body read.
- Flow: `cancelRequest` (`domain/requests.ts:416`): 404 / lazy-expire→409 / not pending →409 `request <id> was dismissed|…`.
- Success 200: `{"request": <OwnerRequest state "cancelled">}`.
- Side effects: state `cancelled`, `decided_at`; publishes `request.answered{state:"cancelled"}`.
- **Not idempotent**: second DELETE → 409 `request <id> was dismissed`.

There is no owner GET for one request; read it from `/api/snapshot/requests`.

---

## 3. Devices (the owner's phones)

Storage `owner_devices` (`db/owner-devices.ts:13-29`): `seq, id, name, token_hash, apns_token,
apns_environment ('sandbox'|'production'), live_activity_token, push_to_start_token, created_at,
last_seen_at, failures, alert_project_ids (JSON array text | NULL)`. No device endpoint publishes a bus
event (`domain/devices.ts` header).

Device view (`http/owner/devices.ts:56-67`), key order:
`{"id","name","createdAt":ISO,"lastSeenAt":ISO|null,"hasApns":bool,"hasLiveActivity":bool,"alertProjects":[slug…]|null,"alertPrefs":{types?,levels?,priorities?}|null}`.
`hasLiveActivity` = `live_activity_token !== null` (push-to-start ignored). `alertProjects`
maps stored ids → current slugs, silently dropping ids of deleted projects (`domain/devices.ts:178`).

Minting (`domain/devices.ts:58`): name `requiredText(…,"name",100)`; token = 32 random bytes
base64url, no padding (43 chars); stores HMAC hex under `TOKEN_SECRET`.

### `POST /api/devices` (`http/owner/devices.ts:70`)

- Auth: cookie or device bearer (the app uses its web cookie: `DashboardClient.kt registerDevice`).
- Body `{name: string}`. Order: `typeof name !== "string"` → 400 `name must be a string`; no
  `TOKEN_SECRET` → **503 `{"error":"misconfigured"}`** (no message); trimmed blank → 400
  `name is required`; >100 → 400 `name must be at most 100 characters`.
- 201: `{"device":{"id","name","createdAt":ISO},"token":"<43 chars>"}`. Token shown only here.

### `POST /api/devices/sign-in` (`http/routes/api/devices/sign-in/+server.ts:10`, `http/owner/devices.ts:111`)

- Auth: **none** (public, `guard.ts:36`). Not wrapped by `ownerAction`; own `json()` with
  `content-type: application/json; charset=utf-8`, `cache-control: no-store` (`devices.ts:92`).
- Body `{password, name}`. Order:
  1. bad JSON → 400 `{"error":"invalid_argument","message":"body must be JSON"}` / `…a JSON object`.
  2. `name` not string → 400 `name must be a string`; trimmed blank/`>100` → 400 (no guess spent).
  3. Password check shared with the web login form (`http/auth/login.ts:87`, one limiter
     `:153`): config (SESSION_SECRET + ADMIN_PASSWORD_HASH) invalid → 503
     `{"error":"misconfigured","message":"This deployment has no valid ADMIN_PASSWORD_HASH and SESSION_SECRET, so nobody can log in. See .env.example."}`;
     `password === ""` → 400 `{"error":"wrong_password","message":"Enter your password."}` (no attempt
     counted); limiter (5 failures / 15 min per `clientAddress`, `login.ts:33-34`) blocked → 429
     `{"error":"rate_limited","message":"Too many attempts. Try again later."}` + header
     `retry-after: <ceil(ms/1000)>` (applies to a correct password too); argon2 verify fails (missing
     or non-string password is verified as `""`) → record failure → 400
     `{"error":"wrong_password","message":"That password is not right."}`; success resets the key.
  4. no `TOKEN_SECRET` → 503 `{"error":"misconfigured"}`.
- 201: same body as `POST /api/devices`.
- `clientAddress` = `event.getClientAddress()` (honours `ADDRESS_HEADER`/`XFF_DEPTH`).
- Other `DomainError` codes rethrown (500).

### `GET /api/devices` (`devices.ts:154`)

- Auth: cookie or bearer. 200 `{"devices":[<device view>…]}` ordered by `seq` (`db/owner-devices.ts:108`).

### `DELETE /api/devices/[id]` (`devices.ts:164`)

- 200 `{"removed":true}` or `{"removed":false}` (idempotent). A device may delete itself; its key fails
  from the next request.

### `PUT /api/devices/current/apns` (`devices.ts:185`)

- Auth: device bearer only. Cookie caller → 400
  `this needs a device key: send Authorization: Bearer <device token>` (checked before the body).
- Body `{token, environment}`. `token`: string → else `token must be a string`; trimmed, 1..512 chars,
  `/^[0-9a-f]+$/i` → else `token must be a hex string`; stored lowercased (`domain/devices.ts:105`).
  `environment` ∈ `sandbox|production` → else `environment must be sandbox or production`.
- Writes `apns_token`, `apns_environment`, `failures=0`; clears the same `apns_token` on any other
  device row (`db/owner-devices.ts:141-170`). Row gone → 404 `no such device: <id>`.
- 200 `{"ok":true}`.

### `PUT /api/devices/current/live-activity` (`devices.ts:195`)

- Device bearer only (as above).
- Body must contain `updateToken` and/or `pushToStartToken` (key presence) → else 400
  `send updateToken, pushToStartToken, or both (null clears one)`.
- Each: `null` clears; string → same hex rule as above with field name (`updateToken must be a hex string`);
  omitted key leaves column as is (`db/owner-devices.ts:191`). Each set token is cleared from other rows.
- 200 `{"ok":true}`.

### `PUT /api/devices/current/alert-projects` (`devices.ts:214`)

- Device bearer only.
- Body must contain key `projects` → else 400 `send projects: an array of slugs, or null`.
- `null` → no limit (`alert_project_ids = NULL`). Else array of strings → else
  `projects must be an array of project slugs, or null`; >200 → `projects must name at most 200`;
  each `trim().toLowerCase()`, deduped, looked up by slug only (`findProjectBySlug`: a project id is
  not accepted here, unlike other project references); unknown slugs → 400 `unknown projects: a, b` (nothing written);
  stored as JSON array of project ids (`domain/devices.ts:153`).
- `[]` is valid and means "only alerts about no project" (`deviceWantsAlert`, `domain/devices.ts:184`).
- 200 `{"ok":true}`.

### `GET`/`PUT /api/devices/current/alert-prefs` (TS b65832d, migration 047)

- Device bearer only. Stored in `owner_devices.alert_prefs` (JSON text, NULL = every alert).
- `PUT`: strict JSON body; it must contain key `prefs` → else 400 `send prefs: { types, levels, priorities }, or null`.
  `prefs` is checked as Web Push preferences were (`assertPushPrefs`): `null` → NULL; not an object (or an
  array) → `preferences must be an object, or null for the default`; each of `types` (`request, update, message,
  comment`), `levels` (`info, success, warn, error`), `priorities` (`low, medium, high`; Kotlin also `critical`) when present (even
  `null`) must be a list of those words → `<field> must be a list` / `<field> must be any of: a, b, …`;
  deduped. Kotlin only, critical alerts: `critical` (true/false; anything else → `critical must be true or
  false`) turns paging on, off by default; `criticalAgents` (agent ids) and `criticalProjects` (project slugs)
  narrow it → `<field> must be a list` / `<field> must be a list of strings`; deduped. Stored in the order
  `types, levels, priorities, critical, criticalAgents, criticalProjects`; other keys dropped; nothing left → NULL.
  200 `{"ok":true,"prefs":<checked or null>}`.
- `GET`: 200 `{"prefs":<stored or null>}`.
- `GET /api/devices` entries gain `alertPrefs` (last key).
- APNs alerts go only to phones whose prefs `notifies(prefs, about)`: the type must be in `types`
  (default all four); for an update, also its level in `levels` and priority in `priorities` when both are set
  (`critical` passes where `high` does). A phone that `pages(prefs, about)` (priority `critical`, `critical:
  true`, and its agent id and project slug in the lists when they are set) is told whatever its filters say.

---

## 4. Web Push (`/api/push`, `http/owner/push.ts`)

Config: `pushConfig` on when `VAPID_PUBLIC_KEY` and `VAPID_PRIVATE_KEY` set; subject
`VAPID_SUBJECT ?? PUBLIC_BASE_URL` (`config.ts:166`). Storage `push_subscriptions`
(`db/push.ts`). No bus events from these endpoints.

### `GET /api/push` (`push.ts:46`)

200:
```json
{"enabled":true,"publicKey":"B…","subscriptions":1,
 "devices":[{"endpoint":"https://…","label":"Firefox","last_sent_at":1763474000000,
             "prefs":{"types":["request","update","message","comment"]}}]}
```
`publicKey` null when off. `devices` ordered by `created_at` (`db/push.ts:143`). `last_sent_at` epoch
ms or null (snake_case!). `prefs` = stored object as-is, or `{"types":[DEFAULT_PUSH_TYPES]}` when null
(`push.ts:69`; `domain/push.ts:88`).

### `POST /api/push` (`push.ts:82`)

- Push off → 409 `{"error":"conflict","message":"push is not configured on this deployment"}` (before body).
- Body `{endpoint, keys:{p256dh, auth}, label?}`; values coerced with `String(x ?? '')`; `label` used
  only if a string.
- `subscribeToPush` (`domain/push.ts:181`): endpoint trimmed; `''` → `endpoint is required`; not a URL
  → `endpoint must be an absolute URL`; not `https:` → `endpoint must be an https URL`; trimmed
  p256dh/auth empty → `keys.p256dh and keys.auth are required`; label `optionalText` ≤200.
- Upsert on `endpoint`: replaces keys, **label (null if omitted)**, `failures=0`; keeps prefs
  (`db/push.ts:123`).
- 200 `{"subscribed":true,"endpoint":"<trimmed>"}`.

### `PATCH /api/push` (`push.ts:130`)

- Body `{endpoint: string, prefs}`; endpoint not a non-empty string → 400 `endpoint is required`
  (not trimmed).
- `prefs` (`domain/push.ts:130`): null/absent → default (stored NULL). Non-object/array →
  `preferences must be an object, or null for the default`. Members `types` ⊆
  `request,update,message,comment`, `levels` ⊆ `info,success,warn,error`, `priorities` ⊆
  `low,medium,high`; non-array → `<f> must be a list`; bad member → `<f> must be any of: a, b, …`;
  deduped (first-occurrence order). `{}` → null.
- Validated before lookup; no row → 404 `no such subscription: <endpoint>`.
- 200 `{"prefs": <checked|null>}`.

### `DELETE /api/push?endpoint=…` (`push.ts:108`)

- Query `endpoint` (trimmed); missing/blank → 400 `endpoint is required`.
- 200 `{"removed":true|false}`.

### 4.5 What Web Push sends (side effect of other routes; `domain/push.ts:620`)

On `request.created`, `update.created`, `message.created`. Payload JSON `PushMessage`
`{title, body, url, tag, requestId?, actions?}`; TTL 900 s (`:378`); per-subscription filter `notifies`
(`:110`). Request: title `<agent name|"An agent"> is waiting on you`, body = plain text of question
≤140 chars + `…`, url `<base>/projects/<slug>?focus=<id>` or `<base>/`, tag `request-<id>`;
actions confirm → `[{action:"confirm-yes",title:"Approve",value:true},{action:"confirm-no",title:"Reject",value:false}]`,
buttons/choice → first 2 options `{action:"option-<i>",title,value:option}`, others none (`:432`).
404/410 deletes the subscription; 10 consecutive other failures deletes it (`:159`, `:351-363`).

---

## 5. Notifications (the bell)

Rows written by a bus subscriber (`domain/notifications.ts:231`) on `update.created`,
`message.created` (agent authors only), `request.created`; unique per target, `ON CONFLICT DO NOTHING`
(`db/notifications.ts:86-95`). Request row: kind `request`, title `<agent> is waiting on you`, body =
question whitespace-collapsed, ≤140 + `…` (`notifications.ts:61,197`). Publishes
`notification.created {notificationId, kind, projectId}` (`:141`).

Notification JSON (key order: row `db/notifications.ts:34-48`, then view `notifications.ts:76-81`):
```json
{"seq":7,"id":"01JD…","kind":"request","projectId":"01JD…","updateId":null,"messageId":null,
 "requestId":"01JD…","agentId":"01JD…","title":"claude is waiting on you","body":"Which colour?",
 "createdAt":1763474000000,"seenAt":null,"projectSlug":"web","projectName":"Web",
 "path":"/projects/web?focus=01JD…"}
```
`path` (`notifications.ts:67`): focus = updateId ?? messageId ?? requestId, URI-encoded; base
`/projects/<slug>` or `/`.

### `GET /api/notifications` (`http/owner/notifications.ts:40`)

- Query: `unseen=true` (exact string) → only `seen_at IS NULL`; `limit` → `Number(param ?? '')`;
  integer > 0 → `min(n, 100)`; else 50 (`:47-48`). Order `seq DESC`.
- 200 `{"seq": bus.lastSeq (read first), "at": ISO now, "notifications":[…], "unseen": <count all unseen>}`.

### `POST /api/notifications/seen` (`notifications.ts:69`)

- Body optional. Unparseable/empty body → treated as `{}`. `ids`: if an array, its string members;
  otherwise **undefined → mark ALL unseen** (`:73-78`). `ids: []` or all-non-strings → 0 changed.
- 200 `{"changed": n, "unseen": <count>}`. If n>0 publishes `notifications.seen {count:n}`
  (`domain/notifications.ts:108`).

---

### 5.3 `POST /api/notifications/cards-seen` (Kotlin addition)

The owner opened these cards on some device (the app sends it when a thread opens, a moment
after, with any others opened meanwhile).

- Body `{"cards":["<id>", …]}`: a required list of 1–200-character strings, at most 500. Anything
  else → 400 `invalid_argument`. Never means "everything", unlike a missing `ids` on `/seen`.
- Marks seen every unseen row whose `update_id`, `request_id` or `message_id` is one of the ids,
  and every row for a line in their threads (`messages.update_id`, `reply_to`, `request_id` or
  `task_id` is one of them). So an update, post, question or single line can be named.
- 200 `{"changed": n, "unseen": <count>}`. Publishes `notifications.seen {count}` and
  `notifications.cleared {tags}` when anything changed (spec/08 §6.9).

### 5.4 `POST /api/notifications/handled` (Kotlin addition)

A device asks which of the alerts it still shows are done with, by the tag each alert carried
(spec/08 §6.7), to catch up on clears it missed while closed or offline.

- Body `{"tags":["message-01J…", …]}`: same limits as `cards` above.
- 200 `{"handled":[…]}`: the asked tags that are done with, in the order asked, no repeats.
  - `request-<id>`: not pending, past its deadline, or no such request.
  - `pairing-<id>`: not pending, past its deadline, or no such pairing (settled ones are purged).
  - `update-<id>` / `message-<id>`: deleted, or its bell row is seen. One the dashboard never had
    is **not** done with (it may be an app's own notice, such as the desktop's `update-check`).
  - Any other shape: never done with.
- An older dashboard answers 404; apps take that as "none".

## 6. Summary and phone pushes (iOS contract)

### `GET /api/summary` (`http/owner/devices.ts:225`, `domain/summary.ts:40`)

- Auth: cookie or bearer. 200, key order:
```json
{"at":"2026-11-18T13:52:00.000Z",
 "onlineAgents":[{"agentId":"01JD…","name":"claude","host":"box","cwd":"/srv/x"}],
 "requests":[{"id":"01JD…","agentId":"01JC…","agentName":"claude","projectSlug":"web","projectName":"Web",
   "question":"Ship it?","detail":null,"default":"true","kind":"confirm","options":null,
   "expiresAt":"2026-11-18T14:52:00.000Z"}],
 "work":{"todo":3,"inProgress":1,"blocked":0},
 "projects":[{"slug":"web","name":"Web"}],
 "working":[{"agentId":"01JC…","agent":"claude","task":"Fix the fee rounding","since":1763460000}]}
```
- `onlineAgents`: one per agent with an open session whose last heartbeat ≥ now−5 min
  (`domain/sessions.ts:71,237`), newest heartbeat first; host/cwd from session meta, non-empty strings
  else null (`sessions.ts:477`).
- `requests`: §1.8 list; `agentName` fallback `"An agent"`; `default` = `config.default ?? null`;
  `options` null when empty/none; `expiresAt` ISO.
- `work`: counts of `updates`+`messages` with non-null `status`, not deleted, in **active** projects
  (`db/cards.ts:240`); statuses `todo|in_progress|blocked|done|cancelled` (`db/cards.ts:39`), only three
  exposed.
- `projects`: active only, pinned first then `name.localeCompare` (`summary.ts:74-76`).
- Client: `DashboardSummary.kt` (all fields above; `default` → `defaultAnswer`).

### 6.1 APNs alert (`domain/apns.ts`)

On `request.created`/`update.created`/`message.created` when APNS_KEY_ID, APNS_TEAM_ID, APNS_KEY_PATH,
APNS_BUNDLE_ID all set (`config.ts:179`). Sent to devices with `apns_token` and
`deviceWantsAlert` (`apns.ts:426`); filter is always defaults (`notifies(null,…)` `:423`).
Headers (`:336`): `apns-push-type: alert`, `apns-topic: <bundleId>`, `apns-priority: 10`,
`apns-expiration: now_s+900`, `apns-collapse-id: tag` (≤64 bytes). Payload (`:311`):
```json
{"aps":{"alert":{"title":"…","body":"…"},"sound":"default","thread-id":"<project slug|dashboard>",
  "category":"REQUEST","mutable-content":1,"interruption-level":"time-sensitive"},
 "url":"…","tag":"request-<id>","markdown":"<question ≤2048 bytes>",
 "requestId":"<id>","requestKind":"choice","options":["Red","Blue"]}
```
`mutable-content` only for REQUEST; `interruption-level` `active` for UPDATE/MESSAGE; UPDATE adds
`"reply":{"update":<id>}`, MESSAGE `"reply":{"answers":<messageId>}`; `options` null when none
(`apns.ts:362-412`). Environment host from `apns_environment` (default production). 410, or 400
`BadDeviceToken`/`DeviceTokenNotForTopic` clears that token (`:229`); 200 resets `failures`.

### 6.2 Live Activity (`domain/live-activity.ts`)

Recomputed ≤2 s after any of `request.created/answered/updated`, `agent.presence`, `agent.renamed`,
`update.*`, `message.*`, `project.created/updated` (`:39-53`), and every 60 s (`:34`).
Content state (`domain/summary.ts:91`): `{"online","waiting","inProgress","topAgent","topQuestion",
"topRequestId","topKind"}` from the summary (top = oldest pending). Update push to each
`live_activity_token` only when the state JSON changed: headers `apns-push-type: liveactivity`,
`apns-topic: <bundleId>.push-type.liveactivity`, priority 10 if waiting>0 or waiting changed else 5;
payload `{"aps":{"timestamp":<s>,"event":"update","content-state":{…}}}` (`:133`). On a new request,
devices with `push_to_start_token` and no `live_activity_token` (5 min cooldown) get
`{"aps":{"timestamp","event":"start","content-state","attributes-type":"AgentsActivityAttributes","attributes":{},"alert":{"title","body"}}}`
(`:140-180`).

---

## 7. Agents

### `PATCH /api/agents/[id]` (`http/owner/actions.ts:127`)

- Body must be exactly `{ "name": string }` (one key) → else 400
  `an agent patch changes name and nothing else`; non-string → `name must be a string` (`:265`).
- Unknown agent → 404 `no such agent: <id>` (checked before name trim; `domain/agents.ts:252`).
  Trimmed name 1..100 (`AGENT_NAME_MAX_LENGTH`, `agents.ts:42`). No uniqueness check; works on
  revoked agents.
- 200 `{"agent":{"id","name"}}`. Publishes `agent.renamed {agentId, name}` (even if unchanged).

---

## 8. Apps

**Only two routes exist**: `GET /apps/[view]` and `POST /api/apps/[view]/state`. There is no
`/api/apps` or `/api/apps/[view]` (`find src/http/routes/api/apps` → only `[view]/state`).
`view` is an `app_views.id` (one showing of an app on one card).

### `GET /apps/[view]` (`http/routes/apps/[view]/+server.ts:12`, `http/apps/serve.ts:94`)

- Auth: hook first — unauthenticated → **303** to `/login?redirectTo=%2Fapps%2F<id>` (not under
  `/api`). Handler's own check → 401 JSON (cookie or bearer).
- Unknown view, missing app, or app with a `url` (external page) → 404 `{"error":"not_found"}`,
  headers `content-type: application/json`, `cache-control: no-store` (`serve.ts:86`). Deleted card is
  **not** checked (`domain/apps.ts:415`).
- 200 body = stored `apps.html` verbatim. Headers (`serve.ts:106-115`):
  `content-type: text/html;profile=mcp-app;charset=utf-8`, `content-security-policy: <policyFor>`,
  `x-content-type-options: nosniff`, `referrer-policy: no-referrer`, `cache-control: no-store`.
- CSP (`serve.ts:63`), joined with `; `:
  `default-src 'none'`, `script-src 'unsafe-inline'`, `style-src 'unsafe-inline' <resource…>`,
  `img-src data: blob: <resource…>`, `font-src data: <resource…>`, `media-src data: blob: <resource…>`,
  `connect-src <connect…|'none'>`, `frame-src <frame…|'none'>`, `form-action 'none'`, `base-uri 'none'`,
  `frame-ancestors 'self'`. Each list joined by a space; trailing space trimmed. Lists come from
  `apps.csp` JSON via `parseAppCsp`, or empty on parse failure (`domain/apps.ts:476`).

### `POST /api/apps/[view]/state` (`http/owner/apps.ts:24`)

- Body `{state}`; unparseable body treated as `{}`. `state` must be `null` or a non-array object →
  else 400 `state must be an object, or null to forget it` (so a missing `state` is 400).
- `saveAppState` (`domain/apps.ts:387`): unknown view → 404 `no app view "<id>"` (id JSON-quoted);
  card missing/deleted → 404 `no such <kind>: <id>`; object re-serialised with `JSON.stringify`,
  >20000 chars → 400 `state may be at most 20000 characters of JSON` (`apps.ts:593`, `APP_STATE_MAX_LENGTH`
  `:124`).
- Writes `app_views.state` (text or NULL) and `updated_at` (`db/apps.ts:254`). **No bus event.**
- 200 `{"view_id":"<id>","state":{…}|null}`.

AppView JSON (used in snapshot `cardApps`, `domain/apps.ts:441-459`), key order:
`{"id","card":{"kind","id"},"uri","name","description","url","title","data":{…},"state":{…}|null,"author","agentId","createdAt","updatedAt"}`.
Stored JSON that fails to parse reads as `{}`.

---

## 9. Snapshots (`http/stream/snapshot.ts`)

Handler `createSnapshotHandler` (`snapshot.ts:360`): auth via `ownerAuthenticated` (cookie or
bearer) → `seq = bus.lastSeq` read **before** state → state → 200 `{"seq","at":ISO, …state}` (key
order: `seq`, `at`, then state keys). Headers `content-type: application/json; charset=utf-8`,
`cache-control: no-store`. `DomainError` → `{error,message}` 400/404/409.

Query (`readSnapshotQuery`, `snapshot.ts:392`) — malformed values are dropped, not rejected:
- `limit`: trimmed must match `^\d+$`, safe integer >0, else 50. Then capped at 200 by
  `listUpdates` (`domain/updates.ts:378`).
- `project`: trimmed non-empty → slug or id (`domain/projects.ts:195`); unknown → 404
  `{"error":"not_found","message":"no such project: <ref>"}`.
- `cursor`: trimmed non-empty; must match `^[0-9]+$` else 400
  `{"error":"invalid_argument","message":"cursor is not one this server issued"}` (`updates.ts:389`).
  Means "updates with seq < cursor".
- `status`: `active`|`archived` filters only the project list.
- `task`: parsed and **ignored**.

### `GET /api/snapshot` (`readFullSnapshot`, `snapshot.ts:258`)

State keys in order: `projects, tags, cardTags, cardFields, cardApps, updates, agentNames, messages,
acks, messageMedia, messageEmbeds`. Read order (first error wins): projects → messages (project
resolve) → updates (project, cursor) → pending requests → tags/fields/apps → tags list.

| key | content | order | source |
|---|---|---|---|
| `projects` | every project (or `status` filtered) | `pinned DESC, seq DESC` | `db/projects.ts:103` |
| `tags` | tags of `project`, or all | `seq` | `domain/tags.ts:148`, `db/tags.ts:105` |
| `cardTags` | card tags on page updates, snapshot messages, **all** pending requests | per kind update→message→request, each by `seq` | `db/tags.ts:225` |
| `cardFields` | same scope | same | `domain/fields.ts:151` |
| `cardApps` | same scope, AppView §8 | same | `domain/apps.ts:430` |
| `updates` | `{items, nextCursor, hasMore}` | `seq DESC` | `snapshot.ts:317` |
| `agentNames` | `{agentId: name}` every agent incl. revoked | `seq` | `domain/agents.ts:215` |
| `messages` | newest ≤200 live messages in scope, plus missing parent posts, oldest first | `seq ASC` | `domain/messages.ts:966` |
| `acks` | acks on those messages | `seq ASC` | `db/acknowledgements.ts:116` |
| `messageMedia` | `{messageId: Media[]}` only non-empty | message order | `snapshot.ts:296` |
| `messageEmbeds` | `{messageId: Embed[]}` only non-empty | message order | `snapshot.ts:301` |

Shapes (key order = mapper order):
- Project (`db/projects.ts:29`): `seq,id,slug,name,description,status,pinned,createdAt,updatedAt,theme,board`.
  `theme` `{background?,accent?,logoMediaId?,logoReplacesName?}|null`; `board`
  `{columns:[{title,states:["todo"|"claimed"|"done"|"cancelled"…]}]}|null` (`db/types.ts:34-62,247`).
- Update item (`db/updates.ts:38` + `snapshot.ts:343-349`):
  `seq,id,projectId,agentId,sessionId,title,body,level,pinned,createdAt,deletedAt,editedAt,priority,taskId,status,statusAt,media,embeds,reactions[,share]`.
  `share` only when a live share exists: `{"views":n,"sharedAt":ms}` (`domain/shares.ts:164`).
  `nextCursor` = `String(last.seq)` when `hasMore`, else null (`domain/updates.ts:206-210`).
- Media (`domain/media.ts:556`): `id,updateId,messageId,requestId,taskId,kind,mime,status,bytes,width,height,durationMs,filename,variants`;
  `variants` ⊆ `original,thumb-640,thumb-1600,poster,video` (`media/serve.ts:52,224`).
- Embed (`domain/embeds.ts:783`): `id,updateId,messageId,requestId,taskId,type,typeName,spec,data,sourceUrl,createdAt`;
  embeds whose type/spec can't be read are dropped.
- Reaction (`db/reactions.ts:33`): `seq,id,card{kind,id},messageId,actor,emoji,createdAt`.
- Tag (`db/tags.ts:32`): `seq,id,projectId,name,slug,colour,agentId,createdAt`.
- CardTag (`db/tags.ts:160`): `seq,id,tagId,card,author,createdAt`.
- CardField (`db/fields.ts:33`): `seq,id,card,name,slug,value,author,createdAt,updatedAt`.
- Message (`db/messages.ts:47`): `seq,id,projectId,updateId,taskId,author,body,createdAt,replyTo,deletedAt,answers,requestId,editedAt,pinned,status,statusAt`.
- Ack (`db/acknowledgements.ts:34`): `seq,id,agentId,card,messageId,taskId,updateId,requestId,state,createdAt,updatedAt`;
  `state` `thinking|read|done`.
- `card.kind` ∈ `update|message|request` (`db/cards.ts:30`); card resolved message→update→request for
  acks/reactions, update→message→request for tags/fields/apps.

### `GET /api/snapshot/updates` (`snapshot.ts:310`)

`{"seq","at","updates":{items,nextCursor,hasMore}}`. Same query rules.

### `GET /api/snapshot/requests` (`http/stream/requests.ts:53`)

No query used. `{"seq","at","requests":[…]}` — each = OwnerRequest (§1.7) + `media` (all media on the
request incl. option pictures, `seq` order), `embeds`, `reactions` (kind `request`). List per §1.8.
Client: `FeedModels.kt RequestView` (`pictureFor` maps `config.optionMedia[option]` → media id;
`cardMedia` excludes pictures).

### `GET /api/snapshot/agents` (`http/stream/agents.ts:30`)

`{"seq","at","agents":[…]}`; each (`domain/sessions.ts:256-266`):
`agentId,name,sessionId,startedAt,lastHeartbeatAt,sessions,host,cwd,model` (times epoch ms; `sessions`
= open live sessions of that agent). Newest heartbeat first.

---

## Surprises

1. **Expired-but-unswept request + owner action**: answer/DELETE flips it to `timeout`, publishes
   `request.answered{state:"timeout"}`, *then* returns 409 `request <id> timed out`
   (`domain/requests.ts:392,417,843`). A failed call has a side effect.
2. `DELETE /api/requests/:id` is not idempotent (409 the second time), unlike `DELETE /api/devices/:id`.
3. Editing options on a picture question drops the `optionMedia` entry but leaves the media row on the
   request (`requests.ts:548-559`); the dropped picture then shows as ordinary card media in clients
   (`RequestView.cardMedia`).
4. `text` answers default `min` 1 (empty refused); `form` text default `min` 0 (empty allowed);
   multi_choice `[]` allowed unless `min` set (`requests.ts:716,733,754`).
5. Option matching is exact on the answer, but options were trimmed at creation; option-picture keys
   must equal the trimmed option.
6. `POST /api/notifications/seen` with `ids` that is not an array (e.g. `"x"`) marks **everything**
   seen (`http/owner/notifications.ts:76-78`).
7. `GET /api/notifications?limit=` uses JS `Number()`: `0x10`→16, `1e1`→10, ` 7 `→7, `2.5`→50.
8. Snapshot `?task=` is parsed but ignored (`snapshot.ts:404` vs `:317`).
9. Full snapshot `cardTags/cardFields/cardApps` include **every** pending request deployment-wide even
   when `?project=` is set (`snapshot.ts:280`).
10. `/api/devices/current/*` with a cookie (no bearer) → **400** `invalid_argument`, not 401
    (`http/owner/devices.ts:173-181`).
11. Alert projects: `null` = all, `[]` = only project-less alerts. The native client sends `null` for an
    empty list (`DashboardClient.kt setAlertProjects`), so it can never send `[]`.
12. `POST /api/devices` with no `TOKEN_SECRET` → 503 `{"error":"misconfigured"}` **without** `message`;
    sign-in's config 503 has a message.
13. Sign-in: missing/non-string `password` counts as a wrong guess; `""` does not (`http/auth/login.ts:102,116`).
    The limiter is in-memory, per process, shared with the web form.
14. Mixed wire conventions: devices/summary use ISO strings; requests/notifications/snapshot rows use
    epoch ms; `GET /api/push` has snake_case `last_sent_at` but other keys camelCase; apps state route
    returns `view_id`.
15. `GET /apps/:view` unauthenticated → 303 redirect (hook), not 401; it also serves apps on deleted cards.
16. `/api/apps` and `/api/apps/[view]` do not exist.
17. Summary project sort uses `String#localeCompare` (ICU, locale-aware), not code-point order.
18. `PATCH /api/agents/:id` publishes `agent.renamed` even when the name is unchanged; 404 beats a bad
    name; any extra key is 400.
19. `POST /api/push` overwrites the stored label with `null` when the browser omits it.
20. `GET /api/push` returns stored prefs as-is (may lack `types`); default substitution only when null.
21. Device auth writes `last_seen_at` (at most once a minute) on reads, including `GET /api/devices` from
    that device.
22. Web Push actions: first **2** options; native iOS shows up to **4** (`RequestActions.MaxOptionButtons`)
    from the APNs `options` list. `text` has a Reply action on iOS but none in Web Push.
23. JS `trim()` strips U+FEFF; Kotlin `String.trim()` does not. Use a JS-compatible trim.
24. `settledAt` on `request.answered` is ISO; `answeredAt` in JSON is epoch ms.

## Parity test notes

Setup: owner cookie `C` (log in via `/login`) or device key `K` from sign-in; agent token `A` for MCP.
Create requests with MCP `tools/call request_input` (hold returns `pending` after ≤55 s; pass a short
`timeout_s` where needed). `H = -H 'authorization: Bearer K' -H 'content-type: application/json'`.

**Auth**
- `GET /api/summary` no auth → 401 `{"error":"unauthenticated"}`, `content-type: application/json`.
- `GET /api/summary` with `Bearer xxx` + valid cookie → 401 (bearer decides).
- `GET /apps/01JD…` no auth → 303 `location: /login?redirectTo=%2Fapps%2F01JD…`.

**Sign-in**
- `POST /api/devices/sign-in {"password":"right","name":"  Phone  "}` → 201
  `{"device":{"id":"…","name":"Phone","createdAt":"…Z"},"token":"<43>"}`.
- `{"password":"","name":"x"}` → 400 `{"error":"wrong_password","message":"Enter your password."}`.
- `{"password":"right","name":"   "}` → 400 `{"error":"invalid_argument","message":"name is required"}`.
- 5× wrong password from one address, then right → 429 `rate_limited`, `retry-after` ≈ 900.

**Devices**
- `PUT /api/devices/current/apns` with cookie → 400 `this needs a device key: send Authorization: Bearer <device token>`.
- `PUT … {"token":"ABCDEF","environment":"sandbox"}` (bearer) → 200 `{"ok":true}`; then
  `GET /api/devices` shows `hasApns:true`; DB token `abcdef`.
- `{"token":"xyz","environment":"sandbox"}` → 400 `token must be a hex string`.
- `PUT …/live-activity {}` → 400 `send updateToken, pushToStartToken, or both (null clears one)`.
- `PUT …/alert-projects {"projects":["nope"]}` → 400 `unknown projects: nope`;
  `{"projects":["WEB"]}` → 200; `GET /api/devices` → `alertProjects:["web"]`.
- `DELETE /api/devices/<id>` twice → `{"removed":true}`, `{"removed":false}`; old key → 401.

**Requests — answer per kind** (POST `/api/requests/<id>/answer`)
- confirm: `{"value":true}` → 200, `request.state:"answered"`, `answer:{"kind":"confirm","value":true}`;
  `{"value":"yes"}` → 400 `a confirm is answered true or false`; `{}` → 400 `an answer must carry a value`.
  `{"value":false}` → answered; DB `state='rejected'`, `decided_value='false'`.
- text (`min:3`): `{"value":"  ab "}` → 400 `the answer must be at least 3 characters`;
  `{"value":"  abc "}` → `answer.value:"abc"`; `{"value":""}` with no min → 400 `…at least 1 characters`.
- buttons `["Yes","No"]`: `{"value":"Maybe"}` → 400 `"Maybe" is not one of the options offered`;
  `{"value":5}` → 400 `5 is not one of the options offered`; `{"value":"No"}` → 200.
- choice: same as buttons.
- multi_choice `["a","b","c"]`, `max:2`: `{"value":["a","a"]}` → 400 `an option was chosen twice`;
  `["a","b","c"]` → 400 `choose at most 2`; `[]` → 200 `value:[]`; `"a"` → 400
  `a multi_choice is answered with a list of options`.
- form `["Send","Cancel"]`: `{"value":{"text":"hi"}}` → 400 `undefined is not one of the actions offered`;
  `{"value":{"action":"Send"}}` → 400 `a form’s text is a string`;
  `{"value":{"action":"Send","text":"  hi  "}}` → 200 `answer:{"kind":"form","value":{"action":"Send","text":"hi"}}`, DB `decided_value='Send'`.
- Second answer → 409 `request <id> has already been answered`. Unknown id → 404 `no such request: <id>`.
- Agent's `await_request` then returns `answered`; stream frame `request.answered{state:"answered"}`.

**Dismiss**
- `DELETE /api/requests/<id>` → 200 `request.state:"cancelled"`, `answeredAt` set, `answer:null`;
  again → 409 `request <id> was dismissed`; agent hears `cancelled`.

**Expire**
- Create with `timeout_s:5`; wait 6 s (before a sweep): `GET /api/snapshot/requests` omits it;
  `POST …/answer {"value":true}` → 409 `request <id> timed out` and a `request.answered{state:"timeout"}`
  frame appears. Without touching it, within ≤30 s the sweeper publishes the same frame.
- `timeout_s:4` at creation → MCP error `timeout_s must be a whole number of seconds between 5 and 86400`.

**Withdraw**
- MCP `withdraw_request {request_id}` → state `withdrawn`; owner answer then → 409 `request <id> was withdrawn`;
  another agent's withdraw → `request belongs to another agent`.

**Edit**
- `edit_request {request_id, options:["Red","Green"]}` on choice with default `Blue` →
  `the default must be one of the options`. With default `Red` → OK, `editedAt` set, frame `request.updated`.
- `edit_request {request_id}` alone → `an edit must change the question, the detail, the options or the default`.

**Option pictures**
- `request_input {kind:"choice", options:["Red","Blue"], option_media:{"Red":<img>}}` → snapshot
  request `config.optionMedia:{"Red":<img>}`, `media` contains `<img>`.
- `option_media` on `form` → `pictures on options are for a buttons, choice or multi_choice request`;
  key `"Green"` → `Green is not one of the options`; video media → `media <id> must be an image to go on an option`;
  same id in `media_ids` → `a picture on an option cannot also be in media_ids`.
- Edit options to `["Blue","Green"]` → `config` loses `optionMedia`; `media` still lists `<img>`.

**Summary / snapshots**
- After creating a confirm with `default:"true"`: `GET /api/summary` → `requests[0]` has
  `"default":"true","kind":"confirm","options":null`, `expiresAt` ISO with `.000Z`-style millis.
- `GET /api/snapshot?limit=abc` → 200 with ≤50 items; `?cursor=x` → 400
  `cursor is not one this server issued`; `?project=nope` → 404 `no such project: nope`;
  `?limit=500` → ≤200 items; `hasMore` true ⇒ `nextCursor` = last item `seq` as string.
- Response starts with `{"seq":` then `"at":` (key order).

**Push / notifications**
- Push off: `POST /api/push {}` → 409 `push is not configured on this deployment`; `GET` → `enabled:false,publicKey:null`.
- `PATCH /api/push {"endpoint":"https://x","prefs":{"types":["bogus"]}}` → 400
  `types must be any of: request, update, message, comment`.
- `DELETE /api/push` → 400 `endpoint is required`.
- After a request: `GET /api/notifications` → first row `kind:"request"`, `title:"<agent> is waiting on you"`,
  `path:"/projects/<slug>?focus=<id>"`; `POST /api/notifications/seen {"ids":"x"}` → clears all.

**Apps**
- `POST /api/apps/<view>/state {}` → 400 `state must be an object, or null to forget it`;
  `{"state":{"tab":2}}` → 200 `{"view_id":"<view>","state":{"tab":2}}`; unknown view → 404 `no app view "<view>"`.
- `GET /apps/<view>` → 200 with exact CSP string, e.g. no origins:
  `default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; media-src data: blob:; connect-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'; frame-ancestors 'self'`.
