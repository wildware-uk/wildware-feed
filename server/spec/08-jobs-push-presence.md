# 08 — Background jobs, presence, notifications, push

Scope: everything that runs without a request, and everything that leaves the box
(Web Push, APNs alerts, Live Activity). Source of truth: the working tree of
the `agent-dashboard` repository. All times are epoch milliseconds unless stated.

---

## 1. Process wiring

All background work is started once, at module scope of `src/hooks.server.ts`, guarded by
`if (!building)`. adapter-node 5.5.7 imports hooks during `server.init()` at boot, so they
start at process start, not on the first request.

Start order (this is also the bus subscription order, which matters, see §9):

| # | Call | Source |
|---|------|--------|
| 1 | `startPresenceSweeper()` | `src/hooks.server.ts:47` |
| 2 | `startRequestSweeper()` | `src/hooks.server.ts:54` |
| 3 | `startRequestPusher()` (Web Push, bus subscriber) | `src/hooks.server.ts:63` |
| 4 | `startApnsPusher()` (bus subscriber) | `src/hooks.server.ts:68` |
| 5 | `startLiveActivityPusher()` (bus subscriber + timers) | `src/hooks.server.ts:69` |
| 6 | `startNotificationRecorder()` (bus subscriber) | `src/hooks.server.ts:76` |
| 7 | `startMediaSweeper()` | `src/hooks.server.ts:83` |
| 8 | `startDerivativeWorker()` | `src/hooks.server.ts:94` |

- Every `start*` returns a stop function; hooks ignores all of them. Nothing stops them on
  shutdown. There is no `SIGTERM`/`sveltekit:shutdown` handler in `src` (grep: none).
- Interval timers are `.unref()`'d so they never hold the process open.
- Every job resolves its `DomainContext` per tick via `context()`
  (`src/domain/context.ts:32-38`: process DB, shared bus, `Date.now`). A tick that throws is
  logged with `console.error(<label>, error)` and the timer keeps running.

---

## 2. Timer inventory

Excluding tests, `src/web`, `src/channel`.

| Timer | Kind | Period | First run | unref | Source |
|-------|------|--------|-----------|-------|--------|
| Presence sweeper | `setInterval` | 60 000 (`SWEEP_INTERVAL_MS`) | +60 s | yes | `src/domain/sessions.ts:77,352-362` |
| Request sweeper | `setInterval` | 30 000 (`REQUEST_SWEEP_INTERVAL_MS`) | +30 s | yes | `src/domain/requests.ts:146,682-691` |
| Media sweeper | `setInterval` | 900 000 (15 min, `MEDIA_SWEEP_INTERVAL_MS`) | +15 min | yes | `src/domain/media.ts:644,677-687` |
| Derivative worker | `setInterval` + `setTimeout(0)` | 1 000 (`WORKER_INTERVAL_MS`) | immediately (next tick) | yes | `src/media/pipeline.ts:50,288-302` |
| Live Activity refresh | `setInterval(schedule)` | 60 000 (`LIVE_ACTIVITY_REFRESH_MS`) | +60 s | yes | `src/domain/live-activity.ts:34,220-221` |
| Live Activity coalesce | `setTimeout` | 2 000 (`LIVE_ACTIVITY_COALESCE_MS`) | on first watched event | **no** | `src/domain/live-activity.ts:31,206-212` |
| Owner SSE heartbeat | `setInterval` per connection | 15 000 (`HEARTBEAT_MS`) | +15 s | yes | `src/http/stream/stream.ts:26,126-132` |
| Agent SSE heartbeat | `setInterval` per connection | 15 000 (`AGENT_HEARTBEAT_MS`) | +15 s | yes | `src/http/stream/agent.ts:226,708-713` |
| `bus.waitFor` timeout | `setTimeout` per parked waiter | caller's `timeoutMs` (request hold, `HOLD_S` default 55 s) | — | **no** | `src/events/bus.ts:171`, `src/config.ts` `HOLD_S` |
| APNs HTTP/2 session idle | `session.setTimeout` | 600 000 (`IDLE_SESSION_MS`) → `session.close()` | — | n/a | `src/domain/apns.ts:75,157` |
| APNs stream timeout | `stream.setTimeout` | 10 000 (`REQUEST_TIMEOUT_MS`) → `close(NGHTTP2_CANCEL)` | — | n/a | `src/domain/apns.ts:74,181` |

Things that look like jobs but have **no timer**:

- Event ring trimming: fixed-size ring, capacity 500 (`RING_CAPACITY`), evicts the oldest on
  each `push`. `src/events/bus.ts:10`, `src/events/ring.ts:65-72`.
- MCP per-token rate limiter: in-memory sliding window, 120 req / 60 000 ms, max 10 000
  keys; stale keys are swept lazily from the front of the `Map` on every `take()`; LRU
  eviction past 10 000. `src/mcp/rate-limit.ts:19,22,30,70-100`.
- Login rate limiter: 5 attempts / 15 min per client address
  (`src/http/auth/login.ts:33-34`), same lazy-filter design, max 10 000 keys
  (`src/http/auth/rate-limit.ts:41`). No sweep at all; only LRU eviction.
- Device `last_seen_at`: written at most once per 60 000 ms per device, on authentication
  (`src/domain/devices.ts:40,89-93`, SQL `src/db/owner-devices.ts:125-134`).
- Agent `last_seen_at`: `max(coalesce(last_seen_at,0), now)` on every authenticated MCP
  request (`src/mcp/auth.ts:160`, `src/db/agents.ts:129-138`). Not used for presence.

### 2.1 Presence sweeper

`sweepSessions(ctx, { idleMs = 600 000 })` — `src/domain/sessions.ts:296-321`.

1. `onlineBefore` = agent ids of open sessions with `last_heartbeat_at >= now - 90 000`.
2. `UPDATE sessions SET ended_at = :now WHERE ended_at IS NULL AND last_heartbeat_at < now - 600 000 RETURNING id`
   (`src/db/sessions.ts:132-141`). Strict `<`.
3. For each touched agent that was in `onlineBefore` and is not online now:
   publish `agent.presence {agentId, sessionId: null, online: false}`.

With the default constants step 3 can never fire (a session idle 10 min was not keeping
anyone online). In production the sweep publishes nothing; it only closes rows so the next
`heartbeat` on that session gets `conflict`.

### 2.2 Request sweeper

`expireRequests(ctx)` — `src/domain/requests.ts:655-658`.

- SQL: `UPDATE approvals SET state = 'timeout', decided_at = :now WHERE state = 'pending' AND expires_at <= :now RETURNING …`
  (`src/db/approvals.ts:314-326`). Row order is whatever SQLite returns.
- One `request.answered` per row: `{requestId, agentId, state: 'timeout', settledAt: ISO(decided_at)}`
  (`src/domain/requests.ts:821-834`).
- The same flip also happens lazily on read (`expireIfDue`, `src/domain/requests.ts:843-850`):
  due when `now >= expiresAt`. Same boundary as the sweeper.
- `listPendingRequests` additionally hides rows with `expiresAt <= now` that are not yet swept
  (`src/domain/requests.ts:586-598`).
- No push, APNs or notification is sent or withdrawn on expiry. Live Activity recomputes
  (it watches `request.answered`).

### 2.3 Media sweeper

`sweepMedia` → `sweepOrphanedMedia` (`src/media/sweeper.ts:70-99`).

- Orphan = no update, no message, not a project's `theme.logoMediaId`, not any text value
  anywhere in `embeds.data` (json_tree); status in `ready|pending|failed`; `created_at < now - 3 600 000`
  (`ORPHAN_AGE_MS`); oldest first; at most 200 (`SWEEP_BATCH`).
  `src/media/sweeper.ts:38,41,44`, `src/db/media.ts:384-420`.
- Per orphan: `rm -rf <mediaDir>/<id>` first, then delete the row.
- Then `DELETE FROM upload_tokens WHERE used_at IS NULL AND expires_at <= now`
  (`src/db/upload-tokens.ts:106-109`).
- Async; the interval does not wait for a previous sweep (`void …catch(onError)`,
  `src/domain/media.ts:679`). Publishes no events.

### 2.4 Derivative worker

Details belong to the media spec. Timing only: tick every 1 000 ms plus one at `setTimeout(0)`;
DB and settings resolved on first tick and kept; enqueues up to 50 pending rows per tick
(`DERIVATIVE_BATCH`); concurrency 2 (`DEFAULT_CONCURRENCY`). `src/media/pipeline.ts:39,50,255-303`,
`src/media/queue.ts:27`. Publishes `media.ready`.

---

## 3. Presence and sessions

Source: `src/domain/sessions.ts`, SQL `src/db/sessions.ts`.

### 3.1 Constants

| Name | Value | Source |
|------|-------|--------|
| `HEARTBEAT_INTERVAL_S` | 30 | `src/domain/sessions.ts:68` |
| `PRESENCE_WINDOW_MS` | 300 000 | `src/domain/sessions.ts:71` |
| `SESSION_IDLE_MS` | 600 000 | `src/domain/sessions.ts:74` |
| `SWEEP_INTERVAL_MS` | 60 000 | `src/domain/sessions.ts:77` |
| meta `host` / `cwd` / `model` max length | 200 / 1 000 / 100 | `src/domain/sessions.ts:80-82` |

The browser copies the window: `PRESENCE_WINDOW_MS = 300_000` and re-polls the agents snapshot
every 20 000 ms (`src/web/presence.svelte.ts:78,81`).

### 3.2 Model

- Presence is derived, never stored. Agent is **online** at `t` iff it has an open session
  (`ended_at IS NULL`) with `last_heartbeat_at >= t - 90 000` (`src/domain/sessions.ts:419-424`).
  There is no "idle" state: just online / not.
- `registerSession` (`:116-133`): inserts a session with `started_at = last_heartbeat_at = now`
  (`src/db/sessions.ts:47-66`). Several open sessions per agent are allowed. Publishes
  `agent.presence {agentId, sessionId: <new id>, online: true}` only if the agent was not
  online just before. Returns `heartbeatIntervalS: 30`.
- `heartbeat` (`:157-169`): unknown session → `not_found` "no such session: <id>"; other
  agent's → `invalid_argument` "session belongs to another agent"; ended → `conflict`
  "session <id> has ended; call register_session to start a new one". Sets
  `last_heartbeat_at = now` (only if still open). Publishes `agent.presence … online: true`
  only on an offline→online transition. Returns `{ok: true, unreadMessages, openTasks, pendingApprovals}`.
- `endSession` (`:185-199`): idempotent; already ended → `{session, ended: false}`, no event.
  Otherwise sets `ended_at = now`; publishes `agent.presence {agentId, sessionId, online: false}`
  only if the agent was online before and is not online after (another live session keeps it online).
- **Going offline by silence publishes nothing.** Clients re-derive from `lastHeartbeatAt`
  against their own clock (`src/domain/sessions.ts:26-33`).
- `listLiveAgents(at)` (`:237-270`): open sessions with `last_heartbeat_at >= at - 90 000`,
  newest heartbeat first, one row per agent (the newest session), `sessions` = count.
  Shape: `{agentId, name, sessionId, startedAt, lastHeartbeatAt, sessions, host, cwd, model}`.
- SSE connections (`/api/stream`, `/api/agent/stream`) do **not** touch sessions or presence.
  Only the MCP tools `register_session`, `heartbeat`, `end_session` do
  (`src/mcp/tools/register-session.ts:74`, `heartbeat.ts:61`, `end-session.ts:51`).
- MCP wire: register → `{session_id, heartbeat_interval_s}`; heartbeat →
  `{ok, unread_messages, open_tasks, pending_approvals}`; end → `{session_id, ended}`.

---

## 4. Notifications (the in-app bell)

Source: `src/domain/notifications.ts`, SQL `src/db/notifications.ts`, table
`src/db/migrations/021-notifications.ts`.

- One owner, so there is no recipient column. Every row is for the owner.
- Created by a bus subscriber (`startNotificationRecorder`, `:231-264`) on exactly three events
  (the same subscriber also marks rows seen when what they are about is settled or deleted: §6.9):

| Event | Row | Skipped when | Title | Source |
|-------|-----|--------------|-------|--------|
| `update.created` | `kind:'update'`, `updateId`, `projectId`, `agentId` | update missing or `deletedAt` set | `update.title ?? "<agent name> posted an update"` | `:150-167` |
| `message.created` | `kind:'reply'`, `messageId`, … | missing, deleted, author `human`, author not `agent:<id>` | `"<name> replied to you"` if `repliesToOwner`, else `"<name> commented"` | `:170-194` |
| `request.created` | `kind:'request'`, `requestId`, … | request missing (not checked for pending) | `"<name> is waiting on you"` | `:197-214` |

  Name fallback: `'An agent'`. Body: `firstLine(raw markdown)` = collapse `/\s+/g` to one
  space, trim, cut to 140 UTF-16 units with `trimEnd()` + `…` (`:61-64`).
- Dedupe: partial unique indexes on `update_id`, `message_id`, `request_id`; insert is
  `ON CONFLICT DO NOTHING`. Only a real insert publishes
  `notification.created {notificationId, kind, projectId}` (`:121-147`, `src/db/notifications.ts:69-115`).
- Seen/unseen: `seen_at` NULL = unseen. `markSeen(ids?)` sets `seen_at = now` on unseen rows
  (all of them when `ids` omitted; none for `[]`); publishes `notifications.seen {count}` only
  if `count > 0` (`:108-112`, `src/db/notifications.ts:153-173`).
- List: newest first by `seq`, default limit 50, optional unseen-only (`:85-93`).
- View adds `projectSlug`, `projectName`, `path`:
  `path = (slug ? "/projects/<slug>" : "/") + (focus ? "?focus=" + encodeURIComponent(focus) : "")`,
  `focus = updateId ?? messageId ?? requestId` (`:67-82`).
- No quiet hours, no rate limit, no collapse beyond the per-target unique index.
  Notifications are never deleted.

### 4.1 `repliesToOwner(message)` (`src/domain/push.ts:525-544`)

- `replyTo` set → true iff that message's author is `human`.
- else thread = `updateId` or else `taskId`; none → false.
- else true iff any message in that thread (no limit) has author `human`.

---

## 5. Web Push

Source: `src/domain/push.ts`, `src/db/push.ts`, `src/config.ts:91-171`, `src/service-worker.ts`.
Library: `web-push` 3.6.7 (`package.json:72`).

### 5.1 Config

- On iff `VAPID_PUBLIC_KEY` and `VAPID_PRIVATE_KEY` both set (half set fails config validation).
  `subject = VAPID_SUBJECT ?? PUBLIC_BASE_URL` (`src/config.ts:166-173`). `VAPID_SUBJECT` must
  match `^(mailto:|https://)`. `PUBLIC_BASE_URL` default `http://localhost:5173`, trailing `/`
  stripped.
- Config is re-read (`loadConfig(process.env)`) on every send and every event's base URL.

### 5.2 Subscriptions

Table `push_subscriptions` (`src/db/migrations/003-push-subscriptions.ts`, prefs column from 007):
`seq, id, endpoint (unique), p256dh, auth, label, created_at, last_sent_at, failures (default 0), prefs (JSON|null)`.

- `subscribeToPush` (`src/domain/push.ts:181-207`): endpoint trimmed, required, absolute,
  `https:` only; `keys.p256dh`, `keys.auth` trimmed, required; `label` ≤ 200.
  Upsert on `endpoint`: refresh keys + label, `failures = 0`, keep `created_at`, keep prefs
  (subscribe never passes prefs) (`src/db/push.ts:108-138`).
- `unsubscribeFromPush`: delete by trimmed endpoint, idempotent.
- Prefs `{types?, levels?, priorities?}`; each list must be from
  `types ∈ request|update|message|comment`, `levels ∈ info|success|warn|error`,
  `priorities ∈ low|medium|high`; deduped; empty object → null (`:130-156`).

### 5.3 Who gets what

`notifies(prefs, about)` (`:110-120`): `types` default all four. Non-update → type check only.
Update → also filtered by `levels` / `priorities` when the device set them.

Sent on (`startRequestPusher`, `:620-673`):

| Event | about | Builder | Null (no send) when |
|-------|-------|---------|---------------------|
| `request.created` | `{type:'request'}` | `requestMessage` `:393-418` | request missing or not `pending` |
| `update.created` | `{type:'update', level, priority}` | `updateMessage` `:486-504` | missing or deleted |
| `message.created` | `{type:'message'}` if `repliesToOwner` else `{type:'comment'}` | `replyMessage` `:558-582` | missing, or author not `agent:<id>` (owner's own never push) |

Every subscription is the owner's. No alert-project filter for Web Push. No quiet rules.

### 5.4 Payload (JSON, exactly `JSON.stringify(message)`)

Key order as built:

```json
{"title":"…","body":"…","url":"…","tag":"…","requestId":"…","actions":[{"action":"…","title":"…","value":…}]}
```

- Request: `title = "<name> is waiting on you"`, `body = notificationText(question)`,
  `url = project ? "<base>/projects/<slug>?focus=<requestId>" : "<base>/"` (id **not** URL-encoded,
  and no focus when there is no project), `tag = "request-<id>"`, `requestId`. `actions` only if non-empty:
  - `confirm`: `[{"action":"confirm-yes","title":"Approve","value":true},{"action":"confirm-no","title":"Reject","value":false}]`
  - `buttons` / `choice`: first 2 options → `{"action":"option-<i>","title":opt,"value":opt}`
  - others: none (`:432-449`).
- Update: `title = update.title ?? "<name> posted an update"`, `url = projectUrl(projectId, base, update.id)`,
  `tag = "update-<id>"`. No `requestId`/`actions`.
- Message: `title` as §4, `url = projectUrl(projectId, base, message.id)`, `tag = "message-<id>"`.
- `projectUrl` (`:466-476`): `where = "?focus=" + encodeURIComponent(id)`;
  project found → `<base>/projects/<slug><where>`, else `<base>/<where>`.
- `notificationText` (`:452-455`): markdown → plain text (`src/domain/plain-text.ts`: markdown-it
  `{html:false, linkify:true, breaks:true}`, list items prefixed `• ` or `N. `, code blocks kept,
  images → alt or `image`, lines trimmed, blanks dropped, joined `\n`), then `/[ \t]+/g` → `' '`,
  cut to 140 UTF-16 units + `trimEnd()` + `…`. Newlines survive.

### 5.5 Transport and headers

`webpush.sendNotification(sub, payload, {vapidDetails:{subject, publicKey, privateKey}, TTL: 900})` (`:322-347`).
What web-push 3.6.7 then sends (`node_modules/web-push/src/web-push-lib.js`, `vapid-helper.js`):

- `POST <endpoint>`, `TTL: 900` (`PUSH_TTL_S = 15*60`, `src/domain/push.ts:378`).
- `Content-Encoding: aes128gcm`, `Content-Type: application/octet-stream`.
- `Urgency: normal` (library default; not overridden). No `Topic` header.
- `Authorization: vapid t=<JWT>, k=<publicKey>`; JWT header `{"typ":"JWT","alg":"ES256"}`,
  claims `{"aud":"<endpoint scheme>//<host>","exp":now_s+43200,"sub":subject}`.
- No request timeout set.

### 5.6 Outcome handling (`:330-366`)

All subscriptions in parallel. Per subscription:
- 2xx → `last_sent_at = now, failures = 0`.
- Error with `statusCode` 404 or 410 → delete row.
- Any other error (other status, network, library throw) → `failures += 1`; if `failures >= 10`
  (`MAX_PUSH_FAILURES`) delete row.
- No retry, no queue. `sendPush` never throws; returns `{sent, removed, failed}`.

### 5.7 Service worker contract (`src/service-worker.ts`)

- No `fetch` handler (no caching). `install` → `skipWaiting()`; `activate` → `clients.claim()`.
- `push`: `event.data.json()` (bad/empty → `{}`). `showNotification(title ?? 'Wildware Feed', {body: body ?? 'An agent is waiting on you.', tag: tag ?? 'owner-request', icon: '/icons/icon-192.png', badge: '/icons/icon-192.png', actions: [{action,title}], data: {url: url ?? '/', requestId, actions}})` (`:47-79`).
- `notificationclick`: closes; if the clicked action matches one in `data.actions` and `requestId`
  exists → `POST /api/requests/<encodeURIComponent(id)>/answer`, `credentials:'include'`,
  body `{"value": <action.value>}`; `response.ok` → done. Otherwise focus the first window
  client and `navigate(url)`, or `openWindow(url)` (`:81-141`).
  The Kotlin server must keep that answer route accepting cookie auth and `{value}`.

---

## 6. APNs alerts (iOS)

Source: `src/domain/apns.ts`, devices `src/domain/devices.ts`, `src/db/owner-devices.ts`,
table `src/db/migrations/043-owner-devices.ts` (+ later `alert_project_ids`).

### 6.1 Config

On iff all of `APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_KEY_PATH`, `APNS_BUNDLE_ID` set (partial
fails validation) (`src/config.ts:119-124,179-193`). `environmentApnsSettings` swallows any
config error → treated as off (`src/domain/apns.ts:59-65`).

### 6.2 Device tokens

- `owner_devices` columns: `apns_token`, `apns_environment ('sandbox'|'production')`,
  `live_activity_token`, `push_to_start_token`, `failures`, `alert_project_ids (JSON array|null)`.
- Tokens: string, trimmed, 1..512 chars, `/^[0-9a-f]+$/i`, stored **lowercased**
  (`src/domain/devices.ts:43-44,105-112`).
- `PUT /api/devices/current/apns` `{token, environment}` → sets token + env, `failures = 0`,
  and clears the same token from any other device (`src/db/owner-devices.ts:141-170`).
- `PUT /api/devices/current/live-activity` `{updateToken?, pushToStartToken?}`; omitted = unchanged,
  `null` = clear; at least one key required; same "release elsewhere" rule
  (`src/http/owner/devices.ts:195-208`, `src/db/owner-devices.ts:191-224`).
- `PUT /api/devices/current/alert-projects` `{projects: [slug…] | null}`; slugs trimmed+lowercased,
  deduped, ≤ 200, unknown slug → `invalid_argument "unknown projects: a, b"`; stored as ids
  (`src/domain/devices.ts:153-175`).
- None of these publish an event.

### 6.3 Selection

`sendApnsAlert` (`:416-446`): devices with `apnsToken !== null`, `notifies(device.alertPrefs, about)`
(the phone's own kinds/levels/priorities, migration 047; NULL = every alert), and `deviceWantsAlert`:
`projectId === null || alertProjectIds === null || alertProjectIds.includes(projectId)`
(`src/domain/devices.ts:184-190`). Same three events as Web Push (`:462-501`), same null rules
(builders are reused from `push.ts`).

### 6.4 Transport (`:137-197`)

- Host by device `apns_environment ?? 'production'`:
  `production → https://api.push.apple.com`, `sandbox → https://api.sandbox.push.apple.com` (`:67-70`).
  Port 443 (Node `http2.connect(authority)` defaults).
- One HTTP/2 session per host, cached; dropped from the cache on `error`/`close`/`goaway`;
  idle 10 min → close. Shared per `teamId:keyId:keyPath` between alert and Live Activity
  senders (`:209-220`).
- Request: `:method POST`, `:path /3/device/<token>`, `authorization: bearer <JWT>`,
  `content-type: application/json`, then the type's headers. Body = `JSON.stringify(payload)`.
- Stream timeout 10 s → cancel → rejected "APNs stream closed early".
- Response: `status = :status`, `reason = JSON body .reason` (string) or null.

### 6.5 Provider token (JWT) (`:98-124`)

- Header `{"alg":"ES256","kid":"<APNS_KEY_ID>"}`, claims `{"iss":"<APNS_TEAM_ID>","iat":<floor(now/1000)>}`,
  both base64url of `JSON.stringify` (that key order). Signature: ECDSA-SHA256, IEEE-P1363 (raw r||s), base64url.
- Cached 50 min from signing (`PROVIDER_TOKEN_TTL_MS = 3 000 000`), re-signed on the first send after.
- Invalidated (re-sign next send) when a response is `403` and `reason` matches `/ProviderToken/`.
- `.p8` read lazily on first sign, then the `KeyObject` is kept for the process life.

### 6.6 Alert headers (`alertHeaders`, `:336-344`)

```
apns-push-type: alert
apns-topic: <APNS_BUNDLE_ID>
apns-priority: 10
apns-expiration: <floor(now/1000) + 900>
apns-collapse-id: <tag cut to 64 UTF-8 bytes, no split characters>
```

### 6.7 Alert payload (`alertPayload`, `:311-334`), key order as built

```json
{
  "aps": {
    "alert": {"title": "…", "body": "…"},
    "sound": "default",
    "thread-id": "<project slug or 'dashboard'>",
    "category": "REQUEST|UPDATE|MESSAGE",
    "mutable-content": 1,
    "interruption-level": "time-sensitive|active"
  },
  "url": "…", "tag": "…", "markdown": "…",
  "requestId": "…", "requestKind": "…", "options": ["…"] ,
  "reply": {"update": "<updateId>"}
}
```

- `title/body/url/tag` = the Web Push message (§5.4). No `badge`.
- `mutable-content: 1` only for `REQUEST`. `interruption-level` = `time-sensitive` for
  `REQUEST`, else `active`.
- Kotlin only, critical: a phone the alert pages (spec/03 alert-prefs) gets `interruption-level` =
  `CRITICAL_STYLE` (`time-sensitive` today; `critical`, with `sound` `{"critical":1,"name":"default","volume":1}`,
  once Apple grants the entitlement). Other phones get the normal payload. Every critical alert's payload
  carries `"priority":"critical"` after `markdown`.
- `thread-id`: project slug, or `"dashboard"` when no project (`:346-349`).
- `markdown`: raw source cut to 2048 UTF-8 bytes (`MARKDOWN_MAX_BYTES`): request → `question`
  (not `detail`), update → `body`, message → `body`.
- Request only: `requestId`, `requestKind`, `options` (array, or `null` when empty/absent — key
  is present with `null`).
- `reply`: update → `{"update": "<updateId>"}`; message → `{"answers": "<messageId>"}`; request → absent.
- iOS reads `requestKind`, `options`, `url`, `requestId`, `reply`, categories
  `MESSAGE|UPDATE|REQUEST` (`iosApp/NotificationService/NotificationService.swift:12-18`,
  `iosApp/App/AppDelegate.swift:50-69`, `iosApp/Common/Notifications.swift:11-13`).

### 6.8 Outcome (`deliverToDevice`, `:245-283`)

- No token in that column → `'failed'`, nothing sent.
- `200` → `failures = 0`.
- Dead: `410`, or `400` with reason `BadDeviceToken` or `DeviceTokenNotForTopic` →
  clear **that column** only if it still holds that token (`clearOwnerDeviceToken`).
  The device row is never deleted.
- Anything else, or a thrown transport error → `failures += 1` (never acted on).
- No retry. Never throws.

### 6.9 Clearing alerts once they are dealt with (Kotlin addition)

Once the owner has seen or handled something on any device, its notifications come down on all
of them. Every alert carries a stable `tag` (§6.7) that is also its `apns-collapse-id`:
`request-<id>`, `pairing-<id>`, `update-<id>`, `message-<id>`. The apps that raise their own
notices (Android, desktop) use the same strings as the notification tag.

- **What counts as dealt with** — the bell recorder (§4) publishes `notifications.cleared {tags}`:
  - a bell row becomes seen (bell, "Mark all read", or a card opened: spec/03 §5.3): its
    `update-<id>` or `message-<id>`. A `request` row being seen clears nothing: the question's
    alert keeps its answer buttons until it settles.
  - `request.answered` (answered, cancelled, withdrawn, timed out): the request's bell row and
    its discussion's rows are marked seen, and `request-<id>` is cleared even if they already were.
  - `message.deleted` / `update.deleted`: rows for it and its thread marked seen, their tags cleared.
  - `pairing.updated` with a state other than `pending` (allowed, denied, expired): `pairing-<id>`.
- **Live** — the owner stream carries the event; each running app takes those tags down
  (iOS `removeDeliveredNotifications`, Android `cancel(tag, id)`, Linux notify-send interrupted).
- **iPhone in the background** — the APNs pusher gathers cleared tags for 2 s, then sends one
  background push to every phone with a token:
  headers `apns-push-type: background`, `apns-topic`, `apns-priority: 5`,
  `apns-expiration: now + 6 h`, no collapse id; payload
  `{"aps":{"content-available":1},"clear":["<tag>", …]}` (the newest 60 tags at most). The app
  wakes (`application(_:didReceiveRemoteNotification:)`), takes those down, and asks
  `/api/notifications/handled` about everything else still showing.
  Best effort: iOS rations and merges background pushes, and never delivers them to an app the
  owner swiped away.
- **Catch-up (the reliable path)** — whenever an app's live connection comes up, and each time
  the iPhone app returns to the front, it asks `/api/notifications/handled` about every alert it
  still shows and takes down what is done with.

---

## 7. Live Activity

Source: `src/domain/live-activity.ts`; Swift contract
`iosApp/Common/AgentsActivityAttributes.swift`, `iosApp/App/LiveActivityController.swift`.

### 7.1 Content state (contract)

`liveActivityState(ownerSummary(ctx))` (`src/domain/summary.ts`), key order. The first seven keys
never change; every later key is always present, `null` when there is nothing:

```json
{"online":3,"waiting":2,"inProgress":4,
 "topAgent":"Rover","topQuestion":"…","topRequestId":"r1","topKind":"confirm",
 "topAgentId":"a1","topProject":"Garden Game","topExpiresAt":1763470000,
 "topOptions":null,"topDefault":"true",
 "working":[{"agentId":"a1","agent":"Rover","task":"Fixing fee rounding at checkout","since":1763460000}]}
```

Since TS 9b67e21 (`src/domain/summary.ts` `liveActivityState`), six keys follow the first seven, always present:
- `topAgentId`: the top request's `agentId`.
- `topProject`: its project name, `cut` to 40; null when it has no project (or an empty name).
- `topExpiresAt`: `floor(expiresAtMs / 1000)`.
- `topOptions`: its options unchanged, only for `buttons`/`choice` with 1–4 options each ≤ 40 UTF-16 units; else null. Never cut.
- `topDefault`: the summary's `default` when its JS `.length` ≤ 40 (a string or a list), else null.
- `working`: the summary's `working`, or null when empty.

`cut(text, max)`: whole when `length ≤ max`; else the first `max - 1` units (one fewer if that would
end on a high surrogate), `trimEnd()`, then `…` — so the result is at most `max` units.

- `online` = live agents count; `waiting` = pending requests count; `inProgress` = cards with
  `status = 'in_progress'` in active projects; `top*` from the oldest pending request
  (`agentName`, `question`, `id`, `kind`), else `null`.
- `topAgentId` = that request's `agentId`. `topProject` = its project **name**, `cut(…, 40)`, null with
  no project. `topExpiresAt` = `floor(expiresAtMs / 1000)` (seconds).
- `topOptions` = its `options` unchanged and in order, only when kind is `buttons` or `choice`, there
  are 1–4, and every one is ≤ 40 UTF-16 units; else null. Never cut (the phone answers with the text).
- `topDefault` = `/api/summary`'s `default`, null when unset or longer than 40.
- `working` = summary `working` (§8), or `null` when empty.
- `cut(text, max)`: unchanged when `length ≤ max`, else `text.slice(0, max - 1).trimEnd() + "…"`
  (one fewer when that would split a surrogate pair), so at most `max` UTF-16 units.
- No ticking values: times are fixed instants, so the JSON-equal skip in §7.4 still holds.
- The phone's own `sentAnswer` key is never sent.
- Swift `ContentState` in `iosApp/Common/AgentsActivityAttributes.swift` decodes every key after the
  seventh as optional, so older servers keep working.
  Attributes type name `AgentsActivityAttributes` with no fields → `"attributes": {}`.

### 7.2 Headers (`:72-78`)

```
apns-push-type: liveactivity
apns-topic: <APNS_BUNDLE_ID>.push-type.liveactivity
apns-priority: 5 | 10
```

No `apns-expiration`, no `apns-collapse-id`.

### 7.3 Triggers and throttling (`:39-53,206-221`)

- Watched events: `request.created`, `request.answered`, `request.updated`, `agent.presence`,
  `agent.renamed`, `update.created`, `update.updated`, `update.deleted`, `message.created`,
  `message.updated`, `message.deleted`, `project.created`, `project.updated`.
- Any watched event → `schedule()`: if no timer is pending, start a 2 000 ms timer; later events
  inside that window do not extend it. Also `schedule()` every 60 000 ms (presence expires silently).
- Timer → `flush()`: one run at a time; a flush during a run sets `again` and the loop runs once more.
- `request.created` stores its `requestId` in `newRequestId` (last one wins); a run consumes it.

### 7.4 One run (`:101-178`)

1. Settings null → return (does not consume `newRequestId`).
2. Consume `newRequestId`. No device has either LA token → return.
3. Compute `state`, `timestamp = floor(now/1000)`.
4. **Update** each device with `liveActivityToken`: key `"<deviceId>:<token>"`; skip if the last
   state *sent* to that key is JSON-equal. Priority 10 (updates only go while something waits). Payload:
   `{"aps":{"timestamp":T,"event":"update","content-state":STATE}}`.
   On `sent` remember the state; on `failed`/`removed` forget it (retried next run).
   Since the Kotlin server (not the TS one): the activity is for questions only. In any run with
   `waiting = 0` (work in progress does not count), each device with `liveActivityToken` gets
   `{"aps":{"timestamp":T,"event":"end","content-state":ALL_CLEAR,"dismissal-date":T-1}}` (priority 10)
   instead of an update, so iOS takes it away at once; on `sent` its `live_activity_token` is cleared
   and its push-to-start cooldown is reset, so the next question can start a new one straight away.
   `ALL_CLEAR` is STATE with `waiting = 0` and the question fields and `working` null. The app ends
   its own activity by the same rule when it next fetches the summary (in front, or woken by a clear push).
5. **Push-to-start** only if `newRequestId` was set and `waiting > 0`. Devices with
   `pushToStartToken` and **no** `liveActivityToken`, and `now - lastStart(device) >= 300 000`
   (`PUSH_TO_START_COOLDOWN_MS`). The start time is recorded before sending, whatever the outcome.
   Priority 10. Payload:
   `{"aps":{"timestamp":T,"event":"start","content-state":STATE,"attributes-type":"AgentsActivityAttributes","attributes":{},"alert":{"title":…,"body":…}}}`
   where alert = `requestMessage(newRequestId)` title/body, falling back to
   `"<topAgent ?? 'An agent'> is waiting on you"` / `topQuestion ?? ""`.
6. Same `deliverToDevice` outcome rules as §6.8 (dead token clears that LA column).
   Environment = the device's `apns_environment ?? 'production'` (the alert token's environment).

- State (`lastSent`, start cooldowns) is in memory; a restart re-sends every activity its state.
- The TS server **never sends `event: "end"`**; the Kotlin server ends an idle activity (step 4).
  The app also clears its update token with `PUT … {updateToken: null}` when an activity ends.

---

## 8. Summary (`src/domain/summary.ts:40-78`)

Feeds `GET /api/summary` and the Live Activity.

```json
{
  "at": "<ISO with ms>",
  "onlineAgents": [{"agentId":"…","name":"…","host":null,"cwd":null}],
  "requests": [{"id":"…","agentId":"…","agentName":"…","projectSlug":null,"projectName":null,"question":"…","detail":null,"default":null,"kind":"confirm","options":null,"expiresAt":"<ISO>"}],
  "work": {"todo":0,"inProgress":0,"blocked":0},
  "projects": [{"slug":"…","name":"…"}],
  "working": [{"agentId":"…","agent":"…","task":"…","since":1763460000}]
}
```

- `onlineAgents`: `listLiveAgents` order (newest heartbeat first).
- `requests`: all pending, not expired, oldest first (`seq` asc), capped at 200
  (`PENDING_REQUEST_LIMIT`); `default = config.default ?? null`; `options` null when empty.
  Not filtered by project status.
- `work`: `countWorkByStatus` over `updates` + `messages` with non-null `status`, not deleted,
  in **active** projects only (`src/db/cards.ts:240-265`). Requests are not counted.
- `projects`: active only, pinned first, then `name.localeCompare`.
- Each `requests[]` entry has `agentId` right after `id` (since TS 9b67e21).
- `working`: up to 3 cards with `status = 'in_progress'` in active projects, `listWork` order
  (`COALESCE(status_at, created_at) DESC, id DESC`); `[]` when none. Each, all keys present:
  - `agentId`: an update's `agent_id`; a message's `agent:<id>` author; for an owner-written
    message, the author of its newest `agent:` reply (`reply_to = id`); else null.
  - `agent`: that agent's name, `"An agent"` when unknown, `cut(…, 40)`.
  - `task`: an update's `title`, else the first line of `body` (a message: first line of `body`),
    via `markdownToPlainText`, whitespace collapsed, `cut(…, 60)`.
  - `since`: `floor(COALESCE(status_at, created_at) / 1000)`.

---

## 9. Event fan-out mechanics that affect all of the above

- `bus.publish` is synchronous: stamps `seq` (+1), `at = new Date().toISOString()`, pushes to
  the ring, then calls each listener in subscription order, catching per-listener throws
  (`src/events/bus.ts:85-109`).
- The pushers wrap work in `void (async () => …)()`. Everything before the first `await` runs
  **inside** `publish`: DB lookups, message building, and for Web Push the `sendNotification`
  calls (payload encryption, VAPID JWT signing) and for APNs the JWT signing and stream open.
- The notification recorder is fully synchronous and publishes `notification.created` from
  inside the dispatch of `update.created` / `message.created` / `request.created`.

---

## Surprises

1. **Live SSE frames arrive out of seq order.** The recorder (subscribed at boot) publishes
   `notification.created` (seq N+1) while `update.created` (seq N) is still being dispatched, so every
   SSE connection (subscribed later) receives N+1 before N. The ring holds them in order, so a
   reconnect with `Last-Event-ID: N` replays N+1 a second time. A drop-in must reproduce or
   consciously fix this. `src/domain/notifications.ts:141`, `src/events/bus.ts:100-107`.
2. **Web Push can silently delete every subscription.** If `VAPID_SUBJECT` is unset and
   `PUBLIC_BASE_URL` is `http://…` (the default is `http://localhost:5173`), web-push throws
   "not an https: or mailto: URL" per send; that error has no `statusCode`, so it counts as a
   transient failure, and after 10 sends each subscription is deleted. `src/config.ts:171`,
   `src/domain/push.ts:357-362`.
3. Nothing is withdrawn when a request is answered or expires: no Web Push close, no APNs
   removal, no notification marked seen. The bell row for a settled request stays.
4. Bell body = raw markdown flattened (`firstLine`), push body = markdown rendered to plain text
   with newlines kept. Same event, different text. `notifications.ts:61` vs `push.ts:452`.
5. URL mismatches: Web Push request URL does not URL-encode the id and has no `?focus` when there is
   no project (`push.ts:409`); update/message URLs and bell paths do encode and keep `?focus` on `/`.
   ULIDs make the encoding difference invisible today.
6. Bell `request` rows are recorded even if the request is no longer pending; push/APNs skip it
   (`notifications.ts:201` vs `push.ts:400`).
7. `replyMessage` does not check `deletedAt`; the bell builder does (`push.ts:563` vs
   `notifications.ts:175`). Harmless at creation time.
8. A message addressed with `@name` only to other agents still pushes the owner as a "comment".
9. Phones filter by kind/level/priority (`alert-prefs`) and by project (`alert-projects`). Web Push
   has type/level/priority prefs but no project filter.
10. Live Activity uses the device's **alert** token's `apns_environment`. A phone that registered LA
    tokens but never an alert token goes to production, which breaks sandbox builds
    (`apns.ts:259`).
11. Live Activity priority is always 10 while anything is waiting, which spends Apple's
    high-priority budget fast (`live-activity.ts:126`).
12. Live Activity `timestamp` is whole seconds; two different states sent in the same second carry
    the same timestamp, and iOS may drop the second one.
13. Registering an LA/APNs token publishes nothing, so a fresh activity waits up to ~62 s for its
    first server state (60 s refresh + 2 s coalesce), unless some watched event happens first.
14. The Live Activity coalesce timer and `bus.waitFor` timers are not `unref`'d; APNs HTTP/2 sessions
    stay open up to 10 min. No shutdown hook stops any job.
15. `owner_devices.failures` and `push_subscriptions.last_sent_at` are counted but nothing reads
    `failures` for devices.
16. The presence sweeper's offline announcement cannot fire with the default constants. Offline is
    never announced by the server, only on `end_session`.
17. Text truncation is UTF-16 code units (`slice(0,140)`) and may split a surrogate pair; JS `\s`
    is Unicode-aware, whereas Java/Kotlin `\s` is ASCII-only unless `UNICODE_CHARACTER_CLASS` is set.
18. Media sweeps can overlap (async, not awaited by the interval).
19. `countWorkByStatus` ignores requests even though requests are cards with a status path
    (`cards.ts:135` publishes `request.updated`).

## Parity test notes

Black-box testable against both servers (with a controllable clock, or real waits):

- Presence: `register_session` → agent in `GET /api/snapshot/agents` (`{agents: LiveAgent[]}`,
  `src/http/stream/agents.ts:30-32`) and `/api/summary.onlineAgents`;
  no heartbeat for >5 min → gone, with **no** SSE event; `heartbeat` after going quiet → one
  `agent.presence online:true`; a storm of heartbeats → zero events; `end_session` → one
  `online:false` (none if a second session is live); second `end_session` → `ended:false`, no event.
- Sweep: session idle >600 s, wait ≤60 s → `heartbeat` returns `conflict` with the exact message.
- Request expiry: `request_input` with `timeout_s=5`; within 5 s + ≤30 s an SSE
  `request.answered {state:"timeout"}` appears and the banner/summary drops it; reading it
  earlier than the sweep also flips it (lazy path).
- Bell: post an update / agent message / request → one `notification.created` each, one row each,
  titles exact; owner's own message → none; `POST /api/notifications/seen` → `notifications.seen`
  only when something changed.
- SSE ordering quirk (#1): post an update, record frame ids — expect N+1 before N.
- Media sweep: an unattached upload older than 1 h disappears within 15 min (needs clock
  control or a seeded `created_at`).

Not testable without Apple / push services; how to fake them:

- **Web Push**: subscribe with an endpoint pointing at a local HTTPS stub (endpoint must be
  `https:`; trust a test CA). Generate a real P-256 keypair + 16-byte auth on the test side, then
  decrypt the `aes128gcm` body to compare JSON byte-for-byte. Assert `TTL: 900`, `Urgency: normal`,
  `Authorization: vapid t=…, k=…` (verify JWT `aud/sub`, `exp ≈ now+12h`). Stub returns 410 →
  subscription gone from `GET /api/push`; returns 500 ×10 → gone.
- **APNs**: the hosts are hard-coded. A parity harness needs an override seam in the Kotlin server
  (e.g. env or test-only base URL) and a local HTTP/2 (h2, TLS) stub; the TS server has no such seam,
  so compare it via its unit-test transport injection (`createApnsTransport` `connect` option, used in
  `src/domain/apns.test.ts`) or by redirecting `api.push.apple.com` / `api.sandbox.push.apple.com`
  through `/etc/hosts` + a trusted test CA in a container. Assert path `/3/device/<hex>`, headers in §6.6 / §7.2,
  payload JSON in §6.7 / §7.4, JWT header/claims (verify with the test `.p8`'s public key),
  re-sign after 50 min or after a `403 {"reason":"ExpiredProviderToken"}`, token cleared after
  `410` / `400 BadDeviceToken`.
- **Live Activity timing**: with the stub, assert one push per 2 s burst, no push when state is
  unchanged, priority 5 vs 10, push-to-start only on a new request and not again within 5 min.
- **Service worker**: browser-only; test with Playwright if at all. The server-side contract is the
  payload shape (§5.4) and the `POST /api/requests/<id>/answer {value}` route.
