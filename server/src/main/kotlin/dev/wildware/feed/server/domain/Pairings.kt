package dev.wildware.feed.server.domain

import dev.wildware.feed.server.auth.Tokens
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.db.longOrNull
import dev.wildware.feed.server.events.Bus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom

/**
 * Agent pairing (migration 050): an agent asks to join under a name it picks, the owner allows or
 * denies it, and the agent collects its new token once.
 *
 * Nothing secret is stored. The poll secret is kept as a hash, as agent tokens are. Allowing makes
 * the agent's row with a placeholder hash nobody holds, which keeps the name; the real token is set
 * only when the agent collects it, in the same step that marks the pairing collected, and is never
 * written anywhere but as its hash.
 *
 * The token is not random: it is derived from the server's token secret and the poll secret
 * ([tokenFor]). The server keeps neither in the database, so nothing stored can be turned into
 * the token, but a poll that brings the poll secret back can have the same token made again. That
 * is how an answer lost on the way (a poll cut off just as the owner allowed it) is not a lost
 * token: a repeat poll gets the same token until the token is first used, for up to
 * [COLLECT_MS] after collection, and never after.
 */
class Pairings(
    private val db: Database,
    private val bus: Bus,
    private val tokenSecret: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Pairing(
        val id: String,
        val name: String,
        val host: String?,
        val cwd: String?,
        val address: String?,
        val askedBy: String?,
        val code: String,
        val state: String,
        val agentId: String?,
        val createdAt: Long,
        val expiresAt: Long,
        val decidedAt: Long?,
    )

    /** A pairing just started, with its poll secret: shown once, to the agent that asked. */
    class Started(val pairing: Pairing, val secret: String)

    /** What a poll found. [token] is set on the poll that collects it, and on repeats until it is first used. */
    class Poll(val pairing: Pairing, val token: String? = null, val agentName: String? = null)

    /** Start a pairing. [address] is the client's, [askedBy] the agent asking over MCP, if one is. */
    fun start(name: String, host: String?, cwd: String?, address: String?, askedBy: String?): Started {
        val clean = requiredText(name, "name", NAME_MAX)
        val cleanHost = optionalText(host, "host", HOST_MAX)
        val cleanCwd = optionalText(cwd, "cwd", CWD_MAX)
        expireDue()
        val now = clock()
        fun open(where: String, vararg args: Any?) =
            db.one("SELECT count(*) FROM agent_pairings WHERE team_id = :team AND state = 'pending' AND expires_at > ? $where", now, *args) { it.getInt(1) } ?: 0
        if (open("") >= MAX_PENDING) throw tooMany("too many agents are waiting to pair; ask your owner to allow or deny them, or try again in a few minutes")
        if (address != null && open("AND address = ?", address) >= MAX_PENDING_PER_ASKER) {
            throw tooMany("this address already has $MAX_PENDING_PER_ASKER pairings waiting; wait for those first")
        }
        if (askedBy != null && open("AND asked_by = ?", askedBy) >= MAX_PENDING_PER_ASKER) {
            throw tooMany("this token already has $MAX_PENDING_PER_ASKER pairings waiting; wait for those first")
        }
        val secret = Tokens.random()
        val id = Ulid.next(now)
        val code = newCode()
        db.update(
            "INSERT INTO agent_pairings (team_id, id, name, host, cwd, address, asked_by, code, secret_hash, state, created_at, expires_at) " +
                "VALUES (:team, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?, ?)",
            id, clean, cleanHost, cleanCwd, address, askedBy, code, Tokens.hash(secret, tokenSecret), now, now + TTL_MS,
        )
        val pairing = find(id)!!
        bus.publish("pairing.created", event(pairing))
        return Started(pairing, secret)
    }

    /** Every pairing waiting on the owner, oldest first. */
    fun pending(): List<Pairing> =
        db.query("SELECT * FROM agent_pairings WHERE team_id = :team AND state = 'pending' AND expires_at > ? ORDER BY seq", clock()) { row(it) }

    fun find(id: String): Pairing? = db.one("SELECT * FROM agent_pairings WHERE team_id = :team AND id = ?", id) { row(it) }

    /**
     * The owner allows it, under [name] when given (else the name the agent asked for). The name
     * must not be a live agent's: a clash is a conflict, and the owner picks another.
     */
    fun approve(id: String, name: String?): Pairing {
        expireDue()
        val pairing = waiting(id)
        val chosen = if (name == null) pairing.name else requiredText(name, "name", NAME_MAX)
        val taken = db.query("SELECT name FROM agents WHERE team_id = :team AND revoked_at IS NULL") { it.getString(1) }
            .any { it.lowercase() == chosen.lowercase() }
        if (taken) throw DomainError("name_taken", "an agent is already called \"$chosen\"; choose another name", 409)
        val now = clock()
        val agentId = Ulid.next(now)
        val decided = db.transaction { _ ->
            // A hash of a token nobody holds: the name is kept, and nothing can sign in as it yet.
            db.update(
                "INSERT INTO agents (team_id, id, name, token_hash, created_at) VALUES (:team, ?, ?, ?, ?)",
                agentId, chosen, Tokens.hash(Tokens.random(), tokenSecret), now,
            )
            db.update(
                "UPDATE agent_pairings SET state = 'approved', name = ?, agent_id = ?, decided_at = ?, expires_at = ? " +
                    "WHERE team_id = :team AND id = ? AND state = 'pending'",
                chosen, agentId, now, now + COLLECT_MS, id,
            ).also { if (it != 1) throw conflict("this pairing was already decided") }
        }
        check(decided == 1)
        return find(id)!!.also { bus.publish("pairing.updated", event(it)) }
    }

    fun deny(id: String): Pairing {
        expireDue()
        waiting(id)
        val changed = db.update("UPDATE agent_pairings SET state = 'denied', decided_at = ? WHERE team_id = :team AND id = ? AND state = 'pending'", clock(), id)
        if (changed != 1) throw conflict("this pairing was already decided")
        return find(id)!!.also { bus.publish("pairing.updated", event(it)) }
    }

    /**
     * The agent's poll: waits up to [holdMs] for the owner, then says where it stands. The first poll
     * after the owner allowed it collects the token; a repeat gets the same token back until it is
     * first used ([again]).
     * An unknown id and a wrong secret are the same not-found, so neither can be probed.
     */
    suspend fun await(id: String, secret: String, holdMs: Long): Poll {
        authorised(id, secret)
        // Listen before looking, so a decision made in between is not missed.
        val decided = CompletableDeferred<Unit>()
        val stop = bus.subscribe { e ->
            if (e.type == "pairing.updated" && (e.payload["pairingId"] as? JsonPrimitive)?.content == id) decided.complete(Unit)
        }
        try {
            if (find(id)?.state == "pending" && holdMs > 0) withTimeoutOrNull(holdMs) { decided.await() }
        } finally {
            stop()
        }
        expireDue()
        val now = find(id)!!
        return when (now.state) {
            "approved" -> collect(now, secret)
            "collected" -> again(now, secret)
            else -> Poll(now)
        }
    }

    /**
     * The token a pairing's agent gets. Making it takes the server's token secret, which is not in
     * the database, and the poll secret, which is stored only as a hash: so only this server, shown
     * the poll secret by the agent that holds it, can make it. 32 bytes, base64url, like any token.
     */
    private fun tokenFor(pairing: Pairing, secret: String): String =
        Tokens.base64url(Tokens.hmac(tokenSecret, "$TOKEN_PREFIX${pairing.id}:$secret"))

    private fun collect(pairing: Pairing, secret: String): Poll {
        val token = tokenFor(pairing, secret)
        val collected = db.transaction { _ ->
            val marked = db.update(
                "UPDATE agent_pairings SET state = 'collected', collected_at = ? WHERE team_id = :team AND id = ? AND state = 'approved' AND expires_at > ?",
                clock(), pairing.id, clock(),
            )
            if (marked == 1) db.update("UPDATE agents SET token_hash = ? WHERE team_id = :team AND id = ?", Tokens.hash(token, tokenSecret), pairing.agentId)
            marked == 1
        }
        val now = find(pairing.id)!!
        // Another poll collected it first: this one may still have it, as a repeat.
        if (!collected) return if (now.state == "collected") again(now, secret) else Poll(now)
        bus.publish("pairing.updated", event(now))
        val name = db.one("SELECT name FROM agents WHERE team_id = :team AND id = ?", pairing.agentId) { it.getString(1) }
        return Poll(now, token, name)
    }

    /**
     * A poll after the token was collected: the same token again while the agent has not used it,
     * is not revoked, and collected it under [COLLECT_MS] ago. Once it has signed in with it, or
     * the time is up, the answer is "collected" with no token, for good.
     */
    private fun again(pairing: Pairing, secret: String): Poll {
        val agentId = pairing.agentId ?: return Poll(pairing)
        val collectedAt = db.one("SELECT collected_at FROM agent_pairings WHERE team_id = :team AND id = ?", pairing.id) { it.longOrNull("collected_at") }
            ?: return Poll(pairing)
        if (clock() >= collectedAt + COLLECT_MS) return Poll(pairing)
        data class Holder(val hash: String, val name: String, val unused: Boolean)
        val agent = db.one("SELECT token_hash, name, revoked_at, last_seen_at FROM agents WHERE team_id = :team AND id = ?", agentId) {
            Holder(it.getString("token_hash"), it.getString("name"), it.longOrNull("revoked_at") == null && it.longOrNull("last_seen_at") == null)
        } ?: return Poll(pairing)
        val token = tokenFor(pairing, secret)
        if (!agent.unused || !Tokens.same(agent.hash, Tokens.hash(token, tokenSecret))) return Poll(pairing)
        return Poll(pairing, token, agent.name)
    }

    /**
     * Pending pairings past their ten minutes, and allowed ones never collected, become expired.
     * An allowed one's agent is revoked: nobody ever held its token. Old pairings are deleted.
     */
    fun expireDue(): List<Pairing> {
        val now = clock()
        val due = db.query("SELECT * FROM agent_pairings WHERE team_id = :team AND state IN ('pending', 'approved') AND expires_at <= ?", now) { row(it) }
        val expired = due.filter { p ->
            db.transaction { _ ->
                val changed = db.update("UPDATE agent_pairings SET state = 'expired' WHERE team_id = :team AND id = ? AND state = ?", p.id, p.state)
                if (changed == 1 && p.state == "approved" && p.agentId != null) {
                    db.update("UPDATE agents SET revoked_at = ? WHERE team_id = :team AND id = ? AND revoked_at IS NULL", now, p.agentId)
                }
                changed == 1
            }
        }
        db.update("DELETE FROM agent_pairings WHERE team_id = :team AND state NOT IN ('pending', 'approved') AND created_at < ?", now - KEEP_MS)
        return expired.mapNotNull { find(it.id) }.onEach { bus.publish("pairing.updated", event(it)) }
    }

    private fun authorised(id: String, secret: String): Pairing {
        val missing = notFound("no such pairing, or the poll secret is wrong")
        val hash = db.one("SELECT secret_hash FROM agent_pairings WHERE team_id = :team AND id = ?", id) { it.getString(1) }
        if (hash == null || !Tokens.wellFormed(secret) || !Tokens.same(hash, Tokens.hash(secret, tokenSecret))) throw missing
        return find(id) ?: throw missing
    }

    private fun waiting(id: String): Pairing {
        val pairing = find(id) ?: throw notFound("no such pairing: $id")
        if (pairing.state != "pending") throw conflict("this pairing is ${pairing.state}, not waiting")
        if (pairing.expiresAt <= clock()) throw conflict("this pairing has expired")
        return pairing
    }

    private fun event(p: Pairing) = buildJsonObject {
        put("pairingId", p.id)
        put("state", p.state)
    }

    /** What the owner sees: everything but the secret, with the name of the agent that asked over MCP. */
    fun ownerJson(p: Pairing): JsonObject = buildJsonObject {
        put("id", p.id)
        put("name", p.name)
        put("host", p.host)
        put("cwd", p.cwd)
        put("address", p.address)
        put("code", p.code)
        put("state", p.state)
        if (p.askedBy != null) {
            put("askedBy", buildJsonObject {
                put("id", p.askedBy)
                put("name", db.one("SELECT name FROM agents WHERE team_id = :team AND id = ?", p.askedBy) { it.getString(1) })
            })
        } else {
            put("askedBy", JsonNull)
        }
        put("agentId", p.agentId)
        put("createdAt", p.createdAt)
        put("expiresAt", p.expiresAt)
    }

    private fun row(rs: java.sql.ResultSet) = Pairing(
        rs.getString("id"), rs.getString("name"), rs.getString("host"), rs.getString("cwd"), rs.getString("address"),
        rs.getString("asked_by"), rs.getString("code"), rs.getString("state"), rs.getString("agent_id"),
        rs.getLong("created_at"), rs.getLong("expires_at"), rs.longOrNull("decided_at"),
    )

    private fun tooMany(message: String) = DomainError("too_many_pairings", message, 429)

    companion object {
        const val NAME_MAX = 100
        const val HOST_MAX = 200
        const val CWD_MAX = 500

        /** How long the owner has to decide. */
        const val TTL_MS = 10 * 60_000L

        /**
         * Once allowed, how long the agent has to collect its token; once collected, how long a
         * repeat poll can still get it back if it has not been used.
         */
        const val COLLECT_MS = 10 * 60_000L

        /** Keeps a pairing token's HMAC apart from every other use of the token secret. */
        private const val TOKEN_PREFIX = "agent-dashboard:pairing-token:v1:"

        /** How long one poll waits for the owner: under the 30 seconds a proxy such as Heroku's allows. */
        const val HOLD_MS = 25_000L

        const val MAX_PENDING = 20
        const val MAX_PENDING_PER_ASKER = 3

        /** Decided and expired pairings are kept this long, then deleted. */
        const val KEEP_MS = 7 * 24 * 60 * 60_000L
        const val SWEEP_INTERVAL_MS = 30_000L

        /** No 0/O, 1/I/L or U: easy to read aloud and to check by eye. */
        private const val CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ"
        private val random = SecureRandom()

        /** "K7Q-3MD": what the owner checks matches what the agent says. */
        fun newCode(): String {
            val chars = (1..6).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
            return "${chars.substring(0, 3)}-${chars.substring(3)}"
        }
    }
}
