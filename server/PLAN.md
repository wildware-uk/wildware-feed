# Kotlin server plan

Replace the TypeScript server (`wildware-uk/agent-dashboard`) with a Kotlin (Ktor) one that is a
drop-in: same HTTP API, streams, MCP tools, SQLite file and secrets. It is also a library the
desktop app can host itself (`spec/10-embedding.md`). The contract is `spec/`; the referee is the
parity suite in `parity/`, run against both servers in CI.

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
   001–046 with checksums; owner login form, session cookie, logout, login limiter; device sign-in
   and device keys; agent-token checks on `/mcp` (401s and 429s only); the CLI's `hash-password`,
   `mint-token`, `list-tokens`, `revoke-token`; the read-only owner API (`/api/summary`,
   `/api/snapshot*`, `GET /api/messages`, project tags, devices, notifications, push status).
   Reads are checked differentially: the TS server builds a rich database, every read is recorded,
   then the Kotlin server opens the same file and must answer the same (`ReadParityTest`).
2. **Writes and the live streams.** Every owner write route; the event bus with the replay ring,
   boot id and seq order; `GET /api/stream` and `GET /api/agent/stream`; presence.
3. **MCP.** Stateless Streamable HTTP; all 31 tools with the exact `tools/list`; the long-poll
   holds; the SDK's error wording.
4. **Media, push and jobs.** Uploads with sniffing and limits; derivatives (ffmpeg, thumbnails —
   the WebP question in `spec/10-embedding.md`); serving with ranges and ETags; share links;
   sweepers; Web Push; APNs and Live Activity.
5. **Web.** Serve the built website (static SPA plus JSON bootstrap, per `spec/09`), the share page
   with its preview tags, compression, the SvelteKit behaviours clients see (CSRF, 405, 308).
6. **Side by side.** Run the Kotlin server on a copy of the live database next to the TS one; replay
   real traffic; compare.
7. **Cut-over.** Swap the service; keep the TS build ready to swap back (the database stays
   compatible both ways while no new migration exists).

## Phase 1 detail

- `run-against-kotlin.sh`: build both servers; start TS on a fresh data folder; seed it and record
  every read (`ReadParityTest` in record mode); stop TS; start Kotlin on the same folder with the
  same secrets; run the auth tests and `ReadParityTest` in compare mode. CI runs it after the TS job.
- Tests that need later phases are tagged and excluded from the Kotlin run until their phase lands.
