# Kotlin server plan

Replace the TypeScript server (`wildware-uk/agent-dashboard`) with a Kotlin (Ktor) one that is a
drop-in: same HTTP API, streams, MCP tools, SQLite file and secrets. It is also a library the
desktop app can host itself (`spec/10-embedding.md`). The contract is `spec/`; the referee is the
parity suite in `parity/`, run against both servers in CI.

## Scope

The website is deprecated: it is not ported and not worked on. The Kotlin server serves what the
native app and agents use — the owner JSON API with device sign-in and device keys, the live streams,
MCP, uploads and media, push, and the background jobs. Not ported: the web pages, the
`/login` and `/logout` forms, the session cookie, the static site and service worker, and SvelteKit's
browser-only behaviours (the CSRF form check, redirects to `/login`), and share links: with no
`/s/[token]` page to open, a link is pointless, so their owner routes and the `share` key on updates go
too. Web Push goes as well: only the website subscribed to it. A stranger on an owner path
gets `401 {"error":"unauthenticated"}` everywhere. Parity tests for the website are tagged `tsOnly`.

## Shape

- Gradle module `:server` (this folder): a library (`dev.wildware.feed.server`) plus a standalone
  `main` and a small CLI. Ktor with the CIO engine (pure Kotlin, nothing native to ship),
  kotlinx.serialization, `sqlite-jdbc`, Bouncy Castle for argon2id.
- Layers as in the TS server: `config` → `db` (SQL, rows) → `domain` (rules, errors) → `http`
  (routes, auth, serialisation). The `http` layer never touches SQL.
- One writer: a single SQLite connection used one call at a time, as the TS server does (06).
- Migrations are the TS files' SQL, byte for byte, as resources; checksums are checked the same way,
  so either server can open the other's database.

## Phases

Each phase ends with its parity tests passing against the Kotlin server in CI, beside the TS run.

1. **Boot, auth and reads.** Config and its boot checks; open the database and run migrations
   001–047 with checksums; device sign-in, device keys and the sign-in limiter; agent-token checks on `/mcp` (401s and 429s only); the CLI's `hash-password`,
   `mint-token`, `list-tokens`, `revoke-token`; the read-only owner API (`/api/summary`,
   `/api/snapshot*`, `GET /api/messages`, project tags, devices, notifications, push status).
   Reads are checked differentially: the TS server builds a rich database, every read is recorded,
   then the Kotlin server opens the same file and must answer the same (`ReadParityTest`).
2. **Writes and the live streams.** Every owner write route; the event bus with the replay ring,
   boot id and seq order; `GET /api/stream` and `GET /api/agent/stream`; presence; revocation closes
   streams. Also the MCP `tools/call` dispatcher and the tools the write and stream tests set up with
   (projects, updates, messages, cards, tags, fields, reactions, acks, requests, sessions), because
   only agents can create updates and requests.
3. **The rest of MCP.** The remaining tools (embeds, apps, uploads) and `McpToolsTest`'s exact edge
   cases: the SDK's argument-error wording, every summary line.
4. **Media, push and jobs.** Uploads with sniffing and limits; derivatives (ffmpeg, thumbnails —
   the WebP question in `spec/10-embedding.md`); serving with ranges and ETags;
   sweepers; APNs and Live Activity. Web Push is dropped with the website (nothing else used it).
5. **Side by side** (shadow run). See "Phase 5 detail".
6. **Packaging.** A runnable distribution, a Docker image and a systemd example. See "Phase 6 detail".
7. **Standalone mode.** The desktop app runs the server itself. See "Phase 7 detail".
8. **Cut-over.** Swap the live service to the Kotlin server and keep the TS build ready to swap
   back; the database stays compatible both ways while both servers ship the same migrations.
   Needs the owner's OK. Plan only until then (the runbook is in "Phase 6 detail").

## Phase 5 detail: the shadow run

Goal: real data, real variety (emoji, long threads, every card kind, old rows from early migrations),
both servers, every difference listed.

- **Never touch the live service, its database file or `.local`.** The copy comes from the TS
  server's own `backup` command (SQLite's online backup, which only reads the live file), run by the
  owner or with their OK, into a scratch folder. The media folder is copied read-only beside it.
  Nothing from the copy is committed.
- **Two copies, two servers, loopback only.** Copy A runs a throwaway TS build at the live commit;
  copy B runs the Kotlin server. Each has its own port on 127.0.0.1, a fresh `DATA_DIR` and the
  same throwaway `TOKEN_SECRET` (never the live one). Existing tokens therefore don't verify on
  either copy; the run mints a shadow device key and one shadow agent token in both, through each
  server's own CLI, with the same rows in both.
- **Traffic is scripted, not captured.** Capturing live traffic would mean touching the live proxy,
  and the reads below cover more than a day of real use would. In order:
  1. A read sweep: every owner read route over every project, card, request, tag, field, page and
     cursor the copy holds, plus `/api/summary`, `/api/devices` and `/api/notifications`; each
     answer compared byte for byte. This is `ReadParityTest`'s approach on real data.
  2. The MCP reads per shadow agent: `tools/list`, `list_projects`, `list_work`,
     `get_messages` (`mark_read: false`), `list_tags`, `get_card_fields`, `list_embed_types`, `list_apps`.
  3. A write script run against both in lockstep: post, reply, react, tag, set fields, change
     status, pin, ask and answer a request, upload and attach a picture. Each answer is compared,
     then the read sweep is run again on what changed.
  4. Media: every original byte for byte; thumbnails and posters by type and dimensions (their
     bytes differ by design).
  5. Both live streams are opened during step 3, and their frames are compared after the run.
- **Normalised, and nothing else:** timestamps the servers stamp themselves, boot ids, and ids the
  write script creates (mapped A→B in order).
- **Output:** a report grouped by route: the counts of identical and differing answers, and the
  first few differences of each kind with a one-line diff. It is written to scratch; I send you a
  summary. Each real difference is either fixed in Kotlin, or added to the spec README as a known
  difference after you agree.
- **Tooling:** `server/shadow/` holds a small Kotlin runner (a test-scope main in `:server-parity`,
  reusing its HTTP and SSE helpers) and `shadow-run.sh`. Neither contains paths or data. Gradle
  goes through the slot script.

## Phase 6 detail: packaging and the cut-over runbook

- **Distribution:** the `application` plugin's `installDist`/`distTar` (`bin/feed-server` plus
  `lib/`). It already gives `feed-server` and `feed-server <cli command>`. Add a `jlink` task
  (modules from `jdeps`) for a trimmed Java runtime of about 60 MB, used by the Docker image and the
  desktop bundle.
- **Graceful stop:** on SIGTERM, Ktor stops with a short grace period. Streams close, the bus stops,
  jobs stop, then the database closes. A test checks it stops within 2 s with streams open,
  matching the TS shutdown fix.
- **Docker:** two stages.
  - Build: Gradle `installDist` plus the jlink runtime.
  - Run: `debian:stable-slim` with `ffmpeg` from apt, a non-root user, `DATA_DIR=/data` as a
    volume, and `EXPOSE 3000`.
  - The same environment variables as the TS image, so an existing `.env` works.
  - A `HEALTHCHECK` on a TCP connect (there is no unauthenticated health route, and adding one
    would be a contract change).
  - CI builds the image without pushing it (with `timeout-minutes`).
- **systemd example:** `server/deploy/feed-server.service`:
  - `EnvironmentFile=`, a dedicated user, `Restart=on-failure`, `TimeoutStopSec=10`;
  - hardening: `NoNewPrivileges`, `ProtectSystem=strict`, `ReadWritePaths=<DATA_DIR>`, `PrivateTmp`.

  A README shows install, upgrade and logs. It uses no real paths.
- **Self-updates (an option; not decided).** Nothing updates itself today. Three routes, from
  least to most work. Whichever is chosen, it's off unless the owner turns it on.
  1. **Docker:** publish versioned images (`:1.4.2`, `:1`, `:latest`) from release tags. The owner
     opts in with their own puller (a `docker compose pull && up -d` timer, or Watchtower). No
     server code at all.
  2. **systemd + dist:** a `feed-server update` CLI command and an optional daily timer.
     - It reads the latest GitHub release, the same source and `SHA256SUMS.txt` the desktop app's
       updater already uses (`composeApp/.../update/GitHubReleaseSource.kt`; the release-reading
       part moves into a small shared JVM module rather than being copied).
     - It downloads the dist tarball, checks the SHA-256, and verifies an Ed25519 signature over
       the sums file against a public key built into the binary. A checksum from the same release
       page alone doesn't protect against a tampered release.
     - It unpacks to `versions/<v>`, takes a database backup first, flips the `current` symlink,
       and exits. systemd then restarts it on the new version.
     - Rollback means flipping the symlink back. That works only while the new version added no
       migration; otherwise the old binary refuses the newer database, so the update keeps the
       pre-update backup and prints how to restore it.
     - It refuses to downgrade, and it refuses an update whose release notes say the cut-over or
       a manual step is needed.
  3. **Desktop standalone:** the server is inside the app, so it updates with the app's existing
     updater (download, checksum, install, restart). The embedded server stops gracefully first.
     Same migration caveat, same backup-before-update.

  Recommended: 1 for Docker users, 3 comes free, and 2 only if a non-Docker self-host audience
  shows up. Release signing (a key the owner holds, used in the release workflow) is the one new
  thing it would need, and it would help the desktop updater too.
- **Cut-over runbook (plan only; needs the owner's OK):**
  1. Build and smoke the Kotlin dist on the box.
  2. Take a backup with the TS `backup` command.
  3. Stop the TS service.
  4. Start the Kotlin service on the same `DATA_DIR`, port and env (one restart, batched).
  5. Smoke: the app signs in, `/api/summary` works, an agent's `heartbeat` works, the channel
     bridge reconnects.
  6. Rollback: stop Kotlin, start TS. The database stays compatible while no migration exists in
     only one of them.

## Phase 7 detail: standalone mode in the desktop app

- **One code path:** the desktop app depends on `:server` (desktop/JVM target only). It starts it
  in-process with `App(config, Database.open(dir))` plus `serve(app, wait = false)`, the same entry
  point as `main`. Two things move from global state into `Config`, so an embedded and a
  standalone server can't disagree:
  - `clientAddress()` reads `ADDRESS_HEADER` from the environment today;
  - the limiters.
- **Data folder:** the platform app-data directory:
  - macOS: `~/Library/Application Support/Wildware Feed/server`
  - Windows: `%APPDATA%\Wildware Feed\server`
  - Linux: `$XDG_DATA_HOME/wildware-feed/server`

  It holds the database, media and a `settings.json`. A lock file (`FileChannel.tryLock`) stops a
  second app instance from hosting; that instance tells the user the server is already running.
- **First run:** generate `TOKEN_SECRET` and `SESSION_SECRET` (32 random bytes each, never
  regenerated), pick the port and save it, and set `PUBLIC_BASE_URL=http://127.0.0.1:<port>`.
  - Default port 4317; if it's taken, the next free one, saved so agents' config stays valid.
  - No owner password is needed while the server is loopback-only: the app mints its own device
    key in-process.
- **Loopback only by default:** bind 127.0.0.1. Turning on "Let my phone connect":
  - asks for an owner password (hashed with argon2id),
  - binds 0.0.0.0, and sets the base URL to the machine's LAN address,
  - shows a warning that plain http on a LAN sends device keys in the clear (a VPN like Tailscale
    is the recommended path).
- **The 127.0.0.1 lockout fix:** the sign-in limiter keys on the client address, and locally every
  client is 127.0.0.1, so one bad script would lock the owner out for 15 minutes.
  - Exempt loopback addresses (127.0.0.0/8, ::1) from the limiter when `Config.trustLoopback`
    is set; the embedded host sets it.
  - A standalone server behind a proxy leaves it off, so nothing changes there and parity is
    unaffected.
  - Unit test: five wrong passwords from 127.0.0.1 don't lock 127.0.0.1 when it's set, and still
    do when it isn't.
- **Lifecycle:** the server starts when the app starts in "This computer" mode and keeps running
  while the app sits in the tray. Quitting stops it gracefully; agents and the channel bridge
  reconnect on their own. "Start at login" already exists and covers always-on.
- **Optional pieces:** ffmpeg found on `PATH` or at a path set in settings, and without it images
  keep only `original`; APNs off (it needs the owner's `.p8`).
- **Agents:** Settings → "Connect Claude Code" mints a token for this machine and shows the plugin
  settings (`dashboard_url`, `agent_token`) with copy buttons. Writing the plugin's config file
  directly is a later nicety.
- **Sign-in screen:** two choices.
  - **This computer:** start (or reuse) the local server; no address and no password.
  - **A server:** today's address and password form.

  Settings can switch later. Switching doesn't move data between servers (out of scope). The iOS
  and Android apps are unchanged: a phone signs in to the desktop's server like any server once
  phone access is on.
- **Size:** the app gains sqlite-jdbc (natives for every platform, about 13 MB), Bouncy Castle,
  commonmark and Ktor server, about 25 MB in all.
- **Tests:**
  - desktop: the embedded server starts in a temp folder on a free port, the app's device key
    reads `/api/summary`, and it stops cleanly;
  - first run writes settings once and a second start reuses them;
  - the lock file refuses a second host;
  - the loopback exemption (above).
- **Order:** phase 5 first (it may find real differences), then 6, then 7. Helpers stay at two at
  most, and Gradle goes through the slot script.

## Phase 1 detail

- `run-against-kotlin.sh`: build both servers; start TS on a fresh data folder; seed it and record
  every read (`ReadParityTest` in record mode); stop TS; start Kotlin on the same folder with the
  same secrets; run the auth tests and `ReadParityTest` in compare mode. CI runs it after the TS job.
- Tests that need later phases are tagged and excluded from the Kotlin run until their phase lands.
