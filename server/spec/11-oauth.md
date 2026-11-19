# 11 — OAuth for MCP clients

Kotlin server only (the TS server has none of this). It is the MCP authorization spec
(2025-06-18 and 2025-11-25): OAuth 2.1 with PKCE, RFC 9728 protected resource metadata, RFC 8414
authorization server metadata, RFC 7591 dynamic client registration, RFC 8707 resource indicators,
RFC 7009 revocation and RFC 9207's `iss`. Code: `domain/OAuth.kt`, `http/OAuthRoutes.kt`,
`http/OAuthPage.kt`. Tables: migration 051.

`<base>` below is `PUBLIC_BASE_URL`, canonical (lower-case scheme and host, no default port, no
trailing slash).

## 1. Discovery

- `/mcp` with no token: 401 as before (spec 05), but `WWW-Authenticate` is now
  `Bearer resource_metadata="<base>/.well-known/oauth-protected-resource/mcp", scope="agent"`.
  A bad token gets `Bearer error="invalid_token", error_description="…", resource_metadata="…", scope="agent"`.
  Bodies and codes are unchanged, so existing clients see no difference.
- `GET /.well-known/oauth-protected-resource` → `{resource: "<base>", authorization_servers: ["<base>"],
  scopes_supported: ["agent"], bearer_methods_supported: ["header"], resource_name}`.
  `GET /.well-known/oauth-protected-resource/mcp` is the same with `resource: "<base>/mcp"`.
- `GET /.well-known/oauth-authorization-server` → issuer `<base>`, the endpoints below,
  `response_types_supported: ["code"]`, `grant_types_supported: ["authorization_code", "refresh_token"]`,
  `token_endpoint_auth_methods_supported: ["none"]`, `code_challenge_methods_supported: ["S256"]`,
  `authorization_response_iss_parameter_supported: true`. No OpenID Connect discovery, no client ID
  metadata documents (clients fall back to registration).
- Metadata, `/oauth/register`, `/oauth/token` and `/oauth/revoke` send
  `Access-Control-Allow-Origin: *` and answer `OPTIONS`, for browser-based clients.
- `/.well-known/*` and `/oauth/*` are public (the guard lets them through).

## 2. Registration — `POST /oauth/register`

JSON, at most 16 KiB. `redirect_uris` is required (1–10). Each must be `https` (with a host, no
user info), `http` to a loopback host (`localhost`, `127.x.x.x`, `[::1]`), or a private-use scheme an
app registers (`cursor:`, `vscode:`, …). Never `javascript:`, `data:`, `file:` and similar; never a
fragment, whitespace or more than 2000 characters. `grant_types` ⊆ {authorization_code,
refresh_token}; `response_types` ⊆ {code}. `client_name` (≤ 100, control characters dropped, default
"An MCP client") and `client_uri` are kept. Whatever `token_endpoint_auth_method` is asked, the answer
is `none`: every client is public and proves itself with PKCE. 201 with `client_id` (43 random
characters), `client_id_issued_at` and the metadata. Errors: 400 `invalid_redirect_uri` /
`invalid_client_metadata`, 413, 503 when 500 clients are waiting unallowed. 20 an hour per address.

## 3. The authorize page — `/oauth/authorize`

`GET` with the usual query; `POST` is the page's own form (the same parameters as hidden fields,
plus `action`). Checks, in order:

1. Any of the OAuth parameters given twice, an unknown `client_id`, or a `redirect_uri` the client
   did not register: an error page, 400, and no redirect. With no `redirect_uri`, the client's only
   one. A loopback `http` URI matches on any port (RFC 8252).
2. `response_type=code`, `code_challenge` (43 base64url characters), `code_challenge_method=S256`,
   `resource` (if given) `<base>` or `<base>/mcp` (default `<base>/mcp`), `state` ≤ 2000. A failure is
   also an error page, never a redirect: anyone can register a client, so redirecting errors would
   make the server an open redirector (RFC 9700 §4.11.2).
3. Not signed in: a password form. `action=sign_in` checks the owner password like device sign-in
   (same lockout). Right: `Set-Cookie: __Host-feed_owner=v1.<expiry>.<nonce>.<sig>; Path=/; Max-Age=43200;
   HttpOnly; SameSite=Lax; Secure` (named `feed_owner`, no `Secure`, when `PUBLIC_BASE_URL` is http),
   then 303 back to the GET. The signature is HMAC-SHA256 with `SESSION_SECRET` over the expiry, the
   nonce and a hash of the password hash, so a new password ends every session. The cookie signs in
   nothing but this page.
4. Signed in: "<client name> wants to connect as an agent", where the answer will go (the
   redirect host, or "an app on this computer" for loopback, or "the app that opens x:// links"),
   a choice of a new agent (named after the client) or an existing one (preselected when one has the
   client's name), and Deny / Allow. The form carries `csrf` = HMAC(`SESSION_SECRET`, nonce + client,
   redirect URI, state, challenge, resource, scope).
5. `action=allow|deny` needs the session cookie (else 401 and the password form) and that exact
   `csrf` (else 403). Every `POST` must be same-origin: `Sec-Fetch-Site: same-origin` when the browser
   sends it, else an `Origin` equal to `ORIGIN`; otherwise 403.
6. Deny: 303 to the redirect URI with `error=access_denied`, `state`, `iss`. Allow: makes the agent
   (a taken name is a 409 on the page) or takes the chosen live one, records a grant, and 303s to the
   redirect URI with `code`, `state` and `iss`.

Every page: `Cache-Control: no-store`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, CSP
`default-src 'none'; style-src 'unsafe-inline'; form-action 'self' <redirect origin or scheme>;
frame-ancestors 'none'; base-uri 'none'`. No scripts. 60 loads or posts a minute per address.

## 4. Tokens — `POST /oauth/token`

Form-encoded, at most 16 KiB; `client_id` in the body (or as HTTP Basic's user). Answers are
`Cache-Control: no-store`. Errors are RFC 6749 §5.2 JSON `{error, error_description}`: 401
`invalid_client` for an unknown client, else 400.

- `authorization_code`: `code`, `code_verifier`, optional `redirect_uri` and `resource`. The code
  must be this client's, unused, under 2 minutes old, for this redirect URI and resource, its
  grant and agent unrevoked, and `base64url(sha256(code_verifier))` its challenge. A code used a
  second time is refused and its grant revoked (what the first use made stops working).
- `refresh_token`: `refresh_token`, optional `resource`. Rotates: the old refresh token is spent. A
  spent one presented again within 60 seconds is only refused (a client racing itself); later, the
  whole grant is revoked.
- Answer: `{access_token, token_type: "Bearer", expires_in: 3600, refresh_token, scope: "agent"}`.
  Both tokens are 43-character random strings, the same shape as agent tokens. Refresh tokens last
  90 days.

`POST /oauth/revoke` (`token`, `client_id`): revokes the token's grant when it is that client's;
200 with an empty body either way. The token endpoint's 60 a minute per address covers both.

## 5. Using a token

An access token is accepted wherever an agent token is (`/mcp`, `/api/agent/stream`, `/api/agent/media`):
`Agents.authenticate` looks in `agents` first, then `oauth_tokens` (kind `access`). It is that
agent, exactly. Refusals add `expired_token` ("expired access token: use your refresh token, or sign
in again") and use `revoked_token` when the grant or the agent is revoked. A refresh token is never
an access token. Revoking the agent ends its OAuth tokens and refreshes.

## 6. Storage (migration 051)

`oauth_clients` (id, name, client_uri, redirect_uris JSON, address, created_at, approved_at),
`oauth_grants` (one per Allow: client, agent, resource, scope, redirect URI, code hash, challenge,
code expiry and use, revoked_at) and `oauth_tokens` (token hash, grant, kind, created, expires,
used_at for spent refresh tokens). Codes and tokens are stored only as HMAC-SHA256 with
`TOKEN_SECRET`, like agent tokens. Every 10 minutes expired tokens, grants revoked, never redeemed or with no live token left,
and clients never allowed after a day are deleted. Nothing logs a code or a token.
