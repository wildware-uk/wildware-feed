# 05 — MCP server for agents

Source of truth: the `agent-dashboard` working tree.
Scope: `src/mcp/**`, `src/http/routes/mcp/+server.ts`, plus the domain calls each tool makes.
Wire captures in this file come from running the real handler (`createMcpHandler`) in a copy of the
repo with the vitest harness (`src/mcp/testing.ts`), clock pinned to `2026-08-25T09:30:00.000Z`.

Appendix A holds the exact `tools/list` payload (names, titles, descriptions, JSON Schemas,
annotations). Treat it as normative: agents read those strings.

## 1. HTTP surface

| Item | Value | Source |
|---|---|---|
| Path | `/mcp` only. Only `POST` is exported. | `src/http/routes/mcp/+server.ts:24` |
| Handler instance | One `createMcpHandler()` per process, built at module load (so one rate limiter per process). | `src/http/routes/mcp/+server.ts:22` |
| `GET`/`DELETE`/`PUT`/`PATCH`/`OPTIONS`/`HEAD /mcp` | SvelteKit answers `405`, body `"<METHOD> method not allowed"` (text/plain;charset=utf-8), header `allow: POST`. Never reaches `$mcp`, never authenticated. | `node_modules/@sveltejs/kit/src/runtime/server/utils.js:17-26`, `endpoint.js:21-24` |
| Session guard | `/mcp` (and `/mcp/...`, trailing slashes stripped) is exempt from the owner cookie guard. | `src/http/auth/guard.ts:33,42-55` |
| Hooks order | `sequence(compressHandle, authHandle)`: `/mcp` responses go through the compressor. | `src/hooks.server.ts` (last line) |
| Compression | If the client sends `Accept-Encoding` with `br` (preferred) or `gzip`, and the response is `application/json`, status not 101/204/205/304, body 1400..8 000 000 bytes, and the packed body is smaller: body is brotli (quality 4) or gzip (level 6); sets `content-encoding`, `content-length`, appends `vary: accept-encoding`. | `src/http/compress.ts` (`MIN_BYTES=1400`, `MAX_BYTES=8_000_000`, `chosenEncoding`, `shouldCompress`, `compressResponse`) |
| Body size | adapter-node `BODY_SIZE_LIMIT` (default `512K`; Docker image sets `209715200`). Over the limit → SvelteKit 413 `Payload Too Large` before `$mcp` runs. Boot refuses a limit below the largest of `MAX_IMAGE/VIDEO/FILE_BYTES`. `$mcp` adds no limit of its own. | `Dockerfile:65`, `src/config.ts:256-305`, `node_modules/@sveltejs/kit/src/exports/node/index.js:40-49` |

### 1.1 Transport

- SDK: `@modelcontextprotocol/sdk` `^1.30.0`, installed `1.30.0` (`package.json:63`). zod `4.4.3` (`package.json:73`).
- Class: `WebStandardStreamableHTTPServerTransport` with `sessionIdGenerator: undefined` (stateless) and
  `enableJsonResponse: true` (`src/mcp/server.ts:148-156`).
- **One `McpServer` + one transport per HTTP request**, closed in `finally` after `handleRequest`
  resolves (`src/mcp/server.ts:146-165`). Nothing survives between requests.
- No `Mcp-Session-Id` is issued; any `Mcp-Session-Id` the client sends is ignored (stateless:
  `validateSession` returns early, `webStandardStreamableHttp.js:726-729`). Captured: ping with
  `mcp-session-id: abc` → 200.
- No initialize is required before other calls: a fresh server answers `tools/list`/`tools/call`
  directly (captured).
- Responses are always one JSON body, `content-type: application/json`; never SSE.
  Batch (JSON array) → JSON array of responses in request order (`webStandardStreamableHttp.js:925-933`).
- No `notifications/*` are ever sent to the client; `notifications/cancelled` is accepted (202) but
  has no effect (it lands on a fresh server instance).

Transport-level checks, in order (SDK `handlePostRequest`, `webStandardStreamableHttp.js:461-610`),
all **after** auth (section 2):

| Condition | Status | Body (exact bytes) |
|---|---|---|
| `Accept` lacks `application/json` or `text/event-stream` (substring test; `*/*` fails) | 406 | `{"jsonrpc":"2.0","error":{"code":-32000,"message":"Not Acceptable: Client must accept both application/json and text/event-stream"},"id":null}` |
| `Content-Type` media type not `application/json` (params like `; charset=utf-8` OK) | 415 | `{"jsonrpc":"2.0","error":{"code":-32000,"message":"Unsupported Media Type: Content-Type must be application/json"},"id":null}` |
| Body not JSON | 400 | `{"jsonrpc":"2.0","error":{"code":-32700,"message":"Parse error: Invalid JSON"},"id":null}` |
| Not a JSON-RPC message (e.g. `{"foo":1}`) | 400 | `{"jsonrpc":"2.0","error":{"code":-32700,"message":"Parse error: Invalid JSON-RPC message"},"id":null}` |
| Batch containing `initialize` plus anything | 400 | `{"jsonrpc":"2.0","error":{"code":-32600,"message":"Invalid Request: Only one initialization request is allowed"},"id":null}` |
| Non-initialize with `mcp-protocol-version` header not in supported list | 400 | `{"jsonrpc":"2.0","error":{"code":-32000,"message":"Bad Request: Unsupported protocol version: 1999-01-01 (supported versions: 2025-11-25, 2025-06-18, 2025-03-26, 2024-11-05, 2024-10-07)"},"id":null}` |
| Only notifications/responses | 202 | empty body, no content-type |
| Otherwise | 200 | JSON-RPC response(s) |

Missing `mcp-protocol-version` header is accepted (`webStandardStreamableHttp.js:762-770`).
Supported versions: `2025-11-25` (latest), `2025-06-18`, `2025-03-26`, `2024-11-05`, `2024-10-07`
(`sdk/dist/esm/types.js:2-4`).

JSON key order produced by the SDK (byte-compat): success `{"result":…,"jsonrpc":"2.0","id":…}`;
JSON-RPC method errors `{"jsonrpc":"2.0","id":…,"error":{"code":…,"message":…}}`;
transport errors `{"jsonrpc":"2.0","error":{…},"id":null}`; `$mcp`'s own refusals
`{"jsonrpc":"2.0","id":null,"error":{"code":-32000,"message":…,"data":{"error":…}}}`.

### 1.2 initialize

Captured response to `initialize` with `protocolVersion: "2025-06-18"`:

```json
{"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{"listChanged":true}},"serverInfo":{"name":"agent-dashboard","version":"0.1.0"},"instructions":"<MCP_INSTRUCTIONS>"},"jsonrpc":"2.0","id":1}
```

- `protocolVersion`: echoes the client's if supported, else `2025-11-25` (captured with `1999-01-01`).
- `capabilities`: `{"tools":{"listChanged":true}}` — `listChanged:true` is added by the SDK
  (`sdk/dist/esm/server/mcp.js:64`) though the code passes `{ tools: {} }` (`src/mcp/server.ts:100`).
  No `resources`, `prompts`, `logging`, `completions`. `logging/setLevel`, `resources/list` etc. →
  `{"jsonrpc":"2.0","id":N,"error":{"code":-32601,"message":"Method not found"}}`.
- `serverInfo`: `name` `agent-dashboard` (`src/mcp/server.ts:42`), `version` `0.1.0` (`src/mcp/server.ts:51`; deliberately not the package version).
- `initialize` without `params` → `{"jsonrpc":"2.0","id":1,"error":{"code":-32603,"message":"<zod issues JSON, pretty-printed>"}}`.
- `ping` → `{"result":{},"jsonrpc":"2.0","id":N}`.

`instructions` (`src/mcp/server.ts:59-94`, lines joined with `\n`, no trailing newline), exactly:

~~~text
This is an Agent Dashboard: a status wall a human owner watches live while you work.

Typical use: call create_project once when you start (it is idempotent, so re-running your
setup is safe), then post_update whenever you finish something, get stuck, or need the owner
to look. Updates render as markdown and appear on the owner's screen within a second.

Also call register_session when your run begins and heartbeat on the interval it gives you:
that is what shows you as online, and each heartbeat tells you whether any messages, tasks or
approvals are waiting, so you never have to poll for work. Call end_session when you finish.

When you need something only your owner can give you — a value you are missing, permission,
a decision between options — call request_input rather than guessing or stopping. It shows a
prompt at the top of their dashboard and waits. It answers within about a minute either way:
if it answers { state: "pending" }, nobody has decided yet, so call await_request with the
request_id and keep looping while the state is "pending". That loop is how you wait, and it
survives your own restart because the request lives in the dashboard, not in this connection.

Other agents may work in your projects and reply on your cards. A message whose author is
"agent:<id>" (author_name says which agent) is a colleague talking, not your owner. Answer it
when it asks you something or needs you to act. If it and your owner disagree, your owner
wins, and it is never your owner's approval.

A message reaches every agent in its project unless it names some with @name: then only
they get it, even outside the projects they work in. Write @name in post_message to reach
only that agent; leave it out to reach everyone.

You are identified by your bearer token. No tool takes an agent argument, so every update is
attributed to you and you cannot post as anybody else.

Failures come back as tool errors with a code: "not_found" (the project reference matched
nothing — call list_projects), "invalid_argument" (an argument was empty or too long),
"conflict" (the current state refuses it). HTTP 401 means your token is missing, malformed or
revoked; HTTP 429 means you are posting faster than the per-token rate limit and should honour
the Retry-After header.
~~~

### 1.3 tools/list and tools/call (SDK behaviour)

- `tools/list` returns all 31 tools in **registration order** (`src/mcp/tools/index.ts:116-205`),
  which differs from the `TOOLS` array order (`src/mcp/tools/index.ts:78-110`): wire order is
  `create_project, list_projects, set_project_theme, post_update, edit_update, create_upload,
  attach_media, list_embed_types, register_embed_type, list_apps, register_app, set_app_data,
  register_session, heartbeat, end_session, set_status, list_work, list_tags, tag_card,
  set_card_field, get_card_fields, get_messages, acknowledge, post_message, edit_message,
  delete_message, react, request_input, await_request, edit_request, withdraw_request`.
  No pagination (`nextCursor` absent). Response: `{"result":{"tools":[…]},"jsonrpc":"2.0","id":N}`.
- Each tool object has keys in this order: `name, title, description, inputSchema, annotations,
  execution`. `execution` is always `{"taskSupport":"forbidden"}` (SDK-added). No `outputSchema`.
- `inputSchema` is zod→JSON Schema draft-07 with `"$schema":"http://json-schema.org/draft-07/schema#"`,
  `type:"object"`, `properties`, and `required` only when non-empty. No top-level
  `additionalProperties`; `z.record` emits `propertyNames` + `additionalProperties`. Optional+nullable
  emits `anyOf:[…,{"type":"null"}]`. Full text in Appendix A.
- Unknown argument keys are silently stripped (zod object default). Captured:
  `list_projects {bogus:1}` → normal success.
- `tools/call` errors from the SDK are **tool results**, not JSON-RPC errors
  (`sdk/dist/esm/server/mcp.js:100-160`), shape `{"content":[{"type":"text","text":"<msg>"}],"isError":true}`
  (no `structuredContent`):
  - unknown tool: `MCP error -32602: Tool nope not found`
  - argument validation: `MCP error -32602: Input validation error: Invalid arguments for tool <name>: <issues>`
    where issues are zod v4 messages, `"<message> at <dot.path>"`, joined by `\n`
    (`sdk/dist/esm/server/zod-compat.js:141-153`). Captured examples:
    - missing `arguments`: `Invalid input: expected object, received undefined`
    - wrong type: `Invalid input: expected string, received number at name`
    - missing required: `Invalid input: expected string, received undefined at body`
    - too long: `Too big: expected string to have <=200 characters at name`
    - bad enum: `Invalid option: expected one of "active"|"archived" at status`
    - two issues: `Invalid option: expected one of "text"|"confirm"|"buttons"|"choice"|"multi_choice"|"form" at kind\nInvalid input: expected string, received undefined at question`
- Validation therefore happens in two layers: zod (shape, `max` lengths, enums, ints) → SDK message
  above; then domain rules (`trim`, required, limits) → `failed(code, message)` (section 3).

### 1.4 Tool discovery (Kotlin only)

About 50 tools' full schemas are some 40,000 tokens, which every session paid for up front. So the
Kotlin server lists less by default (`mcp/Discovery.kt`):

- Plain `/mcp`: `tools/list` returns only `list_tools`, `describe_tools` and `call_tool`
  (`resources/mcp/discovery-tools.json`).
  - `list_tools({query?})`: every tool's name and its `title` as a one-line summary, grouped by
    area (messages, updates, work, requests, docs, channels, sessions, secrets, media, apps,
    feedback). A query keeps tools where every word is in the name, area, title or description.
  - `describe_tools({names})`: up to 20 tools' entries exactly as the full list has them; names
    that match nothing come back under `unknown`.
  - `call_tool({name, arguments?})`: runs that tool through the same `ToolRegistry.call` a direct
    call uses, so auth, permissions, validation, errors and the result are the same. Left-out
    `arguments` are `{}`. A `mcp__<server>__` prefix on the name is dropped. An unknown name says
    `Tool <name> not found` and points at `list_tools`; a validation error is the tool's own, with
    a line pointing at `describe_tools`.
- `/mcp?tools=all`: `tools/list` is the full list, exactly as before. Use it for old clients and
  for the parity suite.
- In both modes a direct `tools/call` by a tool's own name works as before, and the discovery
  tools can be called too.
- The `initialize` instructions are the same rules either way. Plain `/mcp` adds a short
  "Finding tools" part (`resources/mcp/instructions-discovery.txt`, where `{{tools}}` stands in
  `instructions.txt`) naming the everyday calls and their key arguments.

## 2. Authentication and identity

Order of refusal, first failure wins (`src/mcp/server.ts:128-141`, `src/mcp/auth.ts:119-163`):

1. **Config** — `mcpConfig()` runs `loadConfig(process.env)` on **every request**; any throw → 503
   (`src/mcp/env.ts:28-33`, `src/mcp/server.ts:130`):
   `{"jsonrpc":"2.0","id":null,"error":{"code":-32000,"message":"this dashboard is not configured for agents: TOKEN_SECRET is missing or too short","data":{"error":"server_not_configured"}}}`
   headers `content-type: application/json`, `retry-after: 30` (`src/mcp/responses.ts:84-91`).
2. **Header** — `Authorization`, trimmed; scheme = text before first whitespace, compared
   case-insensitively to `bearer`; token = rest, trimmed (`src/mcp/auth.ts:52-82`).
   - empty/missing header → `missing_token`, message `missing Authorization header: send Authorization: Bearer <agent token>`
   - other scheme → `unsupported_scheme`, message `unsupported Authorization scheme "<scheme>": this server accepts Bearer tokens only`
   - `Bearer` with nothing after → `missing_token`, message `empty bearer token: send Authorization: Bearer <agent token>`
3. **Shape** — `/^[A-Za-z0-9_-]{43}$/` (`src/domain/agents.ts:58-62`) else `malformed_token`,
   `malformed bearer token: not a token this server issued` (`src/mcp/auth.ts:130-137`).
4. **Rate limit** — key = `HMAC-SHA256(token, TOKEN_SECRET)` lowercase hex (`src/domain/agents.ts:71-74`),
   one unit per request (section 2.2). Spent **before** the token is looked up, so unknown/revoked tokens
   are limited too (`src/mcp/auth.ts:139-149`).
5. **Identity** — `authenticateAgent` (`src/domain/agents.ts:168-190`): lookup `agents` by `token_hash`,
   constant-time compare; none → `unknown_token` `unknown bearer token: no agent holds it`;
   `revoked_at` set → `revoked_token` `revoked bearer token: ask the dashboard owner to mint a new one`
   (`src/domain/agents.ts:143-148`).
6. Success → `noteAgentSeen` updates `agents.last_seen_at` (never backwards) once per served request
   (`src/mcp/auth.ts:160`, `src/domain/agents.ts:192-194`), then the server is built with that agent bound.

### 2.1 401 response

`jsonRpcError(401, message, error, {'www-authenticate': challenge})` (`src/mcp/responses.ts:21-37,65-75`):

- Body: `{"jsonrpc":"2.0","id":null,"error":{"code":-32000,"message":"<message>","data":{"error":"<error>"}}}`
- `content-type: application/json`
- `WWW-Authenticate`: `Bearer` when `error == missing_token` (either missing-token message);
  otherwise `Bearer error="invalid_token", error_description="<quotable(message)>"`, where `quotable`
  replaces every char outside `\x20-\x7e` with a space, then deletes `"` and `\` (`src/mcp/responses.ts:47-49,59-62`).
  Captured: `Bearer error="invalid_token", error_description="unsupported Authorization scheme Basic: this server accepts Bearer tokens only"`.

### 2.2 Rate limit (429)

- 120 requests per sliding 60 000 ms window per token-hash (`src/mcp/rate-limit.ts:19,22`).
  In memory, per process, lost on restart. Max 10 000 tracked keys, least-recently-seen evicted
  (`src/mcp/rate-limit.ts:30,97-100`); keys whose newest hit aged out are swept on every `take`
  (`src/mcp/rate-limit.ts:70-75`).
- A refused request is **not** recorded (`src/mcp/rate-limit.ts:84-91`). Every POST counts, including
  `initialize`, notifications and `ping`.
- `retryAfterMs = max(oldestHit + 60000 − now, 0)`; header seconds = `max(1, ceil(ms/1000))`
  (`src/mcp/rate-limit.ts:90,116-118`).
- Response (`src/mcp/responses.ts:66-70`): status 429, `content-type: application/json`,
  `retry-after: <s>`, body
  `{"jsonrpc":"2.0","id":null,"error":{"code":-32000,"message":"rate limit exceeded for this token: retry after <s>s","data":{"error":"rate_limited"}}}`.

### 2.3 Identity and scope

- The agent is `deps.agent`, fixed per request; **no tool has an agent argument**
  (`src/mcp/tools/types.ts:18-40`). Writes record `agent_id` / author `agent:<agent_id>`.
- Tokens: 32 random bytes, base64url unpadded (43 chars), stored only as HMAC hash
  (`src/domain/agents.ts:45-48,105-115`). Agent name ≤100 chars (`src/domain/agents.ts:42`).
- Several sessions (processes) can share one token = one agent; all per-agent state (read cursors,
  pending requests) is shared between them.
- There are no explicit project subscriptions on `/mcp`. "The agent's projects" is derived:
  `projectsForAgent` = distinct `project_id` of its `updates`, of `tasks` assigned to it, and of live
  `messages` it authored; `null` (meaning *all projects*) if that set is empty
  (`src/domain/messages.ts:853-856`, SQL `src/db/messages.ts:436-450`). Explicit subscriptions
  (`?project=`) exist only on the channel/stream side (`countUnreadMessagesInScope`,
  `src/domain/messages.ts:867-890`), not here.

## 3. Tool result envelope

All tools answer with `ok`/`failed` (`src/mcp/results.ts:47-71`).

- **Success** `ok(summary, data, extra=[])`:
  `{"content":[{"type":"text","text":"<summary>\n<JSON.stringify(data,null,2)>"}, …extra],"structuredContent":<data>}`.
  No `isError` key. The JSON in `text` is 2-space indented, same key order as `structuredContent`.
  `extra` is only used by `get_messages` (images/files).
- **Domain failure** `failed(code, message)`:
  `{"content":[{"type":"text","text":"<code>: <message>"}],"structuredContent":{"error":"<code>","message":"<message>"},"isError":true}`.
- `guard`/`guarded` (`src/mcp/results.ts:82-108`): a `DomainError` → `failed(error.code, error.message)`;
  any other throw is logged (`console.error('mcp tool failed', error)`) and becomes
  `failed('internal_error', 'the dashboard failed to handle this call; see server logs')`.
- Codes: `invalid_argument`, `not_found`, `conflict` (`src/domain/errors.ts:12-18`), plus `internal_error`.
- `list_embed_types` and `list_apps` are **not** wrapped in `guard` (`src/mcp/tools/list-embed-types.ts:64`,
  `src/mcp/tools/list-apps.ts:36`): a throw there escapes to the SDK and becomes
  `{"content":[{"type":"text","text":"<raw error message>"}],"isError":true}`.
- Timestamps: ISO-8601 via `new Date(ms).toISOString()` (`src/mcp/results.ts:35-37`) — **except**
  `list_apps.apps[].updated_at` and `set_app_data.updated_at`, which are raw epoch-ms numbers.
- Ids are 26-char ULIDs.

### 3.1 Shared views

| View | Fields, in order | Source |
|---|---|---|
| `projectView` | `id, slug, name, description (string\|null), status ("active"\|"archived"), pinned (bool), created_at, updated_at, theme` | `src/mcp/results.ts:217-229` |
| `theme` | `null`, or object with only the set keys among `background, accent, logo_media_id` (`logoReplacesName` is **not** exposed). Colours come back normalised (`#f80` → `#ff8800`). | `src/mcp/results.ts:232-239` |
| `updateView` | `id, project_id, agent_id, session_id\|null, title\|null, level, pinned, body_chars (= body.length, UTF-16 units), created_at, edited_at\|null, status\|null` (body not echoed) | `src/mcp/results.ts:263-277` |
| `messageView` | `id, project_id\|null, update_id\|null, task_id\|null, reply_to\|null, [answers], [request_id], author ("human" or "agent:<id>"), body, created_at, [deleted_at], [edited_at]` — bracketed keys are **omitted** when null | `src/mcp/results.ts:314-336` |
| `reactionView` | `id, card_kind, card_id, message_id\|null, actor, emoji, created_at` | `src/mcp/results.ts:352-362` |
| `ackView` | `id, card_kind, card_id, message_id\|null, task_id\|null, state, created_at, updated_at` | `src/mcp/results.ts:380-391` |
| `requestView` | `id, kind, question, detail\|null, options\|null, project_id\|null, update_id\|null, message_id\|null, expires_at, [edited_at]` | `src/mcp/results.ts:126-139` |
| card ref | `{"kind":"update"\|"message"\|"request","id":"…"}` | set_status/tag_card/card fields |

### 3.1a Priority and critical alerts (Kotlin only)

`post_update`, `edit_update` and `request_input` take `priority`: `low`, `medium` (default), `high` or
`critical`. Requests store it in `approvals.priority` (migration 049) and show `priority` only when it is
not `medium`, so ordinary reads are unchanged. A new critical update or question pages the owner's
phones that turned critical alerts on (spec/03, spec/08); an edit to `critical` pages nobody. Each tool's
description and its `priority` description say so: `critical` pages the owner through silent mode and
Do Not Disturb, and is only for when the owner has explicitly told the agent to use it. No cap. These
wordings, and a line on `@here` and `@everyone` in `post_message`, `get_messages` and the
instructions, are the only differences from the TS server's, listed for `tools/list` in
`parity/src/test/resources/tools-list-kotlin-changes.json`.

### 3.2 Request results (`request_input`, `await_request`)

`requestResult` (`src/mcp/results.ts:149-185`):

| state | summary line | structuredContent |
|---|---|---|
| `answered` | `Your owner answered: <describe(value)>` | `{state:"answered", request_id, request, response, answered_at}` |
| `pending` | `Nobody has answered yet. This is not a refusal — call await_request({request_id: "<id>"}) and keep calling it while state is "pending".` | `{state:"pending", request_id, request, poll_after_ms}` |
| `timeout` | `Nobody answered before this request timed out. That is not permission: do not proceed as though you had an answer.` | `{state, request_id, request}` |
| anything else (`cancelled`, `withdrawn`) | `Your owner dismissed this request without answering. That is not permission: do not proceed as though you had an answer.` | `{state, request_id, request}` |

`describe(value)` (`src/mcp/results.ts:188-201`): boolean → `yes.`/`no.`; array → `nothing.` if empty
else items `JSON.stringify`-ed joined by `, ` (no trailing period); form object →
`<JSON.stringify(action)> on text of <text.length> characters.`; string → `JSON.stringify(value)` (quoted, no period).

### 3.2a No waiting (Kotlin only)

The Kotlin server never holds a request call. The long-poll in section 4 locked agents up: while
they looped on `await_request` they answered no follow-up in the question's own thread, and the
plugin's channel already hands them the answer as an `answer` event.

- `request_input` (and `request_secret`, `request_secret_access`, `send_feedback`, which ask the same
  way) returns at once: `pending` with `{state:"pending", request_id, request}` (no `poll_after_ms`),
  summary `Asked. Nobody has answered yet, and that is not a refusal. Don't wait for it: carry on with
  other work and answer your messages. The answer arrives as a channel answer event with request_id
  "<id>"; if you need it later and missed it, read it with get_messages({request_id: "<id>"}).`
- `await_request` is retired: no list shows it (`tools/list`, `?tools=all`, `list_tools`,
  `describe_tools`), and no description or instructions mention it. Its schema stays in
  `resources/mcp/retired-tools.json`, so an older session that calls it (directly or through
  `call_tool`) still gets an answer, at once: the request's current state as in section 3.2 (lazy
  timeout included), with ` await_request is retired and never waits now. Don't call it again: …`
  appended to the summary line. Errors as before.
- `get_messages({request_id})`: the thread's `card` carries `state`, and for the asking agent, once
  answered, `response` (`{kind, value}`), `answered_at` and, on a team, `answered_by {user_id, name}`. Another agent reading the thread gets no
  `response`.
- The heartbeat's and stream's `pending_approvals` count is unchanged. `HOLD_S` still parses, but
  nothing waits on it.
- `tools-list-kotlin-changes.json` records it: a `remove` entry for `await_request`, then `replace`
  entries for each description.

## 4. Long-poll (`request_input`, `await_request`) (TS server; the Kotlin server has none, §3.2a)

- `HOLD_S`: env, int 1..59, default 55 (`src/config.ts:88`); `holdMs = HOLD_S*1000` passed in `ToolDeps`
  (`src/mcp/env.ts:30`, `src/mcp/server.ts:141`). Domain fallback if absent: `DEFAULT_HOLD_MS = 55000`
  (`src/domain/requests.ts:131`). `POLL_AFTER_MS = 1000` (`src/domain/requests.ts:134`).
- `hold` (`src/domain/requests.ts:777-803`):
  1. lazy expiry: if pending and `now >= expires_at`, `UPDATE approvals SET state='timeout', decided_value=NULL, answer=NULL, decided_at=now WHERE id=? AND state='pending'` and publish `request.answered {requestId, agentId, state:'timeout', settledAt:<ISO>}` (`src/domain/requests.ts:843-850`).
  2. not pending → return the final state immediately.
  3. `timeoutMs = max(0, min(holdMs, expires_at − now))`; `bus.waitFor({types:['request.answered'], where: requestId matches, since: <bus seq read before the row>, timeoutMs})` (`src/domain/requests.ts:786-795`, `src/events/bus.ts:142-178`). The `since` seq makes an answer that lands between the row read and the park still count (replay ring of 500, `src/events/bus.ts:10`).
  4. on wake (event or timer): re-read, lazy-expire, return final state, else `pending` with `poll_after_ms: 1000`.
- Woken only by `request.answered` (answer, dismiss, timeout, withdraw). `request.updated` (edit) wakes nobody.
- **Client disconnect:** no `AbortSignal` is passed (`src/mcp/tools/request-input.ts:292`, `src/mcp/tools/await-request.ts:70`);
  the server-side wait runs to answer/hold/expiry; the response is then written to a closed socket.
  The request row persists, so the agent resumes with `await_request`.
- The HTTP response is held open up to `HOLD_S` seconds with no bytes sent (JSON mode, no SSE keep-alive).
- Request sweeper: every 30 000 ms flips expired pending rows to `timeout` and publishes one
  `request.answered` each (`src/domain/requests.ts:146,655-692`; started in `src/hooks.server.ts`).

## 5. Presence (`register_session`, `heartbeat`, `end_session`)

Constants (`src/domain/sessions.ts:68-82`): `HEARTBEAT_INTERVAL_S = 30` (returned to the agent),
`PRESENCE_WINDOW_MS = 300_000`, `SESSION_IDLE_MS = 600_000`, `SWEEP_INTERVAL_MS = 60_000`,
meta limits host 200 / cwd 1000 / model 100.

- **Online** = the agent has at least one session with `ended_at IS NULL` and
  `last_heartbeat_at >= now − 90 000` (`src/domain/sessions.ts:419-424`).
- `registerSession` (`src/domain/sessions.ts:116-133`): agent must exist (`not_found` `no such agent: <id>`);
  `cleanMeta` trims each of `host/cwd/model`, drops blanks, other keys ignored, too long →
  `invalid_argument` `meta.<field> must be at most <n> characters`; all blank → `meta = null`
  (`src/domain/sessions.ts:461-474`). Inserts a `sessions` row (`started_at = last_heartbeat_at = now`).
  Publishes `agent.presence {agentId, sessionId, online:true}` only if the agent was offline before.
  Not idempotent: every call makes a new session.
- `heartbeat` (`src/domain/sessions.ts:157-169`): `ownSession` → `not_found` `no such session: <id>`,
  other agent → `invalid_argument` `session belongs to another agent` (`src/domain/sessions.ts:442-447`);
  ended → `conflict` `session <id> has ended; call register_session to start a new one`
  (`src/domain/sessions.ts:449-451`). `UPDATE sessions SET last_heartbeat_at=? WHERE id=? AND ended_at IS NULL`
  (`src/db/sessions.ts:79-88`); publishes `agent.presence … online:true` only on an offline→online
  transition. Returns counts (`src/domain/sessions.ts:383-416`):
  - `unread_messages` = `countUnreadMessages` — derived project scope, mentions anywhere, excludes own
    messages (`src/domain/messages.ts:830-839`).
  - `open_tasks` = `countOpenWorkInScope` = non-deleted updates + messages whose `status` is
    `todo|in_progress|blocked`, in the agent's derived projects (all projects if none)
    (`src/domain/cards.ts:226-233`, `src/db/cards.ts:49,214-232`). Counts other agents' and owner cards too.
  - `pending_approvals` = this agent's `approvals` with `state='pending'`, every project
    (`src/domain/requests.ts:614-616`, `src/db/approvals.ts:178-198`). Expired-but-unswept rows still count.
- `end_session` (`src/domain/sessions.ts:185-199`): same ownership errors; already ended →
  `{ended:false}`, no write. Else `UPDATE sessions SET ended_at=? …`; publishes
  `agent.presence {agentId, sessionId, online:false}` only if the agent was online and no other live
  session remains.
- Sweeper (`src/domain/sessions.ts:296-363`, started in `src/hooks.server.ts`): every 60 s closes
  sessions idle > 10 min; for agents that went offline publishes `agent.presence {agentId, sessionId:null, online:false}`.
  Between 5 and 10 min of silence the agent is shown offline with no event.

## 6. Attachments in `get_messages` (`src/mcp/attachments.ts`)

Agents cannot fetch `/media/...` (owner session only), so message attachments are inlined in the
tool result after the main text block.

- Settings: `deps.media` is never set by `createMcpHandler` (`src/mcp/server.ts:141`), so
  `get_messages` calls `mediaSettings()` from env each time, or uses `null` if that throws
  (`src/mcp/tools/get-messages.ts:167-174`, `src/media/settings.ts:38-48`).
- Limits: `MAX_INLINE_IMAGES = 4`, `MAX_INLINE_BYTES = 3_000_000` (shared budget for one call),
  `MAX_INLINE_FILE_BYTES = 400_000` (`src/mcp/attachments.ts:53,61,69`).
- Text mimes: `text/*`, the set at `src/mcp/attachments.ts:78-90`
  (`application/json, application/xml, application/yaml, application/x-yaml, application/toml,
  application/x-ndjson, application/javascript, application/typescript, application/sql, application/x-sh,
  image/svg+xml`), or `/\+(json|xml|yaml)$/` (`src/mcp/attachments.ts:93-97`).
- `download_url` = `<PUBLIC_BASE_URL without trailing slashes>/api/agent/media/<media_id>`, omitted
  if no base (`src/mcp/attachments.ts:112-115`). (That route takes the bearer token; spec'd elsewhere.)
- Bytes: `readAttachmentBytes` tries variants `thumb-1600`, `thumb-640`, `original` in that order,
  only those listed on the media row, skipping any whose size > `maxBytes` (checked before reading)
  or that fail to open (`src/domain/media.ts:447-471`).
- Per message (`listMessageMedia` order), per item (`src/mcp/attachments.ts:176-276`):
  - settings `null`: note `message <id>: <n> attachment(s), which this deployment cannot serve.`;
    every view `included:false, reason:"this deployment has no media configuration"`, no `download_url`.
  - `video`: never inlined; reason `no protocol block carries a video`; note fragment `<id> (video — fetch it with your bearer token at <url>)`.
  - `file`: max = `min(budget, 400000)`; text mime → text block
    `Attached file <JSON name> (<mime>, <n> bytes):\n<<<FILE\n<utf8 text>\nFILE>>>`; other mime →
    `{"type":"resource","resource":{"uri":"attachment:<id>/<encodeURIComponent(name)>","mimeType":<item.mime>,"blob":<base64>}}`.
    `name = filename ?? id`. Too big/unreadable → reason `too large to inline (over 400000 bytes, or no room left in this read)`,
    note fragment `<name> (<mime>, <bytes> bytes: too large to inline<hint>)`.
  - image: if 4 already inlined → reason `over the 4-image limit for one read`, fragment `<id> (over the 4-image limit for one read<hint>)`;
    else `{"type":"image","data":<base64>,"mimeType":<variant mime>}`; unreadable/too big →
    reason `too large to inline, or still being processed`, fragment `<id> (too large to inline, or still being processed<hint>)`.
  - `<hint>` = ` — fetch it with your bearer token at <download_url>` or empty (`src/mcp/attachments.ts:389-392`).
  - Inlined bytes are subtracted from the budget.
- Note per message with attachments: `message <id>: ` + parts joined `; ` + `.`; parts are
  `1 attachment is included below (<x>)` / `<n> attachments are included below (<x, y>)` and
  `not shown: <frag>; <frag>` (`src/mcp/attachments.ts:394-407`). Included image → `<id>`; file → `<name> (<mime>)`.
- `AttachmentView` (in `messages[].attachments`): `media_id, kind, mime, bytes, filename|null, status, included, [download_url], [reason]` (`src/mcp/attachments.ts:279-304`).
- Notes are appended to the summary line, each on its own line (`src/mcp/tools/get-messages.ts:183-192`).

Uploads: tools only reference media by id. `create_upload` reserves a row and returns a PUT URL
(`<PUBLIC_BASE_URL>/api/upload/<token>`); the bytes go to that HTTP route (spec'd elsewhere). Ids are then
passed as `media_ids` (`post_update`, `post_message`, `request_input`), `option_media` (`request_input`),
`logo_media_id` (`set_project_theme`) or to `attach_media`.

## 7. Tools — behaviour

Numbering follows wire order (Appendix A). For each: arguments → domain call → result →
errors → writes/events. Schemas and descriptions are in Appendix A; only behaviour is here.
Common text helpers: `requiredText` trims, `<field> is required` if blank, `<field> must be at most <n> characters`
if long (UTF-16 length); `optionalText` trims, blank → null (`src/domain/text.ts`).
Project references (`project` args) resolve via `resolveProject` (`src/domain/projects.ts:195-210`):
trim; blank → `invalid_argument` `project is required`; if 26-char upper-case ULID try id then
lower-cased slug, else lower-cased slug then id; none → `not_found` `no such project: <trimmed ref>`.
Card arguments (`update_id` / `message_id` / `request_id`) go through `oneCard`
(`src/domain/cards.ts:79-90`): ids trimmed, blank = absent; none → `invalid_argument`
`name one of: update, message, request`; several → `name one of: update, message, request — not <n> of them` (U+2014);
then `resolveCard`: missing or deleted → `not_found` `no such <kind>: <id>` (`src/domain/cards.ts:100-104`).

### 7.1 `create_project` — `src/mcp/tools/create-project.ts`

- `createProject` (`src/domain/projects.ts:158-175`): `name` requiredText ≤200; slug = explicit
  `slug` (trim, lowercase, must match `/^[a-z0-9]+(?:-[a-z0-9]+)*$/`, ≤64, else
  `slug must be lowercase letters, digits and single hyphens, at most 64 characters`) or
  `slugify(name)` (NFKD, strip U+0300–U+036F, lowercase, `[^a-z0-9]+`→`-`, trim `-`, cut 64, trim `-`;
  empty → `could not derive a slug from name; pass slug explicitly`) (`src/domain/slug.ts:13-63`);
  `description` optionalText ≤2000.
- Idempotent **on slug only**: existing slug → returned unchanged, `created:false`, no write, no event
  (even if archived, other name/description). Validation still runs first.
- New: insert `status='active'`, `pinned=false`; event `project.created {projectId, slug}`.
- Summary: `Created project "<name>" (slug <slug>).` / `Project "<name>" already exists (slug <slug>); returning it unchanged.`
  Structured `{"project":<projectView>,"created":bool}` (`create-project.ts:74-78`).

### 7.2 `list_projects` — `src/mcp/tools/list-projects.ts`

- `status?` filter. SQL order `pinned DESC, seq DESC` (`src/db/projects.ts:103-113`). Read-only.
- Summary: 0 → `No projects yet. Create one with create_project.` (also when filtering finds none);
  else `<n> project[s]: <slug>, <slug>.` Structured `{"projects":[<projectView>],"count":n}`.

### 7.3 `set_project_theme` — `src/mcp/tools/set-project-theme.ts`

- Builds a patch with only the keys present (undefined = leave, null = clear) and calls
  `updateProject(ctx, project, {theme})` (`set-project-theme.ts:106-121`).
- `mergeTheme` (`src/domain/projects.ts:306-347`): colours trimmed, must match
  `/^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/` else `<background|accent> must be a hex colour like #1a2b3c`;
  stored lowercase 6-digit. `logo_media_id`: null removes logo and `logoReplacesName`; else
  `not_found` `no such media: <id>`, `a logo must be an image`, `that image is still being processed`
  (no owner check). `logo_replaces_name`: stored only when true; true without a logo →
  `logoReplacesName needs a logo to show instead of the name`. Empty theme → null.
- The tool always passes a `theme` object (maybe `{}`), so the domain's
  `update requires at least one field to change` (`src/domain/projects.ts:278-280`) can never fire:
  `{project}` alone succeeds, rewrites the same theme, bumps `updated_at` and publishes (captured).
- Writes `projects.theme`, `updated_at` (every call); event `project.updated {projectId, slug}` (every call).
- Summary `Styled project <slug>.` Structured `{"project":<projectView>}`.

### 7.4 `post_update` — `src/mcp/tools/post-update.ts`

- Args: `project`, `body` (≤100 000), `title?` (≤200), `priority?` (`low|medium|high`, default medium),
  `level?` (`info|success|warn|error`, default info), `media_ids?` (≤24), `embeds?` (≤8),
  `apps?` (≤4), `session_id?`. Calls `postUpdate` (`src/domain/updates.ts:83-158`).
- Errors in order: project; `not_found` `no such agent: <id>`; body; title;
  session (`not_found` `no such session: <id>`, `invalid_argument` `session belongs to another agent`;
  an ended session is accepted); media (`assertAttachable`, `src/domain/media.ts:483-500`:
  `media_ids must name at least one upload` n/a, `an update may carry at most 24 media items`,
  `not a media id: "<id>"`, `not_found` `no such media: <id>`, `media <id> belongs to another agent`,
  `media <id> is already on another update`, `media <id> has no bytes yet: PUT the file to its upload_url first`);
  embeds; apps; priority. Archived projects are accepted.
- Writes `updates` row (`task_id` null, `status` null), media `update_id`, `embeds`, `app_views`.
- Event `update.created {updateId, projectId, agentId}` → Web Push (per-device level/priority filter),
  APNs `UPDATE`, Live Activity, notification recorder.
- Summary `Posted a <level> update to project <project arg as given> as agent "<name>".` (note "a info").
  Structured `{"update":<updateView>[, "apps":[{"view_id","uri","title"}]]}` — `apps` only when apps
  were passed and views exist (`post-update.ts:208-220`).

### 7.5 `edit_update` — `src/mcp/tools/edit-update.ts`

- Args: `update_id`, `body?`, `title?` (null clears), `priority?`, `level?`.
  `editUpdate` (`src/domain/updates.ts:340-376`).
- Errors: missing/deleted → `not_found` `no such update: <id>`; other author →
  `invalid_argument` `that update was posted by another agent`; field errors; nothing given →
  `an edit must change the title, the body, the level or the priority`.
- Writes changed columns + `edited_at=now` (always). Event `update.updated {updateId, projectId, pinned}`; no new push.
- Summary `Edited update <id>.` Structured `{"update":<updateView>}`.

### 7.6 `create_upload` — `src/mcp/tools/create-upload.ts`

- Args: `filename` (≤255), `mime`, `bytes` (positive int). `createUpload` (`src/domain/media.ts:137-152` → `src/media/upload.ts:198-263`).
- Mime canonicalised (before `;`, trim, lowercase, aliases audio/x-m4a|audio/m4a→audio/mp4,
  audio/x-wav|audio/wave→audio/wav, audio/vorbis|audio/opus→audio/ogg). Kind: image
  (`image/png, image/jpeg, image/webp, image/gif`), video (`video/mp4, video/webm, video/quicktime`),
  audio (`audio/mpeg, audio/mp4, audio/ogg, audio/wav`), else `file` if `type/subtype`-shaped
  (`src/media/mime.ts:39-149`). So `application/x-msdownload` is accepted (captured).
- Errors (`invalid_argument`): `filename is required`, `filename must be at most 255 characters`,
  `filename contains control characters`; bad mime → `"<mime>" must look like "type/subtype", e.g. "image/png", "application/pdf" or "text/plain". Images and video are rendered on the card and are checked against their magic bytes; anything else is stored as a file the owner downloads.`;
  `bytes must be the whole number of bytes the file has, and at least 1`;
  over cap → `<an|a> <kind> may be at most <limit> bytes; this one declares <bytes>` (image 10 485 760,
  video 209 715 200, audio/file 52 428 800 by default); `not_found` `no such agent: <id>`.
- Writes (one transaction): `media` row (pending, `sha256=''`), upload-token row (own ULID, expires now+900 000 ms).
- `upload_url` = `<PUBLIC_BASE_URL>/api/upload/<tokenRowId>.<base64url(HMAC-SHA256(TOKEN_SECRET, "agent-dashboard:upload-token:v1:"+tokenRowId))>` (`src/media/tokens.ts:35-87`). Token id ≠ media id.
- Summary `Reserved <mime as given> upload <media_id>. PUT the raw bytes to <upload_url> within 15 minutes, then pass <media_id> to post_update as media_ids.`
  Structured `{"media_id","upload_url","expires_at":<ISO>,"max_bytes":<= bytes>}` (`create-upload.ts:112-122`). No event.
- If env config is invalid the call throws a non-domain error → `internal_error` (captured in a harness without env).

### 7.7 `attach_media` — `src/mcp/tools/attach-media.ts`

- Args: `update_id`, `media_ids` (1..24). `attachMedia` (`src/domain/media.ts:290-329`).
- Checks: ids de-duplicated; `not a media id: "<id>"` (ULID shape) — checked **before** the update;
  `an update may carry at most 24 media items`; update missing/deleted **or another agent's** →
  `not_found` `no such update: <id>`.
- Attaches ids that exist, are this agent's, have bytes, and are on nothing
  (`UPDATE media SET update_id=? … RETURNING id`). Rest → `skipped` (input order), including ids
  already on this same update. No event.
- Summary `Attached <n> media to update <id>.` or `Attached <n> media to update <id>; skipped <k> that were not available to you.`
  Structured `{"update_id","attached":[ids],"skipped":[ids]}`.

### 7.8 `list_embed_types` — `src/mcp/tools/list-embed-types.ts`

- No args; not guarded. `listEmbedTypeViews` order: built-ins first, then `name COLLATE NOCASE, slug`
  (`src/db/embeds.ts:93-101`); unparseable specs dropped. Built-ins: `confluence-page`, `github-comment`,
  `jira-issue`, `linked-page`, `slack-message`.
- Item `{slug, name, built_in, blocks:[block.type…], fields:[placeholders]}`; `fields` = unique
  `{{name}}` (`/\{\{\s*([A-Za-z0-9_-]+)\s*\}\}/g`) scanning `chrome` then `blocks`, first-seen order
  (`list-embed-types.ts:18-39`).
- Summary `1 embed look.` or `<n> embed looks.` Structured `{"types":[…],"count":n}`.

### 7.9 `register_embed_type` — `src/mcp/tools/register-embed-type.ts`

- Args: `slug` (≤60), `name` (≤80), `spec` (object). `registerEmbedType` (`src/domain/embeds.ts:570-608`).
- Errors: `slug must be lowercase words joined by hyphens, e.g. "slack-message", at most 60 characters`;
  `name is required, and at most 80 characters`; spec: `spec must be an object`,
  `spec.blocks must be a list of blocks`, `spec.blocks must hold at least one block`,
  `spec.blocks may hold at most 24 blocks`, then per-field `<path> must be an object|must be one of: …|is required|must be text|must be at most 20000 characters|must be a hex colour like #1a2b3c`
  (captured: `blocks[0].value is required`); `conflict` for a built-in slug
  (`"<slug>" is one of the looks this dashboard ships, so it cannot be redefined. Register your own under a different slug.`)
  or another agent's (`"<slug>" was registered by another agent. Pick a different slug.`).
- Upsert by slug (keeps id/created_at). **No event.**
- Summary `Registered the "<slug>" look. Pass embeds: [{ type: "<slug>", data: { ... } }] to post_update or post_message to show one.`
  Structured `{"slug","name","built_in","blocks":[types]}`.

### 7.10 `list_apps` — `src/mcp/tools/list-apps.ts`

- No args; not guarded. `listAppSummaries(ctx, agent.id)` order `name COLLATE NOCASE, uri` (`src/db/apps.ts:105-110`).
- Item `{uri, name, description|null, url|null, bytes (UTF-8 length of html), csp:{connect,resource,frame}, mine, updated_at:<epoch ms number>}`.
- Summary `No apps are registered here yet. register_app ships one.` or `<n> app(s) registered.`
  Structured `{"apps":[…],"count":n}`.

### 7.11 `register_app` — `src/mcp/tools/register-app.ts`

- Args: `uri` (≤120), `name` (≤80), `description?` (≤400), `html?` (≤250 000), `url?` (≤2000),
  `csp?:{connect?,resource?,frame?}`. `registerApp` (`src/domain/apps.ts:199-257`).
- Errors: uri (`uri must start with ui://, e.g. "ui://review-board"`, `uri may be at most 120 characters`,
  `uri must be ui:// followed by lowercase words, e.g. "ui://review-board" or "ui://acme/review-board"`);
  `name is required, and at most 80 characters`; `description may be at most 400 characters`;
  `give html or url, not both: an app is a document, or a page to frame`;
  `an app needs html — a self-contained document — or url, an https page to frame`; html too long;
  url (`must be an absolute https URL…`, `must use https…`, `must not carry credentials`);
  csp (`csp.<key> must be a list of https origins`, `csp.<key> may name at most 10 origins`,
  `csp.<key>[<i>] must be an origin — a host, with no path`, …);
  `conflict` built-in / other agent's uri.
- Upsert on uri. **No event.** uri stored lowercase; html untrimmed; url as `href`; csp origins as `origin`.
- Summary `Registered "<uri>"[, framing <url>]. Pass apps: [{ app: "<uri>", data: { ... } }] to post_update or post_message to show it.`
  Structured `{"uri","name","url"|null,"bytes","csp"}` (`register-app.ts:238-242`).

### 7.12 `set_app_data` — `src/mcp/tools/set-app-data.ts`

- Args: `view_id`, `data` (object, replaces wholesale), `title?` (≤120; `""` clears). `setAppData` (`src/domain/apps.ts:362-377`).
- Errors: `not_found` `no app view "<view_id>"`; card gone → `not_found` `no such <kind>: <id>`;
  `data must be a JSON object`, `data may be at most 100000 characters of JSON`; title errors.
  No ownership check.
- Events: card announce (`update.updated` / `message.updated` / `request.updated`), then `apps.changed {projectId}`.
- Summary `Updated the "<uri>" app on that card.` Structured `{"view_id","uri","title"|null,"updated_at":<epoch ms number>}`.

### 7.13 `register_session` — `src/mcp/tools/register-session.ts`

- Args: `meta?: {host?, cwd?, model?}` (unknown keys stripped). Calls `registerSession` (section 5).
- Summary: `Registered session <id> for agent "<agent name>". Call heartbeat({session_id}) at least every 30 seconds, and end_session({session_id}) when you finish.` (`register-session.ts:80-84`)
- Structured: `{"session_id":"<ulid>","heartbeat_interval_s":30}`.
- Events: `agent.presence {agentId, sessionId, online:true}` if the agent was offline.

### 7.14 `heartbeat` — `src/mcp/tools/heartbeat.ts`

- Args: `session_id` (required).
- Structured: `{"ok":true,"unread_messages":N,"open_tasks":N,"pending_approvals":N}` (`heartbeat.ts:67-72`).
- Summary (`heartbeat.ts:83-101`): all zero → `Heartbeat recorded. Nothing is waiting for you; carry on.`;
  else `Heartbeat recorded. Waiting for you: ` + non-zero parts joined `, ` + `.`, parts
  `<n> unread message[s]`, `<n> open task[s]`, `<n> pending approval[s]` (plural `s` unless n = 1).
- Errors: `not_found` `no such session: <id>`; `invalid_argument` `session belongs to another agent`;
  `conflict` `session <id> has ended; call register_session to start a new one`.
- Writes `sessions.last_heartbeat_at`; `agent.presence` only on offline→online.

### 7.15 `end_session` — `src/mcp/tools/end-session.ts`

- Args: `session_id`. Idempotent.
- Summary: ended → `Ended session <id>. Agent "<name>" is no longer shown as working.`; already →
  `Session <id> was already ended; nothing to do.` Structured `{"session_id":…,"ended":true|false}` (`end-session.ts:57-62`).
- Errors: `not_found` / `invalid_argument` as heartbeat.
- Writes `sessions.ended_at`; `agent.presence … online:false` if that took the agent offline.

### 7.16 `set_status` — `src/mcp/tools/set-status.ts`

- Args: `update_id?` / `message_id?` (exactly one), `status` (enum or null, **required key**).
  `setStatus` (`src/domain/cards.ts:190-209`). Requests cannot be named here (no `request_id` arg).
- Errors: `oneCard` errors; `status must be one of: todo, in_progress, blocked, done, cancelled`; `resolveCard` not_found.
  **No author check** — any agent may move any card.
- Same status → no write, no event. Else writes `status`, `status_at=now`; event card announce
  (`update.updated {updateId, projectId, pinned}` or `message.updated {messageId, projectId, author:''}`).
- Summary: null → `Unmarked. It is an ordinary card again.`; else `Marked <status with first "_" → " ">. Your owner sees it on the card.`
  Structured `{"card":{"kind","id"},"status":<status|null>}`.

### 7.17 `list_work` — `src/mcp/tools/list-work.ts`

- Args: `project?`, `status?: [enum]`, `limit?` (int 1..200, default 50; `list-work.ts:19-20`).
  `listWorkInScope` (`src/domain/cards.ts:283-318`). Scope: project or derived projects (all if none).
  `status: []` → empty list. Order `COALESCE(status_at, created_at) DESC, id DESC` (`src/db/cards.ts:325-365`).
  Only cards with a status are listed.
- Item `{kind, id, project_id, status, title (null for messages), summary (trimmed body, 240 chars + "…"), author, created_at, status_at|null}`.
- Summary: 0 → `No work matches. Nothing is marked in that state.`; else
  `<n> card[s]: <count> <status with first "_" → " ">, …` (groups in first-seen order) + `.`
  Structured `{"work":[…],"count":n}`.

### 7.18 `list_tags` — `src/mcp/tools/tag-card.ts:24-66`

- `project?` (all projects if omitted). Order `seq` (oldest first).
- Item `{id, project_id, name, colour|null, agent_id|null, created_at}`.
- Summary: 0 → `No tags yet. tag_card with create: true decides the first one.`; else `<n> tag[s]: <name>, <name>.`

### 7.19 `tag_card` — `src/mcp/tools/tag-card.ts:94-152`

- Args: one of `update_id|message_id|request_id`, `tag` (id or name), `on?`, `create?`.
  `tagCard` (`src/domain/tags.ts:251-291`).
- Errors: card; `this card does not belong to a project, so it cannot be tagged`; tag by id from another
  project → `that tag belongs to another project`; unknown name without create →
  `not_found` `this project has no tag called "<tag>"`; create errors (`a tag needs a word`,
  `a tag may be at most 32 characters`, `"<name>" has no letters or digits in it`,
  `this project already has 50 tags, which is as many as a picker can show`);
  `a card may carry at most 8 tags`.
- `on` omitted **toggles** (`wanted = on ?? !already`). No change → no event. Else write and events
  card announce + `tags.changed {projectId}`; creating also publishes `tags.changed`.
- Summary: on → `Tagged "<name>". Your owner sees it on the card.`; off → `Took "<name>" off.`
  (name JSON-quoted). Structured `{"card","tag":{"id","name","colour"},"on":bool}`.

### 7.20 `set_card_field` — `src/mcp/tools/card-fields.ts:62-117`

- Args: one card id, `name`, `value` (string or null, required key). `setField` (`src/domain/fields.ts:90-139`).
- Errors: card; `a field needs a name`; `a field name may be at most 40 characters`;
  `"<name>" has no letters or digits in it`; null on missing → `not_found` `this card has no field called "<name>"`;
  `a field needs a value, or null to remove it`; `a field value may be at most 500 characters`;
  `a card may carry at most 20 fields`. Any agent, any card kind.
- Unchanged → no write/event. Else upsert by slug; events card announce + `fields.changed {projectId}`.
- Summary: set → `Noted "<name arg>" on the card. Your owner can see it under Details.`; null →
  `Took "<name arg>" off the card.` Structured `{"card","fields":[fieldView],"set":bool}`;
  fieldView `{name, value, author, created_at, updated_at}` (`card-fields.ts:26-34`).

### 7.21 `get_card_fields` — `src/mcp/tools/card-fields.ts:119-162`

- Args: one card id. **No `resolveCard`**: `fieldsOn` only, so an unknown/deleted card returns `[]`, not `not_found`.
- Summary: 0 → `Nothing noted on that card yet.`; else `<n> field[s]: <name>, <name>.`
  Structured `{"card","fields":[…],"count":n}`. Order `seq`.

### 7.22 `get_messages` — `src/mcp/tools/get-messages.ts`

- Args: `since?` (decimal message `seq` string from a previous `cursor`), `project?` (slug/id),
  `mark_read?` (default true). Calls `readMessages` (`src/domain/messages.ts:726-815`).
- Errors (in order): `not_found` `no such agent: <id>`; `project` given → `invalid_argument` `project is required`
  (whitespace-only) / `not_found` `no such project: <ref>`; `since` not `/^[0-9]+$/` →
  `invalid_argument` `since is not a cursor this server issued` (`src/domain/messages.ts:1201-1205`).
  Empty-string `project`/`since` are treated as absent.
- Selection: live messages, author ≠ `agent:<self>`, obeying @mention audience (a message that names
  agents is visible only to those agents). Scope: `[project]` if given, else the derived projects
  (section 2.3) plus, when no `project` was given, any message that names this agent; derived `null`
  → all projects. Page size 50 (`pageLimit` default; no tool arg). Order `seq ASC`.
  - No `since` or `since:"0"`: unread rows, i.e. `seq >` the agent's `read_cursors` row for that
    project (`''` bucket for project-less).
  - `since:"N"` (N>0): rows with `seq > N`, read or not.
- Mark read (default): per-agent, per-project cursors move forward (never back) to the last seq
  handed over (cautious variant with `since`); publishes `messages.read {agentId, cursor:<number>}`
  for any non-empty page (`src/domain/messages.ts:786-802,1053-1124`).
- Structured (`get-messages.ts:125-155`):
  `{"messages":[<messageView + [author_name] + [in_reply_to] + [attachments]>],"count":N,"cursor":"<seq string>","unread":N,"marked_read":bool}`
  - `author_name`: agent's name (revoked included) for `agent:<id>`; absent for `human`/unknown (`src/domain/messages.ts:1132-1136`).
  - `in_reply_to`: `{id, author, body, truncated}` of the `answers ?? reply_to` parent if it exists and is
    live; body cut to 240 chars + `…` (`trimEnd` first) with `truncated:true` (`src/domain/messages.ts:620,649-665`).
  - `cursor`: `String(lastSeq)`, or `since ?? "0"` when the page is empty.
  - `unread`: remaining unread over the **derived** scope (ignores the `project` arg), after marking.
  - `marked_read`: echoes the argument (`true` default) even when nothing moved.
- Summary (`get-messages.ts:183-192`): 0 → `No new messages. Carry on.`; else
  `<n> new message[s], oldest first.` + (unread>0 ? ` <unread> still unread; call again for the rest.` : ``)
  + attachment notes on following lines.
- Extra content blocks: images/files (section 6), after the text block.
- `guarded` (async) wrapper.
- Kotlin server: `message_id`, `update_id` or `request_id` (one) reads a whole thread instead
  (spec/04 §4.9). Summary `The whole thread: <the post and N replies | the update and N comments |
  the question and N lines of discussion>, oldest first. Your read cursor did not move.`, then
  lines for `earlier` and `withheld` when above zero, then attachment notes (the update's own
  first, `update <id>: …`). On an ordinary read, a line that @mentions the agent in a thread it
  was not in has `thread: {kind, id}` and adds a summary line `message <id> @mentions you in a
  thread you were not in: call get_messages with message_id <id> to read all of it, files included.`
  (no braces: clients find the JSON at the first `{` after the summary).

### 7.23 `acknowledge` — `src/mcp/tools/acknowledge.ts`

- Args: `state` (`thinking|read|done`, required), exactly one of `message_id`, `update_id`, `request_id`
  (`oneCard`, see the section 7 intro).
- Summary by state (`acknowledge.ts:124-128`): `done` → `Marked done. Your owner sees a tick on it.`;
  `read` → `Marked read. Your owner sees that it reached you.`; `thinking` → `Acknowledged. Your owner sees that you are on it.`
- Structured `{"ack":<ackView>}`. One ack row per (agent, card), updated in place (same `id`, `created_at`).
- Event `ack.updated` (`src/domain/cards.ts:432`).

### 7.24 `post_message` — `src/mcp/tools/post-message.ts`

- Args: `body` (required, ≤10 000 after trim), `update_id?`, `message_id?` (stored as `replyTo`),
  `answers?`, `request_id?`, `project?`, `media_ids?` (no zod max; domain max 24), `embeds?` (≤8),
  `apps?` (≤4). Author `{kind:'agent', agentId}`. Calls `postMessage` (`src/domain/messages.ts:186-368`).
- Validation order and messages (all `invalid_argument` unless noted):
  1. `body is required` / `body must be at most 10000 characters`
  2. `answers` given and missing/deleted → `not_found` `no such message: <answers>`
  3. `message_id` and `update_id` both present → `a reply answers a message, or hangs off an update, not both`
  4. `request_id` with `update_id` or `message_id` → ``a message belongs to a request`s discussion, or to a card, not both`` (literal backtick)
  5. request missing → `not_found` `no such request: <id>` (state/owner not checked)
  6. `message_id` target missing → `not_found` `no such message: <id>` (deleted target accepted)
  7. `project` → `project is required` / `not_found` `no such project: <ref>`
  8. update anchor missing/deleted → `not_found` `no such update: <id>`
  9. anchor project ≠ named project → `that update, task or message belongs to a different project`
  10. media: `not a media id: "<id>"`, `an update may carry at most 24 media items`, `not_found` `no such media: <id>`,
      `media <id> was uploaded by somebody else`, `media <id> is already on an update|another message|an approval`,
      `media <id> has no bytes yet: upload the file first`
  11. embeds: `a post may carry at most 8 embeds`, `embeds[<i>]: no embed type "<slug>". Call list_embed_types to see what is registered, or register_embed_type to add it.`, data/url errors
  12. apps: `a post may carry at most 4 apps`, `apps[<i>]: no app "<uri>". Call register_app to ship it, or list_apps to see what is already registered.`, data/title errors
  13. `answers` in another thread → `that message is in a different thread`
- Thread resolution: replies are flattened to one level; replying to a message inside a card thread
  stores `update_id=<card>`, `answers=<target>`, `reply_to=null`; replying to a feed post stores
  `reply_to=<post>`, `answers=<target>` (captured). No anchor and no project → stored with `project_id=null` (accepted).
- Writes (no transaction): `messages` row (`task_id` always null), `message_mentions`, media `message_id`,
  `embeds`, `app_views`.
- Events: `message.created {messageId, projectId, author, updateId, replyTo, requestId, taskId}`
  (`src/domain/messages.ts:360-365`). Listeners: owner notification row `kind:'reply'` (title
  `<name> replied to you` or `<name> commented`), Web Push / APNs (`src/domain/notifications.ts:173-197`,
  `src/domain/push.ts:525-580`, `src/domain/apns.ts:472-487`), live stream fan-out to other agents.
- @mentions (`src/domain/mentions.ts`): code spans/fences stripped; for each non-revoked agent name
  `(?<![\w@])@<name>(?![\w-])`, case-insensitive; all agents with that name are named.
- Summary always `Replied. It is on your owner’s screen now.` (U+2019). Structured
  `{"message":<messageView>[, "apps":[{"view_id","uri","title"}]]}` (`post-message.ts:205-212`).

### 7.25 `edit_message` — `src/mcp/tools/edit-message.ts`

- Args: `message_id`, `body` (≤10 000). Calls `editMessage` (`src/domain/messages.ts:408-433`).
- Errors in order: `not_found` `no such message: <id>` (missing or deleted); `not_found` `no such agent: <id>`;
  `invalid_argument` `that message was posted by somebody else`; body errors.
- Writes `body`, `edited_at`; recomputes mentions. Event `message.updated` (same payload as created).
- Summary `Edited. Your owner sees the new text.` Structured `{"message":<messageView>}` (with `edited_at`).

### 7.26 `delete_message` — `src/mcp/tools/delete-message.ts`

- Args: `message_id`. Calls `deleteMessage` (`src/domain/messages.ts:477-505`).
- Missing → `not_found` `no such message: <id>`. **Already deleted → success with the row, no author check,
  no event.** Else not author → `invalid_argument` `that message was posted by somebody else`.
- Soft delete (`deleted_at`), body kept; if the message has no `reply_to`, its live replies
  (`reply_to = id`) are soft-deleted too, whoever wrote them.
- Event `message.deleted {messageId, projectId, replies:<n>, updateId, replyTo, requestId, taskId}`.
- Summary `Unsent. It is gone from your owner’s screen.` Structured `{"message":<messageView incl. deleted_at>}`.

### 7.27 `react` — `src/mcp/tools/react.ts`

- Args: one of `message_id|update_id|request_id`, `emoji`, `on?`. `reactToCard` (`src/domain/cards.ts:349-385`).
- Emoji (`src/domain/reactions.ts:52-120`): trim; strip `:`; lowercase shortcodes map (eyes, tick,
  check, white_check_mark, done, +1, thumbsup, -1, thumbsdown, tada, rocket, heart, thinking,
  thinking_face, warning, x, fire, pray, smile); errors `emoji is required`,
  `an emoji is at most 24 characters`, `an emoji has no spaces in it`,
  `"<s>" is not an emoji. Send the character itself, or one of: eyes, tick, check, white_check_mark, done, +1, thumbsup, -1` (all-ASCII).
- `on` omitted toggles (captured: second identical call removes).
- Event `reaction.updated {card, messageId, projectId, actor, emoji, on}` only when changed.
- Summary: on → `Reacted. Your owner sees it on the card.`; off → `Reaction removed. It is gone from the card.`
  Structured `{"reactions":[reactionView, all on the card by seq],"on":bool}`.

### 7.28 `request_input` — `src/mcp/tools/request-input.ts`

- Args (zod): `kind` (enum, required), `question` (required), `detail?`, `options?: string[]`,
  `placeholder?`, `multiline?`, `label?`, `default?`, `min?`/`max?` (int), `project?`, `update?`
  (an update id — note: not `update_id`), `message_id?`, `media_ids?` (≤24), `option_media?`
  (record string→string), `embeds?` (≤8), `timeout_s?` (int). No zod length limits on strings; the
  domain enforces them.
- Constants (`src/domain/requests.ts:91-134`): question ≤500, detail ≤4000, option/placeholder/label ≤200,
  ≤25 options, text answer/default ≤10 000, `timeout_s` default 3600, range 5..86400.
- Validation order (`src/domain/requests.ts:308-375,935-1109`), all `invalid_argument` unless noted:
  1. `kind must be one of text, confirm, buttons, choice, multi_choice, form`
  2. `question is required` / `question must be at most 500 characters`
  3. `detail must be at most 4000 characters`
  4. options: text/confirm with non-empty → `a <kind> request takes no options`; each item
     `options[<i>] is required` / `… must be at most 200 characters` (trimmed); then
     `a <kind> request needs at least one option`, `at most 25 options`, `options must be distinct`
  5. config: `placeholder is for a text or form request`; `multiline is for a text or form request`
     (only when `true`); `label names a form request’s field`; default:
     `a multi_choice takes no default`, `the default for a confirm is "true" or "false"`,
     `the default must be one of the options` (buttons/choice); min/max on other kinds →
     `min and max are for a text, form or multi_choice request`; `<min|max> must be a whole number`,
     `<min|max> must be at most <ceiling>` (10 000 text/form, options.length multi_choice);
     `min must not exceed max`
  6. anchor: `project` resolved first (`not_found` `no such project: <ref>`); `message_id` wins over
     `update`: `not_found` `no such message: <id>` (missing/deleted), `the message named belongs to a different project`;
     else `update`: `not_found` `no such update: <id>` (deleted accepted), `the update named belongs to a different project`
  7. `timeout_s must be a whole number of seconds between 5 and 86400`
  8. media_ids (as post_message)
  9. embeds (as post_message)
  10. option_media: `pictures on options are for a buttons, choice or multi_choice request`,
      `<option> is not one of the options`, `each picture may be used once`, media checks,
      `media <id> must be an image to go on an option`
  11. `a picture on an option cannot also be in media_ids`; `a request may carry at most 24 media items, pictures included`
  12. fields (Kotlin server only, `FormFields.check`): `fields are for a form request`; with
      fields, placeholder/multiline/label/default/min/max → `a form with fields sets <keys> on each
      field, not on the request`; 1-20 fields, each `{ id, label, type, hint?, required?, default?,
      options?, min?, max?, placeholder? }`, an unknown key or type refused by name. options
      default to `["Submit"]`. Stored as `config.fields`, with `config.label`/`placeholder` set
      to words an older app shows over its one box ("This form needs a newer app").
- Writes: `approvals` row (`state='pending'`, `expires_at = now + timeout_s*1000`), media `request_id`, `embeds`.
- Event `request.created {requestId, agentId, projectId, kind}` → notification row
  (`<name> is waiting on you`), Web Push (confirm → Approve/Reject actions; buttons/choice → first 2 options),
  APNs category `REQUEST`, Live Activity recompute.
- Then holds (section 4). Result per section 3.2. Captured pending:
  `{"state":"pending","request_id":"…","request":{…,"expires_at":"2026-08-25T10:30:00.000Z"},"poll_after_ms":1000}`.
- `response` (answered) = `{kind, value}`: confirm boolean; text string; buttons/choice option string;
  multi_choice string[]; form `{action, text}` (`src/domain/requests.ts:703-766`), or
  `{action, values}` for a form with fields (spec/03 §2).

### 7.29 `await_request` — `src/mcp/tools/await-request.ts`

- Args: `request_id`. Errors: `not_found` `no such request: <id>`; another agent's →
  `invalid_argument` `request belongs to another agent` (`src/domain/requests.ts:864-868`).
- Settled requests return immediately (no error) with their final state; else holds (section 4).
- Only write: lazy timeout.
- Kotlin server: retired and unlisted, never holds (§3.2a).

### 7.30 `edit_request` — `src/mcp/tools/edit-request.ts`

- Args: `request_id`, `question?` (≤500), `detail?` (≤4000, null clears), `options?` (items ≤200),
  `default?` (≤10 000, null clears). Calls `editRequest` (`src/domain/requests.ts:498-583`).
- Errors in order: ownership (as await); lazy expiry; not pending → `conflict` `request <id> has already been answered|timed out|was dismissed|was withdrawn`
  (`src/domain/requests.ts:870-879`); field errors as request_input; options change orphaning the
  default → `the default must be one of the options`; nothing changed →
  `an edit must change the question, the detail, the options or the default`; lost race → `conflict`.
- Writes `edited_at` + changed columns; option pictures for removed options dropped from config.
- Event `request.updated {requestId, agentId, projectId}` (Live Activity only; no waiter wakes).
- Summary `Edited "<question>". Your owner sees the new question.` Structured `{"request":<requestView incl. edited_at>}`.

### 7.31 `withdraw_request` — `src/mcp/tools/withdraw-request.ts`

- Args: `request_id`. Errors: ownership; lazy expiry; not pending → `conflict` as edit_request
  (captured second call: `conflict: request <id> was withdrawn`).
- Writes `state='withdrawn', decided_at=now`. Event `request.answered {requestId, agentId, state:'withdrawn', settledAt}` (wakes waiters → `state:"withdrawn"`).
- Summary `Withdrew "<question>". It is off your owner's dashboard.` (ASCII apostrophe). Structured
  `{"request_id":"…","state":"withdrawn"}` (`withdraw-request.ts:68-71`).

## Surprises

Keep these unless the owner decides otherwise; agents may depend on them.

1. **`GET /mcp` is 405 from SvelteKit, not from the SDK.** Calling the SDK transport directly with GET
   opens an SSE stream (200 `text/event-stream`), and DELETE returns 200. Only the route's single
   `POST` export makes them 405 (`src/http/routes/mcp/+server.ts:24`). A Ktor port must 405 them itself.
2. **`listChanged: true` is advertised** but the tool list never changes and no notification can be sent (stateless).
   The Kotlin server says `listChanged: false` instead, so it promises nothing it cannot send.
3. **Unknown `protocolVersion` in `initialize` is not refused**: the server answers `2025-11-25`.
4. **503 on any bad env var.** `mcpConfig` reparses the whole environment per request; an invalid
   unrelated variable (e.g. `ADMIN_PASSWORD_HASH`) yields 503 "TOKEN_SECRET is missing or too short"
   (`src/mcp/env.ts:28-33`). Boot normally catches this first.
5. **Rate limit is spent before identity** — unknown/revoked tokens consume budget; notifications and
   pings count; a 429 comes before 406/415/400 checks.
6. **Tool errors come in two text formats**: SDK `MCP error -32602: …` (no `structuredContent`) and
   domain `<code>: <message>` (with `structuredContent`). Zod v4 message wording leaks onto the wire.
7. **Two tools return epoch-ms numbers** for `updated_at` (`list_apps`, `set_app_data`); every other timestamp is ISO.
8. **`list_embed_types`/`list_apps` are not wrapped in `guard`** — a throw surfaces raw.
9. **`projectView.theme` hides `logo_replaces_name`**, so an agent cannot read back what it set.
10. **`set_project_theme` always writes and publishes**, even with nothing to change.
11. **Summary grammar**: `Posted a info update …`, `Posted a error update …`; the summary echoes the
    `project` argument as typed, not the slug.
12. **`await_request` has a fifth state `withdrawn`**; its summary says "Your owner dismissed this request",
    which is wrong for an agent-withdrawn request. Descriptions document four states.
13. **No abort on disconnect**: a parked `request_input` keeps running up to `HOLD_S` after the client leaves.
14. **"Not yours" is inconsistent**: `edit_update`/`edit_message`/`delete_message`/sessions/requests →
    `invalid_argument` (which reveals the id exists); `attach_media` → `not_found`.
15. **`delete_message` on an already-deleted message succeeds for anyone** (no author check), and
    deleting a top-level post soft-deletes everyone's replies to it.
16. **`set_status`, `set_card_field`, `tag_card`, `set_app_data` have no ownership check**: any agent may change any card.
17. **`get_card_fields` never 404s**: unknown or deleted card → empty list.
18. **Toggles**: `tag_card` and `react` without `on` flip the current state, so a retried call undoes itself.
19. **`tag_card` with `create:true` defines the tag even when it then refuses** (8-tag limit) or does nothing (`on:false`).
20. **Media errors say "update" on messages/requests** (`an update may carry at most 24 media items`).
21. **`post_update` media check is weaker than the attach SQL**: media already on a message or request
    passes `assertAttachable` and is then silently not attached (`src/domain/updates.ts:129-131`).
22. **`acknowledge` publishes `ack.updated` on every call**, even unchanged.
23. **Heartbeat counts differ in scope**: `pending_approvals` spans all projects; `unread_messages` and
    `open_tasks` use derived projects; `open_tasks` counts everyone's cards, not just the agent's.
24. **`get_messages.unread` ignores the `project` argument**, and `marked_read` echoes the argument even
    when nothing moved; `since:"0"` acts like no `since` for selection.
25. **An agent with history never sees project-less messages** unless they @name it.
26. **`register_session` is not idempotent** and `post_update` accepts an ended session id.
27. **No transactions** around multi-row writes in `post_message`, `request_input`, `post_update`.
28. **Lower-case ULIDs** never match a project by id (`isId` is upper-case only).
29. **`list_work` zod rejects `limit:0`** with the SDK message (`Too small: expected number to be >0 at limit`), not a domain error.
30. **Wire tool order ≠ `TOOLS` order** (`get_messages` and `acknowledge` sit after `get_card_fields`).

## Parity test notes

Setup: two agents `scout` (token A) and `builder` (token B). Every request: `POST /mcp`,
`content-type: application/json`, `accept: application/json, text/event-stream`,
`authorization: Bearer <A>`. Compare `structuredContent` exactly (ignore ULID values/timestamps by
pattern) and the first line of `content[0].text` exactly.

Transport and auth:

- `initialize` (2025-06-18) → body matches section 1.2 byte-for-byte (key order, instructions).
- `initialize` with `1999-01-01` → `protocolVersion:"2025-11-25"`.
- `notifications/initialized` → 202, empty body. `ping` → `{"result":{},"jsonrpc":"2.0","id":5}`.
- `tools/list` → 31 tools, order and objects equal to Appendix A.
- `GET /mcp` → 405, body `GET method not allowed`, `allow: POST`. Same for DELETE.
- `accept: application/json` → 406; `content-type: text/plain` → 415; `{not json` → 400 -32700; `{"foo":1}` → 400 -32700 "Invalid JSON-RPC message".
- Header `mcp-protocol-version: 1999-01-01` on `tools/list` → 400 (message in 1.1).
- No auth → 401, `www-authenticate: Bearer`, `data.error:"missing_token"`. `Basic abc` → 401 `unsupported_scheme`
  with the quote-stripped challenge. `Bearer abc` → `malformed_token`. `Bearer ` + 43×`a` → `unknown_token`.
  Revoked agent → `revoked_token`. `bearer   <A>  ` → 200.
- Rate limit: 120 requests in a window pass, 121st → 429 `retry-after: 60` (approx; ceil seconds), body per 2.2.
- Unknown tool → `MCP error -32602: Tool nope not found`. `create_project {name:5}` → `…Invalid input: expected string, received number at name`.
  `tools/call list_projects` without `arguments` → `…Invalid input: expected object, received undefined`.
- Batch `[tools/list, ping]` → JSON array of two responses.

Per tool (A unless noted; ids from earlier steps):

| Tool | Call | Expect |
|---|---|---|
| create_project | `{name:"Demo Project!",description:"d"}` | `created:true`, slug `demo-project`, `theme:null` |
| create_project | same name, `description:"other"` | `created:false`, description still `"d"`, summary `Project "Demo Project!" already exists (slug demo-project); returning it unchanged.` |
| create_project | `{name:"   "}` | `invalid_argument: name is required` |
| list_projects | `{}` on empty db | `{"projects":[],"count":0}`, `No projects yet. Create one with create_project.` |
| list_projects | `{status:"x"}` | SDK `Invalid option: expected one of "active"\|"archived" at status` |
| set_project_theme | `{project:"demo-project",background:"#101820",accent:"#f80"}` | theme `{"background":"#101820","accent":"#ff8800"}` |
| set_project_theme | `background:"red"` | `invalid_argument: background must be a hex colour like #1a2b3c` |
| set_project_theme | `{project:"nope",accent:"#fff"}` | `not_found: no such project: nope` |
| set_project_theme | `{project:"demo-project",logo_replaces_name:true}` (no logo) | `invalid_argument: logoReplacesName needs a logo to show instead of the name` |
| post_update | `{project:"demo-project",title:"T",body:"hello **world**",level:"success",priority:"high",session_id:S}` | `update.level:"success"`, `body_chars:15`, `session_id:S`, summary `Posted a success update to project demo-project as agent "scout".` |
| post_update | `{project:"demo-project",body:""}` | `invalid_argument: body is required` |
| post_update | `session_id:"bogus"` | `not_found: no such session: bogus` |
| post_update | `apps:[{app:"ui://demo/board",data:{a:1},title:"B"}]` | adds `"apps":[{"view_id":…,"uri":"ui://demo/board","title":"B"}]` |
| edit_update | `{update_id:U,title:null,body:"edited"}` | `title:null`, `body_chars:6`, `edited_at` set |
| edit_update | B edits U | `invalid_argument: that update was posted by another agent` |
| edit_update | `{update_id:U}` | `invalid_argument: an edit must change the title, the body, the level or the priority` |
| create_upload | `{filename:"shot.png",mime:"image/png",bytes:1234}` | `media_id`, `upload_url` `^<base>/api/upload/[0-9A-Z]{26}\.[A-Za-z0-9_-]{43}$`, `expires_at` = now+15 min, `max_bytes:1234` |
| create_upload | `bytes:10000000000` | `invalid_argument: an image may be at most 10485760 bytes; this one declares 10000000000` |
| create_upload | `mime:"application/x-msdownload"` | accepted (kind file) |
| attach_media | `{update_id:U,media_ids:["nope"]}` | `invalid_argument: not a media id: "nope"` |
| attach_media | valid-shaped unknown id | `attached:[]`, `skipped:[id]`, `…; skipped 1 that were not available to you.` |
| list_embed_types | `{}` | `count:5`, slugs `confluence-page, github-comment, jira-issue, linked-page, slack-message` in that order, `built_in:true` |
| register_embed_type | `{slug:"bad",name:"Bad",spec:{}}` | `invalid_argument: spec.blocks must be a list of blocks` |
| register_embed_type | `{slug:"mini",name:"Mini",spec:{blocks:[{type:"text",text:"{{text}}"}]}}` | `invalid_argument: blocks[0].value is required` |
| register_embed_type | slug `slack-message` with a valid spec | `conflict: "slack-message" is one of the looks this dashboard ships, …` |
| list_apps | `{}` empty | `No apps are registered here yet. register_app ships one.` |
| register_app | `{uri:"ui://demo/board",name:"Board",html:"<p>hi</p>"}` | `{"uri":"ui://demo/board","name":"Board","url":null,"bytes":9,"csp":{"connect":[],"resource":[],"frame":[]}}` |
| register_app | `uri:"http://x"` | `invalid_argument: uri must start with ui://, e.g. "ui://review-board"` |
| list_apps | after register | `mine:true`, `updated_at` is a **number** |
| set_app_data | `{view_id:V,data:{a:2}}` | `{"view_id":V,"uri":"ui://demo/board","title":"B","updated_at":<number>}` |
| set_app_data | `view_id:"nope"` | `not_found: no app view "nope"` |
| register_session | `{meta:{host:"box",cwd:"/w",model:"m"}}` | `{"session_id":…,"heartbeat_interval_s":30}` |
| heartbeat | `{session_id:S}` fresh | `{"ok":true,"unread_messages":0,"open_tasks":0,"pending_approvals":0}`, `Heartbeat recorded. Nothing is waiting for you; carry on.` |
| heartbeat | after owner + B write on U and U is `in_progress` | `unread_messages:2, open_tasks:1`, `Heartbeat recorded. Waiting for you: 2 unread messages, 1 open task.` |
| heartbeat | B uses S | `invalid_argument: session belongs to another agent` |
| heartbeat | after end_session | `conflict: session <S> has ended; call register_session to start a new one` |
| end_session | twice | `ended:true` then `ended:false` with `Session <S> was already ended; nothing to do.` |
| set_status | `{update_id:U,status:"in_progress"}` | `{"card":{"kind":"update","id":U},"status":"in_progress"}`, `Marked in progress. …` |
| set_status | `{status:"todo"}` | `invalid_argument: name one of: update, message, request` |
| set_status | both ids | `invalid_argument: name one of: update, message, request — not 2 of them` |
| list_work | `{}` | one item `{kind:"update",…,"summary":"edited","author":"agent:<A id>"}`, `1 card: 1 in progress.` |
| list_work | `{limit:0}` | SDK `Too small: expected number to be >0 at limit` |
| list_tags | `{project:"demo-project"}` | `No tags yet. tag_card with create: true decides the first one.` |
| tag_card | `{update_id:U,tag:"urgent"}` | `not_found: this project has no tag called "urgent"` |
| tag_card | `+create:true`, then repeat without `on` | `on:true` then `on:false` (toggle), summary `Took "urgent" off.` |
| tag_card | on a project-less message | `invalid_argument: this card does not belong to a project, so it cannot be tagged` |
| set_card_field | `{update_id:U,name:"PR",value:"#12"}` then `value:null` | `set:true` with one field; then `set:false`, `fields:[]` |
| get_card_fields | unknown valid id | `{"card":…,"fields":[],"count":0}` (no error) |
| get_messages | owner + B posted on U | 2 messages oldest first, B's has `author_name:"builder"`, owner's has none, `cursor:"2"` (seq), `unread:0`, `marked_read:true` |
| get_messages | again | `{"messages":[],"count":0,"cursor":"0","unread":0,"marked_read":true}` |
| get_messages | `{since:"abc"}` | `invalid_argument: since is not a cursor this server issued` |
| acknowledge | `{state:"thinking",message_id:M}` then `done` | same `ack.id`, state changes; summaries per 7.23 |
| post_message | `{body:"reply from scout",message_id:M}` (M on U) | `update_id:U`, `reply_to:null`, `answers:M` |
| post_message | `{body:"nowhere"}` | success, `project_id:null` |
| post_message | `{body:"x",update_id:U,message_id:M}` | `invalid_argument: a reply answers a message, or hangs off an update, not both` |
| edit_message | own | `edited_at` present; on M (owner's) → `invalid_argument: that message was posted by somebody else` |
| react | `{message_id:M,emoji:"👍"}` twice | `on:true` 1 reaction; then `on:false`, `reactions:[]` |
| react | `emoji:"nope"` | `invalid_argument: "nope" is not an emoji. Send the character itself, or one of: eyes, tick, check, white_check_mark, done, +1, thumbsup, -1` |
| delete_message | own twice | both succeed with `deleted_at`; on M → `invalid_argument: that message was posted by somebody else` |
| request_input | `{kind:"confirm",question:"Ship it?",project:"demo-project"}`, HOLD_S=1 | after ~1 s `state:"pending"`, `poll_after_ms:1000`, `expires_at` = now+3600 s |
| request_input | `{kind:"choice",question:"Pick"}` | `invalid_argument: a choice request needs at least one option` |
| request_input | `timeout_s:1` | `invalid_argument: timeout_s must be a whole number of seconds between 5 and 86400` |
| request_input | owner answers during hold | returns at once `state:"answered"`, `response:{"kind":"confirm","value":true}`, summary `Your owner answered: yes.` |
| await_request | `{request_id:R}` pending | `pending` after hold; B → `invalid_argument: request belongs to another agent`; unknown → `not_found: no such request: <id>` |
| await_request | after withdraw | `state:"withdrawn"`, summary `Your owner dismissed this request without answering. …` |
| await_request | `timeout_s:5`, wait 6 s | `state:"timeout"`, DB row flipped |
| edit_request | `{request_id:R,question:"Ship it now?"}` | `request.edited_at` present; summary `Edited "Ship it now?". Your owner sees the new question.` |
| edit_request | `{request_id:R}` only | `invalid_argument: an edit must change the question, the detail, the options or the default` |
| withdraw_request | twice | `{"request_id":R,"state":"withdrawn"}`; then `conflict: request <R> was withdrawn` |

Side-effect checks: subscribe to the event bus (or the owner SSE stream) and assert the event names
in sections 5 and 7 fire exactly when stated (e.g. no `project.created` on a repeat `create_project`,
`agent.presence` only on transitions, `ack.updated` on every `acknowledge`).

## Appendix A — exact `tools/list` entries

Captured from the live handler (`tools/list`), in wire order. `description` is verbatim (fences use `~~~`); `inputSchema` is the exact JSON (whitespace re-indented; key order preserved). Non-ASCII characters (— ’ …) are literal UTF-8 on the wire.

### A.1 `create_project`

title: `Create a project` · annotations: `{"idempotentHint": true, "destructiveHint": false, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
Create a project to post status updates into, or get back the one that already owns the slug.

Idempotent on slug: calling this again with the same name or slug returns the existing
project unchanged and reports created=false, so it is safe to call on every startup
instead of checking first. It never overwrites an existing project, so a description
passed on a repeat call is ignored.

Arguments:
- name (required): display title, e.g. "Agent Dashboard". 1-200 characters.
- slug (optional): the short id to refer to this project by later, lowercase letters,
  digits and hyphens, at most 64 characters. Anything else is normalised.
  Defaults to a slug derived from name.
- description (optional): one paragraph shown in the dashboard, at most
  2000 characters.

Returns { project: { id, slug, name, description, status, pinned, created_at, updated_at },
created }. Pass project.slug or project.id as the "project" argument to post_update.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "name": {
   "type": "string",
   "maxLength": 200,
   "description": "Display title, e.g. \"Agent Dashboard\". 1-200 characters."
  },
  "slug": {
   "description": "Short id used to refer to this project later, e.g. \"agent-dashboard\". Lowercase letters, digits and hyphens, at most 64 characters; anything else is normalised (spaces and punctuation become hyphens, capitals are lowered). Defaults to a slug derived from name.",
   "type": "string",
   "maxLength": 64
  },
  "description": {
   "description": "One short paragraph shown under the project in the dashboard. At most 2000 characters.",
   "type": "string",
   "maxLength": 2000
  }
 },
 "required": [
  "name"
 ]
}
~~~

### A.2 `list_projects`

title: `List projects` · annotations: `{"readOnlyHint": true, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
List the dashboard's projects, pinned first and then newest — the order the owner sees
them in the sidebar. Call this to find the slug to post an update into.

Arguments:
- status (optional): "active" or "archived". Omit to get both.

Returns { projects: [{ id, slug, name, description, status, pinned, created_at,
updated_at }], count }. An empty list is a normal answer, not an error: use
create_project first.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "status": {
   "description": "Filter by lifecycle state. Omit to get both active and archived projects.",
   "type": "string",
   "enum": [
    "active",
    "archived"
   ]
  }
 }
}
~~~

### A.3 `set_project_theme`

title: `Style a project` · annotations: `{"idempotentHint": true, "destructiveHint": false, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
Give a project its own look on your owner's dashboard: a background colour, an accent
colour for buttons and links, and a logo beside its name. Use it to make a board you
created recognisable at a glance.

Arguments:
- project (required): the slug or the 26-character id.
- background (optional): hex colour for the page, e.g. "#101820".
- accent (optional): hex colour for buttons and links.
- logo_media_id (optional): a ready image from create_upload.
- logo_replaces_name (optional): true to show the logo instead of the name.

COLOURS MUST BE HEX. "#101820" and "#182" are accepted; "red", "rgb(1,2,3)" and
"var(--x)" are refused, because these values become CSS on the dashboard and it will
not take anything it cannot check. You do not need to pick a text
colour: readable text, borders and raised surfaces are derived from your background.

Each field is merged with what the project already has, so setting an accent leaves an
existing logo alone. Pass null for a field to clear just that one. Every open tab
restyles without a reload.

Returns { project: { id, slug, name, description, status, pinned, created_at,
updated_at, theme } }.

On failure: "not_found" means the project reference matched nothing, or the logo id has
no media behind it. "invalid_argument" means a colour was not a hex literal, the logo
is a video, or its bytes have not finished processing yet — try again once the upload
has been derived. Nothing is written when either happens.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "project": {
   "type": "string",
   "description": "Which project to style: its slug (\"agent-dashboard\") or its 26-character id, as create_project and list_projects return."
  },
  "background": {
   "description": "The page background, as a hex colour: \"#101820\" or \"#182\" both work. Named colours, rgb() and anything else are refused. Omit to leave it; pass null to clear it. The dashboard picks readable text and borders for whatever you set, so a dark background gets light text without you asking.",
   "anyOf": [
    {
     "type": "string"
    },
    {
     "type": "null"
    }
   ]
  },
  "accent": {
   "description": "The colour of buttons and links, as a hex colour. Same rules as background. Omit to leave it; pass null to clear it.",
   "anyOf": [
    {
     "type": "string"
    },
    {
     "type": "null"
    }
   ]
  },
  "logo_replaces_name": {
   "description": "True to show the logo INSTEAD of the project name, for a logo that is the name — a wordmark. The name is still read by screen readers as the image’s alt text. Needs a logo: setting this without one is refused. Omit to leave it; false or null shows both.",
   "anyOf": [
    {
     "type": "boolean"
    },
    {
     "type": "null"
    }
   ]
  },
  "logo_media_id": {
   "description": "An image to show beside the project name: a media id from create_upload whose bytes you have already PUT and which has finished processing. It must be an image, not a video. Omit to leave it; pass null to remove it.",
   "anyOf": [
    {
     "type": "string"
    },
    {
     "type": "null"
    }
   ]
  }
 },
 "required": [
  "project"
 ]
}
~~~

### A.4 `post_update`

title: `Post a status update` · annotations: `{"idempotentHint": false, "destructiveHint": false, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
Post a status update to a project's timeline. The owner sees it appear live, with no
reload, so this is how you report progress, ask for eyes on something, or record a
failure.

Arguments:
- project (required): the project slug ("agent-dashboard") or its 26-character id, from
  create_project or list_projects.
- body (required): the update as markdown, at most 100000 characters. Raw
  HTML is shown as text, never executed.
- title (optional): a headline, at most 200 characters.
- level (optional): "info" (default), "success", "warn" or "error". The dashboard colours
  the card by level, so use "error" only for something you want looked at.
- priority (optional): "low", "medium" (default) or "high" — whether it can wait. Your
  owner filters notifications on it per device, so "high" is what reaches a phone.
- media_ids (optional): up to 24 ids from create_upload whose bytes
  you have already uploaded. Images and video render on the card; anything else is
  listed as a download.
- embeds (optional): things to show in a registered look — a Slack message, a GitHub
  comment, a Jira ticket — rather than pasted as flat text. Each is { type, data,
  source_url? }; list_embed_types says which looks exist and which values each reads.
- apps (optional): interactive UI, rendered in a sandboxed frame on the card. Each is
  { app: "ui://…", data?, title? }; register_app ships one and list_apps says what
  exists. set_app_data pushes fresh values into one already posted.

If the card is a piece of work, mark it with set_status — "in_progress" while you are
on it, "done" when it is finished. That is what the task board used to be.
- session_id (optional): the id register_session returned, if you have one. The update is
  filed against that run, so the owner can see which session produced it. It must be one
  of your own sessions.

The posting agent is taken from your bearer token; there is deliberately no argument for
it, so you can only ever post as yourself.

Returns { update: { id, project_id, agent_id, session_id, title, level, pinned,
body_chars, created_at } }. `session_id` is what you passed, or null if you passed
nothing.

On failure: error "not_found" means a reference matched nothing — the project (call
list_projects), a media id (nothing was uploaded under it), or the session (it was never
registered, so register_session again). "invalid_argument" means an argument was empty,
too long, named media that is not yours to attach, or named a session belonging to
another agent. Nothing is posted when either happens.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "project": {
   "type": "string",
   "description": "Which project to post into: either its slug (\"agent-dashboard\") or its 26-character id, both as returned by create_project and list_projects."
  },
  "body": {
   "type": "string",
   "maxLength": 100000,
   "description": "The update itself, as markdown, at most 100000 characters. Headings, lists, links and fenced code blocks all render. Raw HTML does not: it is shown as text, never executed."
  },
  "title": {
   "description": "Optional headline shown above the body, at most 200 characters. Omit for a body-only note.",
   "type": "string",
   "maxLength": 200
  },
  "priority": {
   "description": "How much this needs your owner NOW: \"low\", \"medium\" (the default) or \"high\". A different question from level — level is what happened, priority is whether it can wait. A routine error from a flaky test is low; an info that a migration is about to run against production is high. Your owner filters notifications on this, per device, so \"high\" is what reaches a phone at 2am. Use it sparingly or it stops meaning anything.",
   "type": "string",
   "enum": [
    "low",
    "medium",
    "high"
   ]
  },
  "level": {
   "description": "How the dashboard colours the card: \"info\" (the default) for progress, \"success\" for something finished, \"warn\" for something the owner should look at, \"error\" for a failure you are stuck on.",
   "type": "string",
   "enum": [
    "info",
    "success",
    "warn",
    "error"
   ]
  },
  "media_ids": {
   "description": "Files to put on the card: ids from create_upload whose bytes you have already PUT, at most 24. All of them must be yours and unused, or the whole post is refused — nothing is published half-illustrated. If an upload finishes after you post, use attach_media instead.",
   "maxItems": 24,
   "type": "array",
   "items": {
    "type": "string"
   }
  },
  "embeds": {
   "description": "Things to show in a registered look — a Slack message, a GitHub comment, a Jira ticket — instead of pasting them as flat text. At most 8. Call list_embed_types for the looks and the values each one reads; register_embed_type adds a new one. An unknown look fails the whole post rather than dropping the embed.",
   "maxItems": 8,
   "type": "array",
   "items": {
    "type": "object",
    "properties": {
     "type": {
      "type": "string",
      "description": "The look's slug, from list_embed_types."
     },
     "data": {
      "type": "object",
      "propertyNames": {
       "type": "string"
      },
      "additionalProperties": {},
      "description": "The values it shows, e.g. { \"username\": \"sam\", \"text\": \"it is green\" }."
     },
     "source_url": {
      "description": "Where you copied it from, if anywhere. http or https.",
      "type": "string"
     }
    },
    "required": [
     "type",
     "data"
    ]
   }
  },
  "apps": {
   "description": "Interactive UI to put on the card — a board your owner can tick through, a chart of the run you are in the middle of. At most 4. register_app ships one; set_app_data pushes fresh values into it afterwards. An unknown app fails the whole card rather than dropping the frame.",
   "maxItems": 4,
   "type": "array",
   "items": {
    "type": "object",
    "properties": {
     "app": {
      "type": "string",
      "description": "The app's ui:// URI, from list_apps or register_app."
     },
     "data": {
      "description": "What to hand it, e.g. { \"open\": 3, \"files\": [\"fees.ts\"] }.",
      "type": "object",
      "propertyNames": {
       "type": "string"
      },
      "additionalProperties": {}
     },
     "title": {
      "description": "A line above the frame.",
      "type": "string"
     }
    },
    "required": [
     "app"
    ]
   }
  },
  "session_id": {
   "description": "Optional: the session_id register_session gave you, recorded on the update so this post can be traced back to the run that made it. Must be one of your own sessions. Omit it if you never registered one.",
   "type": "string"
  }
 },
 "required": [
  "project",
  "body"
 ]
}
~~~

### A.5 `edit_update`

title: `Edit an update you posted` · annotations: `{"idempotentHint": true, "destructiveHint": false, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
Correct an update you have already posted. Use it when what you said has stopped being
true — a step you reported as running has finished, a number was wrong, a level was too
alarming or not alarming enough.

Arguments:
- update_id (required): the id post_update returned. Only your own updates.
- body (optional): the replacement markdown, at most 100000 characters. It
  replaces the body; it does not append to it.
- title (optional): the replacement headline, at most 200 characters, or
  null to remove it.
- level (optional): "info", "success", "warn" or "error".
- priority (optional): "low", "medium" or "high". Demote something that has stopped
  mattering rather than leaving it shouting.

At least one of body, title, level or priority must be given.

The card keeps its place in the timeline and is marked as edited, so the owner can see
that what they read earlier has changed. There is no revision history: the new text is
the only text. If the update is wrong in a way a correction cannot fix, post a new one
rather than rewriting history the owner has already acted on.

Prefer this over posting a near-duplicate card. Do not use it to keep one card as a
running log — the timeline is a sequence of things that happened, and an owner who
scrolled past your card will not see it change.

Returns { update: { id, project_id, agent_id, session_id, title, level, pinned,
body_chars, created_at, edited_at } }.

On failure: "not_found" means there is no such update, or it has been deleted — a
deleted card cannot be edited back into existence. "invalid_argument" means the update
belongs to another agent, the body was empty or too long, or you passed no fields to
change. Nothing is written when either happens.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "update_id": {
   "type": "string",
   "description": "The 26-character id of the update to edit, as post_update returned it. It must be one of your own updates."
  },
  "body": {
   "description": "The replacement body, as markdown, at most 100000 characters. Omit to leave the body alone. This replaces the whole body rather than appending to it.",
   "type": "string",
   "maxLength": 100000
  },
  "title": {
   "description": "The replacement headline, at most 200 characters. Omit to leave it alone; pass null to remove the headline entirely.",
   "anyOf": [
    {
     "type": "string",
     "maxLength": 200
    },
    {
     "type": "null"
    }
   ]
  },
  "priority": {
   "description": "How much this needs your owner NOW: \"low\", \"medium\" (the default) or \"high\". A different question from level — level is what happened, priority is whether it can wait. A routine error from a flaky test is low; an info that a migration is about to run against production is high. Your owner filters notifications on this, per device, so \"high\" is what reaches a phone at 2am. Use it sparingly or it stops meaning anything.",
   "type": "string",
   "enum": [
    "low",
    "medium",
    "high"
   ]
  },
  "level": {
   "description": "The corrected level: \"info\", \"success\", \"warn\" or \"error\". Omit to leave it alone. Use this when something you reported as in progress has finished, or has failed.",
   "type": "string",
   "enum": [
    "info",
    "success",
    "warn",
    "error"
   ]
  }
 },
 "required": [
  "update_id"
 ]
}
~~~

### A.6 `create_upload`

title: `Reserve an upload` · annotations: `{"idempotentHint": false, "destructiveHint": false, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
Reserve somewhere to put a file, so you can show the owner the thing itself instead of
describing it: a screenshot, a screen recording, a voice note, a PDF, a log, a CSV,
an archive.

Three steps, in this order:

1. Call create_upload with the name, type and exact size of the file.
2. PUT the raw bytes — the file itself, no multipart wrapper, no base64 — to the
   upload_url you get back. It is absolute; use it exactly as given.
   You MUST send a Content-Type header. A PUT with no Content-Type has its body
   discarded before it reaches the server. Send "application/octet-stream" unless you are
   uploading an image, a video or a sound, in which case send the mime you declared
   here.
   Never send a form-like type (text/plain, multipart/form-data,
   application/x-www-form-urlencoded): those are refused as cross-site form posts,
   which is why a text file is PUT as "application/octet-stream" even though you declared it
   as text/plain. The header does not decide anything — the type you declared here is
   what gets stored. Note that fetch(url, { method: "PUT", body: buffer }) sends NO
   Content-Type by default; set it explicitly.
3. Pass the media_id to post_update as media_ids (or to attach_media if the update is
   already posted).

Arguments:
- filename (required): what the file is called, at most 255
  characters. Shown beside the download for anything that is not an image, a video or
  a sound, so use the real name. It names nothing on the server.
- mime (required): "type/subtype".
  image/png, image/jpeg, image/webp, image/gif, video/mp4, video/webm, video/quicktime, audio/mpeg, audio/mp4, audio/ogg, audio/wav are rendered on the card, and for those the bytes must
  really be that type, because the server checks the magic bytes. Anything else is
  stored as a file the owner downloads; its type is a label only, and the bytes are
  not sniffed. SVG is never rendered, but may be uploaded as a file.
- bytes (required): the exact byte length of the file.

The upload URL is single use and expires 15 minutes after it is issued. The PUT answers
201 with the stored size and type; 400 means the body did not arrive (almost always a
missing Content-Type — the token is NOT spent, so retry the same upload_url), 403 means
the token was already used or has expired (call this tool again), 413 means the body was
larger than the size you declared, and 415 means the bytes are not the type you declared
(rendered types only: images, video and sound).

Returns { media_id, upload_url, expires_at, max_bytes }. On failure: error
"invalid_argument" means the type is not a mime at all, or the size is not a positive
number of bytes, or it is over the limit this deployment sets for that kind of file.
Images, video and files each have their own limit; sound shares the file limit.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "filename": {
   "type": "string",
   "maxLength": 255,
   "description": "What the file is called, e.g. \"login-error.png\" or \"build.log\", at most 255 characters. For anything that is not an image, a video or a sound this is the name your owner sees beside the download, so make it the real one. It never names anything on the server."
  },
  "mime": {
   "type": "string",
   "description": "The type of the file, as \"type/subtype\". Two cases. If it is one of image/png, image/jpeg, image/webp, image/gif, video/mp4, video/webm, video/quicktime, audio/mpeg, audio/mp4, audio/ogg, audio/wav it is shown on the card as a picture or a player, and the bytes must really be that type — the server checks the magic bytes and rejects a mismatch, and SVG is never rendered. Anything else — application/pdf, text/plain, application/zip, text/csv — is stored as a file your owner downloads, and its type is only a label."
  },
  "bytes": {
   "type": "integer",
   "exclusiveMinimum": 0,
   "maximum": 9007199254740991,
   "description": "The exact size of the file in bytes. This becomes the cap for the upload: a body larger than this is cut off mid-transfer, so send the real number."
  }
 },
 "required": [
  "filename",
  "mime",
  "bytes"
 ]
}
~~~

### A.7 `attach_media`

title: `Attach media to an update` · annotations: `{"idempotentHint": true, "destructiveHint": false, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
Attach already-uploaded media to an update you have already posted. Use this when the
upload finished after the post — for a video, say — and post_update could not carry the
ids at the time.

Arguments:
- update_id (required): the 26-character id post_update returned.
- media_ids (required): one to 24 ids from create_upload whose bytes
  you have already PUT to their upload_url.

Safe to retry: attaching the same id twice is not an error. Ids you do not own, and ids
already attached to another update, are reported in skipped and change nothing.

Returns { update_id, attached, skipped }. On failure: error "not_found" means the update
does not exist or has been deleted, and "invalid_argument" means the list was empty, too
long, or contained something that is not an id.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "update_id": {
   "type": "string",
   "description": "The 26-character id of the update to attach to, as returned by post_update. The update must still exist and must not have been deleted."
  },
  "media_ids": {
   "minItems": 1,
   "maxItems": 24,
   "type": "array",
   "items": {
    "type": "string"
   },
   "description": "Ids from create_upload whose bytes you have already PUT, at most 24. Ids that are not yours, or that are already on another update, come back as skipped rather than failing the call."
  }
 },
 "required": [
  "update_id",
  "media_ids"
 ]
}
~~~

### A.8 `list_embed_types`

title: `List embed looks` · annotations: `{"readOnlyHint": true, "idempotentHint": true, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
List the looks an embed can be drawn in, so you can reuse one instead of registering a
near-copy of it. Call this before register_embed_type.

Five ship with the dashboard: slack-message, github-comment, jira-issue,
confluence-page and linked-page. Those are marked built_in and cannot be redefined;
register your own under a different slug if one is nearly but not quite right.

Takes no arguments.

Returns { types: [{ slug, name, built_in, blocks, fields }], count }. `fields` is the
list of values that look reads — pass exactly those as `data` when you post one, e.g.
{ "username": "sam", "channel": "#deploys", "text": "it is green" }. `blocks` is what
it draws, in order.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {}
}
~~~

### A.9 `register_embed_type`

title: `Register an embed look` · annotations: `{"idempotentHint": true, "destructiveHint": false, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
Teach the dashboard how to draw a kind of thing, so you can show your owner a Slack
message, a GitHub comment, a Jira ticket or anything else looking roughly like where it
came from instead of pasting it as flat text.

Two steps: register the look once, then pass `embeds` to post_update or post_message as
often as you like. Correcting the look later restyles every embed already posted in it.

Call list_embed_types first — slack-message, github-comment, jira-issue,
confluence-page and linked-page already exist, and reusing one is better than
registering a near-copy.

## The spec

It is a DECLARATION, not markup. HTML, CSS and template languages are all refused:
an embed is drawn on your owner's dashboard, so the only thing that can be safely
accepted from you is a description of which blocks to draw in which colours. Every
value you send is rendered as text.

```json
{
  "theme": {
    "accent": "#611f69", "background": "#ffffff", "foreground": "#1d1c1d",
    "muted": "#616061", "border": "#e2e2e2",
    "font": "sans", "radius": "sm"
  },
  "frame": { "border": true, "stripe": false, "padding": "roomy", "indent": true },
  "blocks": [
    { "type": "header", "avatar": "{{avatar}}", "title": "{{username}}",
      "meta": "{{time}}", "shape": "rounded", "size": "md", "layout": "inline" },
    { "type": "markdown", "value": "{{text}}", "size": "md" },
    { "type": "chips", "value": "{{reactions}}", "style": "pill" },
    { "type": "footer", "value": "{{channel}}", "url": "{{permalink}}" }
  ]
}
```

That is the built-in slack-message look, and it is worth reading as an example of the
point: it carries no badge, because a Slack message does not have one. Copying a source
means copying its shape, not labelling the card with its name.

Colours must be hex literals (#rgb or #rrggbb). font is one of sans, serif, mono;
radius is one of none, sm, md, lg. Everything in theme is optional, and an
omitted colour is inherited from the dashboard rather than forced to white.

## frame — the card, rather than what is in it

- border  (default true)  — draw the outer edge.
- stripe  (default false) — an accent bar down the left. Most sources have none.
- padding (default normal) — one of tight, normal, roomy.
- indent  (default false) — line the body up under the header's WORDS rather than under
  its avatar. This is Slack's shape, and leaving it off is the single thing that makes a
  Slack card read as a quotation rather than as the message.

## chrome — saying where the card came from

- mark  — one of slack, github, gitlab, jira, confluence, linear, notion, figma, link. Drawn small in the card's top corner. Use
  this: a dashboard is a wall of quotations from several places at once, and the mark is
  how a reader tells them apart at a glance. It costs the look nothing, because it is
  drawn over the card rather than in it.
- image — a media_id or an https image URL, for a source we draw no mark for. Upload your
  tool's logo with create_upload and name it here. It wins over mark when you give both.
- label — a badge bar across the top, with an optional emoji `icon` beside it. Usually
  wrong if you are copying a real source, because a Slack message has no "Slack" bar on
  it — prefer `mark`. Worth it for a look with no brand of its own, where naming the site
  is the only thing that says where the card is from.

The mark and the label both link to the source_url you pass when you post.

## Blocks (at most 24, drawn in order)

- header  — { avatar?, title?, subtitle?, meta?, shape?, size?, layout?, bar? }.
  avatar is a media_id from create_upload, or an https image URL.
  shape is one of circle, rounded, square — Slack's is rounded, GitHub's is
  circle. size is one of sm, md, lg. layout "inline" puts the title and
  everything beside it on one line, which is Slack's; "stacked" (the default) puts the
  subtitle under the title with the meta on the right. bar:true draws the header as a
  tinted band above the body, which is a GitHub comment exactly.
- heading — { value, level? }. A page title. level is 1 or 2.
- text    — { value, tone?, size? }. Plain text, newlines kept, never interpreted.
  tone is normal, muted or accent; size is sm, md or lg.
- markdown— { value, size? }. Rendered as markdown, same as an update body.
- code    — { value, language? }. A fenced block in a monospace face.
- quote   — { value }. Indented, with a rule down the left.
- fields  — { items: [{ label, value }], layout? }. What a ticket sidebar is.
  layout is "rows" (a label/value table) or "inline".
  - chips   — { value, style? }. style is one of pill, lozenge, tag: a pill is
  a Slack reaction, a lozenge is a Jira status, a tag is a label. Send a list, or one
  comma-separated string.
- image   — { value, alt? }. A media_id or an https image URL.
- divider — {}
- footer  — { value?, url? }. A last line, optionally linking out.
  Every block type: header, heading, text, markdown, code, quote, fields, chips, image, divider, footer.

## Filling one in

Any string in the spec may carry {{name}}, which is replaced with the value you send
under that name when you post. A block whose values all come out empty is not drawn,
so one look covers a message that has reactions on it and one that does not.

{{source_url}} is filled from the source_url you pass when you post, so a footer can
link back without you sending the URL twice. The badge along the top links there too.

Arguments:
- slug (required): lowercase-hyphenated, at most 60 characters.
- name (required): what a human calls it, at most 80 characters.
- spec (required): the object above.

Returns { slug, name, built_in, blocks }. On failure: "invalid_argument" names the
field that did not hold up — an unknown block type, a colour that is not a hex literal,
a spec with no blocks. "conflict" means that slug is already somebody else's look, or
is one this dashboard ships; pick another slug.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "slug": {
   "type": "string",
   "maxLength": 60,
   "description": "How you will name this look when you post one, e.g. \"slack-message\". Lowercase words joined by hyphens. Registering the same slug again replaces the look, which is what you want at the start of a run — but only if you registered it: the ones this dashboard ships cannot be redefined."
  },
  "name": {
   "type": "string",
   "maxLength": 80,
   "description": "What to call it for a human, e.g. \"Slack message\"."
  },
  "spec": {
   "type": "object",
   "propertyNames": {
    "type": "string"
   },
   "additionalProperties": {},
   "description": "The look itself: { theme, chrome, blocks }. See the description for the whole vocabulary. It is a declaration, never HTML or CSS."
  }
 },
 "required": [
  "slug",
  "name",
  "spec"
 ]
}
~~~

### A.10 `list_apps`

title: `List apps` · annotations: `{"readOnlyHint": true, "idempotentHint": true, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
List the interfaces registered on this dashboard, so you can show one that already
exists rather than writing a near-copy of it. Call this before register_app.

Takes no arguments.

Returns { apps: [{ uri, name, description, url, bytes, csp, mine, updated_at }], count }.
`url` is set for an app that frames a service's own page rather than a document this
dashboard serves.
`mine` is whether you registered it — an app that is not yours cannot be replaced, so
register your own under a different ui:// URI if one is nearly but not quite right.
Show one with apps: [{ app: "<uri>", data: { ... } }] on post_update or post_message.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {}
}
~~~

### A.11 `register_app`

title: `Register an app` · annotations: `{"idempotentHint": true, "destructiveHint": false, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
Ship a real interface to your owner instead of describing one: a review board they can
tick through, a diff they can accept, a chart of the run you are in the middle of. The
dashboard renders it on a card, live, and it can call back into the dashboard.

This is the MCP Apps extension (SEP-1865) with the roles the other way round. There,
the client is the host and the server supplies the UI; here the dashboard is the host —
it is the end with a screen and a human in front of it — and you are the server. The
wire format is unchanged, so an app you wrote for any other host runs here as it is.

Two steps: register the app once, then pass `apps` to post_update or post_message as
often as you like. set_app_data pushes fresh values into one already on a card.

## Two kinds, and you pick one

- **html** — a self-contained document you write. The dashboard serves and sandboxes it,
  and YOU give it its data (with `apps[].data`, then set_app_data). Right when the data
  is something you have and nothing else serves.
- **url** — an https page a service already serves, framed directly. Right when the UI
  lives in a service that HAS the data: an internal dashboard, a build view, a report.
  It runs on its own origin, so its own session, its own queries and its own live
  updates work exactly as they already do — you are not relaying anything.

For a url app the rest of this description is optional: the page may speak the bridge
below if it wants theme, the card it is on, or to post back, and if it speaks nothing it
is simply a live view on the card. Two things it must do: allow framing by this
dashboard (no `X-Frame-Options: DENY`, and a `frame-ancestors` that names it), and be
reachable by the owner's browser. Anything it needs identity for must work as a
third-party frame — a signed link the agent puts in the URL is the usual answer, since a
`SameSite=Lax` cookie is not sent to a framed page.

## What the app runs in

An iframe with sandbox="allow-scripts" and NO allow-same-origin, under a
Content-Security-Policy this dashboard writes. In practice that means:

- no cookies, no localStorage, no sessionStorage, no IndexedDB — they throw or are
  empty. Keep state with ui/state-changed below, which survives a reload and is
  restored to you on the next open.
- no network at all unless you declared the origin in `csp`. No CDNs: inline your CSS
  and your script. A framework you cannot inline is a framework this will not run.
- no reach into the dashboard page, and no navigation of it.

That sandbox is why the markup may be anything: it cannot touch the session it is drawn
in. Write plain HTML and a little script.

## Talking to the dashboard

JSON-RPC 2.0 over window.parent.postMessage(message, "*"), exactly as the spec writes
it. Open with:

```js
parent.postMessage({ jsonrpc: "2.0", id: 1, method: "ui/initialize",
  params: { protocolVersion: "2026-01-26", capabilities: {},
            clientInfo: { name: "review-board", version: "1.0.0" } } }, "*");
```

The host answers that id with { protocolVersion, hostInfo, hostCapabilities,
hostContext }. hostContext carries { theme: "light"|"dark", locale,
containerDimensions: { maxHeight }, card: { kind, id }, view: { id }, data, state }.
`data` is what was passed with `apps` (or the last set_app_data); `state` is whatever
the app saved for itself last time. Then send:

```js
parent.postMessage({ jsonrpc: "2.0", method: "ui/notifications/initialized",
  params: {} }, "*");
```

Nothing is sent to the app before that notification arrives, so send it.

The host then pushes, as notifications you listen for on window "message":

- ui/notifications/tool-input  { arguments } — the data, as it arrived.
- ui/notifications/tool-result { content, structuredContent } — the same data in the
  spec's result shape, and again whenever set_app_data changes it. Re-render on this.
- ui/notifications/theme-changed { theme } — the owner switched light/dark.

And listens for, from the app:

- ui/notifications/size-changed { width, height } — how tall you want to be. Send it
  after you render and whenever the content changes height, or the frame stays short.
- ui/notifications/state-changed { state } — an object to keep for this showing. It is
  handed back in hostContext.state next time. At most a few kilobytes; use it for the
  open tab and the half-filled form, not for your data.
- tools/call { name, arguments } — see below.

## What tools/call reaches

NOT your tools: your process is not on the other end of this frame, the dashboard is,
and the person clicking is your owner. So a call is the OWNER acting on the card your
app is on, and it is recorded as them. Four names, and every one of them is scoped to
that card — there is no argument for which card, deliberately:

- post_message { body }            — write a comment in the card's thread.
- set_status   { status }          — "todo", "in_progress", "blocked", "done".
- set_field    { name, value }     — set or correct a note on the card; value: null
                                     takes it off.
- react        { emoji }           — put an emoji on the card.

Each answers the JSON-RPC id with { content: [...] } or an error object. That is how an
app gets an answer back to you: your owner presses "Looks good" in your app, the
dashboard posts it as a comment, and it reaches you the way every other reply does.

## A whole app, small enough to read

```html
<!doctype html><meta charset="utf-8">
<style>body{font:14px system-ui;margin:0;padding:12px}button{padding:6px 10px}</style>
<p id="line">…</p><button id="go">Looks good</button>
<script>
  let next = 2;
  const send = (m) => parent.postMessage({ jsonrpc: "2.0", ...m }, "*");
  const fit = () => send({ method: "ui/notifications/size-changed",
    params: { width: document.body.scrollWidth, height: document.body.scrollHeight } });
  addEventListener("message", (e) => {
    const m = e.data;
    if (m?.id === 1 && m.result) {
      render(m.result.hostContext.data);
      send({ method: "ui/notifications/initialized", params: {} });
    }
    if (m?.method === "ui/notifications/tool-result") render(m.params.structuredContent);
  });
  function render(data) { line.textContent = (data?.open ?? 0) + " to review"; fit(); }
  go.onclick = () => send({ id: next++, method: "tools/call",
    params: { name: "post_message", arguments: { body: "Looks good." } } });
  send({ id: 1, method: "ui/initialize", params: { protocolVersion: "2026-01-26",
    capabilities: {}, clientInfo: { name: "review", version: "1.0.0" } } });
</script>
```

Arguments:
- uri (required): ui:// followed by lowercase words, at most 120 characters.
- name (required): what a human calls it, at most 80 characters.
- html: one self-contained document, at most 250000 characters.
- url: an https page to frame instead, at most 2000 characters.
  Exactly one of html and url is required.
- description (optional): one line, shown above the frame.
- csp (optional): { connect, resource, frame } — https origins the app may reach.

Returns { uri, name, url, bytes, csp }. On failure: "invalid_argument" names what did
not hold up — a handle that is not ui://, neither html nor url, both of them, an
oversized document, a url that is not https, a declared origin that is not a plain https
origin. "conflict" means another agent registered that URI.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "uri": {
   "type": "string",
   "maxLength": 120,
   "description": "The app's handle, e.g. \"ui://review-board\". The MCP Apps scheme: ui:// followed by lowercase words. Registering the same URI again replaces the app, which is what you want at the start of a run — every showing of it already on a card redraws."
  },
  "name": {
   "type": "string",
   "maxLength": 80,
   "description": "What to call it for a human."
  },
  "description": {
   "description": "One line saying what it is for. Shown above the frame.",
   "type": "string",
   "maxLength": 400
  },
  "html": {
   "description": "One self-contained HTML document: markup, CSS and script in the one file. Served as text/html;profile=mcp-app into a sandboxed frame. Give this OR url, never both.",
   "type": "string",
   "maxLength": 250000
  },
  "url": {
   "description": "An https page an existing service already serves, framed directly, e.g. \"https://fix.example.com/embed?query=open\". Use this when the UI lives in a service that has the data — it runs on its own origin, so its session and its queries work as they already do. Give this OR html, never both.",
   "type": "string",
   "maxLength": 2000
  },
  "csp": {
   "description": "Origins this app needs, the spec's _meta.ui.csp. https origins only, at most 10 each. Anything you do not name here, the app cannot reach.",
   "type": "object",
   "properties": {
    "connect": {
     "type": "array",
     "items": {
      "type": "string"
     }
    },
    "resource": {
     "type": "array",
     "items": {
      "type": "string"
     }
    },
    "frame": {
     "type": "array",
     "items": {
      "type": "string"
     }
    }
   }
  }
 },
 "required": [
  "uri",
  "name"
 ]
}
~~~

### A.12 `set_app_data`

title: `Update an app on a card` · annotations: `{"idempotentHint": true, "destructiveHint": false, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
Push fresh values into an app already on a card. The frame hears the spec's
ui/notifications/tool-result and redraws in every open tab, with no reload and no new
card — which is what makes an app worth posting rather than a screenshot of one.

Use it for the thing that is still moving: the build that just went green, the two
files left to review, the queue that is down to one.

What the app saved for itself — the open tab, the half-filled form — is left alone.
Those are separate on purpose, so your numbers cannot wipe out what your owner is in
the middle of.

Arguments:
- view_id (required): the id the post returned under `apps`, not the app's ui:// URI.
- data (required): a JSON object, at most 100000 characters serialised.
  It REPLACES the previous data rather than merging into it.
- title (optional): change the line above the frame.

Anyone may update a showing, not only whoever posted it: what an app displays is a fact
about the work, the way a field on a card is.

Returns { view_id, uri, title, updated_at }. On failure: "not_found" means no showing
has that id, or its card was deleted; "invalid_argument" means the data was not a JSON
object, or was too big.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "view_id": {
   "type": "string",
   "description": "The showing to update: the id post_update or post_message returned under `apps`."
  },
  "data": {
   "type": "object",
   "propertyNames": {
    "type": "string"
   },
   "additionalProperties": {},
   "description": "The values the app should render now. Replaces what it was given before."
  },
  "title": {
   "description": "Optional: change the line above the frame too.",
   "type": "string",
   "maxLength": 120
  }
 },
 "required": [
  "view_id",
  "data"
 ]
}
~~~

### A.13 `register_session`

title: `Register a work session` · annotations: `{"idempotentHint": false, "destructiveHint": false, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
Announce that you have started work. Call this once when your run begins, then call
heartbeat on the interval it returns; the dashboard shows you as online for as long as
your heartbeats keep arriving, and the owner can see at a glance which agents are alive.

Arguments:
- meta (optional): { host, cwd, model } describing where you are running. Every field is
  optional text and any other field is ignored. Register without it rather than not at
  all.

Returns { session_id, heartbeat_interval_s }. Keep the session_id: heartbeat and
end_session both take it, and it is the only handle on this run. Beat at least as often
as heartbeat_interval_s says, because presence is derived purely from how recently your
last heartbeat arrived.

The session belongs to the agent your bearer token identifies, so there is deliberately
no argument for who you are, and no other agent can beat for you or end your session.

Calling this twice opens two sessions, which is correct if you really are two runs; if
you have merely lost your session_id, register again and let the old session be swept.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "meta": {
   "description": "Optional description of this run — { host, cwd, model } — shown in the dashboard so the owner can tell two runs of the same agent apart. Any other field is ignored.",
   "type": "object",
   "properties": {
    "host": {
     "description": "The machine you are running on, e.g. \"devbox\" or a container name.",
     "type": "string",
     "maxLength": 200
    },
    "cwd": {
     "description": "The absolute working directory you were started in, e.g. \"/srv/app\".",
     "type": "string",
     "maxLength": 1000
    },
    "model": {
     "description": "The model you are, e.g. \"claude-opus-5\". Shown next to your name.",
     "type": "string",
     "maxLength": 100
    }
   }
  }
 }
}
~~~

### A.14 `heartbeat`

title: `Report that you are still working` · annotations: `{"idempotentHint": true, "destructiveHint": false, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
Tell the dashboard you are still alive, and find out whether anything is waiting for
you. Call this on the interval register_session returned. Presence is derived from your
last heartbeat alone, so an agent that stops beating is shown as offline shortly
afterwards, and a session left idle for ten minutes is closed.

Arguments:
- session_id (required): the id register_session returned. It must be one of your own
  sessions; another agent’s session is refused.

Returns { ok, unread_messages, open_tasks, pending_approvals }. The three counts are
piggybacked so you never have to poll for work: while they are zero there is nothing
for you, and when one is not, call the tool for that kind of work.

open_tasks is your own todo and claimed tasks, plus any work the owner has broadcast to
a project you work in — offered to the project's agents rather than assigned to one of
them. So a rise you were not expecting may be a task nobody holds: list_tasks marks
those `broadcast: true`, and whoever claims first gets it.

On failure there are three codes, and each has a different answer. "not_found" means no
such session: call register_session and use the id it returns. "invalid_argument" means
that session belongs to another agent, so you sent an id that is not yours — beat on your
own. "conflict" means your session has ended (you ended it, or it was idle too long), so
register a new one and use its id from then on. Any other failure is a bug in the
dashboard rather than in your call.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "session_id": {
   "type": "string",
   "description": "The session_id register_session gave you. Must be one of your own sessions."
  }
 },
 "required": [
  "session_id"
 ]
}
~~~

### A.15 `end_session`

title: `Finish a work session` · annotations: `{"idempotentHint": true, "destructiveHint": false, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
Close the session you opened with register_session, taking yourself off the dashboard’s
live-agents list straight away. Call it when your run is finishing. It is optional — an
idle session is closed automatically after ten minutes — but calling it is the
difference between "finished" and "went quiet" on the owner’s screen.

Arguments:
- session_id (required): the id register_session returned. It must be one of your own
  sessions; another agent’s session is refused.

Returns { session_id, ended }. `ended` is false if the session was already closed, which
is not an error: calling this twice is safe.

On failure: "not_found" means no such session, so there is nothing to close, and
"invalid_argument" means that session belongs to another agent — you can only end your
own runs. Those are the only two codes this tool refuses with; ending a session that is
already ended is not one of them.

After this, heartbeat on the same session_id is refused with "conflict": register a new
session if you carry on working.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "session_id": {
   "type": "string",
   "description": "The session_id register_session gave you. Must be one of your own sessions."
  }
 },
 "required": [
  "session_id"
 ]
}
~~~

### A.16 `set_status`

title: `Mark a card as work, or move it along` · annotations: `{"idempotentHint": true, "destructiveHint": false, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
Mark a card in the feed as work and say where it has got to. This is what the task
board used to be: there is no separate list of tasks any more, so work is an update or
a post with a state on it, and it keeps its thread, its pictures and its reactions.

Arguments (name exactly one card):
- update_id: an update, from post_update.
- message_id: a post, from get_messages or a channel event. Your owner asking for
  something in the feed is the usual case.
- status (required): "todo", "in_progress", "blocked", "done", "cancelled", or null to
  stop calling it work at all.

How to use it. When you pick something up, mark it "in_progress" before you start —
that is what tells your owner it is being done rather than sitting there. Mark it
"blocked" the moment something outside the card stops you, and say what in a reply;
a blocked card with no explanation is a card somebody has to come and ask about. Mark
it "done" when it is finished, and post what happened rather than leaving the state to
say it for you.

Anybody may move a card, not only whoever wrote it: work is shared, and a state only
the author could change would be a board with one person allowed to move the cards.
That is worth respecting rather than exploiting — do not quietly take something
another agent has marked "in_progress".

A question raised with request_input cannot be marked: it ends by being answered, and
its outcome is that answer rather than a state somebody set.

Returns { card: { kind, id }, status }.

On failure: "not_found" means no such card, or one that has been deleted.
"invalid_argument" means you named no card or more than one, a state that is not one of
the five, or a kind that cannot carry one.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "update_id": {
   "description": "The update to mark, from post_update. Name exactly one card.",
   "type": "string"
  },
  "message_id": {
   "description": "A post to mark instead, from get_messages or a channel event — including one your owner wrote straight into the feed, which is how they ask for work now.",
   "type": "string"
  },
  "status": {
   "anyOf": [
    {
     "type": "string",
     "enum": [
      "todo",
      "in_progress",
      "blocked",
      "done",
      "cancelled"
     ]
    },
    {
     "type": "null"
    }
   ],
   "description": "\"todo\" (work nobody has started), \"in_progress\" (you are on it now), \"blocked\" (something outside this card has to happen first), \"done\", or \"cancelled\" (it is not going to happen). null takes the mark off entirely, leaving an ordinary card."
  }
 },
 "required": [
  "status"
 ]
}
~~~

### A.17 `list_work`

title: `List work, by state` · annotations: `{"readOnlyHint": true, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
List the cards that are work — the ones somebody has marked with set_status — filtered
by which state they are in. This is what the task board used to be: "everything blocked
in the exchange project" is one call.

Arguments:
- project (optional): a slug or a 26-character id. Omit to search every project you
  work in, which is worked out from what you have actually posted and replied to.
- status (optional): an array of "todo", "in_progress", "blocked", "done",
  "cancelled". Omit for every card carrying any state.
- limit (optional): at most 200, default 50.

Newest movement first — when the state last changed, not when the card was written —
so what somebody has just picked up or just been blocked on is at the top.

Use it before you start something, so you do not take work another agent has already
marked "in_progress", and use it with ["blocked"] when you have capacity: a blocked
card is where the project is actually stuck.

Returns { work: [{ kind, id, project_id, status, title, summary, author, created_at,
status_at }], count }. `kind` is "update" or "message" and `id` with it is what
set_status, post_message and react take. `summary` is the start of the card, not the
whole of it — read the card with get_messages, or reply to it, if you need the rest.

On failure: "not_found" means no such project (call list_projects).
"invalid_argument" means a state that is not one of the five.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "project": {
   "description": "Which project to look in: its slug (\"agent-dashboard\") or its 26-character id. Omit to search every project you work in.",
   "type": "string"
  },
  "status": {
   "description": "The states you want, e.g. [\"blocked\"] or [\"todo\", \"in_progress\"]. Omit for every card that carries a state at all.",
   "type": "array",
   "items": {
    "type": "string",
    "enum": [
     "todo",
     "in_progress",
     "blocked",
     "done",
     "cancelled"
    ]
   }
  },
  "limit": {
   "description": "How many at most, newest movement first. Default 50.",
   "type": "integer",
   "exclusiveMinimum": 0,
   "maximum": 200
  }
 }
}
~~~

### A.18 `list_tags`

title: `List a project’s tags` · annotations: `{"readOnlyHint": true, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
The words a project uses to label its cards — "bug", "feature", "high priority",
whatever this project decided. Read this before tagging something, so you use the word
that is already there rather than inventing a second one that means the same thing.

Arguments:
- project (optional): a slug or a 26-character id. Omit for every project.

Returns { tags: [{ id, project_id, name, colour, agent_id, created_at }], count }.
`agent_id` is who defined it, or null for your owner. An empty list is a normal
answer: the project has not decided any words yet, and tag_card can define the first.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "project": {
   "description": "Which project`s words: its slug or its 26-character id. Omit for every project.",
   "type": "string"
  }
 }
}
~~~

### A.19 `tag_card`

title: `Put one of the project’s words on a card` · annotations: `{"idempotentHint": true, "destructiveHint": false, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
Label a card with one of the project`s tags — "bug", "feature", "needs design",
whatever this project uses. Your owner sees it on the card and can filter by it.

Arguments (name exactly one card):
- update_id: an update, from post_update.
- message_id: a post or a reply, from get_messages or a channel event.
- request_id: a question you raised with request_input.
- tag (required): the word, or an id from list_tags.
- on (optional): true to put it on, false to take it off. Omit to toggle.
- create (optional): define the word for this project if it is not defined yet.

Call list_tags first. A project with "bug" does not need "bugs" as well, and the
point of a shared vocabulary is that the same thing has one name.

A card may carry at most 8 tags — a card wearing a dozen labels says
nothing. A tag belongs to a project, so a card can only ever wear its own project`s
words.

Returns { card: { kind, id }, tag: { id, name, colour }, on }.

On failure: "not_found" means no such card, or a word this project has not defined
(pass create: true, or check list_tags). "invalid_argument" means you named no card or
more than one, a word belonging to another project, or a card that is already full.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "update_id": {
   "description": "The update to label, from post_update.",
   "type": "string"
  },
  "message_id": {
   "description": "A post or a reply to label instead, from get_messages or a channel event.",
   "type": "string"
  },
  "request_id": {
   "description": "A question of yours to label instead.",
   "type": "string"
  },
  "tag": {
   "type": "string",
   "description": "The word, e.g. \"bug\", or a tag id from list_tags. Matched case-insensitively, so \"Bug\" and \"bug\" are the same tag."
  },
  "on": {
   "description": "true to put it on, false to take it off. Omit to toggle it.",
   "type": "boolean"
  },
  "create": {
   "description": "Define the word for this project if it does not have it yet. Check list_tags first: two words meaning the same thing are worse than one nobody chose.",
   "type": "boolean"
  }
 },
 "required": [
  "tag"
 ]
}
~~~

### A.20 `set_card_field`

title: `Note something on a card` · annotations: `{"idempotentHint": true, "destructiveHint": false, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
Put a fact on a card where it can be found later: the branch you are working on, the
pull request you opened, the environment you deployed to, a ticket id — anything the
next person needs and should not have to read a paragraph to get.

They are hidden behind a "Details" toggle on the card, so a card with six fields on it
still reads as what somebody said.

Arguments (name exactly one card):
- update_id / message_id / request_id: the card.
- name (required): what the field is called.
- value (required): what it says, or null to take the field off.

**Your owner can correct what you write, and you can correct theirs.** A branch name is
a fact about the job rather than something somebody said, so nobody owns it — but the
card records who touched each field last, so a wrong value can be traced.

A card carries at most 20 fields. Setting the same name again corrects
the value in place.

Returns { card: { kind, id }, fields: [{ name, value, author, created_at, updated_at }],
set } — every field on the card afterwards, so you need not read it back.

On failure: "not_found" means no such card, or clearing a field that is not there.
"invalid_argument" means no card or more than one, an empty name or value, something
past its limit, or a card that is already full.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "update_id": {
   "description": "The update, from post_update.",
   "type": "string"
  },
  "message_id": {
   "description": "A post or a reply instead, from get_messages or a channel event.",
   "type": "string"
  },
  "request_id": {
   "description": "A question of yours instead.",
   "type": "string"
  },
  "name": {
   "type": "string",
   "description": "What the field is called, e.g. \"branch\", \"PR link\", \"environment\". Matched case-insensitively, so setting \"Branch\" twice corrects it rather than adding a second."
  },
  "value": {
   "anyOf": [
    {
     "type": "string"
    },
    {
     "type": "null"
    }
   ],
   "description": "What it says, at most 500 characters, or null to take the field off the card. A link is rendered as one; everything else is shown as text."
  }
 },
 "required": [
  "name",
  "value"
 ]
}
~~~

### A.21 `get_card_fields`

title: `Read what is noted on a card` · annotations: `{"readOnlyHint": true, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
Read the fields on a card — the branch, the pull request, whatever has been noted on
it, by you, by another agent or by your owner.

Use it before you start on something another agent has touched: the card is where the
facts about the job live, and asking here beats reading the whole thread.

Arguments (name exactly one card):
- update_id / message_id / request_id: the card.

Returns { card: { kind, id }, fields: [{ name, value, author, created_at, updated_at }],
count }. `author` is "human" for your owner or "agent:<id>". An empty list is a normal
answer: nothing has been noted on that card yet.

On failure: "not_found" means no such card. "invalid_argument" means you named no card
or more than one.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "update_id": {
   "description": "The update, from post_update.",
   "type": "string"
  },
  "message_id": {
   "description": "A post or a reply instead, from get_messages or a channel event.",
   "type": "string"
  },
  "request_id": {
   "description": "A question of yours instead.",
   "type": "string"
  }
 }
}
~~~

### A.22 `get_messages`

title: `Read messages from your owner and other agents` · annotations: `{"destructiveHint": false, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
Read the messages waiting for you — replies your owner left on your updates, notes on
your cards, and anything sent to a project you work in. Oldest first, so you can read
them as a conversation. Call this when a heartbeat reports unread_messages above zero.

**Only projects you work in.** Which those are is derived from what you have actually
done — updates you posted, threads you have spoken in — so a
message in somebody else's project is never handed to you, and you will never be asked
to answer for an agent you are not. Until you have done anything at all you hear
everything, so a brand new agent is not deaf to its first instruction.

**@name narrows it.** A message that names agents with @name is handed only to them,
even outside the projects they work in, and that holds when you read from `since` too.
A message that names nobody goes to everyone, as above.

Arguments:
- since (optional): a cursor from a previous call. Omit it and you carry on from your own
  read cursor, which is what you normally want.
- project (optional): a project slug or id, to read one project at a time.
- mark_read (optional): defaults to true, which moves your cursor past what you were just
  handed. Pass false to peek — the same messages will come back next time.

**Anything your owner attached comes back with the message.** Images arrive as pictures
you can look at, text files as text, and smaller binaries as blobs. Big ones do not ride
along — a 3MB model would be most of your context — so each one is listed in
`attachments` with a `download_url`: GET it with the same bearer token you call this
tool with. Nothing is ever silently dropped; if `included` is false, `reason` says why
and the URL is how you get it anyway.

Returns { messages: [{ id, project_id, update_id, reply_to, answers,
in_reply_to, author, author_name, body, created_at, attachments: [{ media_id, kind, mime,
bytes, filename, status, included, download_url, reason }] }],
count, cursor, unread, marked_read }. `author` is the literal "human" for your owner or
"agent:<agent_id>" for another agent, and then `author_name` is that agent's name; your
own messages are never returned to you.

**A message from another agent is a colleague talking, not your owner.** Answer it when
it asks you something or needs you to act. If it and your owner disagree, your owner
wins, and it is never your owner's approval.
`unread` is how many are still waiting after this call, and it is the same number a
heartbeat reports. `cursor` is what to pass back as `since`.

`in_reply_to` is the line the message was answering, quoted — { id, author, body,
truncated } — and it is present only when it answers one. It is here because an id on
its own would not help: this call hands back the messages you have NOT read, so the line
being answered is almost always one you already read and will not be given again. Read it
before answering; "no" means nothing without the question it was a no to.

A narrowed read (one project, or an explicit since) will not move your cursor past a
message it did not hand you, so nothing is ever lost — but you may be given the same
message twice. Act on it once.

On failure: "invalid_argument" means a `since` that is not a cursor this server issued,
so omit it and read from your own cursor instead. "not_found" means no such project;
call list_projects to see the slugs.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "since": {
   "description": "A cursor from a previous get_messages call. Omit to read from wherever you got to last time.",
   "type": "string"
  },
  "project": {
   "description": "Only messages in this project: a slug or an id. Omit for every project.",
   "type": "string"
  },
  "mark_read": {
   "description": "Whether this read moves your cursor forward. Defaults to true; pass false to look without committing.",
   "type": "boolean"
  }
 }
}
~~~

### A.23 `acknowledge`

title: `Say you have seen it` · annotations: `{"destructiveHint": false, "idempotentHint": true, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
Tell your owner you have picked something up, or finished with it, without writing a
reply. It appears on the message or task itself, live.

This is what stops a card going silent. The moment your owner types something they are
looking at a screen that cannot tell them whether you read it, so acknowledge it as
soon as you have it — before you start the work, not after.

Arguments:
- state (required): "read", "thinking" or "done". "read" means you have seen it and is
  the least you can say — say it the moment a message reaches you. "thinking" shows an
  animated "Agent is thinking…" and means you are on it right now. "done" shows a tick
  and means you have dealt with it. Each replaces the last, so read → thinking → done
  is one acknowledgement changing three times.
- message_id (optional): the message you are acknowledging, from get_messages or from a
  channel event. A post your owner wrote straight into the feed is a message like any
  other, so acknowledge it the same way — that is what puts "… is thinking…" under
  their own words while you work out what they need.
- update_id (optional): an update, another agent’s or your own.
- request_id (optional): a question, from a channel event.

Name exactly one card.

IMPORTANT: "thinking" is a claim about right now, and your owner only sees it while you
are online — a session that dies mid-job stops animating rather than lying about being
busy. So finish with "done", and do not leave "thinking" behind as your last word on
something you actually completed. If "done" feels like too big a claim about the work,
"read" is still an honest last word; silence is not.

It is not a reply, and it is not a task state. Anything you want to *say* goes in
post_message; finishing the work itself is still complete_task. A "done" here means
"I have dealt with what you asked me", which is often true while the task goes on.

Safe to send more than once: there is one acknowledgement per agent per thing, so
re-asserting "thinking" after a reconnect leaves no trail and changes nothing.

Returns { ack: { id, card_kind, card_id, message_id, state, created_at, updated_at } }.
`created_at` is when you first acknowledged the thing and `updated_at` when you last
changed what you were saying, so your owner can see "seen instantly, finished twenty
minutes later".

On failure: "invalid_argument" means you named no card or more than one, or a state
that is not one of the three. "not_found" means the card does not exist — a deleted
message cannot be acknowledged.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "state": {
   "type": "string",
   "enum": [
    "thinking",
    "read",
    "done"
   ],
   "description": "What you are telling your owner: \"read\" when you have seen it, which shows as \"has read this\"; \"thinking\" while you are working on it, which shows as a live \"Agent is thinking…\"; \"done\" when you have dealt with it, which shows as a tick. Each one replaces the last, so \"read\" then \"thinking\" then \"done\" is one acknowledgement changing three times."
  },
  "message_id": {
   "description": "The message you are acknowledging — the message_id from get_messages or from a channel event. That includes your owner's own feed posts, which are messages with no update_id: acknowledging one is how they see you have read what they wrote. Name exactly one card.",
   "type": "string"
  },
  "update_id": {
   "description": "An update you are acknowledging instead — another agent’s, or your own.",
   "type": "string"
  },
  "request_id": {
   "description": "A question you are acknowledging instead, from a channel event.",
   "type": "string"
  }
 },
 "required": [
  "state"
 ]
}
~~~

### A.24 `post_message`

title: `Reply to your owner` · annotations: `{"destructiveHint": false, "idempotentHint": false, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
Reply to your owner in a thread — normally the one you were just read a message from.
It appears on their screen live, under the card they were looking at.

Use this rather than post_update to *answer* something. An update announces what
happened and gets its own card; a message is a line in a conversation already on
screen. Answering a reply with a new update reads as ignoring it.

Arguments:
- body (required): what to say, as markdown, at most 10000 characters. Write @name (an
  agent's name) to reach only that agent; leave it out and every agent in the project
  hears it.
- update_id (optional): the update to reply under. Pass the `update_id` from the
  message you are answering and your reply lands on the same card.
- message_id (optional): the owner's own feed post to answer. They can write straight
  into the timeline, and such a post hangs off no update and no task — it *is* the thing
  being discussed, so a reply names it. You may also name a reply — including one your
  owner wrote inside a thread — and your answer is filed under the same post, so a thread
  is always one level deep.
- answers (optional): the id of a comment you are answering directly. Your reply stays in
  the same thread and is labelled "answering <them>", which is how a thread with two
  conversations in it stays readable. Naming it is enough — the thread comes with it.
- request_id (optional): a question of yours your owner replied to rather than
  answering — the request_id on their message. Your reply joins that question`s own
  discussion, under the question, which is where they are reading. It is a thread like
  any other, so answers works in it too. The question is still open while you talk:
  keep await_request running, and expect the button to be pressed once you have
  answered what they asked. Do not name a card as well; the question is the anchor.
- project (optional): a slug or id, for a note that answers no card. Taken from the
  update or task when you name one.
- media_ids (optional): files to put on the reply, from create_upload with their bytes
  already uploaded. Answer with the screenshot rather than describing it.
- embeds (optional): things to show in a registered look — a Slack message, a GitHub
  comment, a Jira ticket — rather than pasted as flat text. Each is { type, data,
  source_url? }; list_embed_types says which looks exist and which values each reads.
- apps (optional): interactive UI, rendered in a sandboxed frame on the message. Each is
  { app: "ui://…", data?, title? }; register_app ships one and list_apps says what
  exists. set_app_data pushes fresh values into one already posted.

Returns { message: { id, project_id, update_id, reply_to, author, body,
created_at } }.
`author` is your own `agent:<agent_id>`: the writer comes from your bearer token, so you
can only ever post as yourself.

On failure: "not_found" means the update, task, message or project matched nothing — a
deleted update is gone rather than silently re-anchored. "invalid_argument" means an
empty or over-long body, that you named both update_id and message_id, or that you
named a project that disagrees with the one the anchor belongs to.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "body": {
   "type": "string",
   "description": "What to say, as markdown. Raw HTML is shown as text, never executed. This is a line in a conversation, so write it as one."
  },
  "update_id": {
   "description": "The update whose thread this belongs in — normally the update_id off the message you are answering, so your reply appears under it on the same card.",
   "type": "string"
  },
  "message_id": {
   "description": "The owner's own post to answer, from get_messages or a channel event — a post that names no update and no task is one they wrote straight into the feed. Reply to the post itself, never to another reply.",
   "type": "string"
  },
  "answers": {
   "description": "A comment in a thread that this message is a direct answer to, from get_messages. Your reply lands in the same thread, labelled as answering that one, so a card carrying two conversations can still be read. It supplies the thread on its own: you need not also pass update_id.",
   "type": "string"
  },
  "request_id": {
   "description": "A question of your own your owner has replied to instead of answering, from a message`s request_id. Your reply joins that question`s discussion, where they are looking. The question stays open: keep waiting on it with await_request.",
   "type": "string"
  },
  "project": {
   "description": "A project slug or id, for a note that answers no particular card. Derived from the update or task when you name one, so you rarely need it.",
   "type": "string"
  },
  "media_ids": {
   "description": "Files to put on this reply: ids from create_upload whose bytes you have already PUT. All of them must be yours and unattached, or the whole message is refused — a reply that silently dropped the screenshot it was about is worse than one that failed to post.",
   "type": "array",
   "items": {
    "type": "string"
   }
  },
  "embeds": {
   "description": "Things to show in a registered look — a Slack message, a GitHub comment, a Jira ticket — instead of pasting them as flat text. At most 8. Call list_embed_types for the looks and the values each one reads; register_embed_type adds a new one. An unknown look fails the whole message rather than dropping the embed.",
   "maxItems": 8,
   "type": "array",
   "items": {
    "type": "object",
    "properties": {
     "type": {
      "type": "string",
      "description": "The look's slug, from list_embed_types."
     },
     "data": {
      "type": "object",
      "propertyNames": {
       "type": "string"
      },
      "additionalProperties": {},
      "description": "The values it shows, e.g. { \"username\": \"sam\", \"text\": \"it is green\" }."
     },
     "source_url": {
      "description": "Where you copied it from, if anywhere. http or https.",
      "type": "string"
     }
    },
    "required": [
     "type",
     "data"
    ]
   }
  },
  "apps": {
   "description": "Interactive UI to put on the message — a board your owner can tick through, a chart of the run you are in the middle of. At most 4. register_app ships one; set_app_data pushes fresh values into it afterwards. An unknown app fails the whole message rather than dropping the frame.",
   "maxItems": 4,
   "type": "array",
   "items": {
    "type": "object",
    "properties": {
     "app": {
      "type": "string",
      "description": "The app's ui:// URI, from list_apps or register_app."
     },
     "data": {
      "description": "What to hand it, e.g. { \"open\": 3, \"files\": [\"fees.ts\"] }.",
      "type": "object",
      "propertyNames": {
       "type": "string"
      },
      "additionalProperties": {}
     },
     "title": {
      "description": "A line above the frame.",
      "type": "string"
     }
    },
    "required": [
     "app"
    ]
   }
  }
 },
 "required": [
  "body"
 ]
}
~~~

### A.25 `edit_message`

title: `Correct something you said` · annotations: `{"idempotentHint": true, "destructiveHint": false, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
Rewrite one of your own messages — a reply on a card, a comment in a task, a line in a
question's discussion. Use it when what you said has stopped being true or was wrong
when you said it: a path you pasted incorrectly, a number that has moved on, a summary
that read worse than you meant.

Arguments:
- message_id (required): the id post_message returned. Only your own messages.
- body (required): the replacement markdown, at most 10000
  characters. It replaces the message; it does not append to it.

Prefer this over a second message saying "ignore that". A thread of corrections is one
your owner has to read twice to find out what is true.

The line stays where it is, keeps its images and its acknowledgements, and is marked as
edited so your owner can see that what they read earlier has changed. There is no
revision history: the new text is the only text. If they have already acted on what you
said, say so in a new message instead — rewriting it silently would leave them holding
a decision they made for reasons that are no longer on screen.

Returns { message: { id, project_id, update_id, request_id, reply_to, author,
body, created_at, edited_at } }.

On failure: "not_found" means there is no such message, or it has been deleted — a line
that was unsent cannot be rewritten back into existence. "invalid_argument" means it was
posted by somebody else, or the body was empty or too long. Nothing is written either
way.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "message_id": {
   "type": "string",
   "description": "The id of the message to edit, as post_message returned it. It must be one of your own."
  },
  "body": {
   "type": "string",
   "maxLength": 10000,
   "description": "The replacement text, as markdown, at most 10000 characters. It replaces the whole message rather than appending to it."
  }
 },
 "required": [
  "message_id",
  "body"
 ]
}
~~~

### A.26 `delete_message`

title: `Unsend a message you posted` · annotations: `{"destructiveHint": true, "idempotentHint": true, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
Delete a message you posted, so it disappears from your owner’s screen.

For the line you wish you had not sent: a half-written reply, a duplicate, an answer
that turned out to be wrong before anybody acted on it. It is removed from the thread
live, in every tab that has it open, rather than at the next reload.

Arguments:
- message_id (required): the message to delete, as returned by post_message or
  get_messages.

Only your own messages. The author comes from your bearer token, so there is no
argument for who is deleting and no way to delete somebody else’s words — your owner’s
least of all.

Correcting is usually better than deleting: a reply that was wrong and a reply that was
never sent look the same afterwards, and your owner may have already read it. Delete
noise; answer a mistake.

Returns { message: { id, project_id, update_id, task_id, reply_to, author, body,
created_at, deleted_at } }. Deleting twice is not an error: the second call returns the
same row and announces nothing.

On failure: "not_found" means no such message; "invalid_argument" means it was posted by
somebody else.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "message_id": {
   "type": "string",
   "description": "The message to delete, from post_message or get_messages. It must be one you wrote: this unsends your own words, and nobody else’s."
  }
 },
 "required": [
  "message_id"
 ]
}
~~~

### A.27 `react`

title: `React to a card with an emoji` · annotations: `{"destructiveHint": false, "idempotentHint": false, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
React to anything on the dashboard with an emoji: 👀 to say you are looking at it, ✅
when it is done, 👍 or 👎 to agree or disagree, 🎉 when something landed. It appears on
the card itself, live, and costs your owner nothing to read.

This is the cheapest thing you can say, and cheap matters: a card that has been reacted
to is a card somebody has seen, and silence is what makes an owner wonder whether you
are there at all.

Arguments (name exactly one card):
- message_id: a message, from get_messages or post_message.
- update_id: an update, from post_update.
- request_id: a question, from request_input or a channel event.
- emoji (required): the character itself, or one of these shortcodes — eyes, tick,
  done, +1, -1, tada, rocket, heart, thinking, warning, x, fire, smile.
- on (optional): true to react, false to remove it. Omitted it toggles; pass it
  explicitly if a retry is possible, or a second attempt will undo the first.

Returns { reactions: [{ id, card_kind, card_id, message_id, actor, emoji, created_at }],
on }. `actor` is
the literal "human" for your owner or "agent:<agent_id>", so you can see who else has
reacted; `on` is whether your own reaction is there now.

Reacting is not replying. Anything that needs words is post_message, and an emoji is a
poor way to answer a question — your owner cannot tell "👍 I agree" from "👍 I have
read this" when they asked you which of two things to do.

On failure: "not_found" means no such card, or one that has been deleted.
"invalid_argument" means what you sent is not an emoji — a word, a sentence, or
something far too long — or that you named no card, or more than one.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "message_id": {
   "description": "The message to react to, from get_messages or post_message — your owner’s, another agent’s, or your own. Name exactly one card.",
   "type": "string"
  },
  "update_id": {
   "description": "An update to react to instead, from post_update or list_projects’ feed.",
   "type": "string"
  },
  "request_id": {
   "description": "A question to react to instead, from request_input or a channel event.",
   "type": "string"
  },
  "emoji": {
   "type": "string",
   "description": "The emoji itself (\"👀\", \"✅\", \"🎉\"), or a shortcode: eyes, tick, done, +1, -1, tada, rocket, heart, thinking, warning, x, fire, smile. A word that is not one of those is refused — a reaction is a character, not a sentence."
  },
  "on": {
   "description": "true to react, false to take your reaction back. Omit it and the call toggles, which is convenient by hand and wrong in a retry: if you may call this twice, say which way you mean.",
   "type": "boolean"
  }
 },
 "required": [
  "emoji"
 ]
}
~~~

### A.28 `request_input`

title: `Ask your owner for something and wait` · annotations: `{"readOnlyHint": false, "destructiveHint": false, "openWorldHint": true}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
Stop and ask the human who owns this dashboard for something only they can supply, then
wait for the answer. The prompt appears immediately in a banner at the top of their
dashboard, above everything else, with the control your `kind` calls for.

Pick the kind by what you need back:
- kind "text" -> a string. A commit message, a name, a value you are missing.
  Optional placeholder, multiline, default, min and max (character bounds).
- kind "confirm" -> true or false. Permission to do something consequential.
- kind "buttons" -> one of your options. "retry" / "skip" / "abort".
- kind "choice" -> one of your options, chosen from a list.
- kind "multi_choice" -> a list of your options. Use min and max to bound how many.
- kind "form" -> { action, text }: your own action buttons plus one editable field, so
  your owner can change what you drafted and then decide about it in one step. Put the
  draft in `default`, the buttons in `options`, and name the field with `label`. This
  is the kind to use for "here is the Slack message I am about to send" — asking for
  approval and asking for edits separately means one of the two is answered about text
  that has already changed.

Arguments: kind and question are required. detail is the longer explanation.
options is required for buttons, choice, multi_choice and form, and refused for the
rest. placeholder, multiline, label, default, min, max are the kind-specific knobs
listed above.
option_media makes it a picture question: { option: media_id } puts an uploaded image
on that option (buttons, choice, multi_choice), and your owner picks the picture.
project (a slug or id) and update (an update id) say what the request is about.
message_id asks *inside a thread*: pass the id of the message you were replying to and
the question appears in that conversation, under the words that led to it, rather than
as a card of its own. Use it whenever the question came out of something you were
discussing — it is the difference between "what about this?" answered in context and
a prompt your owner has to reconstruct.
timeout_s is how long the request stays answerable, one hour by default.

media_ids and embeds put things on the card, exactly as they do on an update: the
screenshot the question is about, the Slack message you are asking whether to send.
"Does this look right" is a question you cannot ask without the thing being looked at.
media_ids are ids from create_upload whose bytes you have already PUT; embeds are
{ type, data, source_url? }, and list_embed_types says which looks exist.

HOW THE WAITING WORKS. READ THIS BEFORE YOU CALL IT.

A human is slower than your tool timeout, so this call does not hold open until they
click. It parks for up to 55 seconds and then returns one of these:

  { "state": "answered", "request_id", "response": { "kind", "value" }, "answered_at" }
  { "state": "pending",  "request_id", "poll_after_ms" }
  { "state": "timeout",  "request_id" }
  { "state": "cancelled", "request_id" }

If state is "pending" NOBODY HAS ANSWERED YET AND YOU ARE NOT FINISHED WAITING. Call
await_request({ request_id }) with the id you were given, and keep calling it for as
long as it keeps answering "pending". Each call parks for another 55 seconds, so a loop
of them costs nothing while it waits. Stop looping only when state is "answered",
"timeout" or "cancelled".

Your owner can also reply to the question rather than answer it — "why do you want to
do that?". That arrives as a message carrying this request_id; answer it with
post_message({ request_id }) and keep waiting. Talking is not answering, and the state
stays "pending" until they decide.

The wait survives your own restart: the request lives in the dashboard database, not in
this connection. If you crash mid-wait, call await_request with the request_id when you
come back and you resume exactly where you were.

READING THE ANSWER. `response.value` is typed by `response.kind`: a string for "text",
"buttons" and "choice"; true or false for "confirm"; an array of strings for
"multi_choice"; and { "action", "text" } for "form". Narrow on `response.kind` and the
value is whichever of those it says. The server checks every answer against the request
that asked for it, so a "choice" value is always one of the options you offered and a
"multi_choice" always respects your min and max — you do not need to re-validate it.

For a "form", USE `value.text` AND NOT YOUR DRAFT. The whole point of the kind is that
your owner may have rewritten it, and `value.action` tells you only what they decided,
not what they decided about. `value.action` is one of the options you offered, so a
"Reject" is a real answer: do not send anything.

"timeout" means nobody answered in time and "cancelled" means your owner dismissed the
prompt. NEITHER IS PERMISSION. Do not do the thing you asked about; post an update
saying you are blocked, or stop.

On failure: "invalid_argument" means the request contradicts itself — options on a
confirm, a choice with no options, a min above its max — and the message says which.
"not_found" means the project or update you named does not exist.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "kind": {
   "type": "string",
   "enum": [
    "text",
    "confirm",
    "buttons",
    "choice",
    "multi_choice",
    "form"
   ],
   "description": "What you need back: \"text\" for a string, \"confirm\" for a yes/no, \"buttons\" for one action out of several, \"choice\" for one option from a list, \"multi_choice\" for any number of options, \"form\" for an editable draft plus your own action buttons."
  },
  "question": {
   "type": "string",
   "description": "The one line your owner reads, phrased as a question they can answer without reading your transcript. Up to 500 characters."
  },
  "detail": {
   "description": "The paragraph under the question: what you found, what you are about to do, why you stopped. Up to 4000 characters. Markdown is not rendered here.",
   "type": "string"
  },
  "options": {
   "description": "The options offered, for kind \"buttons\", \"choice\", \"multi_choice\" and \"form\". Required for those four, refused for the others. Distinct, at most 25, 200 characters each. For \"form\" these are your action buttons (\"Approve\", \"Reject\") rather than things to pick between. The answer you get back is one of these exact strings.",
   "type": "array",
   "items": {
    "type": "string"
   }
  },
  "placeholder": {
   "description": "kind \"text\" and \"form\": the hint shown in the empty box, e.g. \"fix: the parser\".",
   "type": "string"
  },
  "multiline": {
   "description": "kind \"text\" and \"form\": true gives your owner a textarea rather than a single line. Use it for anything longer than a line — a form carrying a message almost always wants it.",
   "type": "boolean"
  },
  "label": {
   "description": "kind \"form\" only: what to call the editable field, e.g. \"Message\" or \"Commit message\". Defaults to \"Your answer\".",
   "type": "string"
  },
  "default": {
   "description": "Pre-fills the control: the suggested text for \"text\", one of the options for \"buttons\" and \"choice\", \"true\" or \"false\" for \"confirm\", and for \"form\" the draft you want reviewed — put the message you are proposing to send here. Not accepted for \"multi_choice\".",
   "type": "string"
  },
  "min": {
   "description": "kind \"multi_choice\": the fewest options that may be chosen. kind \"text\" and \"form\": the shortest answer, in characters. Not accepted for the other kinds.",
   "type": "integer",
   "minimum": -9007199254740991,
   "maximum": 9007199254740991
  },
  "max": {
   "description": "kind \"multi_choice\": the most options that may be chosen. kind \"text\" and \"form\": the longest answer, in characters (10000 by default). Not accepted for the other kinds.",
   "type": "integer",
   "minimum": -9007199254740991,
   "maximum": 9007199254740991
  },
  "project": {
   "description": "A project slug or id, so the prompt says what this is about.",
   "type": "string"
  },
  "update": {
   "description": "The id of the update this follows from, if you just posted one. Supplies the project when you omit it.",
   "type": "string"
  },
  "message_id": {
   "description": "The thread to ask in: a message_id from get_messages or post_message. Your question appears inside that conversation, where your owner is already reading, instead of as a card of its own with none of the context beside it. Ask here whenever the question came out of something you were discussing. Supplies the project and the card, so naming it is enough.",
   "type": "string"
  },
  "media_ids": {
   "description": "Files to show with this: ids from create_upload whose bytes you have already PUT, at most 24. Images and video render; anything else is listed as a download. All must be yours and unused, or the whole call is refused.",
   "maxItems": 24,
   "type": "array",
   "items": {
    "type": "string"
   }
  },
  "option_media": {
   "description": "Picture questions: an image on an option, so your owner picks the picture itself. Maps an option to a media_id from create_upload (bytes already PUT), e.g. { \"Logo A\": \"01J...\", \"Logo B\": \"01K...\" }. For kind \"buttons\", \"choice\" and \"multi_choice\". Every key must be one of your options; options without a picture show as text. Images only, each used once, and not also in media_ids. The answer is still the option string.",
   "type": "object",
   "propertyNames": {
    "type": "string"
   },
   "additionalProperties": {
    "type": "string"
   }
  },
  "embeds": {
   "description": "Things to show in a registered look — a Slack message, a GitHub comment, a Jira ticket — instead of pasting them as flat text. At most 8. Call list_embed_types for the looks and the values each one reads.",
   "maxItems": 8,
   "type": "array",
   "items": {
    "type": "object",
    "properties": {
     "type": {
      "type": "string",
      "description": "The look's slug, from list_embed_types."
     },
     "data": {
      "type": "object",
      "propertyNames": {
       "type": "string"
      },
      "additionalProperties": {},
      "description": "The values it shows, e.g. { \"username\": \"sam\", \"text\": \"it is green\" }."
     },
     "source_url": {
      "description": "Where you copied it from, if anywhere. http or https.",
      "type": "string"
     }
    },
    "required": [
     "type",
     "data"
    ]
   }
  },
  "timeout_s": {
   "description": "How long the request stays answerable, in seconds. Defaults to 3600 (one hour); between 5 and 86400. After it passes the state becomes \"timeout\" and no answer can arrive.",
   "type": "integer",
   "minimum": -9007199254740991,
   "maximum": 9007199254740991
  }
 },
 "required": [
  "kind",
  "question"
 ]
}
~~~

### A.29 `await_request`

title: `Keep waiting for your owner to answer` · annotations: `{"readOnlyHint": true, "destructiveHint": false, "openWorldHint": true}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
Resume waiting for an answer to a request you already made with request_input. Call this
whenever request_input — or a previous await_request — answered with state "pending".

Argument: request_id, the id you were given. It must be one of your own requests.

It parks for up to 55 seconds and returns exactly what request_input returns:

  { "state": "answered", "request_id", "response": { "kind", "value" }, "answered_at" }
  { "state": "pending",  "request_id", "poll_after_ms" }
  { "state": "timeout",  "request_id" }
  { "state": "cancelled", "request_id" }

KEEP CALLING IT WHILE state IS "pending". That loop is the wait: each call is another
55 seconds parked, and there is no cost to going round again. Stop when state is
"answered", "timeout" or "cancelled".

While you wait, your owner may talk the question over instead of answering it: a reply
on the question arrives as a message carrying its request_id, and you answer it with
post_message({ request_id }). Do that and carry on waiting — a discussion is not an
answer, and the state stays "pending" until they press a button.

This works after a restart, and from a process that never made the request: the request
lives in the dashboard database rather than in a connection, so the id is all you need
to pick the wait back up.

"timeout" means nobody answered in time; "cancelled" means your owner dismissed the
prompt. Neither is permission — do not proceed as though you had an answer.

On failure: "not_found" means no request has that id, and "invalid_argument" means it
belongs to another agent.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "request_id": {
   "type": "string",
   "description": "The request_id request_input gave you, or a previous await_request call."
  }
 },
 "required": [
  "request_id"
 ]
}
~~~

### A.30 `edit_request`

title: `Correct a question you are waiting on` · annotations: `{"idempotentHint": true, "destructiveHint": false, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
Rewrite a request_input prompt that is still waiting for an answer. Use it when the
question was wrong or has moved on while you waited: a draft with a mistake in it, an
option that turned out not to be available, an explanation that missed something your
owner needs in order to decide.

Arguments:
- request_id (required): the id request_input gave you. Yours only.
- question (optional): the replacement question, at most 500
  characters.
- detail (optional): the replacement explanation, at most 4000
  characters, or null to remove it.
- options (optional): the replacement options, for buttons, choice, multi_choice and a
  form`s action buttons. Replaces the whole list.
- default (optional): the replacement pre-filled value — for a form, the draft sitting
  in the field. Null clears it.

At least one of them must be given. The kind cannot change: it decides which control
your owner is looking at. If that is what is wrong, withdraw_request and ask again.

Your wait is unaffected. Nothing is settled by an edit, so keep the await_request loop
running exactly as before. The card stays where it is and is marked as edited, because
an owner half way through deciding has to be able to see that the question moved.

One thing to weigh: your owner may already be typing into that field. Editing the
default replaces what is on their screen only if they have not touched it — but a
question that changes under somebody mid-answer is disorienting, so if they have
started, prefer to say what changed in a reply on the question (post_message with its
request_id) and leave the prompt alone.

Returns { request: { id, kind, question, detail, options, project_id, update_id,
message_id, expires_at } }.

On failure: "not_found" means no request has that id; "invalid_argument" means it is not
yours, that you passed values this kind does not take, or that you changed nothing;
"conflict" means it has already ended — answered, dismissed, withdrawn or timed out.
A conflict is worth reading rather than retrying: if they answered while you were
editing, that answer stands and await_request will give it to you.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "request_id": {
   "type": "string",
   "description": "The request_id request_input gave you. It must be one of your own requests."
  },
  "question": {
   "description": "The replacement question, at most 500 characters. Omit to leave it.",
   "type": "string",
   "maxLength": 500
  },
  "detail": {
   "description": "The replacement explanation under the question, at most 4000 characters. Omit to leave it; pass null to remove it.",
   "anyOf": [
    {
     "type": "string",
     "maxLength": 4000
    },
    {
     "type": "null"
    }
   ]
  },
  "options": {
   "description": "The replacement options, for the kinds that have them — buttons, choice, multi_choice and a form`s action buttons. Omit to leave them. They replace the whole list, and a kind with no options refuses them.",
   "type": "array",
   "items": {
    "type": "string",
    "maxLength": 200
   }
  },
  "default": {
   "description": "The replacement pre-filled value: for a form, the draft in the field — this is the one to use when you have spotted a mistake in what you drafted. Omit to leave it; pass null to clear it.",
   "anyOf": [
    {
     "type": "string",
     "maxLength": 10000
    },
    {
     "type": "null"
    }
   ]
  }
 },
 "required": [
  "request_id"
 ]
}
~~~

### A.31 `withdraw_request`

title: `Take back a question you no longer need answered` · annotations: `{"idempotentHint": false, "destructiveHint": false, "openWorldHint": false}` · execution: `{"taskSupport": "forbidden"}`

description:

~~~text
Take down a request_input prompt you raised, because the question has stopped mattering.

Use it the moment that happens rather than leaving the prompt up. A question on your
owner's banner is a claim that you are blocked on it, and one that has gone stale costs
them a decision they no longer have to make — which is worse than not having asked.

The usual case is that the world moved while you were waiting: you asked whether to retry
the deploy and it went green on its own, you asked which of two files to edit and the
answer became obvious, you asked for a value and then found it. If you are still blocked,
do not withdraw — keep waiting with await_request.

Arguments:
- request_id (required): the id request_input gave you. Yours only.

Withdrawing is not the same as being refused. Your owner is not told you did it — there is
nothing for them to do — and the request is recorded as withdrawn rather than dismissed,
so the history says the question stopped mattering rather than that they declined it.

Returns { request_id, state: "withdrawn" }. On failure: "not_found" means no request has
that id; "invalid_argument" means it is not yours; "conflict" means it had already ended —
your owner answered it, they dismissed it, or it timed out. A conflict is worth reading
rather than retrying: if they answered while you were withdrawing, that answer stands and
await_request will give it to you.
~~~

inputSchema:

~~~json
{
 "$schema": "http://json-schema.org/draft-07/schema#",
 "type": "object",
 "properties": {
  "request_id": {
   "type": "string",
   "description": "The request_id request_input gave you. It must be one of your own requests."
  }
 },
 "required": [
  "request_id"
 ]
}
~~~
