# Secrets

Agents and the owner share secrets (API keys, passwords, `.env` files) through the dashboard.
The value is stored encrypted, and it never appears in anything an agent reads back: no MCP tool
result and no command output carries it. That matters because an agent's tool results and
shell output go into its conversation transcript.

## How a value moves

Think of a cloakroom ticket. A tool hands the agent a ticket (a URL), never the coat (the value).
The agent hands the ticket to `curl`, and `curl` puts the coat straight into a file.

| Who gives | Who gets | How |
|---|---|---|
| Owner, in the app | Agent | `secret_url(name)` returns a single-use URL. The agent runs `curl -fsS <url> -o .env.local` (or `export X="$(curl -fsS <url>)"`). |
| Agent asks the owner | Agent | `request_secret(name, question)` shows a masked box in the app. The agent learns only that `name` is ready, then uses `secret_url`. |
| Agent | Owner | `create_secret_upload(name)` returns a single-use URL. The agent runs `curl -fsS -T file <url>`, then attaches it to its reply (below). |
| Agent, in a thread | Owner | `post_message` or `post_update` with `secrets: ["NAME"]`. The owner sees a locked chip and opens it in place (below). |

`list_secrets` returns names, notes and dates only.

## Sharing in a thread

An agent hands the owner a secret where they are already talking, not in Settings. It attaches
the secret to a post by name: `post_message({..., secrets: ["STRIPE_TEST_KEY"]})` (or
`post_update`), up to 8. Each must be a secret the agent may read itself (`list_secrets`), and not
paused; anything else refuses the whole post. A name that is not one of the agent's is not echoed
back in the error, in case an agent passed a value by mistake.

The post carries the name only (migration 066, `card_secrets`: the card, the secret's id and name,
and the agent). The owner's app draws a chip, "🔒 STRIPE_TEST_KEY — Reveal · Copy". Nothing is
fetched until they tap. On a phone the app first asks for Face ID, a fingerprint or the passcode.
Reveal shows the value in place for 30 seconds (or until the app loses focus); Copy puts it on the
clipboard and clears it after a minute. Agents, including other agents in the channel, see only the
name: in the post's tool result and in `get_messages` it is `secrets: ["NAME"]`.

`POST /api/cards/<update|message>/<id>/secrets/<NAME>/reveal` `{purpose: "reveal" | "copy"}` opens
it. It needs an owner's or admin's device key or browser cookie: a member gets 403, an agent's
token 401, and another team's key finds nothing. Each opening is in the secret's audit
(`revealed` or `copied`), with the device and "in message <id>, by <person>". Sharing is audited
too (`shared`, by the agent).

A share is checked again on every read. Once the secret is deleted or paused, or the agent that
shared it loses it (or its token), the chip says "No longer available" and reveal answers 410
`secret_unavailable`. Deleting the post does the same.

## Storage

- **Key.** `SECRETS_KEY` (at least 32 characters; `openssl rand -base64 32`). The key that
  wraps everything (the KEK) is HKDF-SHA256 of it. Without `SECRETS_KEY` the feature is off:
  the tools and routes say so, and nothing is stored.
- **Envelope.** Each secret has its own random 256-bit data key. The value is sealed with
  AES-256-GCM under that data key; the data key is sealed with AES-256-GCM under the KEK. Both
  seals carry the secret's id as associated data, so a sealed value cannot be moved onto another
  row. Each row records the KEK's id (a fingerprint, not the key).
- **Rotation.** Set the new key as `SECRETS_KEY` and the old one in `SECRETS_KEY_OLD`
  (comma-separated for more than one). At start the server re-wraps every data key under the
  new KEK. Once the log says it is done, remove `SECRETS_KEY_OLD`.
- **Columns.** Sealed data is base64 text, so the SQLite to Postgres copy and the export carry it
  unchanged and still encrypted. An export is useless without `SECRETS_KEY`; keep the key
  somewhere other than the backups.
- **Never in plain text:** not in logs, events, push notifications, request rows, exports or
  error messages. Events carry only the secret's id.

## Access

- **Scope.** A secret is for all agents, or for named agents. Not per project: on this server
  an agent can post into any project, so a project is not a lock. (Revisit when channels have
  members.)
- **URLs.** 32 random bytes in the path, stored only as an HMAC hash, bound to one agent and
  one secret. Single use, expire after 5 minutes. A download re-checks the scope, that the
  secret is not paused and that the agent's token is not revoked. HEAD never spends one.
  The URL is the only permission a download needs. Asking for the agent's token as well would
  mean the agent typing that token into its command, which puts a long-lived credential in the
  transcript to protect a five-minute one.
- **Who may write.** An agent replaces only a secret it uploaded, while it may still read it,
  it is not paused, and the owner has not changed it (any change by the owner makes it the
  owner's). Answering `request_secret` never shares an existing secret with a new reader: the
  name must be new, or one the asking agent can already read.
- **Pause and delete.** The owner can pause a secret (no agent gets it until resumed) or delete
  it (the sealed value is gone; the audit stays).
- **Reveal.** The owner's reveal and copy go through `POST /api/secrets/<id>/reveal`, behind
  Face ID / Touch ID / device auth in the app where the platform has it.
- **Audit.** Every URL issued, download, upload, reveal, copy, change and refusal is logged:
  what, when, which agent or which device, and for a link, the address and User-Agent that
  used it. A second use of a spent link is logged as a refusal: a sign the link leaked.
- **Limits.** Links: 60 a minute per address, at most 20 unused per agent. Values: 64 KiB.
  An agent may upload at most 50 secrets. The owner sees it in the app.

## Threat model

| Threat | What they get | Why it stops there |
|---|---|---|
| Agent token leaks | The secrets scoped to that agent | Scope narrowly. Every fetch is in the audit. Revoke the token or pause the secret. |
| A download URL leaks (transcript, router log) | Usually nothing | Single use: by the time the URL is read, the agent has spent it. Unused, it dies in 5 minutes. |
| The database alone (a dump, a backup) | Names, notes, audit | Values are sealed under a key that is not in the database. |
| Server logs | Nothing | Values are never logged; URL tokens in router logs are spent or short-lived. |
| Someone looking at the owner's screen | Nothing unless revealed | Values are masked; reveal needs device auth and hides again after 30 seconds. |
| Prompt injection asks the agent to print a secret | Depends on the agent | The tools never return the value, so the agent has to go out of its way. Tool text tells it never to print one and to tell the owner if asked to. Scope and audit limit and show the damage. The server cannot stop an agent that is already hijacked. |
| The server and `SECRETS_KEY` together | Everything | Out of scope: the server must decrypt to hand values over. |

## API

Owner (device key):

- `GET /api/secrets` lists them (no values) and says whether the feature is on.
- `POST /api/secrets` `{name, value, note?, allAgents?, agentIds?}` adds one.
- `PATCH /api/secrets/<id>` `{value?, note?, allAgents?, agentIds?, paused?}` changes one.
- `DELETE /api/secrets/<id>` deletes one.
- `POST /api/secrets/<id>/reveal` `{purpose: "reveal" | "copy"}` returns `{value, encoding}`.
- `POST /api/cards/<kind>/<id>/secrets/<name>/reveal` `{purpose}` opens a secret an agent attached
  to that update or message (owner or admin; see "Sharing in a thread").
- `GET /api/secrets/audit[?secret=<id>]` lists the audit, newest first.
- `POST /api/requests/<id>/secret` `{value, note?}` answers a `request_secret`. The ordinary
  answer route refuses that kind, so its value never sits on the request row.

Agent (URL from a tool, no other auth):

- `GET /api/agent/secret/<token>` returns the bytes once.
- `PUT` or `POST /api/agent/secret/<token>` stores the body once (at most 64 KiB).

## Limits, and what is not covered yet

- Device auth is the app's, not the server's: any device key can call reveal. Desktop, Android 8
  and phones with no screen lock show the value without a prompt.
- A copied value is wiped from the clipboard after a minute, but clipboard history, Android's
  copy preview and Universal Clipboard may keep it.
- The desktop app's own server keeps its SECRETS_KEY in its settings file, beside the database.
- Serve the dashboard over HTTPS (Heroku does). Over plain HTTP, values cross the network in
  the clear, like everything else the dashboard sends.
- Asking the owner each time before a secret is handed over.
- Refusing posts that contain a stored secret's value.
- Per-channel scope.
