package dev.wildware.feed.server.domain

import dev.wildware.feed.server.auth.Tokens
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.db.longOrNull

/** Agents and their bearer tokens (spec/01-config-and-auth.md §7). */
class Agents(private val db: Database, private val tokenSecret: String, private val clock: () -> Long = System::currentTimeMillis) {
    data class Agent(val id: String, val name: String, val createdAt: Long, val revokedAt: Long?, val lastSeenAt: Long?)

    /** Why a bearer was refused, with the TS server's code and message. */
    data class Refusal(val code: String, val message: String)

    fun mint(name: String): Pair<Agent, String> {
        val clean = requiredText(name, "name", 100)
        val token = Tokens.random()
        val agent = Agent(Ulid.next(), clean, clock(), null, null)
        db.update(
            "INSERT INTO agents (id, name, token_hash, created_at) VALUES (?, ?, ?, ?)",
            agent.id, agent.name, Tokens.hash(token, tokenSecret), agent.createdAt,
        )
        return agent to token
    }

    fun list(includeRevoked: Boolean): List<Agent> = db.query(
        "SELECT * FROM agents ${if (includeRevoked) "" else "WHERE revoked_at IS NULL"} ORDER BY seq",
    ) { row(it) }

    fun find(id: String): Agent? = db.one("SELECT * FROM agents WHERE id = ?", id) { row(it) }

    /** True when this call revoked it, false when it already was. */
    fun revoke(id: String): Boolean {
        find(id) ?: throw notFound("no such agent: $id")
        return db.update("UPDATE agents SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL", clock(), id) == 1
    }

    fun rename(id: String, name: String): Agent {
        find(id) ?: throw notFound("no such agent: $id")
        val clean = requiredText(name, "name", 100)
        db.update("UPDATE agents SET name = ? WHERE id = ?", clean, id)
        return find(id)!!
    }

    /** Parse an Authorization header as the TS `readBearerToken` does (§7.2 step 1-2). */
    fun parseBearer(header: String?): Pair<String?, Refusal?> {
        val value = header?.jsTrim().orEmpty()
        if (value.isEmpty()) return null to Refusal("missing_token", "missing Authorization header: send Authorization: Bearer <agent token>")
        val space = value.indexOfFirst { it.isJsWhitespace() }
        val scheme = if (space < 0) value else value.substring(0, space)
        if (!scheme.equals("bearer", ignoreCase = true)) {
            return null to Refusal("unsupported_scheme", "unsupported Authorization scheme \"$scheme\": this server accepts Bearer tokens only")
        }
        val token = if (space < 0) "" else value.substring(space).jsTrim()
        if (token.isEmpty()) return null to Refusal("missing_token", "empty bearer token: send Authorization: Bearer <agent token>")
        if (!Tokens.wellFormed(token)) return null to Refusal("malformed_token", "malformed bearer token: not a token this server issued")
        return token to null
    }

    fun hashOf(token: String) = Tokens.hash(token, tokenSecret)

    /** Identity for a well-formed token, stamping last-seen; or why not (§7.2 step 4-5). */
    fun authenticate(token: String): Pair<Agent?, Refusal?> {
        val hash = hashOf(token)
        val row = db.one("SELECT * FROM agents WHERE token_hash = ?", hash) { it.getString("token_hash") to row(it) }
        if (row == null || !Tokens.same(row.first, hash)) return null to Refusal("unknown_token", "unknown bearer token: no agent holds it")
        val agent = row.second
        if (agent.revokedAt != null) return null to Refusal("revoked_token", "revoked bearer token: ask the dashboard owner to mint a new one")
        db.update("UPDATE agents SET last_seen_at = max(coalesce(last_seen_at, 0), ?) WHERE id = ?", clock(), agent.id)
        return agent to null
    }

    private fun row(rs: java.sql.ResultSet) = Agent(
        rs.getString("id"), rs.getString("name"), rs.getLong("created_at"), rs.longOrNull("revoked_at"), rs.longOrNull("last_seen_at"),
    )
}
