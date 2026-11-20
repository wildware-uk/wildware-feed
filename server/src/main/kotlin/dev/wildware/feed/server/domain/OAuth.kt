package dev.wildware.feed.server.domain

import dev.wildware.feed.server.auth.Tokens
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.db.longOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.security.MessageDigest

/**
 * OAuth 2.1 for MCP clients (migration 051, spec/11-oauth.md): the MCP authorization spec's way for
 * any client to connect without a pasted token.
 *
 * A client registers itself ([register]), the owner allows it in the browser as one agent, new or
 * existing ([allow], which makes a one-time code), and the client trades the code and its PKCE
 * verifier for an access token and a refresh token ([redeem]). An access token works like an agent
 * token wherever one does ([Agents.authenticate] looks here too). It lasts [ACCESS_MS]; the refresh
 * token rotates on every use ([refresh]), and a used one coming back revokes the whole grant.
 *
 * Only hashes of codes and tokens are stored, keyed with TOKEN_SECRET as agent tokens are.
 */
class OAuth(private val db: Database, private val tokenSecret: String, private val clock: () -> Long = System::currentTimeMillis) {
    data class Client(val id: String, val name: String, val clientUri: String?, val redirectUris: List<String>, val createdAt: Long, val approvedAt: Long?)

    data class Grant(
        val id: String,
        val clientId: String,
        val agentId: String,
        val resource: String,
        val scope: String,
        val redirectUri: String,
        /** Whether the authorize request named the redirect URI: then the token request must too. */
        val redirectUriGiven: Boolean,
        val codeChallenge: String,
        val codeExpiresAt: Long,
        val codeUsedAt: Long?,
        val revokedAt: Long?,
    )

    /** One client the owner allowed as an agent, for the owner's list. */
    data class Connection(val id: String, val clientName: String, val redirectUri: String, val createdAt: Long, val lastUsedAt: Long?)

    /** What the token endpoint hands over. */
    data class Issued(val accessToken: String, val refreshToken: String, val expiresInS: Long, val scope: String)

    /** A token-endpoint refusal: RFC 6749 §5.2's error code, a description, and the status. */
    class Refused(val error: String, val description: String, val status: Int = 400) : Exception(description)

    // ── Clients (RFC 7591) ───────────────────────────────────────────────────────────────────────

    fun register(name: String?, clientUri: String?, redirectUris: List<String>, address: String?): Client {
        if (redirectUris.isEmpty()) throw Refused("invalid_redirect_uri", "redirect_uris must name at least one redirect URI")
        if (redirectUris.size > MAX_REDIRECTS) throw Refused("invalid_redirect_uri", "redirect_uris may name at most $MAX_REDIRECTS")
        for (uri in redirectUris) redirectProblem(uri)?.let { throw Refused("invalid_redirect_uri", it) }
        val cleanName = name?.jsTrim()?.takeIf { it.isNotEmpty() }?.let {
            if (it.length > NAME_MAX) throw Refused("invalid_client_metadata", "client_name is at most $NAME_MAX characters")
            displayable(it).jsTrim().ifEmpty { null }
        } ?: "An MCP client"
        val cleanUri = clientUri?.jsTrim()?.takeIf { it.isNotEmpty() }?.also {
            if (it.length > URI_MAX || !it.startsWith("https://") && !it.startsWith("http://")) {
                throw Refused("invalid_client_metadata", "client_uri must be an http(s) URL")
            }
        }
        sweep()
        val now = clock()
        // Full of clients nobody allowed: the oldest makes room, so filling it never locks real clients out.
        val waiting = db.one("SELECT count(*) FROM oauth_clients WHERE team_id = :team AND approved_at IS NULL") { it.getInt(1) } ?: 0
        if (waiting >= MAX_UNAPPROVED_CLIENTS) {
            val oldest = db.query(
                "SELECT id FROM oauth_clients WHERE team_id = :team AND approved_at IS NULL AND id NOT IN (SELECT client_id FROM oauth_grants) ORDER BY seq LIMIT ?",
                waiting - MAX_UNAPPROVED_CLIENTS + 1,
            ) { it.getString(1) }
            for (old in oldest) db.update("DELETE FROM oauth_clients WHERE team_id = :team AND id = ?", old)
        }
        val id = Tokens.random()
        db.update(
            "INSERT INTO oauth_clients (team_id, id, name, client_uri, redirect_uris, address, created_at) VALUES (:team, ?, ?, ?, ?, ?, ?)",
            id, cleanName, cleanUri, JsonArray(redirectUris.map(::JsonPrimitive)).toString(), address, now,
        )
        return client(id)!!
    }

    fun client(id: String): Client? = db.one("SELECT * FROM oauth_clients WHERE team_id = :team AND id = ?", id) { rs ->
        Client(
            rs.getString("id"), rs.getString("name"), rs.getString("client_uri"),
            kotlinx.serialization.json.Json.parseToJsonElement(rs.getString("redirect_uris")).jsonArray.map { it.jsonPrimitive.content },
            rs.getLong("created_at"), rs.longOrNull("approved_at"),
        )
    }

    /**
     * The redirect URI to use for [client], or null when [asked] is not one it registered. With none
     * asked, its only one. A loopback `http` URI matches on any port (RFC 8252 §7.3).
     */
    fun redirectFor(client: Client, asked: String?): String? {
        if (asked == null) return client.redirectUris.singleOrNull()
        if (asked in client.redirectUris) return asked
        if (redirectProblem(asked) != null) return null
        val askedLoop = loopback(asked) ?: return null
        return asked.takeIf { client.redirectUris.any { loopback(it) == askedLoop } }
    }

    // ── The owner allows a client ─────────────────────────────────────────────────────────────────

    /**
     * The owner allowed [client] as the agent [agentId]: a grant with a one-time code, which this
     * returns (it is never stored). The code is good for [CODE_MS], once.
     */
    fun allow(client: Client, agentId: String, resource: String, scope: String, redirectUri: String, redirectUriGiven: Boolean, codeChallenge: String): String {
        val code = Tokens.random()
        val now = clock()
        db.transaction { _ ->
            db.update(
                "INSERT INTO oauth_grants (team_id, id, client_id, agent_id, resource, scope, redirect_uri, redirect_uri_given, code_hash, code_challenge, " +
                    "code_expires_at, created_at) VALUES (:team, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Ulid.next(now), client.id, agentId, resource, scope, redirectUri, if (redirectUriGiven) 1 else 0, hash(code), codeChallenge,
                now + CODE_MS, now,
            )
            db.update("UPDATE oauth_clients SET approved_at = ? WHERE team_id = :team AND id = ? AND approved_at IS NULL", now, client.id)
        }
        return code
    }

    /**
     * The live agents an earlier, unrevoked grant allowed for this same redirect target ([redirectKey]):
     * the only ones the page may offer first. A client's name is its own claim; where it sends the
     * answer is not.
     */
    fun agentsAllowedFor(redirectUri: String): Set<String> {
        val key = redirectKey(redirectUri) ?: return emptySet()
        return db.query("SELECT agent_id, redirect_uri FROM oauth_grants WHERE team_id = :team AND revoked_at IS NULL AND code_used_at IS NOT NULL") {
            it.getString(1) to it.getString(2)
        }.filter { redirectKey(it.second) == key }.map { it.first }.toSet()
    }

    /** The owner's list of [agentId]'s live connections, newest first. */
    fun connections(agentId: String): List<Connection> = db.query(
        "SELECT g.id, c.name, g.redirect_uri, g.created_at, g.last_used_at FROM oauth_grants g JOIN oauth_clients c ON c.id = g.client_id " +
            "WHERE g.team_id = :team AND g.agent_id = ? AND g.revoked_at IS NULL AND g.code_used_at IS NOT NULL ORDER BY g.seq DESC",
        agentId,
    ) { Connection(it.getString(1), it.getString(2), it.getString(3), it.getLong(4), it.longOrNull("last_used_at")) }

    /** The owner revokes one connection of [agentId]'s. True when this call revoked it. */
    fun revokeConnection(agentId: String, grantId: String): Boolean {
        val grant = grantBy("id", grantId)?.takeIf { it.agentId == agentId && it.codeUsedAt != null }
            ?: throw notFound("no such connection: $grantId")
        if (grant.revokedAt != null) return false
        revokeGrant(grant.id)
        return true
    }

    // ── The token endpoint ────────────────────────────────────────────────────────────────────────

    /**
     * `grant_type=authorization_code`. Every check that fails is `invalid_grant`, as RFC 6749 asks.
     * A code used twice revokes what the first use made: someone else has it.
     */
    fun redeem(code: String, clientId: String, redirectUri: String?, verifier: String, resource: String?, agentLive: (String) -> Boolean): Issued {
        val bad = Refused("invalid_grant", "the code is unknown, used, expired, or was issued to another client or redirect URI")
        if (!Tokens.wellFormed(code)) throw bad
        val grant = grantBy("code_hash", hash(code)) ?: throw bad
        if (grant.clientId != clientId) throw bad
        if (grant.codeUsedAt != null) {
            revokeGrant(grant.id)
            throw bad
        }
        if (grant.codeExpiresAt <= clock() || grant.revokedAt != null) throw bad
        if (redirectUri == null && grant.redirectUriGiven) throw Refused("invalid_request", "redirect_uri is required: the authorize request sent one")
        if (redirectUri != null && redirectUri != grant.redirectUri) throw bad
        if (!pkceMatches(verifier, grant.codeChallenge)) throw Refused("invalid_grant", "code_verifier does not match the code_challenge")
        if (resource != null && resource != grant.resource) throw Refused("invalid_target", "resource is not the one this code was issued for")
        if (!agentLive(grant.agentId)) throw Refused("invalid_grant", "the agent this was allowed as has been revoked")
        val issued = db.transaction { _ ->
            val marked = db.update("UPDATE oauth_grants SET code_used_at = ? WHERE team_id = :team AND id = ? AND code_used_at IS NULL", clock(), grant.id)
            if (marked == 1) issue(grant) else null
        }
        if (issued == null) {
            // Lost a race with another use of the same code: as for any second use, revoke the grant.
            revokeGrant(grant.id)
            throw bad
        }
        return issued
    }

    /**
     * `grant_type=refresh_token`: a new pair, and the old refresh token is spent. A spent one coming
     * back is a stolen copy or a client that lost the race with itself: inside [REUSE_GRACE_MS] it is
     * only refused, after that the whole grant is revoked.
     */
    fun refresh(token: String, clientId: String, resource: String?, agentLive: (String) -> Boolean): Issued {
        val bad = Refused("invalid_grant", "the refresh token is unknown, expired, revoked or was issued to another client")
        if (!Tokens.wellFormed(token)) throw bad
        val row = db.one("SELECT grant_id, expires_at, used_at FROM oauth_tokens WHERE team_id = :team AND token_hash = ? AND kind = 'refresh'", hash(token)) {
            Triple(it.getString(1), it.getLong(2), it.longOrNull("used_at"))
        } ?: throw bad
        val (grantId, expiresAt, usedAt) = row
        val grant = grantBy("id", grantId) ?: throw bad
        if (grant.clientId != clientId) throw bad
        if (usedAt != null) {
            if (clock() - usedAt > REUSE_GRACE_MS) revokeGrant(grant.id)
            throw bad
        }
        if (expiresAt <= clock() || grant.revokedAt != null) throw bad
        if (resource != null && resource != grant.resource) throw Refused("invalid_target", "resource is not the one this token was issued for")
        if (!agentLive(grant.agentId)) throw Refused("invalid_grant", "the agent this was allowed as has been revoked")
        return db.transaction { _ ->
            val spent = db.update("UPDATE oauth_tokens SET used_at = ? WHERE team_id = :team AND token_hash = ? AND used_at IS NULL", clock(), hash(token))
            if (spent != 1) throw bad
            issue(grant)
        }
    }

    /** RFC 7009: revoke the grant behind [token] when it is [clientId]'s. Unknown tokens are not an error. */
    fun revoke(token: String, clientId: String) {
        if (!Tokens.wellFormed(token)) return
        val grantId = db.one("SELECT grant_id FROM oauth_tokens WHERE team_id = :team AND token_hash = ?", hash(token)) { it.getString(1) } ?: return
        val grant = grantBy("id", grantId) ?: return
        if (grant.clientId == clientId) revokeGrant(grant.id)
    }

    /**
     * A new pair for [grant]. A grant holds one access token and one live refresh token at a time:
     * the earlier access token goes. Spent refresh tokens are kept until they would have expired, so
     * a copy coming back revokes the grant however many refreshes came after it.
     */
    private fun issue(grant: Grant): Issued {
        val now = clock()
        db.update(
            "DELETE FROM oauth_tokens WHERE team_id = :team AND grant_id = ? AND (kind = 'access' OR (kind = 'refresh' AND used_at IS NOT NULL AND expires_at <= ?))",
            grant.id, now,
        )
        val access = Tokens.random()
        val refresh = Tokens.random()
        db.update(
            "INSERT INTO oauth_tokens (team_id, token_hash, grant_id, kind, created_at, expires_at) VALUES (:team, ?, ?, 'access', ?, ?), (:team, ?, ?, 'refresh', ?, ?)",
            hash(access), grant.id, now, now + ACCESS_MS, hash(refresh), grant.id, now, now + REFRESH_MS,
        )
        return Issued(access, refresh, ACCESS_MS / 1000, grant.scope)
    }

    private fun revokeGrant(id: String) {
        db.update("UPDATE oauth_grants SET revoked_at = ? WHERE team_id = :team AND id = ? AND revoked_at IS NULL", clock(), id)
    }

    private fun grantBy(column: String, value: String): Grant? = db.one("SELECT * FROM oauth_grants WHERE team_id = :team AND $column = ?", value) { rs ->
        Grant(
            rs.getString("id"), rs.getString("client_id"), rs.getString("agent_id"), rs.getString("resource"), rs.getString("scope"),
            rs.getString("redirect_uri"), rs.getInt("redirect_uri_given") == 1, rs.getString("code_challenge"), rs.getLong("code_expires_at"), rs.longOrNull("code_used_at"),
            rs.longOrNull("revoked_at"),
        )
    }

    private fun hash(token: String) = Tokens.hash(token, tokenSecret)

    /**
     * Expired tokens, grants that are revoked, whose code was never used or whose tokens have all
     * expired, and clients registered an hour ago that the owner never allowed, are deleted.
     */
    fun sweep() {
        val now = clock()
        db.transaction { _ ->
            db.update("DELETE FROM oauth_tokens WHERE team_id = :team AND expires_at <= ?", now)
            val dead = "revoked_at IS NOT NULL OR (code_used_at IS NULL AND code_expires_at <= ?) " +
                "OR (code_used_at IS NOT NULL AND id NOT IN (SELECT grant_id FROM oauth_tokens))"
            db.update("DELETE FROM oauth_tokens WHERE team_id = :team AND grant_id IN (SELECT id FROM oauth_grants WHERE team_id = :team AND ($dead))", now)
            db.update("DELETE FROM oauth_grants WHERE team_id = :team AND ($dead)", now)
            db.update(
                "DELETE FROM oauth_clients WHERE team_id = :team AND approved_at IS NULL AND created_at <= ? AND id NOT IN (SELECT client_id FROM oauth_grants)",
                now - UNAPPROVED_KEEP_MS,
            )
        }
    }

    companion object {
        const val SCOPE = "agent"
        const val NAME_MAX = 100
        const val URI_MAX = 2000
        const val MAX_REDIRECTS = 10
        const val MAX_UNAPPROVED_CLIENTS = 500

        /** A code is traded straight away by the client that asked for it. */
        const val CODE_MS = 2 * 60_000L
        const val ACCESS_MS = 60 * 60_000L
        const val REFRESH_MS = 90L * 24 * 60 * 60_000L
        const val REUSE_GRACE_MS = 60_000L
        const val UNAPPROVED_KEEP_MS = 60 * 60_000L
        const val SWEEP_INTERVAL_MS = 10 * 60_000L

        /** Schemes that can never be a client's redirect: script, data and local files, or the web without TLS. */
        private val FORBIDDEN_SCHEMES = setOf(
            "javascript", "data", "file", "vbscript", "about", "blob", "filesystem", "view-source", "ftp", "ws", "wss",
            "mailto", "tel", "sms", "chrome", "chrome-extension", "moz-extension", "resource", "jar", "intent",
        )

        /**
         * Why [uri] cannot be a redirect URI, or null when it can: `https`, `http` to a loopback
         * host (localhost, 127.0.0.1, [::1]), or a private-use scheme an app registered (cursor://,
         * vscode://). No fragment, no user info, nothing longer than [URI_MAX].
         */
        fun redirectProblem(uri: String): String? {
            if (uri.length > URI_MAX) return "a redirect URI is at most $URI_MAX characters"
            if (uri.any { it.isWhitespace() || it.isISOControl() }) return "a redirect URI has no spaces or control characters"
            val parsed = runCatching { URI(uri) }.getOrNull() ?: return "\"$uri\" is not a URI"
            val scheme = parsed.scheme?.lowercase() ?: return "\"$uri\" is not an absolute URI"
            if (parsed.rawFragment != null) return "a redirect URI has no fragment"
            if (!Regex("^[a-z][a-z0-9+.-]*$").matches(scheme)) return "\"$scheme\" is not a valid scheme"
            return when {
                scheme == "https" -> when {
                    parsed.host.isNullOrEmpty() -> "an https redirect URI needs a host"
                    parsed.rawUserInfo != null -> "a redirect URI has no user info"
                    else -> null
                }
                scheme == "http" -> when {
                    parsed.rawUserInfo != null -> "a redirect URI has no user info"
                    !isLoopbackHost(parsed.host) -> "an http redirect URI must be to localhost, 127.0.0.1 or [::1]; use https otherwise"
                    else -> null
                }
                scheme in FORBIDDEN_SCHEMES -> "\"$scheme:\" cannot be a redirect URI"
                else -> null
            }
        }

        fun isLoopbackHost(host: String?): Boolean {
            val h = host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
            return h == "localhost" || h == "::1" || h == "0:0:0:0:0:0:0:1" || Regex("^127(\\.\\d{1,3}){3}$").matches(h)
        }

        /** An http loopback URI without its port, to match on any port; null for any other URI. */
        private fun loopback(uri: String): String? {
            val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
            if (parsed.scheme?.lowercase() != "http" || !isLoopbackHost(parsed.host)) return null
            return "http://${parsed.host.lowercase()}${parsed.rawPath.orEmpty()}${parsed.rawQuery?.let { "?$it" }.orEmpty()}"
        }

        /**
         * [s] without what could hide or reorder text on the page: control characters, Unicode format
         * characters (bidirectional overrides, zero-width joiners and spaces, tags) and line separators.
         */
        fun displayable(s: String): String = buildString {
            s.codePoints().forEach { cp ->
                val type = Character.getType(cp)
                val hidden = Character.isISOControl(cp) || type == Character.FORMAT.toInt() ||
                    type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt()
                if (!hidden) appendCodePoint(cp)
            }
        }

        /**
         * What makes two redirect URIs the same place, for offering an agent first: an https origin,
         * or an app scheme's whole URI. Loopback has none: any program on the computer can listen there.
         */
        fun redirectKey(uri: String): String? {
            val u = runCatching { URI(uri) }.getOrNull() ?: return null
            return when (val scheme = u.scheme?.lowercase()) {
                null -> null
                "https" -> u.host?.let { "https://${it.lowercase()}${if (u.port == -1 || u.port == 443) "" else ":${u.port}"}" }
                "http" -> null
                else -> "$scheme:${u.rawSchemeSpecificPart.substringBefore('?')}"
            }
        }

        /** RFC 7636: a 43-128 character verifier whose base64url SHA-256 is the challenge. */
        fun pkceMatches(verifier: String, challenge: String): Boolean {
            if (!Regex("^[A-Za-z0-9._~-]{43,128}$").matches(verifier)) return false
            val digest = Tokens.base64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
            return Tokens.same(digest, challenge)
        }

        /** An S256 challenge has this shape: base64url of 32 bytes. */
        fun challengeWellFormed(challenge: String) = Regex("^[A-Za-z0-9_-]{43}$").matches(challenge)
    }
}
