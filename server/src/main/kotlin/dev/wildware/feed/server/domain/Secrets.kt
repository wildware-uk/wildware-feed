package dev.wildware.feed.server.domain

import dev.wildware.feed.server.auth.SecretBox
import dev.wildware.feed.server.auth.Tokens
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.db.bool
import dev.wildware.feed.server.db.longOrNull
import dev.wildware.feed.server.events.Bus
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.ResultSet

/**
 * Secrets the owner and agents share (migration 054, docs/SECRETS.md). A value is sealed at rest by
 * [box] and leaves only three ways: as the body of a single-use URL an agent was granted, to the
 * owner's reveal, or not at all. It is never in a tool result, an event, a log line or an error.
 *
 * [box] is null when SECRETS_KEY is not set: then nothing can be stored or handed over, and every
 * call that would says so with `secrets_disabled`.
 *
 * Never call into this while holding the database lock: it publishes.
 */
class Secrets(
    private val db: Database,
    private val bus: Bus,
    private val box: SecretBox?,
    private val tokenSecret: String,
    /** This work's team's address, where its secret links point. */
    private val baseUrl: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Secret(
        val id: String,
        val name: String,
        val note: String?,
        val allAgents: Boolean,
        val agentIds: List<String>,
        val bytes: Int,
        val createdBy: String,
        val createdAt: Long,
        val updatedAt: Long,
        val pausedAt: Long?,
        val lastUsedAt: Long?,
    )

    /** Who did something, for the audit: the owner on a device, an agent, or the server itself. */
    data class Actor(val kind: String, val id: String?, val name: String?) {
        companion object {
            fun owner(device: Devices.Device?) = Actor("owner", device?.id, device?.name)
            fun agent(agent: Agents.Agent) = Actor("agent", agent.id, agent.name)
            val SERVER = Actor("server", null, null)
        }
    }

    data class Grant(val name: String, val url: String, val expiresAt: Long)

    data class AuditEntry(
        val id: String,
        val secretId: String?,
        val secretName: String,
        val action: String,
        val actor: Actor,
        val detail: String?,
        val at: Long,
    )

    /** A URL that does not work, whatever the reason; the reason itself goes to the audit. */
    class Rejected : RuntimeException(
        "this secret URL is not valid: it has expired, was already used, or the secret is no longer yours to read. " +
            "Call secret_url (or create_secret_upload) again for a fresh one.",
    )

    val enabled: Boolean get() = box != null

    // ---- the owner ----------------------------------------------------------------------------

    fun list(): List<Secret> {
        val scopes = scopes()
        return db.query("SELECT * FROM secrets WHERE team_id = :team ORDER BY name") { row(it, scopes) }
    }

    fun find(id: String): Secret? = db.one("SELECT * FROM secrets WHERE team_id = :team AND id = ?", id) { row(it, scopes(id)) }

    private fun byName(name: String): Secret? = db.one("SELECT * FROM secrets WHERE team_id = :team AND name = ?", name) { row(it, null) }
        ?.let { it.copy(agentIds = scopes(it.id)[it.id].orEmpty()) }

    fun create(name: String, value: ByteArray, note: String?, allAgents: Boolean, agentIds: List<String>, actor: Actor): Secret {
        val sealer = sealer()
        val clean = checkName(name)
        checkValue(value)
        val cleanNote = optionalText(note, "note", NOTE_MAX)
        val agents = checkAgents(agentIds)
        if (byName(clean) != null) throw conflict("a secret named $clean already exists")
        val now = clock()
        val id = Ulid.next(now)
        val sealed = sealer.seal(id, value)
        db.transaction {
            db.update(
                "INSERT INTO secrets (team_id, id, name, note, all_agents, key_id, wrapped_key, sealed, bytes, created_by, created_at, updated_at) " +
                    "VALUES (:team, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, clean, cleanNote, allAgents, sealed.keyId, sealed.wrappedKey, sealed.sealed, value.size, by(actor), now, now,
            )
            for (agent in agents) db.update("INSERT INTO secret_agents (team_id, secret_id, agent_id) VALUES (:team, ?, ?)", id, agent)
            audit(id, clean, "created", actor, scopeText(allAgents, agents))
        }
        changed(id, "created")
        return find(id)!!
    }

    /** A change from the owner. A null field is left as it is; [noteGiven] with a null [note] clears it. */
    fun change(
        id: String,
        actor: Actor,
        value: ByteArray? = null,
        noteGiven: Boolean = false,
        note: String? = null,
        allAgents: Boolean? = null,
        agentIds: List<String>? = null,
        paused: Boolean? = null,
    ): Secret {
        val secret = find(id) ?: throw notFound("no such secret: $id")
        if (value == null && !noteGiven && allAgents == null && agentIds == null && paused == null) {
            throw invalid("a change must set the value, the note, who may use it, or whether it is paused")
        }
        val sealed = value?.let { checkValue(it); sealer().seal(id, it) }
        val cleanNote = if (noteGiven) optionalText(note, "note", NOTE_MAX) else null
        val agents = agentIds?.let(::checkAgents)
        val now = clock()
        db.transaction {
            if (sealed != null) {
                db.update(
                    "UPDATE secrets SET key_id = ?, wrapped_key = ?, sealed = ?, bytes = ?, updated_at = ? WHERE team_id = :team AND id = ?",
                    sealed.keyId, sealed.wrappedKey, sealed.sealed, value!!.size, now, id,
                )
                audit(id, secret.name, "value_changed", actor, null)
            }
            if (noteGiven) db.update("UPDATE secrets SET note = ?, updated_at = ? WHERE team_id = :team AND id = ?", cleanNote, now, id)
            if (allAgents != null || agents != null) {
                val all = allAgents ?: secret.allAgents
                db.update("UPDATE secrets SET all_agents = ?, updated_at = ? WHERE team_id = :team AND id = ?", all, now, id)
                val named = agents ?: secret.agentIds
                if (agents != null) {
                    db.update("DELETE FROM secret_agents WHERE team_id = :team AND secret_id = ?", id)
                    for (agent in agents) db.update("INSERT INTO secret_agents (team_id, secret_id, agent_id) VALUES (:team, ?, ?)", id, agent)
                }
                audit(id, secret.name, "scope_changed", actor, scopeText(all, named))
            }
            if (paused != null && paused != (secret.pausedAt != null)) {
                db.update("UPDATE secrets SET paused_at = ? WHERE team_id = :team AND id = ?", if (paused) now else null, id)
                audit(id, secret.name, if (paused) "paused" else "resumed", actor, null)
            }
            // Once the owner has changed it, it is the owner's: the agent that uploaded it can no
            // longer overwrite what every other reader fetches.
            if (actor.kind == "owner") db.update("UPDATE secrets SET created_by = 'owner' WHERE team_id = :team AND id = ?", id)
            // Any change of value, scope or pause makes the URLs already handed out worthless.
            if (sealed != null || allAgents != null || agents != null || paused == true) {
                db.update("DELETE FROM secret_grants WHERE team_id = :team AND secret_id = ? AND used_at IS NULL", id)
            }
        }
        changed(id, "changed")
        return find(id)!!
    }

    fun delete(id: String, actor: Actor) {
        val secret = find(id) ?: throw notFound("no such secret: $id")
        db.transaction {
            db.update("DELETE FROM secrets WHERE team_id = :team AND id = ?", id)
            audit(id, secret.name, "deleted", actor, null)
        }
        changed(id, "deleted")
    }

    /** The value for the owner's own eyes ([purpose] "reveal") or clipboard ("copy"). */
    fun reveal(id: String, purpose: String, actor: Actor): ByteArray {
        if (purpose != "reveal" && purpose != "copy") throw invalid("purpose must be reveal or copy")
        val opener = sealer()
        val secret = find(id) ?: throw notFound("no such secret: $id")
        val value = opener.open(id, sealedOf(id))
        audit(id, secret.name, if (purpose == "copy") "copied" else "revealed", actor, null)
        changed(id, "audited")
        return value
    }

    /**
     * A secret an agent attached to [card] (migration 066), for a person's eyes ([purpose] "reveal")
     * or clipboard ("copy"). [who] is the person, for the audit. A share that can no longer be opened
     * (the card or the secret is gone, the secret is paused, or the agent that shared it may no longer
     * read it) answers `secret_unavailable`, 410.
     */
    fun revealShared(card: CardRef, name: String, purpose: String, actor: Actor, who: String?): ByteArray {
        if (purpose != "reveal" && purpose != "copy") throw invalid("purpose must be reveal or copy")
        if (card.kind != "update" && card.kind != "message") throw invalid("secrets are shared on an update or a message")
        val opener = sealer()
        val shares = CardSecrets(db, clock)
        val shared = shares.find(card, name) ?: throw notFound("no secret named $name on that ${card.kind}")
        val secret = find(shared.secretId)
        if (secret == null || !shared.available || !shares.cardLive(card)) throw unavailable(name)
        val value = opener.open(secret.id, sealedOf(secret.id))
        val where = "in ${card.kind} ${card.id}" + (who?.let { ", by $it" } ?: "")
        audit(secret.id, secret.name, if (purpose == "copy") "copied" else "revealed", actor, where)
        changed(secret.id, "audited")
        return value
    }

    /** Newest first; [secretId] narrows it to one secret, deleted ones included. */
    fun audit(secretId: String? = null, limit: Int = 200): List<AuditEntry> = db.query(
        "SELECT * FROM secret_audit WHERE team_id = :team AND (? IS NULL OR secret_id = ?) ORDER BY seq DESC LIMIT ?",
        secretId, secretId, limit.coerceIn(1, 1000),
    ) {
        AuditEntry(
            it.getString("id"), it.getString("secret_id"), it.getString("secret_name"), it.getString("action"),
            Actor(it.getString("actor"), it.getString("actor_id"), it.getString("actor_name")), it.getString("detail"), it.getLong("at"),
        )
    }

    /**
     * The owner answers a `request_secret`: the value is stored under the name the agent asked for
     * (replacing it if there is one), the asking agent may now read it, and the request is settled
     * with the name only. The request row never sees the value.
     */
    fun provide(requests: Requests, requestId: String, value: ByteArray, note: String?, actor: Actor, by: String = People.FOUNDER): Secret {
        sealer()
        val request = requests.find(requestId) ?: throw notFound("no such request: $requestId")
        if (request.kind != "secret") throw invalid("request $requestId is not asking for a secret")
        val name = (request.config?.get("secretName") as? JsonPrimitive)?.content ?: throw invalid("request $requestId names no secret")
        checkValue(value)
        val existing = byName(name)
        // Answering never shares an existing secret with a new reader: the asker must already be one.
        if (existing != null && !mayRead(existing, request.agentId)) {
            throw conflict("a secret named $name already exists and is not shared with the agent asking: share it, or answer with another name")
        }
        // Claim the request first (this also times it out if it is past due), so a value is only
        // stored for a request that was still waiting.
        val claimed = requests.claimSecret(requestId, name, by)
        val secret = try {
            if (existing == null) {
                create(name, value, note, allAgents = false, agentIds = listOf(request.agentId), actor = actor)
            } else {
                change(existing.id, actor, value = value, noteGiven = note != null, note = note)
            }
        } catch (e: Throwable) {
            // Nothing stored, so the agent must not hear "saved": the request waits again.
            requests.releaseSecret(claimed)
            throw e
        }
        audit(secret.id, name, "provided", actor, "for request $requestId")
        requests.announceSecret(claimed)
        return secret
    }

    /** `request_secret` asks only for a new name, or for one the agent may already read. */
    fun checkRequestable(agentId: String, name: String): String {
        sealer()
        val clean = checkName(name)
        val existing = byName(clean)
        if (existing != null && !mayRead(existing, agentId)) {
            throw conflict("$clean is taken by a secret that is not shared with you: ask for it under another name, or ask your owner to share it")
        }
        return clean
    }

    // ---- agents -------------------------------------------------------------------------------

    /** The secrets [agentId] may read, without values. */
    fun visibleTo(agentId: String): List<Secret> = list().filter { mayRead(it, agentId) }

    /** `secret_url`: a single-use URL that answers with the value of [name], for [agent] only. */
    fun issueRead(agent: Agents.Agent, name: String): Grant {
        sealer()
        val clean = checkName(name)
        val secret = byName(clean)?.takeIf { mayRead(it, agent.id) }
            ?: throw notFound("no secret named $clean that you may read. Call list_secrets to see yours, or request_secret to ask your owner for it.")
        if (secret.pausedAt != null) throw conflict("your owner has paused $clean: it cannot be handed out until they resume it")
        val grant = grant(agent, "read", secret.id, clean, null)
        audit(secret.id, clean, "url_issued", Actor.agent(agent), null).also { changed(secret.id, "audited") }
        return grant
    }

    /** `create_secret_upload`: a single-use URL that stores what is sent to it as [name]. */
    fun issueWrite(agent: Agents.Agent, name: String, note: String?): Grant {
        sealer()
        val clean = checkName(name)
        val cleanNote = optionalText(note, "note", NOTE_MAX)
        val existing = byName(clean)
        if (existing != null && !mayReplace(existing, agent.id)) {
            throw conflict("$clean is taken and is not yours to replace: pick another name")
        }
        if (existing == null) {
            val mine = db.one("SELECT count(*) FROM secrets WHERE team_id = :team AND created_by = ?", "agent:${agent.id}") { it.getInt(1) } ?: 0
            if (mine >= AGENT_SECRETS_MAX) throw conflict("you have uploaded $mine secrets, the most an agent may: ask your owner to delete some")
        }
        val grant = grant(agent, "write", existing?.id, clean, cleanNote)
        audit(existing?.id, clean, "upload_url_issued", Actor.agent(agent), null)
        existing?.let { changed(it.id, "audited") }
        return grant
    }

    /** The value behind a read URL, once. Any refusal is [Rejected]; the audit says which. */
    fun redeemRead(token: String, from: String? = null): ByteArray {
        val opener = box ?: throw Rejected()
        val grant = spend(token, "read", from) ?: throw Rejected()
        val agent = liveAgent(grant.agentId)
        val secret = grant.secretId?.let(::find)
        val refusal = when {
            agent == null -> "the agent's token was revoked"
            secret == null -> "the secret was deleted"
            secret.pausedAt != null -> "the secret is paused"
            !mayRead(secret, agent.id) -> "the agent may no longer read it"
            else -> null
        }
        val actor = agent?.let(Actor::agent) ?: Actor("agent", grant.agentId, null)
        if (refusal != null) {
            if (secret != null) audit(secret.id, secret.name, "refused", actor, listOfNotNull(refusal, from).joinToString(", ")).also { changed(secret.id, "audited") }
            throw Rejected()
        }
        val value = opener.open(secret!!.id, sealedOf(secret.id))
        db.transaction {
            db.update("UPDATE secrets SET last_used_at = ? WHERE team_id = :team AND id = ?", clock(), secret.id)
            audit(secret.id, secret.name, "downloaded", actor, from)
        }
        changed(secret.id, "audited")
        return value
    }

    /** Store [value] through a write URL, once. A bad body leaves the URL unspent, so it can be retried. */
    fun redeemWrite(token: String, value: ByteArray, from: String? = null): Secret {
        val sealer = box ?: throw Rejected()
        checkValue(value)
        val grant = spend(token, "write", from) ?: throw Rejected()
        val agent = liveAgent(grant.agentId) ?: throw Rejected()
        val actor = Actor.agent(agent)
        val existing = byName(grant.name)
        val id = when {
            existing == null -> {
                if (grant.secretId != null) throw Rejected() // it was deleted since the URL was issued
                val mine = db.one("SELECT count(*) FROM secrets WHERE team_id = :team AND created_by = ?", "agent:${agent.id}") { it.getInt(1) } ?: 0
                if (mine >= AGENT_SECRETS_MAX) throw Rejected()
                create(grant.name, value, grant.note, allAgents = false, agentIds = listOf(agent.id), actor = actor).id
            }
            !mayReplace(existing, agent.id) || existing.id != grant.secretId -> throw Rejected()
            else -> {
                val sealed = sealer.seal(existing.id, value)
                val now = clock()
                db.transaction {
                    db.update(
                        "UPDATE secrets SET key_id = ?, wrapped_key = ?, sealed = ?, bytes = ?, updated_at = ?, note = coalesce(?, note) WHERE team_id = :team AND id = ?",
                        sealed.keyId, sealed.wrappedKey, sealed.sealed, value.size, now, grant.note, existing.id,
                    )
                    db.update("DELETE FROM secret_grants WHERE team_id = :team AND secret_id = ? AND used_at IS NULL", existing.id)
                }
                existing.id
            }
        }
        val name = grant.name
        audit(id, name, "uploaded", actor, from)
        changed(id, "changed")
        return find(id)!!
    }

    // ---- upkeep -------------------------------------------------------------------------------

    /** Re-seal every data key still under an old SECRETS_KEY; how many were. Rows it cannot open are left and counted in [unopened]. */
    fun reseal(): Pair<Int, Int> {
        val sealer = box ?: return 0 to 0
        val stale = db.query("SELECT id, key_id, wrapped_key, sealed FROM secrets WHERE team_id = :team AND key_id <> ?", sealer.currentKeyId) {
            it.getString(1) to SecretBox.Sealed(it.getString(2), it.getString(3), it.getString(4))
        }
        var done = 0
        var unopened = 0
        for ((id, row) in stale) {
            val fresh = try {
                sealer.reseal(id, row)
            } catch (_: SecretBox.CannotOpen) {
                unopened++
                null
            } ?: continue
            if (db.update("UPDATE secrets SET key_id = ?, wrapped_key = ? WHERE team_id = :team AND id = ? AND key_id = ?", fresh.keyId, fresh.wrappedKey, id, row.keyId) == 1) done++
        }
        return done to unopened
    }

    /** Spent and expired URLs go. */
    fun sweep() {
        val now = clock()
        db.update(
            "DELETE FROM secret_grants WHERE team_id = :team AND (used_at IS NULL AND expires_at <= ?) OR (used_at IS NOT NULL AND used_at <= ?)",
            now - SPENT_KEPT_MS, now - SPENT_KEPT_MS,
        )
    }

    // ---- wire ---------------------------------------------------------------------------------

    /** What the owner's app reads: never the value. */
    fun ownerJson(secret: Secret, agentName: (String) -> String?): JsonObject = buildJsonObject {
        put("id", secret.id)
        put("name", secret.name)
        put("note", secret.note)
        put("allAgents", secret.allAgents)
        put("agents", JsonArray(secret.agentIds.map { id -> buildJsonObject { put("id", id); put("name", agentName(id)) } }))
        put("bytes", secret.bytes)
        put("createdBy", secret.createdBy)
        put("createdAt", Wire.isoOf(secret.createdAt))
        put("updatedAt", Wire.isoOf(secret.updatedAt))
        put("pausedAt", secret.pausedAt?.let(Wire::isoOf))
        put("lastUsedAt", secret.lastUsedAt?.let(Wire::isoOf))
    }

    fun auditJson(entry: AuditEntry): JsonObject = buildJsonObject {
        put("id", entry.id)
        put("secretId", entry.secretId)
        put("secretName", entry.secretName)
        put("action", entry.action)
        put("actor", buildJsonObject { put("kind", entry.actor.kind); put("id", entry.actor.id); put("name", entry.actor.name) })
        put("detail", entry.detail)
        put("at", Wire.isoOf(entry.at))
    }

    // ---- inside -------------------------------------------------------------------------------

    private class Spent(val secretId: String?, val agentId: String, val name: String, val note: String?)

    private fun unavailable(name: String) = DomainError("secret_unavailable", "$name is no longer available", 410)

    private fun sealer(): SecretBox = box ?: throw DomainError(
        "secrets_disabled",
        "secrets are not set up on this dashboard: its owner has to set SECRETS_KEY on the server first",
        503,
    )

    private fun grant(agent: Agents.Agent, kind: String, secretId: String?, name: String, note: String?): Grant {
        val now = clock()
        val live = db.one(
            "SELECT count(*) FROM secret_grants WHERE team_id = :team AND agent_id = ? AND used_at IS NULL AND expires_at > ?", agent.id, now,
        ) { it.getInt(1) } ?: 0
        if (live >= LIVE_GRANTS_MAX) throw conflict("you have $live unused secret URLs: use or let those expire first")
        val token = Tokens.random()
        val expiresAt = now + GRANT_TTL_MS
        db.update(
            "INSERT INTO secret_grants (team_id, id, token_hash, kind, secret_id, name, note, agent_id, created_at, expires_at) VALUES (:team, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            Ulid.next(now), hashOf(token), kind, secretId, name, note, agent.id, now, expiresAt,
        )
        return Grant(name, "${baseUrl()}/api/agent/secret/$token", expiresAt)
    }

    /** Whether [token] is a write URL that is still unused and unexpired: checked before a body is read. */
    fun writeLinkLive(token: String): Boolean {
        if (!Tokens.wellFormed(token)) return false
        return db.one(
            "SELECT 1 FROM secret_grants WHERE team_id = :team AND token_hash = ? AND kind = 'write' AND used_at IS NULL AND expires_at > ?", hashOf(token), clock(),
        ) { 1 } != null
    }

    /** Spend a URL token of [kind]: the grant, or null for a token that is unknown, spent or expired. */
    private fun spend(token: String, kind: String, from: String?): Spent? {
        if (!Tokens.wellFormed(token)) return null
        val hash = hashOf(token)
        val now = clock()
        val spent = db.one(
            "UPDATE secret_grants SET used_at = ? WHERE team_id = :team AND token_hash = ? AND kind = ? AND used_at IS NULL AND expires_at > ? " +
                "RETURNING secret_id, agent_id, name, note",
            now, hash, kind, now,
        ) { Spent(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }
        if (spent != null) return spent
        // A second use of a real URL may mean somebody else has it: worth the owner knowing.
        db.one("SELECT secret_id, name, agent_id, used_at FROM secret_grants WHERE team_id = :team AND token_hash = ? AND kind = ?", hash, kind) {
            Triple(it.getString(1), it.getString(2), it.getString(3)) to it.longOrNull("used_at")
        }?.let { (grant, usedAt) ->
            val (secretId, name, agentId) = grant
            val why = if (usedAt != null) "a URL that was already used was tried again" else "an expired URL was tried"
            audit(secretId, name, "refused", Actor("agent", agentId, liveAgent(agentId)?.name), listOfNotNull(why, from).joinToString(", "))
            secretId?.let { changed(it, "audited") }
        }
        return null
    }

    private fun hashOf(token: String) = Tokens.hex(Tokens.hmac(tokenSecret, "secret-grant:$token"))

    private fun liveAgent(id: String): Agents.Agent? = db.one("SELECT id, name, created_at, revoked_at, last_seen_at FROM agents WHERE team_id = :team AND id = ?", id) {
        Agents.Agent(it.getString(1), it.getString(2), it.getLong(3), it.longOrNull("revoked_at"), it.longOrNull("last_seen_at"))
    }?.takeIf { it.revokedAt == null }

    private fun mayRead(secret: Secret, agentId: String) = secret.allAgents || agentId in secret.agentIds

    /** An agent replaces only a secret it uploaded, that the owner has not taken over, that it may still read, and that is not paused. */
    private fun mayReplace(secret: Secret, agentId: String) =
        secret.createdBy == "agent:$agentId" && mayRead(secret, agentId) && secret.pausedAt == null

    private fun sealedOf(id: String): SecretBox.Sealed =
        db.one("SELECT key_id, wrapped_key, sealed FROM secrets WHERE team_id = :team AND id = ?", id) {
            SecretBox.Sealed(it.getString(1), it.getString(2), it.getString(3))
        } ?: throw notFound("no such secret: $id")

    private fun scopes(only: String? = null): Map<String, List<String>> = db.query(
        "SELECT secret_id, agent_id FROM secret_agents WHERE team_id = :team AND (? IS NULL OR secret_id = ?) ORDER BY agent_id", only, only,
    ) { it.getString(1) to it.getString(2) }.groupBy({ it.first }, { it.second })

    private fun row(rs: ResultSet, scopes: Map<String, List<String>>?): Secret {
        val id = rs.getString("id")
        return Secret(
            id = id,
            name = rs.getString("name"),
            note = rs.getString("note"),
            allAgents = rs.bool("all_agents"),
            agentIds = scopes?.get(id).orEmpty(),
            bytes = rs.getInt("bytes"),
            createdBy = rs.getString("created_by"),
            createdAt = rs.getLong("created_at"),
            updatedAt = rs.getLong("updated_at"),
            pausedAt = rs.longOrNull("paused_at"),
            lastUsedAt = rs.longOrNull("last_used_at"),
        )
    }

    private fun checkName(name: String): String {
        val trimmed = name.jsTrim()
        if (!NAME.matches(trimmed)) {
            throw invalid("a secret's name is 1-100 letters, digits, _ . or -, starting with a letter or _ (like OPENAI_API_KEY)")
        }
        return trimmed
    }

    private fun checkValue(value: ByteArray) {
        if (value.isEmpty()) throw invalid("a secret's value cannot be empty")
        if (value.size > VALUE_MAX_BYTES) throw DomainError("too_large", "a secret is at most $VALUE_MAX_BYTES bytes", 413)
    }

    private fun checkAgents(ids: List<String>): List<String> {
        val unique = ids.distinct()
        if (unique.size > 200) throw invalid("at most 200 agents")
        for (id in unique) {
            db.one("SELECT 1 FROM agents WHERE team_id = :team AND id = ?", id) { 1 } ?: throw notFound("no such agent: $id")
        }
        return unique
    }

    private fun by(actor: Actor) = if (actor.kind == "agent") "agent:${actor.id}" else actor.kind

    private fun scopeText(all: Boolean, agents: List<String>) = if (all) "all agents" else "${agents.size} agent(s)"

    private fun auditArgs(secretId: String?, name: String, action: String, actor: Actor, detail: String?): Array<Any?> =
        arrayOf(Ulid.next(clock()), secretId, name, action, actor.kind, actor.id, actor.name, detail, clock())

    private fun audit(secretId: String?, name: String, action: String, actor: Actor, detail: String?) {
        db.update("INSERT INTO secret_audit $AUDIT_COLUMNS VALUES (:team, ?, ?, ?, ?, ?, ?, ?, ?, ?)", *auditArgs(secretId, name, action, actor, detail))
    }

    /** Tell the owner's app; only the id and what happened, never a name or a value. */
    private fun changed(id: String, what: String) {
        bus.publish("secret.changed", buildJsonObject { put("secretId", id); put("change", what) })
    }

    companion object {
        const val GRANT_TTL_MS = 5 * 60_000L
        const val VALUE_MAX_BYTES = 64 * 1024
        const val NOTE_MAX = 500
        const val LIVE_GRANTS_MAX = 20
        const val AGENT_SECRETS_MAX = 50
        const val SWEEP_INTERVAL_MS = 60_000L
        private const val SPENT_KEPT_MS = 24 * 60 * 60_000L
        private val NAME = Regex("^[A-Za-z_][A-Za-z0-9_.-]{0,99}$")
        private const val AUDIT_COLUMNS = "(team_id, id, secret_id, secret_name, action, actor, actor_id, actor_name, detail, at)"
    }
}
