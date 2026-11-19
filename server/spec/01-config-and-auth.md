# 01 — Configuration and authentication

Source of truth: the `agent-dashboard` working tree (SvelteKit 2.70.3, adapter-node 5.5.7, argon2 0.45.1, zod 4.4.3, cookie 0.6.0). Every line cited is `path:line` in that tree.

Five kinds of caller exist: the owner (cookie, or a device key), an agent (agent token), an upload (upload token), a share-link visitor (share token), and nobody. This file says how each one proves who it is, and what config the server boots with.

---

## 1. Environment variables

### 1.1 App variables (`src/config.ts`)

All are read by `loadConfig(env)` (`src/config.ts:206`). Before validation, **an empty string counts as unset** (`src/config.ts:207-212`), so `FOO=` means "use the default". Only the names in `CONFIG_VARS` are looked at (`src/config.ts:12-29`).

| Name | Type | Default | Rule | Source |
|---|---|---|---|---|
| `DATA_DIR` | string | `data` | length ≥ 1 | `src/config.ts:46` |
| `ADMIN_PASSWORD_HASH` | string | **required** | must start with `$argon2id$` | `src/config.ts:49-51` |
| `SESSION_SECRET` | string | **required** | length ≥ 32 (JS string length) | `src/config.ts:34,38-41,54` |
| `TOKEN_SECRET` | string | **required** | length ≥ 32 | `src/config.ts:57` |
| `PUBLIC_BASE_URL` | string | `http://localhost:5173` | must match `/^https?:\/\/[^/]+/` (not end-anchored, case-sensitive); then every trailing `/` is stripped | `src/config.ts:60-66` |
| `MAX_IMAGE_BYTES` | int | `10485760` | `z.coerce.number().int().positive()` | `src/config.ts:36,69` |
| `MAX_VIDEO_BYTES` | int | `209715200` | same | `src/config.ts:72` |
| `MAX_FILE_BYTES` | int | `52428800` | same | `src/config.ts:81` |
| `HOLD_S` | int | `55` | coerced int, 1..59 | `src/config.ts:88` |
| `VAPID_PUBLIC_KEY` | string | unset | length ≥ 1 | `src/config.ts:100` |
| `VAPID_PRIVATE_KEY` | string | unset | length ≥ 1 | `src/config.ts:101` |
| `VAPID_SUBJECT` | string | unset (falls back to `PUBLIC_BASE_URL`, `src/config.ts:171`) | must match `/^(mailto:\|https:\/\/)/` | `src/config.ts:108-113` |
| `APNS_KEY_ID` | string | unset | length ≥ 1 | `src/config.ts:119` |
| `APNS_TEAM_ID` | string | unset | length ≥ 1 | `src/config.ts:120` |
| `APNS_KEY_PATH` | string | unset | length ≥ 1 | `src/config.ts:122` |
| `APNS_BUNDLE_ID` | string | unset | length ≥ 1 | `src/config.ts:124` |

Cross-field rules (`src/config.ts:126-153`):
- VAPID: both keys or neither. Message `VAPID_PUBLIC_KEY and VAPID_PRIVATE_KEY must be set together, or neither`.
- APNs: all four or none. Message `APNS_KEY_ID, APNS_TEAM_ID, APNS_KEY_PATH and APNS_BUNDLE_ID must be set together, or none`.
- These only run when every per-field rule passed (zod 4 skips `superRefine` after a field error; observed).

Number coercion is JS `Number(string)`: `" 5 "`→5, `"0x10"`→16, `"1e3"`→1000 all pass; `"1.5"` fails `int`; `"abc"` → NaN fails.

Derived helpers:
- `pushConfig` — on only when both VAPID keys set (`src/config.ts:166-173`).
- `apnsConfig` — on only when all four APNS_* set (`src/config.ts:179-194`).
- `mcpConfig().holdMs = HOLD_S * 1000` (`src/mcp/env.ts:30`).

**Error text.** One `Error`, message `Invalid configuration:\n  ` + issues joined by `\n  `. Each issue is `NAME: message`, or just `message` when the issue has no path (cross-field) (`src/config.ts:217-221`). Observed examples:

```
Invalid configuration:
  ADMIN_PASSWORD_HASH: ADMIN_PASSWORD_HASH is required
  SESSION_SECRET: SESSION_SECRET is required
  TOKEN_SECRET: TOKEN_SECRET is required
```
Other messages seen: `ADMIN_PASSWORD_HASH must be an argon2id hash`, `SESSION_SECRET must be at least 32 characters`, `PUBLIC_BASE_URL must be an absolute http(s) URL`, `Invalid input: expected number, received NaN`, `Too small: expected number to be >0`, `Too big: expected number to be <=59`, `Too small: expected number to be >=1`, `Invalid input: expected int, received number`, `VAPID_SUBJECT must be a mailto: address or an https:// URL`. The zod-default ones are zod's wording; a rewrite only needs the same variable names and a non-zero exit.

### 1.2 Request-path config readers (fail closed, never throw)

These wrap `loadConfig(process.env)` per call and return `null` on failure:

| Helper | Returns | Source |
|---|---|---|
| `authConfig()` | `{sessionSecret, adminPasswordHash}` | `src/http/auth/env.ts:20-30` |
| `mcpConfig()` | `{tokenSecret, holdMs}` | `src/mcp/env.ts:27-34` |
| `mediaConfig()` | media settings | `src/http/media/env.ts:13-19` |
| `environmentDevices` | device or null | `src/http/stream/owner.ts:32-40` |

Config is **re-parsed on every request** (no caching). Since boot already crashes on bad config (§2), the `null` paths are near-unreachable in production.

### 1.3 Boot-only app checks (not in `CONFIG_VARS`)

| Name | Read by | Effect | Source |
|---|---|---|---|
| `TRUST_SOCKET_ADDRESS` | `assertClientAddressTrustworthy` | exactly `"true"` skips the proxy check | `src/config.ts:242` |
| `ADDRESS_HEADER` | same | must be non-empty when raw `PUBLIC_BASE_URL` starts with `https://` | `src/config.ts:239-254` |
| `BODY_SIZE_LIMIT` | `assertBodyLimitAllowsUploads` | parsed limit must be ≥ max(MAX_IMAGE, MAX_VIDEO, MAX_FILE) | `src/config.ts:290-307` |

`assertClientAddressTrustworthy` reads the **raw** `PUBLIC_BASE_URL` (not validated/trimmed). Error:
```
Invalid configuration:
  PUBLIC_BASE_URL is https, so a reverse proxy is terminating TLS, but
  ADDRESS_HEADER is unset. Every request would report the proxy as its
  client address, so the login rate limiter would treat all visitors as
  one client and any stranger could lock the owner out.
  Set ADDRESS_HEADER=X-Forwarded-For and XFF_DEPTH=1 (one proxy), or set
  TRUST_SOCKET_ADDRESS=true if this process really does face the internet.
```

`BODY_SIZE_LIMIT` parse (`src/config.ts:272-277`): `/^(\d+)([KMG])?$/` on the trimmed value, K/M/G = 1024/1024²/1024³. Unset → `"512K"` (`src/config.ts:264,291`). Unparseable (incl. `""`, `Infinity`, lowercase suffix) → check skipped. Error (observed with defaults, no BODY_SIZE_LIMIT):
```
Invalid configuration:
  BODY_SIZE_LIMIT is 512K (524288 bytes) but this deployment accepts uploads
  up to 209715200 bytes. adapter-node enforces BODY_SIZE_LIMIT before any route
  runs, so larger uploads would be rejected with a 413 the app never sees and
  cannot explain, after the agent has already spent its upload token.
  Set BODY_SIZE_LIMIT=209715200 (or lower MAX_IMAGE_BYTES / MAX_VIDEO_BYTES / MAX_FILE_BYTES).
```

### 1.4 adapter-node variables (`build/server/chunks/handler-*.js`, `build/index.js`)

| Name | Default | Behaviour |
|---|---|---|
| `PORT` | `3000` (not 8010; Dockerfile sets 8010) | listen port (`build/index.js:27`) |
| `HOST` | `0.0.0.0` | bind address (`build/index.js:26`) |
| `SOCKET_PATH` | unset | listen on a unix socket instead |
| `ORIGIN` | unset | if set, must parse as an http(s) URL or boot throws `Invalid ORIGIN: ...`; becomes the request URL origin for **every** request. If unset, origin = `PROTOCOL_HEADER` value or **`https`** + `HOST_HEADER`/`Host` |
| `ADDRESS_HEADER` | `""` | lower-cased; when set, `getClientAddress()` reads it. **Throws (→ 500) if the header is absent** on a request that calls `getClientAddress()` |
| `XFF_DEPTH` | `1` | for `x-forwarded-for`: take `addresses[len - depth].trim()`; throws if depth < 1 or > count |
| `PROTOCOL_HEADER`, `HOST_HEADER`, `PORT_HEADER` | `""` | used only when `ORIGIN` unset |
| `BODY_SIZE_LIMIT` | `512K` | suffix K/M/G (case-insensitive), `Infinity` allowed; NaN → boot throws `Invalid BODY_SIZE_LIMIT: '…'. Please provide a numeric value.`. Over limit → 413 before any app code. **Request with no `Content-Type` gets a null body** |
| `SHUTDOWN_TIMEOUT` | `30` | seconds |
| `IDLE_TIMEOUT`, `KEEP_ALIVE_TIMEOUT`, `HEADERS_TIMEOUT`, `LISTEN_PID`, `LISTEN_FDS` | — | socket tuning / systemd activation |

adapter-node also sets `x-accel-buffering: no` on any response whose `content-type` is exactly `text/event-stream`.

A deployment behind a TLS proxy typically sets `ORIGIN` (the public https URL), `ADDRESS_HEADER=X-Forwarded-For`, `XFF_DEPTH=1`, `HOST=127.0.0.1` and a large `BODY_SIZE_LIMIT`, plus the secrets, an https `PUBLIC_BASE_URL`, the VAPID pair, `MAX_FILE_BYTES` and the four `APNS_*` values when push is wanted. It runs as `node build` with that environment.

### 1.5 Variables that are not the server's

`AGENT_DASHBOARD_URL`, `AGENT_DASHBOARD_TOKEN`, `AGENT_DASHBOARD_PROJECTS`, `AGENT_DASHBOARD_MONITOR`, `CLAUDE_PLUGIN_DATA`, `CLAUDE_SESSION_ID` are read by the channel bridge / monitor client processes (`src/channel/bridge.ts`, `src/channel/monitor.ts`, `src/channel/claim.ts`), not the server.

---

## 2. Startup (`src/hooks.server.ts`)

Order, at module import (adapter-node imports hooks during `server.init`, before listening):

1. adapter-node: parse `ORIGIN`, `XFF_DEPTH`, `ADDRESS_HEADER`, `BODY_SIZE_LIMIT` (throws on bad ORIGIN / NaN limit).
2. `assertClientAddressTrustworthy(process.env)` — throws, boot fails (`src/hooks.server.ts:31`).
3. `assertBodyLimitAllowsUploads(process.env, loadConfig(process.env))` — `loadConfig` throws on any bad var, so **invalid config = boot failure** (`src/hooks.server.ts:39`).
4. Start background jobs (skipped while building): `startPresenceSweeper`, `startRequestSweeper`, `startRequestPusher`, `startApnsPusher`, `startLiveActivityPusher`, `startNotificationRecorder`, `startMediaSweeper`, `startDerivativeWorker` (`src/hooks.server.ts:47-94`). Details in file 07.
5. Install `handle = sequence(compressHandle, authHandle)` (`src/hooks.server.ts:110-112`).

Database: opened **lazily** on the first `getDatabase()` (via `context()`, `src/domain/context.ts:34`) — file `<DATA_DIR>/agent-dashboard.db`, parent dir created, pragmas `journal_mode=WAL`, `synchronous=NORMAL`, `foreign_keys=ON`, `busy_timeout=5000`, then all pending migrations (`src/db/connection.ts:47-84`). Schema/migrations: file 02.

Docker health check: `GET http://127.0.0.1:$PORT/login` must answer 2xx (`Dockerfile:90-91`).

---

## 3. Request pipeline

For every request that reaches SvelteKit (static client assets are served by `sirv` before this, with `.br`/`.gz` precompressed files and `cache-control: public,max-age=31536000,immutable` under `/_app/immutable/`):

| Step | What | Where |
|---|---|---|
| 1 | Body size limit (413) | adapter-node |
| 2 | CSRF origin check | SvelteKit `respond.js`, before hooks |
| 3 | `compressHandle` wraps everything after it | `src/http/compress.ts:195` |
| 4 | `authHandle` (the guard) | `src/http/auth/handle.ts:31-57` |
| 5 | Route; many routes re-check auth themselves | see §9 |

No global security headers are added (no CSP, HSTS, X-Frame-Options, Referrer-Policy). Only specific routes set them (media, apps, share page — files 05/06).

### 3.1 CSRF (SvelteKit built-in, `csrf_check_origin: true`, `csrf_trusted_origins: []` in the build)

A request is refused when **all** hold:
- method is POST, PUT, PATCH or DELETE;
- `Content-Type` (before `;`, trimmed, lower-cased) is `application/x-www-form-urlencoded`, `multipart/form-data`, `text/plain` or `application/x-sveltekit-formdata`;
- `Origin` header is missing or ≠ the request URL origin (= `ORIGIN` env when set).

Response: **403**. If `Accept` is exactly `application/json`: body `{"message":"Cross-site POST form submissions are forbidden"}` (`content-type: application/json`). Otherwise plain body `Cross-site POST form submissions are forbidden` with no content-type header. The method name in the message is the request's method. This applies to **every path**, `/api/upload/*` and `/mcp` included. JSON bodies are never affected.

### 3.2 Compression (`src/http/compress.ts`)

Runs on the response after the guard, so redirects and 401s are compressed too.

Compress only when all hold (`shouldCompress`, `:112-124`):
- no `content-encoding` already;
- status not in `101, 204, 205, 304` and body present;
- if `Content-Length` is declared: finite and `1400 ≤ len ≤ 8000000` (`MIN_BYTES :47`, `MAX_BYTES :56`);
- content type (before `;`, lower-cased) is one of `text/html`, `text/plain`, `text/css`, `text/javascript`, `application/javascript`, `application/json`, `application/manifest+json`, `image/svg+xml` (`:64-73`). `text/event-stream` is not, so SSE is untouched.

Encoding choice (`chosenEncoding`, `:79-95`): split `Accept-Encoding` on `,`; each entry `name;q=x` (default q=1, `Number(q)`, NaN counts as 0). `br` if its q>0, else `gzip` if q>0, else none. Order in the header and `*` are ignored.

Then (`compressResponse`, `:132-167`): buffer the whole body; if actual length < 1400 or > 8000000 → send uncompressed (same headers). Brotli quality 4 with size hint, or gzip level 6. If the result is not smaller → send uncompressed. Else set `content-encoding`, `content-length`, and add `accept-encoding` to `Vary` (appended `, accept-encoding` if a different Vary exists; not duplicated).

### 3.3 The guard (`src/http/auth/handle.ts`, `src/http/auth/guard.ts`)

```
if route matched (event.route.id !== null) AND requiresSession(pathname):
    bearer = bearerToken(headers)
    if bearer != null:  if !deviceKeyValid(bearer) → refuse   # cookie ignored
    else:               if !validSessionCookie       → refuse
resolve
```
(`src/http/auth/handle.ts:43-55`). Unmatched paths (404s, assets) are never guarded.

`requiresSession(path)` (`src/http/auth/guard.ts:52-55`): path with trailing `/`s removed (unless it is `/`) is **not** equal to or under (`p == prefix || p.startsWith(prefix + "/")`) any of:
- token-authed: `/mcp`, `/api/upload`, `/s`, `/api/agent` (`:33`)
- public: `/login`, `/logout`, `/api/devices/sign-in` (`:15-16,36,39`)

So `/mcp-docs` is guarded; `/mcp/x` is not.

`bearerToken` (`src/http/auth/bearer.ts:13-17`): `Authorization` trimmed, must match `/^bearer\s+(\S+)$/i`; else null (so `Basic …` or `Bearer a b` → treated as "no bearer", falls to cookie).

`refuse` (`src/http/auth/handle.ts:18-29`):
- path under `/api` (and guarded) → **401**, body `{"error":"unauthenticated"}`, `content-type: application/json` (no charset).
- anything else → **303**, no body, `location: loginRedirect(url)`.

`loginRedirect(url)` (`src/http/auth/guard.ts:66-70`): `target = pathname + search`; if `safeRedirectTarget(target) == "/"` → `/login`, else `/login?redirectTo=` + `encodeURIComponent(target)`.

`safeRedirectTarget(raw)` (`src/http/auth/guard.ts:88-108`) returns `/` when: empty/null; not starting with `/`; any char < 0x20 or 0x7F; starts with `//` or `/\`; equals `/`; its URL pathname (trailing `/` stripped) is under a public prefix; or it does not require a session. Otherwise returns `raw` unchanged (query string kept).

Page double-check: `src/http/routes/+layout.server.ts:17-24` redirects 303 to `loginRedirect(url)` when there is **no valid cookie** and the path requires a session. It never looks at a bearer (see Surprises).

---

## 4. Owner password

- Library: `argon2` (node-argon2) 0.45.1. Hash: `argon2.hash(pw, {type: argon2id})` with library defaults: `m=65536` KiB, `t=3`, `p=4`, 32-byte hash, 16-byte random salt, version 0x13 (`src/http/auth/password.ts:22-24`; `node_modules/argon2/argon2.cjs:29-35`).
- String format (PHC, unpadded standard base64, note param order `m,p,t`):
  `$argon2id$v=19$m=65536,p=4,t=3$<salt b64>$<hash b64>`
  Real example (password `correct horse battery staple`): `$argon2id$v=19$m=65536,p=4,t=3$sS0xJIH/XROdP+3fZ1XZQg$6Dq1XKkl0J3S4bKnII+SuCGMGUJSW/ulGcwUNnMDlfc`
- Verify: `argon2.verify(hash, pw)` reads params from the string (any order; `argon2i`/`argon2d` ids also verify, though config demands the `$argon2id$` prefix). Empty password or hash → false; any throw → false (`src/http/auth/password.ts:34-41`). Password bytes = UTF-8 of the JS string.
- Never written by the app. No change-password endpoint.

## 5. Login, rate limit, session cookie, logout

### 5.1 Shared password check (`createPasswordCheck`, `src/http/auth/login.ts:87-125`)

One module-level instance, `ownerPasswordCheck` (`:153`), is shared by the web form and `POST /api/devices/sign-in`, so they share one limiter.

In order:
1. `authConfig()` null → `503`, `UNCONFIGURED` (`:98`).
2. password `=== ''` → `400`, `Enter your password.` — **no attempt spent, checked before the limiter** (`:102`).
3. `limiter.check(clientAddress)` refused → `429`, `Too many attempts. Try again later.`, `retryAfterS = ceil(retryAfterMs/1000)` (`:104-114`). Applies to a correct password too.
4. Non-string password becomes `''`; verify fails → `limiter.record(key)` and `400`, `That password is not right.` (`:116-120`). So a **missing** (`null`/`undefined`) password costs an attempt; only `''` is free.
5. Success → `limiter.reset(key)`, ok (`:122-123`).

`UNCONFIGURED` = `This deployment has no valid ADMIN_PASSWORD_HASH and SESSION_SECRET, so nobody can log in. See .env.example.` (`:43-45`).

### 5.2 Login limiter (`src/http/auth/rate-limit.ts`)

| Item | Value |
|---|---|
| Key | `event.getClientAddress()` string (socket peer, or `ADDRESS_HEADER` per §1.4) |
| Limit | 5 recorded failures (`LOGIN_MAX_ATTEMPTS`, `src/http/auth/login.ts:33`) |
| Window | 15 min = 900000 ms, sliding (`LOGIN_WINDOW_MS`, `:34`) |
| Algorithm | keep timestamps `> now - window`; refuse when count ≥ 5; `retryAfterMs = max(oldest + window - now, 0)` (`rate-limit.ts:53-66`) |
| What counts | only wrong guesses (`record`); refusals are not recorded |
| Reset | successful login clears the key |
| Map cap | 10000 keys, least-recently-recorded evicted (`:41,68-78`) |
| Storage | in memory; restart forgets |

Check-then-verify is not atomic: concurrent wrong guesses can exceed 5 (each passes `check` before any `record`).

### 5.3 Web form login (`src/http/routes/login/+page.server.ts`)

- `GET /login`: if a valid cookie is present → `303` to `safeRedirectTarget(?redirectTo)`. Else render page (`:15-23`).
- `POST /login` (default action, form fields `password`, `redirectTo`; CSRF rule §3.1 applies): `redirectTo` = form field if a string, else `?redirectTo` (`:26-39`).
  - Success: `Set-Cookie` (§5.4) and `303 Location: <safeRedirectTarget(redirectTo)>`.
  - Failure: SvelteKit `fail(status, {error})`. Plain HTML post → login page HTML with HTTP status 400/429/503. **No `Retry-After` header on 429** (the route drops `retryAfterS`).
  - If `Accept` negotiates to `application/json` (SvelteKit `use:enhance` style): HTTP **200** with body
    - failure `{"type":"failure","status":400,"data":"[{\"error\":1},\"That password is not right.\"]"}` (data is devalue-encoded),
    - success `{"type":"redirect","status":303,"location":"/"}` plus the `Set-Cookie`.

### 5.4 Session cookie (`src/http/auth/session.ts`)

| Item | Value |
|---|---|
| Name | `ad_session` (`:17`) |
| Lifetime | 30 days = 2592000 s (`:20`), fixed at login. **No sliding renewal, no rotation, no server-side store** |
| Value | `<payload>.<sig>` |
| payload | `base64url(UTF8(JSON.stringify({"issuedAt":<ms>,"expiresAt":<ms>})))`, no padding, keys in that order, integer epoch ms, `expiresAt = issuedAt + 2592000000` (`:71-78`) |
| sig | `base64url(HMAC-SHA256(key = UTF-8 bytes of SESSION_SECRET, msg = ASCII bytes of the payload string))`, no padding — signs the base64 text, not the JSON (`:66-68`) |
| Set-Cookie | `ad_session=<value>; Max-Age=2592000; Path=/; HttpOnly; Secure; SameSite=Lax` (exact order from `cookie@0.6.0`; no `Expires`; `Secure` always, even on http://localhost) (`:45-47`) |

Test vector: secret `"s"×32`, issuedAt `1700000000000` →
`eyJpc3N1ZWRBdCI6MTcwMDAwMDAwMDAwMCwiZXhwaXJlc0F0IjoxNzAyNTkyMDAwMDAwfQ.VaTNC-sXCNyk_rloaqTvm5YrV47-gvu8B6NCkd4aGj4` (verified against `readSession`).

Verify (`readSession`, `:87-119`), all failures → "no session":
1. value empty, or secret shorter than 32 → fail.
2. split at the **first** `.`; payload must be non-empty and the signature non-empty.
3. recompute sig; compare as UTF-8 bytes, length first, then constant time.
4. base64url-decode payload, `JSON.parse`; must be an object with finite numbers `issuedAt` and `expiresAt`.
5. `expiresAt <= now` → fail. `issuedAt` is not checked.

Rotating `SESSION_SECRET` logs out everyone.

### 5.5 Logout (`src/http/routes/logout/+page.server.ts`, `src/http/auth/logout.ts`)

- `GET /logout`: no valid cookie → `303 /login`; else render a confirm page (`:12-18`).
- `POST /logout` (form action; CSRF §3.1): always clears and `303 Location: /login`, session or not (`:20-25`). Set-Cookie: `ad_session=; Max-Age=0; Path=/; HttpOnly; Secure; SameSite=Lax`.

---

## 6. Owner device keys (native app bearer)

### 6.1 Format and storage

| Item | Value | Source |
|---|---|---|
| Key | `base64url(32 random bytes)`, no padding, **43 chars, no prefix** — same shape as an agent token | `src/domain/devices.ts:63`, `src/domain/agents.ts:45,48` |
| Shape check | `/^[A-Za-z0-9_-]{43}$/` after trim | `src/domain/agents.ts:58-62`, `src/domain/devices.ts:82-83` |
| Stored | `owner_devices.token_hash` = lowercase hex `HMAC-SHA256(key = UTF-8 TOKEN_SECRET, msg = UTF-8 key)` (64 chars). No domain-separation prefix | `src/domain/agents.ts:71-74` |
| Lookup | `SELECT … WHERE token_hash = ?`, then constant-time compare of the hex strings | `src/db/owner-devices.ts:100-105`, `src/domain/devices.ts:85-87` |
| Last seen | on successful lookup: `UPDATE owner_devices SET last_seen_at = now WHERE id = ? AND (last_seen_at IS NULL OR last_seen_at <= now - 60000)` — at most one write per 60 s | `src/domain/devices.ts:40,89-93`, `src/db/owner-devices.ts:125-134` |
| Revoke | delete the row; next request with that key fails | `src/db/owner-devices.ts:116-118` |
| Name | trimmed, 1..100 chars | `src/domain/devices.ts:37,54-56` |

Rotating `TOKEN_SECRET` kills every device key, agent token, upload token and share link.

### 6.2 Issuing

**`POST /api/devices/sign-in`** (public; no cookie needed) — `signInDeviceHandler`, `src/http/owner/devices.ts:111-151`. Body JSON `{password, name}`. All responses: `content-type: application/json; charset=utf-8`, `cache-control: no-store` (`:92-100`).

| Case | Status | Body |
|---|---|---|
| Body not JSON | 400 | `{"error":"invalid_argument","message":"body must be JSON"}` |
| Body not an object (null/array/scalar) | 400 | `{"error":"invalid_argument","message":"body must be a JSON object"}` |
| `name` not a string | 400 | `{"error":"invalid_argument","message":"name must be a string"}` |
| `name` blank after trim | 400 | `{"error":"invalid_argument","message":"name is required"}` |
| `name` > 100 chars | 400 | `{"error":"invalid_argument","message":"name must be at most 100 characters"}` |
| `password` is `""` | 400 | `{"error":"wrong_password","message":"Enter your password."}` |
| wrong password | 400 | `{"error":"wrong_password","message":"That password is not right."}` |
| limited | 429 + `retry-after: <ceil s>` | `{"error":"rate_limited","message":"Too many attempts. Try again later."}` |
| auth config bad | 503 | `{"error":"misconfigured","message":"This deployment has no valid ADMIN_PASSWORD_HASH and SESSION_SECRET, so nobody can log in. See .env.example."}` |
| TOKEN_SECRET bad | 503 | `{"error":"misconfigured"}` |
| ok | **201** | `{"device":{"id":"<ULID>","name":"<trimmed>","createdAt":"<ISO>"},"token":"<43 chars>"}` |

Name is validated **before** the password, so a bad name costs no guess (`:119-123`). The rate-limit key is `getClientAddress()` (`src/http/routes/api/devices/sign-in/+server.ts:10-11`). ISO = `new Date(ms).toISOString()` (always `.sssZ`). Each sign-in makes a new device row.

**`POST /api/devices`** (owner-authed: cookie or device key) — `src/http/owner/devices.ts:70-88`. Body `{name}`. Name not a string → 400 `invalid_argument` `name must be a string`; bad secret → 503 `{"error":"misconfigured"}`; ok → 201, same body shape as sign-in.

`GET /api/devices`, `DELETE /api/devices/[id]` (→ 200 `{"removed":true|false}`, idempotent), and `PUT /api/devices/current/*` are in file 04/06. The `current` routes need a device bearer; a cookie caller gets 400 `{"error":"invalid_argument","message":"this needs a device key: send Authorization: Bearer <device token>"}` (`src/http/owner/devices.ts:173-182`).

### 6.3 Checking a device key on owner routes

`ownerAuthenticated(event)` (`src/http/stream/owner.ts:60-72`), used by every owner API handler (`ownerAction`, `src/http/owner/actions.ts:145-163`), SSE, snapshots, media, apps:
- `bearerToken(headers)` non-null → valid **only if** it resolves to a device. A valid cookie next to a bad bearer is still refused.
- else the session cookie.
- Refusal: 401 `{"error":"unauthenticated"}`, `content-type: application/json` (`src/http/stream/owner.ts:78-83`).

On a guarded path the key is checked twice (hook, then handler); the 60 s rule keeps it to one write.

---

## 7. Agent tokens

### 7.1 Format, storage, minting

| Item | Value | Source |
|---|---|---|
| Token | `base64url(32 random bytes)`, 43 chars, no prefix | `src/domain/agents.ts:107` |
| Stored | `agents.token_hash` = hex `HMAC-SHA256(UTF-8 TOKEN_SECRET, UTF-8 token)`; empty secret throws | `src/domain/agents.ts:71-74` |
| Mint | **CLI only** (`mint-token <name>`); no HTTP route mints agent tokens. Name trimmed 1..100 | `src/cli/index.ts:79-98`, `src/domain/agents.ts:105-115` |
| Rename | CLI `rename-token`, or owner `PATCH /api/agents/[id]` `{name}` (token unchanged) | `src/http/owner/actions.ts:127-138` |
| Revoke | CLI `revoke-token` only: `UPDATE agents SET revoked_at = now WHERE id = ? AND revoked_at IS NULL`; row kept | `src/db/agents.ts:115-121` |
| Last seen | every authenticated request: `last_seen_at = max(coalesce(last_seen_at,0), now)` | `src/db/agents.ts:129-138`, `src/mcp/auth.ts:160` |

Test vector: secret `"t"×32`, token `AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA` (32 zero bytes) → `7fec7d5f16cdafa6abc866d824093848ee299a7ea40e0fcb79d83bed1462fdb0`. Same function and vector for device keys.

### 7.2 Request authentication (`authenticateMcpRequest`, `src/mcp/auth.ts:119-163`)

Used by `POST /mcp`, `GET /api/agent/stream`, `GET /api/agent/media/[id]`. Before it, a bad `TOKEN_SECRET` → 503 (per route below).

1. `readBearerToken` (`:52-82`): header trimmed; empty → `missing_token` `missing Authorization header: send Authorization: Bearer <agent token>`. Scheme = text before first whitespace, compared case-insensitively to `bearer`; else `unsupported_scheme` `unsupported Authorization scheme "<scheme>": this server accepts Bearer tokens only`. Token = rest, trimmed; empty → `missing_token` `empty bearer token: send Authorization: Bearer <agent token>`.
2. Shape `/^[A-Za-z0-9_-]{43}$/` else `malformed_token` `malformed bearer token: not a token this server issued` (`:130-137`).
3. Rate limit, keyed on the token's HMAC hex — **before** identity, so unknown and revoked tokens are limited too (`:139-149`).
4. Lookup by hash + constant-time compare → `unknown_token` `unknown bearer token: no agent holds it`; `revoked_at` set → `revoked_token` `revoked bearer token: ask the dashboard owner to mint a new one` (`src/domain/agents.ts:143-184`).
5. Touch `last_seen_at`.

### 7.3 Per-token rate limit (`src/mcp/rate-limit.ts`)

| Item | Value |
|---|---|
| Limit | 120 requests (`:19`) |
| Window | 60000 ms sliding (`:22`) |
| Key | HMAC hex of the token |
| Algorithm | `take`: sweep keys whose newest hit ≤ cutoff (from the oldest end); keep hits `> now-60000`; if ≥120 → refuse, **not recorded**, `retryAfterMs = max(oldest + 60000 - now, 0)`; else record (`:70-103`) |
| Map cap | 10000 keys, LRU (`:30`) |
| Retry-After | `max(1, ceil(ms/1000))` seconds (`:116-118`) |
| Instances | **one per route**: `/mcp`, `/api/agent/stream`, `/api/agent/media` each have their own (`src/mcp/server.ts:125`, `src/http/stream/agent.ts:565`, `src/http/media/agent.ts:58`). So a token gets 120/min on each |

### 7.4 Refusal responses per route

**`POST /mcp`** (`src/mcp/responses.ts`), `content-type: application/json`:
```json
{"jsonrpc":"2.0","id":null,"error":{"code":-32000,"message":"<message>","data":{"error":"<code>"}}}
```
- 401 + `www-authenticate`: `Bearer` when code is `missing_token`; otherwise `Bearer error="invalid_token", error_description="<message>"` where the message has non-printable-ASCII → space and `"`/`\` removed (`:47-62`). The Kotlin server adds `resource_metadata="<base>/.well-known/oauth-protected-resource/mcp", scope="agent"` to both, for OAuth discovery, and also takes OAuth access tokens (spec/11-oauth.md).
- 429 + `retry-after`, message `rate limit exceeded for this token: retry after <n>s`, code `rate_limited` (`:66-69`).
- 503 + `retry-after: 30`, message `this dashboard is not configured for agents: TOKEN_SECRET is missing or too short`, code `server_not_configured` (`:84-91`).
- `GET /mcp` → SvelteKit 405 (no GET handler).

**`GET /api/agent/stream`** (`src/http/stream/agent.ts:544-549,571-587`): body `{"error":"<code>","message":"<message>"}`, `content-type: application/json`, **no `WWW-Authenticate`, no `Retry-After`** (even on 429). Bad secret → 503 `{"error":"misconfigured","message":"this deployment has no TOKEN_SECRET, so no token can be verified"}`.

**`GET /api/agent/media/[id]`** (`src/http/media/agent.ts:61-77`, `src/http/media/serve.ts:97`): every refusal — missing/bad/revoked token, **rate limit**, bad secret, media not attached — is the media 404 (file 06).

---

## 8. Upload tokens and share tokens (format only; routes in file 06)

### 8.1 Upload token (`src/media/tokens.ts`)

- String: `<id>.<sig>` where `id` is the `upload_tokens.id` ULID (26 chars, Crockford, uppercase) and `sig = base64url(HMAC-SHA256(UTF-8 TOKEN_SECRET, UTF-8 "agent-dashboard:upload-token:v1:" + id))`, no padding → total **70 chars** (`:35-44`).
- Parse (`:61-71`): split on `.` must give exactly 2 parts; `id` must match `/^[0-9A-HJKMNP-TV-Z]{26}$/` (`src/db/ids.ts:22-24`); sig non-empty; constant-time compare. Any failure → "no token".
- TTL 15 min (`:26`), single use, grant details (agent, media, byte cap, mime list, expiry, used_at) live on the DB row, not in the token (`src/db/upload-tokens.ts`).
- URL: `PUBLIC_BASE_URL + "/api/upload/" + token` (`:74-87`).
- Test vector: secret `"t"×32`, id `01HZY3M5X8Q7R2T4V6W8Y0A2C4` → `01HZY3M5X8Q7R2T4V6W8Y0A2C4.N6mosJLXY_82aUyijaTlwhVZalq3a-FEniDWbkU2J-E`.

### 8.2 Share token (`src/domain/shares.ts`)

- Token: `base64url(32 random bytes)`, 43 chars, no prefix (`:61,110`).
- Stored: `update_shares` token hash = hex `HMAC-SHA256(UTF-8 TOKEN_SECRET, UTF-8 "share:" + token)` (`:78-80`).
- Lookup: by hash, constant-time compare, refused if `revoked_at` set or the update is deleted (`:278-288`). **No shape check** — any string is hashed.
- Re-sharing an update revokes its previous link first (`:108`).
- URL: `PUBLIC_BASE_URL` (trailing `/` stripped) + `/s/` + token (`:63-69`).
- Test vector: secret `"t"×32`, token `AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA` → `b2c91b9cb359e29fe3c1a60f5b3ff3678a7e4aa00bb9cb610c7fb4a990ee3815`.

---

## 9. Principals and where each is accepted

| Principal | Credential | Accepted at | Refusal |
|---|---|---|---|
| Owner, cookie | `ad_session` | every guarded path: pages (`/`, `/projects/*`, `/apps/*`, `/media/*`…) and `/api/*` except exempt ones | `/api/*`: 401 `{"error":"unauthenticated"}`; else 303 to `/login…` |
| Owner, device key | `Authorization: Bearer <43>` | guarded `/api/*` endpoints and `/media/*`, `/apps/*` handlers; required by `/api/devices/current/*` | same as above; bad key → refused even with a good cookie |
| Agent | `Authorization: Bearer <43>` | `POST /mcp`, `GET /api/agent/stream`, `GET /api/agent/media/[id]` | §7.4 |
| Upload | token in path | `PUT /api/upload/[token]` | file 06 |
| Share visitor | token in path | `GET /s/[token]`, `GET /s/[token]/media/[id]/[variant]` | 404 |
| Nobody | — | `/login`, `/logout`, `POST /api/devices/sign-in`, unmatched paths, static assets | — |

Device keys and agent tokens look identical and hash identically, but live in different tables: an agent token on an owner route → 401 unauthenticated; a device key on `/mcp` → 401 `unknown_token`.

---

## 10. CLI (`src/cli/index.ts`, run as `node build/cli.js <cmd>`)

Exit codes: 0 ok, 1 command failed, 2 bad command line (usage printed to stderr) (`:51-55`). `--help`/`-h`/`help` print usage to stdout. Docker adds wrappers `agent-dashboard`, `mint-token`, `hash-password` (`Dockerfile:70-75`).

| Command | Does | Output (stdout) | Source |
|---|---|---|---|
| `mint-token <name>` | create agent + token (only `argv[0]` is the name) | `agent <id>  <name>` / `<token>` / `` / `This is the only time the token is shown. Store it in the agent MCP config.` | `:79-98` |
| `vapid-keys` | `web-push.generateVAPIDKeys()` (P-256, base64url) | `VAPID_PUBLIC_KEY=…` / `VAPID_PRIVATE_KEY=…` / `` / two advice lines | `:100-118` |
| `hash-password <pw>` \| `--stdin` | argon2id hash; `--stdin` strips one trailing `\r?\n`; empty → usage error 2 | `<hash>` / `` / `Quote it in .env — the hash contains '$', which a shell would expand.` | `:120-143` |
| `list-tokens [--revoked]` | list agents oldest first | `<id>  <name>  (<ISO last seen \| never seen>)[  REVOKED]`, or `no agents yet — mint one with: mint-token <name>` | `:145-163` |
| `rename-token <id> <name…>` | rename (rest of args joined by space) | `<id> is now <name>` | `:165-182` |
| `revoke-token <id>` | revoke; unknown id → error exit 1 `no such agent: <id>` | `revoked <id>` or `<id> was already revoked` | `:184-195` |
| `backup <dest.db>` | better-sqlite3 online backup; creates parent dirs | `wrote <abs path>` / `Media lives outside the database; rsync DATA_DIR/media/ as well.` | `:197-214` |
| `help` | usage | see below | `:216-223` |

Commands that touch the DB call `loadConfig`, so they need the **full** config valid (incl. `ADMIN_PASSWORD_HASH`, `SESSION_SECRET`). Errors: message to stderr; stack only for non-domain, non-config errors (`:259-273`). Usage text (exact, observed):
```
agent-dashboard <command> [arguments]

  mint-token <name>                   Create an agent and print its bearer token. Printed once, never stored.
  vapid-keys                          Generate a VAPID keypair for push notifications. Paste both into .env.
  hash-password <password> | --stdin  Print an argon2id hash for ADMIN_PASSWORD_HASH.
  list-tokens [--revoked]             List agents and when they were last seen. Tokens are not recoverable.
  rename-token <agent-id> <name>      Give an agent a different display name. The token keeps working.
  revoke-token <agent-id>             Switch an agent token off for good.
  backup <destination.db>             Online backup of the database, safe while the server is running.
  help                                Print this usage.

Configuration is read from the environment; see .env.example.
```
Usage errors print `<message>`, a blank line, then the usage, to stderr: `no command given`, `unknown command: <x>`, `mint-token needs a name, e.g. claude-code@laptop`, `hash-password needs a password, or --stdin to read one`, `hash-password read an empty password`, `rename-token needs an agent id and a name (see list-tokens)`, `revoke-token needs an agent id (see list-tokens)`, `backup needs a destination file path`.

---

## Surprises

1. **Pages ignore device keys.** The hook accepts a device bearer on `/`, but `+layout.server.ts` checks only the cookie and answers 303 to `/login` (`src/http/routes/+layout.server.ts:17-24`). A device key works on `/api/*`, `/media/*`, `/apps/*`, never on pages.
2. **A revoked device key on `/media/*` or `/apps/*` gets 303 to `/login`, not 401**, because `refuse` only sends 401 under `/api` (`src/http/auth/handle.ts:19-28`).
3. **Form login 429 has no `Retry-After`**; device sign-in 429 does (`src/http/routes/login/+page.server.ts:36` vs `src/http/owner/devices.ts:125-130`).
4. **Blank password is `""` only.** A missing or non-string password costs a guess and says "That password is not right." (`src/http/auth/login.ts:102,116-119`). Blank is checked before the limiter, so it gets 400 even while locked out.
5. **Device sign-in maps blank password to `"error":"wrong_password"`** with the message `Enter your password.` (any 400 from the check → `wrong_password`, `src/http/owner/devices.ts:134`).
6. **Agent-route refusals differ by route**: `/mcp` → JSON-RPC envelope + `WWW-Authenticate` + `Retry-After`; `/api/agent/stream` → `{error,message}` with no `WWW-Authenticate`/`Retry-After` even on 429; `/api/agent/media` → 404 for everything, including rate-limit.
7. **Three separate per-token limiters** (one per route), so the real ceiling is 120/min per route, not per token. The Kotlin server keys them per agent instead (spec/11-oauth.md §5).
8. **Rate limit is spent before identity**, so a revoked token keeps eating its own bucket; a refused request is not recorded.
9. **SvelteKit CSRF covers non-browser clients.** Any POST/PUT/PATCH/DELETE with `Content-Type: text/plain`, form-urlencoded or multipart and no matching `Origin` → 403, on every path. An agent `PUT /api/upload/<t>` with `Content-Type: text/plain` (a log file) and no `Origin` is refused before the app runs. `curl` login needs `-H 'Origin: <ORIGIN>'`.
10. **adapter-node drops the body of any request without `Content-Type`** (`get_raw_body` returns null).
11. **`ADDRESS_HEADER` set + no proxy = 500 on login and device sign-in only** (only those call `getClientAddress()`). On a server configured with `ADDRESS_HEADER=X-Forwarded-For`, direct calls to `/login` on its own port without `X-Forwarded-For` 500. An attacker able to reach the port directly could also set any `X-Forwarded-For` and dodge the limiter.
12. **Without `ORIGIN`, adapter-node assumes `https`** for the request URL, so the CSRF origin check fails for a plain-http deployment unless `ORIGIN`/`PROTOCOL_HEADER` is set.
13. **`BODY_SIZE_LIMIT=""`**: the app check treats it as unparseable and skips; adapter-node parses it as 0 → every body 413.
14. **`PORT` default is 3000** in adapter-node; `.env.example` says 8010 (only the Dockerfile sets 8010).
15. **Unauthenticated callers can map routes**: matched guarded routes answer 401/303, unmatched ones 404 (guard skips `route.id === null`, `src/http/auth/handle.ts:43`). A wrong method on a guarded route gets 401 before 405.
16. **Config is re-validated with zod on every request** (and twice for device-key requests). Harmless, but a Kotlin port can parse once.
17. **Session secret length is checked again at verify time** (`src/http/auth/session.ts:92`), counted in JS UTF-16 units, like the boot check. A Kotlin port must count the same way (not bytes).
18. **Session cookie never refreshes.** The owner is logged out exactly 30 days after login, whatever their activity.
19. **Device keys and agent tokens share format and hash with no domain prefix**; upload and share tokens do have prefixes. Keep it that way or existing rows stop matching.
20. **No global security headers** anywhere.
21. The login limiter's check-then-record is not atomic, so parallel wrong guesses can exceed 5 in a window.

## Parity test notes

Use a server with `SESSION_SECRET="s"×32`, `TOKEN_SECRET="t"×32`, a known `ADMIN_PASSWORD_HASH`, `ORIGIN=http://localhost:8010`, no `ADDRESS_HEADER` (so the key is the socket address). Restart between limiter tests (state is in memory).

Boot:
- Missing `TOKEN_SECRET` → process exits non-zero; stderr contains `TOKEN_SECRET: TOKEN_SECRET is required`.
- `PUBLIC_BASE_URL=https://x`, no `ADDRESS_HEADER`, no `TRUST_SOCKET_ADDRESS` → boot fails mentioning `ADDRESS_HEADER is unset`.
- `BODY_SIZE_LIMIT` unset with default caps → boot fails mentioning `BODY_SIZE_LIMIT is 512K (524288 bytes)`.
- `HOLD_S=60` → boot fails (`Too big`). `VAPID_PUBLIC_KEY` alone → fails with the "set together" message.
- `GET /login` → 200 (health check).

Guard:
- `GET /` no cookie → 303, `location: /login`, empty body.
- `GET /projects/demo?x=1` no cookie → 303 `location: /login?redirectTo=%2Fprojects%2Fdemo%3Fx%3D1`.
- `GET /api/summary` no cookie → 401, `content-type: application/json`, body exactly `{"error":"unauthenticated"}`.
- `GET /api/summary/` (trailing slash) → not a bypass (401 or redirect, never 200).
- `GET /api/summary` with valid cookie + `Authorization: Bearer <43 random>` → 401 (bearer decides).
- `GET /api/summary` with `Authorization: Basic Zm9v` + valid cookie → 200.
- `GET /api/does-not-exist` no cookie → 303 `location: /login?redirectTo=%2Fapi%2Fdoes-not-exist` (an unknown path is not treated as API; checked on the live throwaway instance).
- `GET /mcp-docs` → guarded (303), `/mcp` → not guarded.

Login form:
- `POST /login` form `password=wrong` with `Origin: http://localhost:8010` → 400 HTML page containing `That password is not right.`
- Same with `Accept: application/json` → 200 `{"type":"failure","status":400,"data":"[{\"error\":1},\"That password is not right.\"]"}`.
- 5 wrong → each 400; 6th (even correct) → 429 HTML containing `Too many attempts. Try again later.`, no `Retry-After`.
- `password=` (empty) during lockout → 400 `Enter your password.`
- After lockout, `POST /api/devices/sign-in` `{"name":"x","password":"<right>"}` → 429 with `retry-after` ≈ 900 and body `{"error":"rate_limited","message":"Too many attempts. Try again later."}` (shared limiter).
- Correct password → 303 `location: /`, `set-cookie` matches `^ad_session=[A-Za-z0-9_-]+\.[A-Za-z0-9_-]{43}; Max-Age=2592000; Path=/; HttpOnly; Secure; SameSite=Lax$`. Decode payload: keys `issuedAt`,`expiresAt`, difference 2592000000.
- `redirectTo=//evil.com` → `location: /`. `redirectTo=/projects/a` → `location: /projects/a`. `redirectTo=/mcp` → `/`. `redirectTo=/logout` → `/`.
- `POST /login` form with no `Origin` → 403 `Cross-site POST form submissions are forbidden`; with `Accept: application/json` → 403 `{"message":"Cross-site POST form submissions are forbidden"}`.
- Cookie vector: `ad_session=eyJpc3N1ZWRBdCI6MTcwMDAwMDAwMDAwMCwiZXhwaXJlc0F0IjoxNzAyNTkyMDAwMDAwfQ.VaTNC-sXCNyk_rloaqTvm5YrV47-gvu8B6NCkd4aGj4` → rejected (expired 2023). Mint a fresh one with the §5.4 recipe and current time → `GET /api/summary` 200. Flip one sig char → 401.
- `GET /login` with valid cookie → 303 `location: /`.

Logout:
- `POST /logout` (form, same Origin) → 303 `location: /login`, `set-cookie: ad_session=; Max-Age=0; Path=/; HttpOnly; Secure; SameSite=Lax`.
- `GET /logout` without cookie → 303 `/login`.

Device keys:
- Sign-in `{"name":"  Phone  ","password":"<right>"}` → 201, `cache-control: no-store`, `content-type: application/json; charset=utf-8`, `device.name == "Phone"`, `token` matches `^[A-Za-z0-9_-]{43}$`, `createdAt` matches `\.\d{3}Z$`.
- Sign-in body `nope` → 400 `{"error":"invalid_argument","message":"body must be JSON"}`; `{"password":"x"}` → 400 `name must be a string`; `{"name":"","password":"x"}` → 400 `name is required` and no attempt spent.
- With the key: `GET /api/summary` → 200; `GET /` → 303 to `/login` (page ignores bearer).
- `DELETE /api/devices/<id>` → 200 `{"removed":true}`; again → `{"removed":false}`; then the key on `/api/summary` → 401 `{"error":"unauthenticated"}`; on `/media/x/original` → 303.
- `PUT /api/devices/current/apns` with cookie → 400 `{"error":"invalid_argument","message":"this needs a device key: send Authorization: Bearer <device token>"}`.
- `GET /api/devices` twice within 60 s with the device key → that device's `lastSeenAt` is the same both times (written once).
- Agent token as bearer on `/api/summary` → 401.

Agent tokens (`POST /mcp`, JSON-RPC `initialize` body):
- No header → 401, `www-authenticate: Bearer`, body `{"jsonrpc":"2.0","id":null,"error":{"code":-32000,"message":"missing Authorization header: send Authorization: Bearer <agent token>","data":{"error":"missing_token"}}}`.
- `Authorization: Token abc` → 401 `unsupported_scheme`, message `unsupported Authorization scheme "Token": this server accepts Bearer tokens only`.
- `Authorization: Bearer` (no token) → 401 `missing_token` / `empty bearer token: …`, challenge `Bearer`. (Note: `"Bearer"` trims to one word, so scheme ok, token empty.)
- `Bearer short` → 401 `malformed_token`, `www-authenticate: Bearer error="invalid_token", error_description="malformed bearer token: not a token this server issued"`.
- `bearer <valid>` (lower-case scheme) → passes auth (not 401/429; the MCP transport then answers per its own rules, e.g. needs `Accept: application/json, text/event-stream`).
- Revoked token (via CLI) → 401 `revoked_token`, message `revoked bearer token: ask the dashboard owner to mint a new one`.
- 121 requests within 60 s with one token → 121st is 429, `retry-after` integer ≥ 1, body `data.error == "rate_limited"`, message `rate limit exceeded for this token: retry after <n>s`. Same token on `/api/agent/stream` in that minute → still allowed (separate bucket).
- `/api/agent/stream` without header → 401 `{"error":"missing_token","message":"missing Authorization header: send Authorization: Bearer <agent token>"}`, no `www-authenticate`.
- Hash vector: insert agent row with `token_hash=7fec7d5f16cdafa6abc866d824093848ee299a7ea40e0fcb79d83bed1462fdb0` → token `AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA` authenticates.

Upload / share format:
- `signUploadToken("t"×32, "01HZY3M5X8Q7R2T4V6W8Y0A2C4")` == `01HZY3M5X8Q7R2T4V6W8Y0A2C4.N6mosJLXY_82aUyijaTlwhVZalq3a-FEniDWbkU2J-E`; lower-case id or a third `.` part → rejected.
- `hashShareToken("AAAA…A" (43), "t"×32)` == `b2c91b9cb359e29fe3c1a60f5b3ff3678a7e4aa00bb9cb610c7fb4a990ee3815`.

Compression:
- `GET /` (logged in) with `Accept-Encoding: gzip, br` → `content-encoding: br`, `vary` includes `accept-encoding`.
- `Accept-Encoding: br;q=0, gzip` → `gzip`. `Accept-Encoding: *` → none.
- `GET /api/stream` → never `content-encoding`; `x-accel-buffering: no`.
- A JSON body under 1400 bytes → not encoded.

CLI:
- `hash-password ''` → exit 2, stderr starts `hash-password needs a password, or --stdin to read one`.
- `hash-password pw` → stdout line 1 matches `^\$argon2id\$v=19\$m=65536,p=4,t=3\$`.
- `mint-token laptop` → 4 lines; line 2 is a 43-char token that works on `/mcp`.
- `revoke-token <id>` twice → `revoked <id>` then `<id> was already revoked`, exit 0; unknown id → exit 1.
- `bogus` → exit 2, `unknown command: bogus`.
