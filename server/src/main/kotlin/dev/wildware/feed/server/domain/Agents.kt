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

    /** A new agent and its token. [createdBy] is the membership id of the person letting it in, if a person is. */
    fun mint(name: String, createdBy: String? = null): Pair<Agent, String> {
        val clean = checkName(name)
        val token = Tokens.random()
        val agent = Agent(Ulid.next(), clean, clock(), null, null)
        db.update(
            "INSERT INTO agents (team_id, id, name, token_hash, created_at, created_by) VALUES (:team, ?, ?, ?, ?, ?)",
            agent.id, agent.name, Tokens.hash(token, tokenSecret), agent.createdAt, createdBy,
        )
        return agent to token
    }

    /** The live agents [membershipId] let in: what removing them revokes ([People.remove]). */
    fun letInBy(membershipId: String): List<String> =
        db.query("SELECT id FROM agents WHERE team_id = :team AND created_by = ? AND revoked_at IS NULL", membershipId) { it.getString(1) }

    fun list(includeRevoked: Boolean): List<Agent> = db.query(
        "SELECT * FROM agents WHERE team_id = :team ${if (includeRevoked) "" else "AND revoked_at IS NULL"} ORDER BY seq",
    ) { row(it) }

    fun find(id: String): Agent? = db.one("SELECT * FROM agents WHERE team_id = :team AND id = ?", id) { row(it) }

    /** True when this call revoked it, false when it already was. */
    fun revoke(id: String): Boolean {
        find(id) ?: throw notFound("no such agent: $id")
        return db.update("UPDATE agents SET revoked_at = ? WHERE team_id = :team AND id = ? AND revoked_at IS NULL", clock(), id) == 1
    }

    fun rename(id: String, name: String): Agent {
        find(id) ?: throw notFound("no such agent: $id")
        val clean = checkName(name)
        db.update("UPDATE agents SET name = ? WHERE team_id = :team AND id = ?", clean, id)
        return find(id)!!
    }

    companion object {
        /** Words an agent can't be called: what the bridge and the feed call people and the server, and the @groups. */
        val RESERVED = setOf("owner", "human", "system", "server", Mentions.EVERYONE, Mentions.HERE)

        /**
         * An agent's name, cleaned: 1 to [max] characters, none of them a control character, a double quote,
         * a backtick or an angle bracket, and not one of [RESERVED] in any case. Other agents read the name where
         * the bridge says who wrote a message, so it must never pass for the owner or break out of that.
         */
        fun checkName(name: String, max: Int = 100): String {
            val clean = requiredText(name, "name", max)
            if (clean.any { it.isISOControl() || it in "\"<>`" || Character.getType(it) == Character.FORMAT.toInt() }) {
                throw invalid("an agent's name can't hold double quotes, backticks, angle brackets or control characters")
            }
            if (clean.lowercase().removePrefix("@") in RESERVED) throw invalid("\"$clean\" is kept for the dashboard itself: choose another name")
            return clean
        }
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
        val row = db.one("SELECT * FROM agents WHERE team_id = :team AND token_hash = ?", hash) { it.getString("token_hash") to row(it) }
            ?: return oauthAccess(hash)
        if (!Tokens.same(row.first, hash)) return null to Refusal("unknown_token", "unknown bearer token: no agent holds it")
        val agent = row.second
        if (agent.revokedAt != null) return null to Refusal("revoked_token", "revoked bearer token: ask the dashboard owner to mint a new one")
        db.update("UPDATE agents SET last_seen_at = ${db.greatest("coalesce(last_seen_at, 0)", "?")} WHERE team_id = :team AND id = ?", clock(), agent.id)
        return agent to null
    }

    /**
     * An OAuth access token (migration 051) for the agent its grant was allowed as: live while the
     * token is unexpired, the grant unrevoked and the agent unrevoked.
     */
    private fun oauthAccess(hash: String): Pair<Agent?, Refusal?> {
        val found = db.one(
            "SELECT t.token_hash AS hash, t.expires_at AS token_expires, g.revoked_at AS grant_revoked, g.id AS grant_id, a.* FROM oauth_tokens t " +
                "JOIN oauth_grants g ON g.id = t.grant_id AND g.team_id = t.team_id JOIN agents a ON a.id = g.agent_id AND a.team_id = t.team_id " +
                "WHERE t.team_id = :team AND t.token_hash = ? AND t.kind = 'access'",
            hash,
        ) { Triple(it.getString("hash"), Triple(it.getLong("token_expires"), it.longOrNull("grant_revoked"), it.getString("grant_id")), row(it)) }
        if (found == null || !Tokens.same(found.first, hash)) return null to Refusal("unknown_token", "unknown bearer token: no agent holds it")
        val (expires, grantRevoked, grantId) = found.second
        val agent = found.third
        if (agent.revokedAt != null || grantRevoked != null) {
            return null to Refusal("revoked_token", "revoked bearer token: sign in again, or ask the dashboard owner to allow this client again")
        }
        if (expires <= clock()) return null to Refusal("expired_token", "expired access token: use your refresh token, or sign in again")
        db.update("UPDATE agents SET last_seen_at = ${db.greatest("coalesce(last_seen_at, 0)", "?")} WHERE team_id = :team AND id = ?", clock(), agent.id)
        db.update("UPDATE oauth_grants SET last_used_at = ${db.greatest("coalesce(last_used_at, 0)", "?")} WHERE team_id = :team AND id = ?", clock(), grantId)
        return agent to null
    }

    private fun row(rs: java.sql.ResultSet) = Agent(
        rs.getString("id"), rs.getString("name"), rs.getLong("created_at"), rs.longOrNull("revoked_at"), rs.longOrNull("last_seen_at"),
    )
}
