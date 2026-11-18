# 09 — Web frontend: how the website is served

Source: the `agent-dashboard` working tree. Paths are relative to the repo root.
Versions in `node_modules`: `@sveltejs/kit` 2.70.3, `@sveltejs/adapter-node` 5.5.7, `svelte` 5.56.10.

## Answer first

- Only **5 page routes** exist: `/`, `/projects/[slug]`, `/login`, `/logout`, `/s/[token]`.
- `/` and `/projects/[slug]` get their data from the **same function** as `GET /api/snapshot` (`src/http/routes/dashboard.ts:49-57`). The dashboard can run as a pure SPA on the JSON API plus the SSE stream.
- Three things exist **only** as server-rendered pages, with no JSON endpoint:
  1. `/login` form action (sets the cookie).
  2. `/logout` form action (clears the cookie).
  3. `/s/[token]` (public share page and its Open Graph tags).
- There is no CSP on pages, no prerendering, and the service worker does no caching.

---

## 1. Build and process layout

| Item | Value | Source |
|---|---|---|
| Adapter | `@sveltejs/adapter-node`, default options | `vite.config.ts:20` |
| Route dir | `src/http/routes` | `vite.config.ts:25-28` |
| `$lib` dir | `src/web` | `vite.config.ts:27` |
| `svelte.config.js` | none (all config in `vite.config.ts`) | repo root |
| `src/error.html` | none, so kit's built-in `default-error.html` is used | `src/` listing |
| Page CSP (`kit.csp`) | not configured | `vite.config.ts` |
| Build | `vite build` (app), then cli, channel and monitor bundles into the same `build/` | `package.json:8-11` |
| Start | `node build` | `package.json:15` |

`build/` contents:

```
build/index.js, handler.js, env.js, shims.js   adapter-node server
build/server/chunks/...                         SSR bundle
build/client/                                   static files served by sirv (2.8 MB)
  _app/immutable/{entry,chunks,assets}/...      hashed JS/CSS, each with .br and .gz siblings
  _app/version.json                             {"version":"1795009068560"}
  service-worker.js(.br/.gz)
  manifest.webmanifest, robots.txt(.br/.gz), favicon-32.png,
  apple-touch-icon.png, apple-touch-icon-precomposed.png, icons/*
build/channel.js, cli.js, monitor.js            not web; see the channel spec
```

There is no `build/prerendered/`, because nothing is prerendered.

### Request pipeline (adapter-node)

The order is fixed in `build/server/chunks/handler-*.js` (line 1469-1471): `sirv(build/client)`, then `serve_prerendered()` (does nothing here), then SvelteKit `ssr`.

1. **Static (sirv).** If a file in `build/client` matches the path, it is served and nothing else runs: no auth hook, no compress hook.
   - Precompressed `.br` or `.gz` is picked by `Accept-Encoding`, with `Vary: Accept-Encoding`.
   - Every static file gets a weak `ETag: W/"<size>-<mtimeMs>"`, plus `Last-Modified`, `Content-Length` and `Content-Type`. Range requests are supported (206 and 416).
   - Only paths under `/_app/immutable/` that answer 200 also get `cache-control: public,max-age=31536000,immutable` (handler chunk line 1283-1286).
   - **All other static files get no `Cache-Control` header**: manifest, icons, `service-worker.js`, `robots.txt`, `_app/version.json` (handler chunk line 656-666; `maxAge` is not passed).
2. **SvelteKit `respond`**, in this order:
   1. **Trailing slash.** `trailingSlash` defaults to `never`, so `/login/` answers `308` with `location: /login`. This runs **before** the hook, so it applies even without auth (`node_modules/@sveltejs/kit/src/runtime/server/respond.js:369-380`).
   2. **CSRF.** A request is refused with `403` when all of these hold: method is POST, PUT, PATCH or DELETE; content type is form-urlencoded, multipart, or `text/plain`; and `Origin` differs from the server origin. **A missing `Origin` header also counts.** The body is `Cross-site POST form submissions are forbidden` as text, or as JSON `{message}` if `accept: application/json` (`respond.js:81-97`). The server origin comes from the `ORIGIN` env var (`.env.example:99-100`).
   3. **Missing file under `/_app/`.** Answers `404 Not found` (text) with `cache-control: public, max-age=0, must-revalidate` (`respond.js:326-331`).
   4. **`handle` hook** = `sequence(compressHandle, authHandle)` (`src/hooks.server.ts:112`). The compress hook wraps auth on purpose, so redirects get compressed too. It compresses text, JSON, manifest and SVG bodies between 1400 bytes and 8 MB, with brotli at quality 4 or gzip at level 6. It adds `content-encoding` and `vary: accept-encoding` (`src/http/compress.ts:47,56,60-70,76,159-165,195-198`).

## 2. Auth guard for pages

The hook and the root layout both read the same path rules in `src/http/auth/guard.ts`.

- **Public prefixes** (match on whole path segments): `/login`, `/logout`, `/api/devices/sign-in` (`guard.ts:39`).
- **Token prefixes**, never cookie-guarded: `/mcp`, `/api/upload`, `/s`, `/api/agent` (`guard.ts:33`).
- **Everything else** needs the `ad_session` cookie (`guard.ts:52-55`).

What the hook does (`src/http/auth/handle.ts:39-56`):

- It skips requests where `event.route.id === null`, meaning nothing matched: unknown paths.
- It checks a bearer token first: an `Authorization: Bearer <device key>` header decides on its own.
- If refused:
  - an `/api/*` path gets `401` with `{"error":"unauthenticated"}` and `content-type: application/json` (`handle.ts:18-26`);
  - anything else gets `303` with no body and `location: loginRedirect(url)` (`handle.ts:28`).

How `loginRedirect` builds the location (`guard.ts:66-70`):

- target = `pathname + search`.
- If `safeRedirectTarget(target) === '/'`, the location is just `/login`, **with no query**. This covers `/` itself.
- Otherwise it is `/login?redirectTo=<encodeURIComponent(target)>`. Example: `/login?redirectTo=%2Fprojects%2Facme`.

`safeRedirectTarget(raw)` (`guard.ts:88-108`) returns `/` when any of these hold:

- `raw` is empty or does not start with `/`;
- `raw` contains a C0 control character or DEL;
- `raw` starts with `//` or `/\`;
- the normalised path is under a public prefix;
- the path does not need a session (for example `/mcp` or `/s/...`).

Otherwise it returns `raw` unchanged, query included.

The root layout repeats the check for pages (`src/http/routes/+layout.server.ts:17-24`). If there is no cookie session and the path needs one, it calls `redirect(303, loginRedirect(url))`. It returns `{ authenticated: boolean }`.

- **Cookie only.** The layout ignores bearer tokens, so a device-key bearer gets past the hook on a page route and is then sent to `/login` anyway.
- **It also runs when kit renders a 404 for an unknown path** (`node_modules/@sveltejs/kit/src/runtime/server/page/respond_with_error.js:45-60,114-115`). So an unknown path **without** a cookie gives `303` to `/login?redirectTo=%2F<path>`, and **with** a cookie gives the 404 error page. The same holds for unknown `/api/*` paths: no route matches, so the hook never runs, and the layout redirects with a 303 rather than answering 401.

## 3. Page routes

Page options: no route exports `prerender`, `ssr`, `csr` or `trailingSlash`. Defaults apply: SSR on, client hydration on, prerender off, `trailingSlash: 'never'` (grep finds nothing in `src`). There is no `+page.ts` or `+layout.ts` anywhere.

| Path | Files | Auth | Load returns |
|---|---|---|---|
| (root layout) | `+layout.server.ts`, `+layout.svelte` (imports `app.css` only) | see §2 | `{ authenticated }` |
| `/` | `+page.server.ts`, `+page.svelte` | session | `DashboardData` with `project: null` |
| `/projects/[slug]` | `projects/[slug]/+page.server.ts`, `+page.svelte` | session | `DashboardData` with `project: slug` |
| `/login` | `login/+page.server.ts`, `+page.svelte` | public | `{ configured, redirectTo }`, plus `default` action |
| `/logout` | `logout/+page.server.ts`, `+page.svelte` | public path, but load needs a session | `{}`, plus `default` action |
| `/s/[token]` | `s/[token]/+page.server.ts`, `+page.svelte` | token in path | `{ card, token, preview }` |

### `/` and `/projects/[slug]`

- Load: `loadDashboard(null)` for `/` and `loadDashboard(params.slug)` for a project (`src/http/routes/+page.server.ts:9`, `src/http/routes/projects/[slug]/+page.server.ts:10`).
- `loadDashboard` (`src/http/routes/dashboard.ts:52-66`) runs in this order:
  1. `seq = bus.lastSeq`, read **before** the state;
  2. `readFullSnapshot({ limit: 50, project? })` (`src/http/stream/snapshot.ts:52,258`);
  3. returns `{ project, snapshot: { seq, at: new Date().toISOString(), ...FullSnapshot } }`.
- A `DomainError` is mapped `invalid_argument` to 400, `not_found` to 404, `conflict` to 409, and passed to kit's `error(status, message)` (`dashboard.ts:22,59-62`). An unknown slug therefore renders kit's error page at that status.
- `FullSnapshot` (`snapshot.ts:207-219`) has these keys: `projects, tags, cardTags, cardFields, cardApps, updates{items,nextCursor,hasMore}, agentNames, messages, acks, messageMedia, messageEmbeds`.
- This is **byte-for-byte the same reader** that `GET /api/snapshot?limit=50&project=<slug>` serves (`src/http/routes/api/snapshot/+server.ts:18`, `snapshot.ts:360-382`). Neither the page nor the API applies a `status` filter by default.
- Page component (`src/http/routes/+page.svelte:12-17`, `projects/[slug]/+page.svelte:10-27`):
  - renders `<Shell snapshot project focus>`;
  - `focus` is read **client-side** from `?focus=`;
  - title is `Agent Dashboard`, or `<project.name ?? 'Project'> · Agent Dashboard` on a project page;
  - the project page keys `Shell` on the slug, so switching project rebuilds the shell.
- Shell hydrates its stores from `snapshot` once. After that, everything comes from the API and the stream (§5).
- The HTML embeds the whole snapshot twice: once rendered, once as hydration data. The live page measured 962 KB raw and 100 KB compressed (`src/http/compress.ts:4-10`).

### `/login`

- **Load** (`src/http/routes/login/+page.server.ts:15-23`):
  - `redirectTo = safeRedirectTarget(?redirectTo)`;
  - if a valid session cookie is present, `303` to `redirectTo`;
  - otherwise returns `{ configured: authConfig() !== null, redirectTo }`.
- **Form** (`src/http/routes/login/+page.svelte:19-54`): plain `method="POST"` to the same URL, with no `use:enhance`. Fields are a hidden `redirectTo` and `password`. It shows `form.error` and an "unconfigured" note when `!configured`.
- **`default` action** (`login/+page.server.ts:25-40`, `src/http/auth/login.ts:147-218`):
  - `redirectTo` = the form field if it is a string, else the `?redirectTo=` query value.
  - The password check runs these steps in order:
    1. Secrets do not load (`SESSION_SECRET`, `ADMIN_PASSWORD_HASH`): **503** `This deployment has no valid ADMIN_PASSWORD_HASH and SESSION_SECRET, so nobody can log in. See .env.example.` (`login.ts:43-45,98`).
    2. `password === ''`: **400** `Enter your password.` This does not use up an attempt (`login.ts:102`).
    3. Rate limit, keyed by `getClientAddress()`: 5 attempts per 15 min, sliding window (`login.ts:33-34`), at most 10 000 keys tracked (`src/http/auth/rate-limit.ts:41`). When over the limit: **429** `Too many attempts. Try again later.`, even for the right password (`login.ts:105-114`).
    4. Wrong password, checked with argon2id `verifyPassword`: record one attempt, then **400** `That password is not right.` (`login.ts:41,117-119`). A missing or non-string `password` field counts as a wrong guess.
    5. Success: reset that client's attempts (`login.ts:122`).
  - Failures go out as `fail(status, { error })`. The page re-renders as HTML at that status, showing the error. **No `Retry-After` header is sent**: `retryAfterS` is dropped by the route.
  - On success it sets the cookie, then `303` to `safeRedirectTarget(redirectTo)` (`login.ts:139-145`).
- **Session cookie** (`src/http/auth/session.ts:17,20,46,71-79`):
  - Name `ad_session`.
  - Value is `base64url(JSON{"issuedAt":ms,"expiresAt":ms})` + `.` + `base64url(HMAC-SHA256(SESSION_SECRET, payload))`.
  - Attributes: `Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=2592000` (30 days).
  - Stateless: no server-side session row exists.
- The limiter is shared with `/api/devices/sign-in` through `ownerPasswordCheck` (`login.ts:213-218`). It is held in memory and reset on restart.

### `/logout`

- **Load** (`src/http/routes/logout/+page.server.ts:12-18`): no valid session, or auth not configured, gives `303` to `/login`. Otherwise it renders a page with a POST form, a "Sign out" button and a "Stay signed in" link to `/` (`logout/+page.svelte:11-19`).
- **`default` action** (`logout/+page.server.ts:20-25`):
  - `cookies.delete('ad_session', {path:'/',httpOnly:true,secure:true,sameSite:'lax'})` (`src/http/auth/logout.ts:12`, `session.ts:54-55`), then `303` to `/login`.
  - It runs whether or not a session exists, but the CSRF origin check still applies.
- The Shell's "Sign out" control is a **link** to `/logout`, not a POST (`src/web/Shell.svelte:617-622`). The flow is two steps: the link, then the form.

### `/s/[token]` (public share)

- Auth: `/s` is a token prefix, so neither the hook nor the layout asks for a cookie.
- **Load** (`src/http/routes/s/[token]/+page.server.ts:18-37`):
  1. `loadConfig(process.env)` (needs `TOKEN_SECRET`, `PUBLIC_BASE_URL`);
  2. `readShare(context(), { token, secret })` (`src/domain/shares.ts:228-256`);
     - token is hashed (HMAC), looked up by hash, compared in constant time; a revoked share gives `null` (`shares.ts:278-290`);
     - a missing or deleted update gives `null`;
     - **side effect:** `recordShareView` on every successful read (`shares.ts:238`);
  3. `null` gives `error(404, 'not found')`. It is the same 404 for an unknown, revoked or deleted share;
  4. headers `x-robots-tag: noindex, nofollow` and `cache-control: no-store`. These are set only on success, after the 404 check;
  5. `preview = sharePreview(card, { baseUrl: PUBLIC_BASE_URL, token })` (`src/web/preview.ts:49-92`).
- `card` shape (`shares.ts:190-214`):
  `{ update:{id,title,body,level,createdAt,editedAt}, agentName (default 'An agent'), projectName|null, media: MediaAttachment[], embeds: EmbedView[] }`.
- `preview` shape (`preview.ts:22-34`): `{ title, description, url, image?, imageAlt?, imageWidth?, imageHeight?, video?, videoType? }`.
  - `title` = update title, else `"<agent> in <project>"` or `"An update from <agent>"`.
  - `description` = `excerpt(body)` (at most 200 chars, markdown stripped; `src/web/markdown.ts:112,129-154`), else the title.
  - `url` = `<PUBLIC_BASE_URL without trailing slash>/s/<token>`.
  - `image` = the first ready image's `thumb-1600` or `original`, else a video `poster`. It is an absolute URL under `/s/<token>/media/<id>/<variant>`.
  - `video` = `video` (`video/mp4`) or `original` (with its stored mime).
- **Head tags**, which must be in the server HTML because unfurl crawlers run no JS (`preview.ts:12-15`, `s/[token]/+page.svelte:16-58`):
  - `<title>{title} · Agent Dashboard</title>`, `robots noindex,nofollow`, `description`;
  - `og:type=article`, `og:site_name=Agent Dashboard`, `og:title`, `og:description`, `og:url`;
  - `twitter:title`, `twitter:description`;
  - with an image: `og:image`, `og:image:alt`, `og:image:width`/`og:image:height` when known, `twitter:card=summary_large_image`, `twitter:image`. Without one: `twitter:card=summary`;
  - with a video: `og:video`, `og:video:secure_url`, `og:video:type`.
- **Body:** `<SharedCard card mediaPrefix="/s/{token}">` plus a fixed footer line (`s/[token]/+page.svelte:60-73`). The markdown is rendered by `markdown-it` (`src/web/markdown.ts:15`), which runs in both server and browser.
- **Media** for a share: `GET /s/[token]/media/[id]/[variant]` uses the owner media handler, authorised by `shareGrantsMedia` (`src/http/routes/s/[token]/media/[id]/[variant]/+server.ts:20-29`). See the media spec.

### `/apps/[view]` (endpoint, not a page)

`GET` only (`src/http/routes/apps/[view]/+server.ts:10-12`, `src/http/apps/serve.ts:94-117`).

- **Auth:**
  - no cookie: the hook sends a **303** to `/login?redirectTo=%2Fapps%2F<view>`, because the path is not under `/api`. The handler's own 401 is reached only by a request that passed the hook;
  - a valid device bearer also passes.
- **Not found:** `404` `{"error":"not_found"}` with `cache-control: no-store` (`serve.ts:86-91`).
- **Success:** `200`, body = the agent-written HTML, with these headers:
  - `content-type: text/html;profile=mcp-app;charset=utf-8`
  - `content-security-policy: policyFor(csp)` (`serve.ts:63-84`)
  - `x-content-type-options: nosniff`
  - `referrer-policy: no-referrer`
  - `cache-control: no-store`
- The browser loads it in an iframe at `app.url ?? /apps/<id>` (`src/web/AppFrame.svelte:90`), sandboxed with `allow-scripts` and no `allow-same-origin` (`serve.ts:10-15`).

### Error pages

- There is no `+error.svelte` and no `src/error.html`.
- A load `error()` or an unknown route renders the root layout plus kit's built-in error component: `<h1>{status}</h1><p>{message}</p>` (`node_modules/@sveltejs/kit/src/components/svelte-5/error.svelte`). It uses the `app.html` shell, so the theme script and icons are present.
- Messages:
  - unknown route: `Not Found`;
  - unknown share: `not found`;
  - unknown slug: the domain message.
- If rendering itself fails, kit falls back to `default-error.html` (`respond_with_error.js:37,118`).

### Response headers on any server-rendered page

From `node_modules/@sveltejs/kit/src/runtime/server/page/render.js:591-641`:

- `content-type: text/html`;
- `x-sveltekit-page: true`;
- `etag: "<hash>"`, answered with 304 on `If-None-Match` (`respond.js:502-520`);
- `link:` preload headers (`rel="preload"; as="style"` and `rel="modulepreload"`, each with `nopush`);
- **no `cache-control`** on `/`, `/projects/*`, `/login`, `/logout`;
- no CSP.

Asset URLs in the HTML are **relative** (`paths.relative` default): `./_app/...` on `/`, `../_app/...` on `/projects/x` (`render.js:121-137`).

### Client-side navigation (hidden server calls)

- `<body data-sveltekit-preload-data="hover">` (`src/app.html:88`). These links use kit's client router:
  - Sidebar project links: `resolve('/projects/[slug]')` (`src/web/Sidebar.svelte:82`);
  - the `/logout` link.
- Hovering or clicking one fetches `GET <path>/__data.json?x-sveltekit-invalidated=...` (devalue-encoded). That runs the full layout and page load, so **each hover over a project link costs one full snapshot read**.
- A `__data.json` request without a session: the hook's 303 is turned into a 200 JSON `{"type":"redirect","location":"/login?..."}` (`respond.js:533-540`).
- The notification bell navigates with `window.location.href = path`, which is a full page load (`src/web/NotificationBell.svelte:29`).

## 4. Static assets, `app.html`, service worker

### `static/` (copied as-is into `build/client/`)

- `manifest.webmanifest`:
  - `name "Agent Dashboard"`, `short_name "Agents"`, `start_url "/"`, `scope "/"`, `id "/"`, `display "standalone"`;
  - `background_color` and `theme_color` `#111419`;
  - icons: `/icons/icon-192.png` (192, any), `/icons/icon-512.png` (512, any), `/icons/maskable-512.png` (512, maskable), `/icons/icon.svg` (any, svg).
- `robots.txt`: `User-agent: *` / `Disallow:` (allow all).
- `favicon-32.png`, `apple-touch-icon.png`, `apple-touch-icon-precomposed.png`.
- `icons/`: `apple-touch-icon{,-152,-167}.png`, `icon-192.png`, `icon-512.png`, `icon.svg`, `maskable-512.png`, `maskable.svg`.

### `src/app.html`

- `<html lang="en" data-theme="dark" class="dark">` (line 2). Meta tags:
  - `charset utf-8`;
  - `viewport width=device-width, initial-scale=1`;
  - `color-scheme dark light`;
  - `text-scale scale` (line 7);
  - `description` "Watch what your coding agents are doing, and answer the questions they stop on." (lines 8-11).
- Lines 32-44:
  - `manifest`;
  - `theme-color` `#111419` (dark) and `#fbfcfd` (light), by media query;
  - `apple-touch-icon` at 180 (`/apple-touch-icon.png`), 167 and 152;
  - `icon` as svg, as png 32 (`/favicon-32.png`) and as png 192;
  - `apple-mobile-web-app-capable yes`, `mobile-web-app-capable yes`, `apple-mobile-web-app-title Agents`, `apple-mobile-web-app-status-bar-style black-translucent`.
- Inline blocking script (lines 57-85), which runs before paint:
  - theme comes from `localStorage.theme`, else `prefers-color-scheme`;
  - sets `data-theme` and the `dark` class;
  - sets `data-native-app` when the UA matches `/\bAgentsiOS\//` (the iOS companion app);
  - replaces the theme-color metas with one that matches the chosen theme.
- **No CSP**, no `%sveltekit.nonce%`.

### Service worker (`src/service-worker.ts`, built to `/service-worker.js`)

- **Registration:** automatic. Kit puts an inline script in every server-rendered page that calls `navigator.serviceWorker.register('/service-worker.js')` on `load` (`node_modules/@sveltejs/kit/src/runtime/server/page/render.js:554-576`). This includes `/login` and `/s/[token]`.
- **No `fetch` handler, so nothing is cached** (`service-worker.ts:12-18`).
- **Lifecycle:** `install` calls `skipWaiting()`; `activate` calls `clients.claim()` (lines 39-45).
- **`push`** (lines 47-79):
  - payload is JSON `{title?, body?, url?, tag?, requestId?, actions?: [{action,title,value}]}`; a bad or empty payload is treated as `{}`;
  - calls `showNotification(title ?? 'Agent Dashboard', {...})` with:
    - `body ?? 'An agent is waiting on you.'`
    - `tag ?? 'owner-request'`
    - `icon` and `badge` both `/icons/icon-192.png`
    - `actions` as `[{action,title}]`
    - `data: {url ?? '/', requestId, actions}`
- **`notificationclick`** (lines 81-107):
  - if the clicked action matches and there is a `requestId`, it sends `POST /api/requests/<id>/answer` with body `{"value": <value>}`, `credentials: include`, JSON content type (lines 118-130);
  - if that answer fails, or no action was chosen, it focuses the first window and navigates it to `url`, or opens a new window (lines 133-141).

### Cache headers summary

| Path | Cache-Control | Other |
|---|---|---|
| `/_app/immutable/**` (200) | `public,max-age=31536000,immutable` | weak ETag, Last-Modified, br/gz, `Vary: Accept-Encoding` |
| `/_app/<missing>` | `public, max-age=0, must-revalidate` | 404 text `Not found` |
| other `build/client` files (manifest, icons, `service-worker.js`, `robots.txt`, `_app/version.json`) | **none** | weak ETag, Last-Modified, br/gz where a sibling file exists |
| SSR pages `/`, `/projects/*`, `/login`, `/logout` | none | strong `etag` from a content hash |
| `/s/[token]` (200) | `no-store` | `x-robots-tag: noindex, nofollow` |
| `/apps/[view]` | `no-store` | CSP, nosniff, `no-referrer` |
| `/api/snapshot*` | `no-store` | `src/http/stream/snapshot.ts:419-428` |

## 5. Everything the browser bundle calls

All calls are same-origin and use the cookie. No other origin is called.

| Call | Where |
|---|---|
| `EventSource /api/stream` or `/api/stream?last_event_id=<seq>` (one leader tab, shared to other tabs over `BroadcastChannel` `agent-dashboard:stream`) | `src/web/stream.ts:170,179-180,189,231` |
| `GET /api/snapshot?limit=&project=&task=` | `src/web/timeline.svelte.ts:328,382-388` |
| `GET /api/snapshot/updates?limit=&project=&task=&cursor=` | `src/web/timeline.svelte.ts:293` |
| `GET /api/snapshot/agents` | `src/web/presence.svelte.ts:259` |
| `GET /api/snapshot/requests` | `src/web/requests.svelte.ts:188` |
| `GET /api/messages[?project=]` | `src/web/threads.svelte.ts:388-392` |
| `GET /api/notifications`; `POST /api/notifications/seen` `{ids?}` | `src/web/notifications.svelte.ts:145,170` |
| `GET` / `POST` / `PATCH` / `DELETE ?endpoint=` on `/api/push` | `src/web/push.svelte.ts:122,184,216,246` |
| `POST /api/projects`; `PATCH /api/projects/<ref>` | `src/web/actions.ts:414-416` |
| `POST /api/cards/<kind>/<id>/{pin,status,tags,fields,reactions}` | `src/web/actions.ts:420-448,486` |
| `POST /api/apps/<view>/state` `{state}` | `src/web/actions.ts:454` |
| `POST /api/projects/<p>/tags`; `PATCH` / `DELETE /api/tags/<id>` | `src/web/actions.ts:457-463` |
| `PATCH` / `DELETE /api/updates/<id>`; `POST` / `DELETE /api/updates/<id>/share` | `src/web/actions.ts:466-467,496-497` |
| `POST /api/media?filename=` (raw bytes over XHR, for upload progress) | `src/web/actions.ts:468-473`, `src/web/bytes.ts:84` |
| `PATCH /api/agents/<id>` `{name}` | `src/web/actions.ts:475` |
| `POST /api/messages`; `DELETE /api/messages/<id>`; `POST /api/messages/<id>/reactions` | `src/web/actions.ts:476-479` |
| `POST /api/requests/<id>/answer` `{value}`; `DELETE /api/requests/<id>` | `src/web/actions.ts:491-492` |
| `<img>` / `<video>` `src`: `/media/<id>/<variant>`; on a share page `/s/<token>/media/<id>/<variant>` | `src/web/media.ts:50-51` |
| `<iframe src>`: `/apps/<view>` | `src/web/AppFrame.svelte:90` |
| Kit router: `/<page>/__data.json` on hover or click of internal links | §3 |
| Service worker: `POST /api/requests/<id>/answer` | `src/service-worker.ts:120` |

Notes on the table:

- `actions.ts` JSON calls send `accept: application/json` and, when there is a body, `content-type: application/json` (`src/web/actions.ts:295-299`).
- Browser to iOS app bridge: `window.webkit.messageHandlers[...]` (`src/web/native-composer.ts:26-37`). This never reaches the server.
- Load data used by a page and **not** from `/api/*`:
  - dashboard: nothing. `/api/snapshot` has it all.
  - `/login`: `configured` and `redirectTo`.
  - `/s/[token]`: `card`, `token`, `preview`.
  - layout: `authenticated`, which **no component reads** (grep in `src/web` and the route components finds no use).

## 6. Assessment: serving the site from Kotlin

### What depends on server-rendered data with no JSON endpoint

| Page | Gap |
|---|---|
| `/`, `/projects/[slug]` | **None for data.** `GET /api/snapshot?limit=50[&project=]` is the same reader. The client must fetch it on boot and treat a 404 as "unknown project". The auth redirect has to stay on the server: an HTML `GET` without a session must 303 to `/login...`. |
| `/login` | A form POST (urlencoded `password`, `redirectTo`) that sets the cookie and 303s, or re-renders with an error at 400, 429 or 503. The `configured` flag. Needs a Kotlin form handler and HTML. `/api/devices/sign-in` issues a device key, not the cookie, so it is not a substitute. |
| `/logout` | A POST that clears the cookie and 303s to `/login`. The GET page needs a session check, else 303 `/login`. |
| `/s/[token]` | No JSON endpoint for a share. The OG and Twitter meta tags must be in the HTML a crawler gets. The view counter fires on each GET. Needs a Kotlin-rendered head at minimum, plus either a server-rendered body or a new `GET /api/shares/<token>` endpoint that the SPA renders. |
| any unknown path | Today: no session gives 303 `/login?redirectTo=`; with a session, a 404 HTML page. |

### Option A: static SPA build served by Ktor (recommended shape)

Build:

- `adapter-static` with `fallback: 'index.html'`;
- `export const ssr = false` in the root layout;
- move the `+page.server.ts` loads to `+page.ts` (or to the components) that fetch `/api/snapshot`.

Ktor serves `build/` as follows:

- `/_app/immutable/**` with `public,max-age=31536000,immutable`, plus the precompressed `.br` and `.gz` files;
- other static files with ETag and no `Cache-Control`;
- an HTML `GET` to any SPA path: check the cookie first. Without one, 303 to `/login...` using the `loginRedirect` rules (§2). With one, serve `index.html`.

Gaps to close:

1. `/login` and `/logout`: small Kotlin-rendered HTML pages, or SPA pages that POST to them. They must keep working with no JS, since the plain form is intentional (`login/+page.svelte:2-4`). Keep the 303, the status codes, the error strings, the shared rate limiter and the cookie format.
2. `/s/[token]`: a Kotlin template for the head tags, plus a share-card JSON endpoint or a server-rendered body. Keep `noindex`, `no-store`, the 404-for-everything rule and the view count.
3. Unknown slug: the client shows its own 404, because the API answers JSON 404.
4. First paint gets slower: the markdown that was server-rendered now renders in the browser. The current HTML document goes away (962 KB raw, 100 KB compressed; `compress.ts:4-10`).
5. Service worker registration: kit's SPA fallback still inlines the register script; check this after the switch. The fallback page uses absolute asset paths, not relative ones (`render.js:121-140`).
6. `__data.json` disappears, which also removes the snapshot-per-hover cost.
7. The CSRF origin check that kit gives every form POST for free has to be re-implemented in Kotlin, including the "no `Origin` header means 403" rule.

### Option B: keep Node for pages, Kotlin for the API

- The page loads call `$domain` directly against SQLite (`dashboard.ts:57`, `s/[token]/+page.server.ts:20`). Under this option they must instead call Kotlin over HTTP, forwarding the cookie, or the two processes share the database.
- The login rate limiter is held in memory and shared with `/api/devices/sign-in` (`login.ts:213-218`). Splitting the processes splits the limiter.
- `SESSION_SECRET` and the cookie format must match exactly in both processes.
- Two processes and a proxy, for 5 pages, of which 2 are plain forms.

### Option C: full server-side rendering in Kotlin

Not practical: the shell is a large set of Svelte components. It is only reasonable for the three small pages (`/login`, `/logout`, the `/s/[token]` head and body).

## Surprises

- **`/` redirects to `/login` with no `?redirectTo=`.** Only non-root targets get the query (`guard.ts:68`).
- **The 429 login answer has no `Retry-After` header,** although the domain computes `retryAfterS` (`login.ts:105-114`, `login/+page.server.ts:36`).
- **A form POST with no `Origin` header is refused with 403** by kit's CSRF check. This covers `curl` POSTs to `/login`, `/logout`, and any `/api/*` call sent with form or `text/plain` bodies (`respond.js:81-97`).
- **The root layout ignores bearer tokens.** A device key that passes the hook on a page still gets sent to `/login` (`+layout.server.ts:19-21` vs `handle.ts:45-47`).
- **Unknown paths, including unknown `/api/*`, are not 401 without a session.** The hook skips paths that match no route, and the layout then 303s them to login (`handle.ts:43`, `respond_with_error.js:114-115`).
- **`/apps/[view]` without a cookie 303s to login, not 401.** The route is outside `/api`.
- **Hovering a sidebar project link runs a full snapshot read** through kit's `__data.json` preload (`app.html:88`).
- **Every share GET counts as a view, crawler unfurls included** (`shares.ts:238`). The 404 response has no `x-robots-tag`.
- **The service worker does no caching** (`service-worker.ts:12-18`).
- **No static file except `/_app/immutable/**` sends `Cache-Control`.** That includes `service-worker.js` and the manifest.
- **The `authenticated` value from the layout is unused.**
- **`Sign out` is a link to a confirmation page, not a direct POST.**

## Parity test notes

Black-box checks to run against both servers:

1. `GET /` with no cookie returns `303` with `location: /login` and an empty body.
2. `GET /projects/acme` with no cookie returns `303` with `location: /login?redirectTo=%2Fprojects%2Facme`.
3. `GET /?focus=x` with no cookie returns `303` with `location: /login?redirectTo=%2F%3Ffocus%3Dx`.
4. `GET /nope` with no cookie returns `303` to `/login?redirectTo=%2Fnope`. With a cookie it returns `404` HTML containing `<h1>404</h1>` and `Not Found`.
5. `GET /projects/acme/` returns `308` with `location: /projects/acme`, with or without a cookie.
6. `GET /login` with a valid cookie returns `303` to `/`. With `?redirectTo=/projects/x` it goes to `/projects/x`. With `?redirectTo=//evil.com` it goes to `/`.
7. `POST /login` (form-urlencoded, `Origin` = the server origin):
   - right password: `303` to the sanitised `redirectTo`, with `set-cookie: ad_session=<payload>.<sig>; Max-Age=2592000; Path=/; HttpOnly; Secure; SameSite=Lax`;
   - wrong password: `400` HTML containing `That password is not right.` and no cookie;
   - empty password: `400` `Enter your password.`, and the attempt does not count;
   - after 5 wrong attempts from one address within 15 min: `429` `Too many attempts. Try again later.`, also for the right password, with no `Retry-After`;
   - no valid secrets: `503`.
8. `POST /login` with no `Origin` header returns `403` text `Cross-site POST form submissions are forbidden`.
9. `GET /logout` with no cookie returns `303` to `/login`; with a cookie, `200` HTML containing a POST form.
10. `POST /logout` (with `Origin`) returns `303` to `/login` and clears the cookie (`ad_session=` with an expiry in the past, same attributes).
11. `GET /s/<valid>` returns `200` HTML with:
    - headers `cache-control: no-store` and `x-robots-tag: noindex, nofollow`;
    - `og:title`, `og:description`, `og:url = <PUBLIC_BASE_URL>/s/<token>` in the head;
    - `og:image` an absolute `/s/<token>/media/<id>/<variant>` when an image is ready;
    - and the share's view count goes up by 1.
12. `GET /s/<bad>`, a revoked share or a deleted card each return `404` HTML containing `not found`, with no redirect to `/login`.
13. Static caching:
    - `GET /_app/immutable/<any built file>` returns `cache-control: public,max-age=31536000,immutable`, a weak `etag`, and `content-encoding: br` when `Accept-Encoding: br` is sent;
    - `GET /manifest.webmanifest` and `/service-worker.js` return no `cache-control`, with `etag` and `last-modified`;
    - `GET /_app/missing.js` returns `404` with `cache-control: public, max-age=0, must-revalidate`.
14. With a cookie, `GET /` returns `200` with `x-sveltekit-page: true` and an `etag`. A repeat request with `If-None-Match` returns `304`. The embedded snapshot equals `GET /api/snapshot?limit=50`, ignoring `seq` and `at`.
15. `GET /apps/<view>` with no cookie returns `303` to `/login?redirectTo=%2Fapps%2F<view>`. With a cookie and an unknown view: `404` `{"error":"not_found"}`.
16. Every server-rendered page body contains `navigator.serviceWorker.register` and the `AgentsiOS` UA check, and has no CSP header.
