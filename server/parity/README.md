# Parity suite

Black-box tests that any Wildware Feed server must pass: today's TypeScript server, and the Kotlin
one that will replace it. They talk to a running server over HTTP only — the owner API, the live
streams, MCP and the website — and check the behaviour written down in `../spec/`.

## Run it against the TypeScript server

```sh
server/parity/run-against-ts.sh
```

That clones `wildware-uk/agent-dashboard` at the pinned commit, builds it, starts it on port 18110
with a fresh temporary data directory and fresh secrets, runs the suite, and stops it. Useful
variables:

- `TS_SERVER_DIR=/path/to/checkout` — use an existing checkout instead of cloning (it refuses to
  build in one that has `.local/`, which is a live deployment).
- `SKIP_BUILD=1` — use that checkout's existing `build/`.
- `PARITY_PORT=18120` — another port.
- Anything after the options goes to Gradle, e.g. `--tests '*StreamTest*'`.

## Run it against the Kotlin server

```sh
server/parity/run-against-kotlin.sh
```

It builds both servers, starts the TypeScript one on a fresh data folder, builds a rich database
through its API and records every read (`ReadParityTest`, record mode), stops it, then starts the
Kotlin server on the same folder with the same secrets and runs the test classes for the phases the
Kotlin server has reached, plus `ReadParityTest` in compare mode. `KOTLIN_TESTS` and
`KOTLIN_EXCLUDE_TAGS` change what runs (see the script). It takes the same `TS_SERVER_DIR`,
`SKIP_BUILD` and `PARITY_PORT` as the TypeScript runner.

## Run it against any server

```sh
PARITY_BASE_URL=http://127.0.0.1:18110 \
PARITY_ADMIN_PASSWORD='the owner password' \
PARITY_CLI='/path/to/run-the-server-cli.sh' \
./gradlew --no-daemon :server-parity:test
```

- `PARITY_ADMIN_PASSWORD` is the password whose argon2id hash the server was started with.
- `PARITY_CLI` runs the server's admin CLI against the same data. The suite calls
  `$PARITY_CLI mint-token <name>` (and reads the 43-character token from its output) and
  `$PARITY_CLI revoke-token <agent id>`. Agents can only be made this way, so the Kotlin server needs
  the same two commands.
- `PARITY_SKIP_LOCKOUT=1` skips the login-lockout test, for a server you can't restart.
- `PARITY_SERVER=kotlin` for the Kotlin server (see tools-list below); unset means the TS server.
- `PARITY_DATA_DIR` is the server's data directory. Only `DatabaseTest` uses it, read-only; without it
  those checks are skipped.
- `PARITY_REQUIRE_FIXES=1` makes the decided-fix tests required (see below).
- `PARITY_EXCLUDE_TAGS=media,...` skips tests tagged for parts a server has not built yet.
- `PARITY_READS=record|compare` and `PARITY_READS_FILE` drive `ReadParityTest`; without them it is skipped.

Without these variables every test is skipped, so a plain `./gradlew build` stays green.

## Things to know

- **Use a throwaway server.** The suite writes a lot (projects, agents, uploads), and its last class,
  `RateLimitTest`, locks the login for this machine's address for 15 minutes.
- Tests make unique names, so they don't need an empty database and don't collide with each other.
- The shared agents post faster than one token's 120 requests a minute, so the client waits out a
  429 as a real agent should (`Agent.rpc`).
- **Decided fixes.** Tests wrapped in `pendingFix(n) { … }` state behaviour that was decided as a fix
  (table in `../spec/README.md`). On a server without the fix they are skipped with the reason; with
  `PARITY_REQUIRE_FIXES=1` they must pass. Once both servers have a fix, remove the wrapper.
- Some tests pin behaviour the spec calls a surprise (they say "spec surprise" in a message). If the
  Kotlin server fixes one, fix the TypeScript server or change the test on purpose — don't let them
  drift.
- `src/test/resources/tools-list.json` is the TS server's exact MCP `tools/list` payload. Agents read
  those descriptions, so a server must return exactly that, as JSON. The TS server is retired, so the
  Kotlin server's descriptions may move on, but only through `src/test/resources/tools-list-kotlin-changes.json`:
  each entry names a tool and why, and either adds a whole new tool (`add`, at the end of the list),
  or, at a path inside an existing tool, swaps one exact piece of text or replaces one value. With `PARITY_SERVER=kotlin` (set by `run-against-kotlin.sh`) the suite expects
  the TS payload with those changes applied, nothing more, so any other difference still fails. The
  Kotlin server serves `server/src/main/resources/mcp/tools-list.json`; change the two together.
- The Kotlin server's plain `/mcp` lists only the three discovery tools (spec/05 §1.4). The tests
  that check the whole list ask `/mcp?tools=all`, which the TS server ignores.
- `ReadParityTest` compares reads byte for byte. Keys the Kotlin server adds on purpose (a channel's
  `groupId`, the snapshot's `groups`) are listed, each with why, in its `kotlinAdditions`; each pattern
  matches only the empty value a TS-built database has.
- A few other checks expect a different message from the Kotlin server where it accepts more (a
  `critical` priority, say); they ask `Parity.kotlin`.
- Video tests need `ffmpeg` on the machine running the tests (they are skipped without it).

## What it covers

| Class | Area |
| --- | --- |
| `AuthTest` | Login form, cookies, CSRF, logout, device sign-in and keys, MCP token errors |
| `FeedApiTest` | Projects, themes, boards, messages and threads, reactions, card status/pin/tags/fields, updates, agent rename |
| `RequestsTest` | `request_input` of every kind, answer rules, dismiss, expire, withdraw, edit, option pictures, summary, bell |
| `McpTransportTest`, `McpToolsListTest`, `McpToolsTest` | Streamable HTTP envelope, the exact tool list, every tool |
| `StreamTest` | Owner and agent event streams: framing, heartbeats, resume, resync, presence, delivery |
| `MediaTest` | Uploads, derivatives, serving with ranges and ETags, agent fetch, share links, owner upload |
| `DevicesTest` | Devices, APNs and Live Activity tokens, alert projects, Web Push settings, notifications |
| `WebTest` | Page redirects, 404s, share page previews, asset caching, compression |
| `DatabaseTest` | The migration table, and indexes only the schema can show (needs `PARITY_DATA_DIR`) |
| `ReadParityTest` | Every read endpoint, recorded from one server and replayed against another on the same database |
| `RateLimitTest` | MCP, upload and login limiters (runs last) |
