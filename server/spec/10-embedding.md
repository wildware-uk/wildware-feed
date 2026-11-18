# 10 — Embedding concerns

The Kotlin server will be a library the desktop app can run inside itself ("standalone": agents on
that machine and the app all talk to it), as well as a standalone server like today. This lists
everything today's server needs beyond plain code, and what an embedded desktop host would do
about each.

Legend for "Embedded host": **bundle** (ship it inside the app), **JVM** (a pure-Java/Kotlin
replacement exists, nothing to ship), **generate** (make it on first run and keep it), **optional**
(the feature turns off without it), **n/a** (does not apply on a desktop).

## Native code and external programs

| Today | What it does | Embedded host |
| --- | --- | --- |
| `better-sqlite3` (native addon, `package.json`) | The database (06) | **JVM**: `org.xerial:sqlite-jdbc` carries its own native SQLite for Mac (x64/arm64), Windows and Linux inside the jar. Same file format; WAL works. The parity suite already uses it. |
| `argon2` (native addon) | Owner password hash, argon2id m=65536 t=3 p=4 (01) | **JVM**: Bouncy Castle `Argon2BytesGenerator` (pure Java). Must read and write the `$argon2id$v=19$m=65536,p=4,t=3$...` string exactly — note the TS library writes `p` before `t`. |
| `sharp` (native, bundles libvips) | Image thumbnails `thumb-640`/`thumb-1600` as WebP, size reading, EXIF rotation (`src/media/derive.ts`, 07 §5) | **Bundle or optional.** No pure-JVM WebP *encoder* of the same quality; options: bundle libvips/libwebp per platform, use a JNI WebP encoder (e.g. a webp-imageio build), or emit a different thumbnail format in the embedded build. Thumbnail *names* are part of the contract; their encoding is what clients render, so a JPEG thumbnail under the same name would work in browsers and the app but changes `content-type`. Decide before building. |
| `ffmpeg` / `ffprobe` (system programs, spawned by `src/media/ffmpeg.ts:30-31,137-149`) | Probe video/audio, take the `poster` frame, transcode `.mov`/non-h264 to the `video` mp4 (07 §5) | **Optional, or bundle.** Today a missing ffmpeg fails just that media item (`… (is it installed?)`, 07 §5) rather than the server. Embedded: look for `ffmpeg` on `PATH`, allow a configured path, or bundle a static build per platform (~80 MB each; licence: LGPL build only). Images and files work without it. |
| `file-type` (pure JS) | Sniffs upload bytes against the allowlist (07 §2) | **JVM**: reimplement the magic-number checks for the allowlist only (PNG/APNG, JPEG, WebP, GIF, MP4/MOV/WebM, audio types). The exact sniff results are part of the 415 messages. |
| `web-push` (pure JS, Node crypto) | Web Push with VAPID (08) | **JVM**: ECDH P-256 + AES-128-GCM (RFC 8291) and ES256 JWT, all in the JDK. **Optional** on a desktop: Web Push needs browsers subscribed to a public origin. |
| `node:http2` to `api.push.apple.com` / `api.sandbox.push.apple.com` (`src/domain/apns.ts:18,68-69`) | APNs alerts and Live Activity (08) | **JVM**: JDK `HttpClient` speaks HTTP/2. **Optional**: needs the owner's Apple `.p8` key, which a desktop user won't have; the embedded host normally leaves APNs off (the phone then relies on its own connection to the server). |
| `@modelcontextprotocol/sdk` | MCP over Streamable HTTP (05) | **JVM**: the Kotlin MCP SDK, or a small hand-written JSON-RPC handler — the server is stateless with JSON responses only, so the surface is small. The SDK's exact error texts (`MCP error -32602: …`, zod wording) are part of the contract (05 §1.3, parity suite). |
| `markdown-it`, `highlight.js` | Server-side markdown for the share page and plain-text excerpts (`src/domain/plain-text.ts`, `src/web/markdown.ts`) | **JVM**: a CommonMark library; or keep the share page client-rendered. Excerpt text feeds push bodies and `og:description`, so its rules matter (09). |
| `ulid` | ULID ids (monotonic within a millisecond) | **JVM**: small, write it; must stay Crockford base32, 26 chars, monotonic (06). |
| SvelteKit + adapter-node | Serves pages, CSRF check, 405s, body limit, compression hook (01, 09) | **JVM**: Ktor serves the built static SPA (09 explains which pages need server data). The SvelteKit behaviours clients see (CSRF 403, 405 + `Allow`, trailing-slash 308) must be reproduced by hand. |

## Files and directories

| Today | Embedded host |
| --- | --- |
| `DATA_DIR` (default `data`, relative to the working directory) holds `agent-dashboard.db` (+ `-wal`, `-shm`), `media/<id[0:2]>/<id>/…`, `tmp/uploads/*.part` (06, 07 §6) | **Generate**: the platform's app-data folder (macOS `~/Library/Application Support/<app>`, Windows `%APPDATA%\<app>`, Linux `$XDG_DATA_HOME/<app>`), created on first run. Never the install folder. |
| One process owns the file; the TS server is safe only because it has one connection doing one thing at a time (06 Surprises) | The embedded server must be the only writer. If the desktop app can also *connect* to a remote server, the local database is simply unused then. Two app instances must not both host: take a lock file in `DATA_DIR`. |
| Migrations run at open and refuse unknown newer versions (06) | Same. An older desktop build opening a newer database must fail with a clear message, not corrupt it. |
| `.env` or real environment variables (01) | **Generate**: a settings file in `DATA_DIR`, written on first run. |

## Secrets and settings needed at boot (01)

| Setting | Today | Embedded host |
| --- | --- | --- |
| `ADMIN_PASSWORD_HASH` | Required; made with `cli hash-password` | **Generate** from a password the user picks at first run — or, for a purely local server, skip owner login for the app itself (it holds a device key, below) and only ask for a password when the user turns on access from other devices. |
| `SESSION_SECRET`, `TOKEN_SECRET` (≥32 chars) | Required | **Generate** 32 random bytes each, first run, stored in the settings file. Rotating `TOKEN_SECRET` invalidates every agent token, device key, upload and share link (01), so never regenerate. |
| `PUBLIC_BASE_URL` | Default `http://localhost:5173`; must be what agents can reach, because upload and media URLs handed to agents are built from it | **Generate**: `http://127.0.0.1:<port>` for a local-only server. If the user opens it to the network, it becomes that address. |
| `PORT` / `HOST` | adapter-node; default `3000` / `0.0.0.0` | Bind **127.0.0.1** by default (local agents only); pick a fixed default port with fallback when taken, and record the chosen port in the settings file so agents' config stays valid. |
| `ORIGIN` | CSRF origin; without it the server assumes `https` | Derive from `PUBLIC_BASE_URL`. |
| `BODY_SIZE_LIMIT` | Must be ≥ the largest upload cap or uploads break | Not an env var any more: set from the caps. |
| `MAX_IMAGE_BYTES`, `MAX_VIDEO_BYTES`, `MAX_FILE_BYTES`, `HOLD_S` | Defaults 10 MiB / 200 MiB / 50 MiB / 55 s | Defaults; settings file may override. |
| `VAPID_*`, `APNS_*` | Optional | Optional, off by default. (Decided fix 6: a VAPID subject that would sign with `http://` must refuse to boot, not silently delete subscriptions.) |

## Network assumptions

| Today | Embedded host |
| --- | --- |
| Behind a TLS reverse proxy; `ADDRESS_HEADER=X-Forwarded-For`, `XFF_DEPTH=1`; boot refuses an `https` base URL without them unless `TRUST_SOCKET_ADDRESS` (01) | **n/a** locally: no proxy, plain `http://127.0.0.1`, the socket address is the client. The cookie is marked `Secure`, which browsers still accept on `http://localhost`/`127.0.0.1` but not on a LAN IP over plain http — opening the embedded server to a LAN needs TLS or a non-`Secure` cookie option. |
| The login limiter keys on the client address (5 failures → 15-minute lockout) | Locally every client is `127.0.0.1`, so one bad agent script could lock the owner out. Exempt loopback, or key by something else, in the embedded host. |
| SSE behind nginx needs `proxy_buffering off` (`x-accel-buffering: no`) | n/a locally; keep the header. |

## Process and service

| Today | Embedded host |
| --- | --- |
| A service manager runs `node build` and restarts it | The desktop app's own lifecycle: start the server when the app starts in standalone mode; keep it running when the window closes (tray) if agents should still reach it. Agents and the channel bridge reconnect on their own (`retry: 2000`). |
| Timers start on boot: request expiry sweeper (30 s), media sweeper (15 min), presence sweeper, push senders (08) | Same, started with the embedded server and stopped with it. |
| Restart resets the event cursor and every rate limiter (04) | Same; decided fix 3 (boot id) makes restarts visible to clients, which matters more on a desktop that sleeps and quits. |
| CLI `node build/cli.js` for `hash-password`, `mint-token`, `list-tokens`, `rename-token`, `revoke-token`, `vapid-keys`, `backup` (01) | The app's settings screen does these. The parity suite still needs `mint-token` and `revoke-token` as commands (`PARITY_CLI`), so ship them as a small CLI entry point too. |

## How agents find and authenticate to it

| Today | Embedded host |
| --- | --- |
| Claude Code plugin `plugins/agent-dashboard`: `userConfig.dashboard_url` (origin, no trailing slash), `agent_token` (from `mint-token`), `projects` (channel scope) (`.claude-plugin/plugin.json`) | The app mints a token per machine and shows (or writes) the plugin settings: `dashboard_url = http://127.0.0.1:<port>`, `agent_token = <token>`. A "connect Claude Code" button that writes them is the friendly version. |
| MCP server: `type: http`, `url: <dashboard_url>/mcp`, header `Authorization: Bearer <agent_token>` (`plugins/agent-dashboard/.mcp.json`) | Unchanged — same path, same header. |
| Channel bridge and monitor are Node scripts in the plugin (`bin/channel.mjs`, `bin/monitor.mjs`) using `GET /api/agent/stream` (04 §4) | Unchanged: they run beside the agent, not in the server, and need only the URL and token. They do need Node on the agent's machine, which Claude Code already has. |
| Upload URLs and media URLs handed to agents are absolute, built from `PUBLIC_BASE_URL` (07) | Must be an address the agent can reach: `127.0.0.1` works for agents on the same machine only. |
| The native app signs in with the password (`POST /api/devices/sign-in`) and keeps a device key (01, 03) | The desktop app hosting the server can mint its own device key directly at first run (no password round-trip); phones on the same network sign in as today once the server is reachable from them. |

## Summary: what the embedded build must ship or decide

1. Ship: sqlite-jdbc (native inside the jar), the static web build.
2. Decide: WebP thumbnails (bundle a native encoder, or change format) — the only media piece with no
   pure-JVM equivalent.
3. Optional: ffmpeg (find on PATH, or bundle), Web Push, APNs.
4. Generate at first run: data folder, secrets, port, base URL, owner password (or local-only mode),
   the app's own device key, a token for local agents.
5. Change for local use: bind loopback, exempt loopback from the login lockout, lock `DATA_DIR` against
   a second host.
