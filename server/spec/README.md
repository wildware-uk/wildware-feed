# Server spec

What today's TypeScript server does, exactly, so a Kotlin (Ktor) server can replace it without
anyone noticing: same HTTP API, same live streams, same MCP tools, same SQLite file, same secrets.

**Source of truth:** `wildware-uk/agent-dashboard` as of 2026-11-18, before the fixes below, which is what was
deployed when this was written (its `src/` is identical to the deployed tree). Later commits there
carry the decided fixes below.

Every claim cites `src/path.ts:line` in that tree. Each file ends with **Surprises** (bugs, traps,
inconsistencies — encoded as they are, not fixed) and **Parity test notes** (black-box checks).

## Scope of the Kotlin server

The website is deprecated (not to be used or worked on). The Kotlin server ports what the native app
and agents use: the owner JSON API (device sign-in and device keys), the live streams, MCP, uploads,
media, iOS push (APNs and Live Activities) and the background jobs. It does **not** port the web pages, the
`/login`/`/logout` forms, the session cookie, the static site and service worker, or SvelteKit's
browser-only behaviours (the CSRF form check, 303 redirects to `/login`); where the TS server would
redirect a stranger, the Kotlin server answers `401 {"error":"unauthenticated"}`. Share links are
dropped too: no `/s/[token]` page, no `/s/[token]/media/…`, no `POST|DELETE /api/updates/[id]/share`
(an owner gets the 404 JSON of an unknown API path), and an update in the snapshot never carries a
`share` key, even when the database holds a live share from the TS server. No MCP tool makes share
links, so `tools/list` is the same on both servers. `09-web-frontend.md`
and the cookie and form parts of `01-config-and-auth.md` describe the TS server only. Parity tests
for those parts are tagged `tsOnly`.

## Files

| File | Covers |
| --- | --- |
| [01-config-and-auth.md](01-config-and-auth.md) | Env vars and boot checks, the request pipeline (`hooks.server.ts`), CSRF, compression, owner password (argon2id), session cookie byte format, device keys, agent tokens, upload/share token formats, rate limits, the admin CLI |
| [02-owner-api-feed.md](02-owner-api-feed.md) | Owner API for the feed: projects, updates, messages and threads, reactions, cards (pin, work status, tags, fields), tags, agent rename; the domain rules behind them (slugs, mentions, emoji, threading) |
| [03-owner-api-requests-devices.md](03-owner-api-requests-devices.md) | Requests (`request_input`): kinds, states, answer rules, expiry; devices and sign-in; Web Push settings; notifications (the bell); `/api/summary`; apps; every snapshot shape |
| [04-stream-and-events.md](04-stream-and-events.md) | The event bus and replay ring; every event type and who publishes it; `GET /api/stream` (owner) and `GET /api/agent/stream` (agents) byte framing, resume, `resync`, heartbeats |
| [05-mcp.md](05-mcp.md) | MCP over Streamable HTTP (stateless, JSON responses); auth; long-poll holds; all 31 tools with errors, side effects and events. Appendix A is the exact `tools/list` payload (also in `../parity/src/test/resources/tools-list.json`) |
| [06-database.md](06-database.md) | How the SQLite file is opened, the migration table and checksums, the full schema after migration 045 (verbatim DDL), what each of the 45 migrations did |
| [07-media-uploads-shares.md](07-media-uploads-shares.md) | Upload tokens and `PUT /api/upload/:token`, sniffing and limits, the derive pipeline (sharp, ffmpeg), files on disk, `/media/:id/:variant` with ranges and ETags, agent media fetch, share links and `/s/:token`, the media sweeper |
| [08-jobs-push-presence.md](08-jobs-push-presence.md) | Every timer and background job, presence and sessions, notifications, Web Push (VAPID), APNs alerts, Live Activity content state |
| [09-web-frontend.md](09-web-frontend.md) | The five SvelteKit pages, their server loads and form actions, static assets, the service worker, what the browser calls, and options for serving the site from Kotlin |
| [10-embedding.md](10-embedding.md) | What the server needs beyond plain code (native modules, ffmpeg, files, secrets, ports, proxy, service, agent setup) and what a desktop app hosting it would do about each |
| [11-oauth.md](11-oauth.md) | OAuth 2.1 for MCP clients (Kotlin only): discovery metadata, client registration, the authorize page, tokens and refresh, and how an access token stands in for an agent token |
| [14-feedback.md](14-feedback.md) | Feedback and feature requests (migration 067): people send, agents propose and their team approves, the admin API and the operator's channel |
| [12-channels-and-projects.md](12-channels-and-projects.md) | Channels and projects (groups of channels), following and reach, the owner adding and removing agents |
| [13-people.md](13-people.md) | People on a team: roles, invites, sign-in, alerts per person |
| [15-permissions.md](15-permissions.md) | Per channel and per project permissions: levels, private channels, where they are enforced, the Permissions panel API, the audit log |
| [16-assigning-work.md](16-assigning-work.md) | Assigning work (migration 073): up to five people or agents on a work card, who may assign, the thread line that tells an agent, the bell and phone for a person, list_work and board filters |

## Rules that cut across every file

- **Key order is part of the wire format.** Clients don't care, but the parity suite compares some
  bodies byte for byte, and the spec records key order wherever the TS server has a fixed one.
- **Two naming and time styles.** The owner API (`/api/*`) is camelCase with times as epoch
  milliseconds (`createdAt: 1795010600953`). MCP results are snake_case with ISO strings
  (`created_at: "2026-11-18T14:00:41.720Z"`), except `list_apps`/`set_app_data` `updated_at`,
  which is a number. ISO strings always have three millisecond digits — `Instant.toString()` drops
  them when they are zero, so the Kotlin server needs its own formatter.
- **One error envelope** for owner routes: `{"error":"<code>","message":"<text>"}` with codes
  `invalid_argument` (400), `not_found` (404), `conflict` (409), `unauthenticated` (401),
  `rate_limited` (429). MCP tool failures are `isError: true` results whose text is
  `<code>: <message>`; argument-schema failures come from the MCP SDK as `MCP error -32602: …`
  with zod's wording, which the Kotlin server must reproduce.
- **SvelteKit behaviour is part of the contract**: 405 with `allow` for unexported methods, the
  CSRF rule that refuses cross-origin `text/plain`/form/multipart POST/PUT/PATCH/DELETE with 403
  before any route runs, form actions answering JSON when `Accept` is not HTML, trailing-slash
  handling. See 01 §CSRF and 09.
- **Text rules use JavaScript semantics** (`trim()`, `\s`, `\w`, `localeCompare`, UTF-16 lengths
  for `maxLength`). Kotlin's defaults differ; use explicit character sets.
- **State that lives only in memory** and resets on restart: the event sequence and replay ring,
  every rate limiter, presence of open streams. A restart is visible to clients (see 04).

## Surprises, and what was decided

Each file's **Surprises** section lists what looks like a bug. The twelve that matter most were
decided on 2026-11-18. **Keep** means the parity suite pins today's behaviour. **Fix** means the TS
server was changed and the suite now requires the fixed behaviour; the area files above describe
the server before these fixes, so where they disagree this table wins. (A future decided fix can be
written as `pendingFix(n) { … }` first: skipped on a server that lacks it, required with
`PARITY_REQUIRE_FIXES=1`.)

| # | Surprise | Decision | State |
| --- | --- | --- | --- |
| 1 | Each migration's SQL text is checksummed (06) | **Keep**: the Kotlin server ships byte-identical SQL for every migration, and any new one is the same in both servers | pinned (`DatabaseTest`) |
| 2 | `notification.created` (N+1) reached live streams before the `update.created` (N) that caused it (04, 08) | **Fix**: live events in seq order | fixed, required |
| 3 | The event seq restarts at 1, so a cursor from before a restart silently missed events (04) | **Fix**: every connection opens with `event: hello` naming a boot id; ids are `<boot>-<seq>`; a cursor naming another boot gets `resync` with reason `restarted`. A bare `<seq>` cursor still works within one boot | fixed, required |
| 4 | Answering a request that just expired marks it timed out, tells the agent, then answers 409 (03) | **Keep** | pinned |
| 5 | Migration 038 lost the `media_request` and `media_task` indexes (06) | **Fix**: migration 046 recreates them, same SQL in both servers | fixed, required (`DatabaseTest`) |
| 6 | With `VAPID_SUBJECT` unset and an `http://` base URL every push fails and ten failures delete the subscription (08) | **Fix**: refuse to boot | fixed; not black-box testable by the suite (needs a boot with that config) |
| 7 | A failed upload (413, 415, empty body, 500) spent the upload link (07) | **Fix**: only a successful upload spends it | fixed, required |
| 8 | The app still sends the removed `task` card kind (02) | App bug, fixed in the app | — |
| 9 | `POST /api/notifications/seen` with a non-list `ids` cleared everything (03) | **Fix**: 400 `ids must be a list of notification ids, or left out to clear them all` | fixed, required |
| 10 | A brand-new agent (no projects yet) is counted against the whole deployment (04, 05) | **Keep**: intended, so a new agent is not deaf to its first instruction | pinned |
| 11 | Revoking a token or device key left its open streams running (04) | **Fix**: revocation closes them | fixed (closes within about 5 s), required |
| 12 | (a) Page routes ignore device keys; (b) unknown `/api/*` paths redirected to `/login` (01, 09) | (a) **Keep**. (b) **Fix**: 401 `{"error":"unauthenticated"}` signed out, 404 `{"error":"not_found","message":"no such API route: <path>"}` signed in | (b) fixed, required |
| 13 | `delete_message` on an already-deleted message succeeded for anyone and handed back its text (05 surprise 15) | **Fix**: who may unsend is checked first; only they get the quiet success | fixed in the Kotlin server; parity `pendingFix(13)` |
| 14 | `get_card_fields` read a deleted card's fields (05 surprise 17) | **Fix**: a deleted card has no fields, as an unknown one has none | fixed in the Kotlin server; parity `pendingFix(14)` |

Note for the app: since fix 3 the stream `id:` is `<boot>-<seq>`. `LiveStream.kt` falls back to the
envelope's `seq` when the id is not a number, so it keeps working, but it sends a bare seq on
reconnect and so never hears `restarted`. It should send the id back as it came.

## Which server the suite runs against

The CI job (`server-parity` in `.github/workflows/build.yml`) clones `wildware-uk/agent-dashboard` at
the commit pinned in `server/parity/run-against-ts.sh` (`TS_SERVER_REF`), builds it and runs the
suite: all 114 tests pass against the pinned commit, which has every decided fix. Move the pin
forward when the TS server changes.

## Known differences in the Kotlin server

Places where the Kotlin server cannot match the TS server exactly, kept small and each pinned by a test.

- **Sorting names** (`/api/summary` projects): the TS server uses JavaScript's `localeCompare` (ICU).
  The Kotlin server uses the JDK's collator with special cases for spaces and punctuation, symbols
  and emoji, letters with a stroke, and compatibility characters, so realistic names sort the same
  (`LocaleCompareTest`). One tie still differs: names equal except for case and `ß`/`ss` sort
  `strasse` < `straße` < `Strasse` in Kotlin and `strasse` < `Strasse` < `straße` in Node.

- **Thumbnails** are WebP at quality 80, but made by ffmpeg's libwebp rather than sharp, so their
  bytes differ; widths, heights, variants and types match. Without ffmpeg an image is `ready` with
  only `original`. An animated WebP original fails to derive (ffmpeg cannot decode it).
- **Notification text** (iOS alert bodies) is made from markdown with commonmark-java instead of
  markdown-it. Ordinary markdown gives the same text; unusual constructs may differ.

Deliberate differences, by decision rather than by limit (see "Scope of the Kotlin server" above):

- **No website.** No pages, `/login`, `/logout`, session cookie or CSRF form check; strangers get
  `401 {"error":"unauthenticated"}` instead of a redirect. Tests tagged `tsOnly`.
- **No Web Push.** Only the website subscribed to it. No `/api/push` routes, no `VAPID_*` settings
  (they are ignored, so decided fix 6's boot check is gone too), nothing sent. DevicesTest's web-push
  test is tagged `tsOnly`. iOS alerts and Live Activities (APNs) are kept.
- **Sound and video names.** An upload's filename is kept for kinds `audio` and `video` as well as
  `file` (the TS server kept it for `file` only), so the apps can name each of several sounds. Only
  `image` still drops it. Pinned by `UploadsTest`; no parity test reads a sound's or video's name.
- **No share links.** No `/s/...` routes, no `POST|DELETE /api/updates/[id]/share`, no `share` key on
  updates. `MediaTest` 16 is tagged `tsOnly`.

## The parity suite

`server/parity/` (Gradle module `:server-parity`) runs these checks against any running server. See
[../parity/README.md](../parity/README.md).
