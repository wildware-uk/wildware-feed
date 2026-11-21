package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.db.bool
import dev.wildware.feed.server.db.longOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Secrets an agent attached to a post (migration 066, docs/SECRETS.md "Sharing in a thread"). The
 * post carries the name; the people reading it open the value in place, one at a time, through
 * `POST /api/cards/{kind}/{id}/secrets/{name}/reveal`. The value is never in the post, its event, a
 * tool result or another agent's read of it.
 *
 * An agent may share only a secret it may read itself, and only while it is not paused. A share
 * stays usable while that is still true: once the secret is deleted or paused, or the agent loses
 * it (or its token), the post shows the name as no longer available.
 *
 * Nothing here publishes, so a post can check and attach inside its own database work.
 */
class CardSecrets(private val db: Database, private val clock: () -> Long) {
    /** A secret as a share needs it: who may read it, and whether it is paused. */
    class Shareable(val id: String, val name: String)

    /** One share, as the owner's app and agents read it: the name, and whether it can still be opened. */
    class Shared(val name: String, val secretId: String, val agentId: String, val available: Boolean)

    /** [names] checked for [agentId]: each once, each a secret it may read, none paused. */
    fun check(agentId: String?, names: List<String>?): List<Shareable> {
        if (names.isNullOrEmpty()) return emptyList()
        if (agentId == null) throw invalid("only an agent attaches secrets to a post")
        val unique = names.map { it.jsTrim() }.distinct()
        if (unique.size > MAX) throw invalid("secrets takes at most $MAX names")
        return unique.mapIndexed { i, name ->
            if (!NAME.matches(name)) throw invalid("secrets takes names of secrets (like OPENAI_API_KEY), never values")
            val secret = byName(name)
            // The name is not repeated back: an agent that passed a value by mistake must not see it echoed.
            if (secret == null || !mayRead(secret, agentId)) {
                throw notFound("secrets[$i] is not the name of a secret you may share: list_secrets shows yours. Never put a value in the post instead.")
            }
            if (secret.paused) throw conflict("your owner has paused secrets[$i]: it cannot be shared until they resume it")
            Shareable(secret.id, name)
        }
    }

    /** Put [secrets] on [card], shared by [agentId], and say so in each secret's audit. */
    fun attach(card: CardRef, agentId: String, secrets: List<Shareable>) {
        if (secrets.isEmpty()) return
        val now = clock()
        val agentName = db.one("SELECT name FROM agents WHERE team_id = :team AND id = ?", agentId) { it.getString(1) }
        for (s in secrets) {
            val added = db.update(
                "INSERT INTO card_secrets (team_id, card_kind, card_id, secret_id, secret_name, agent_id, created_at) VALUES (:team, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT DO NOTHING",
                card.kind, card.id, s.id, s.name, agentId, now,
            )
            if (added == 0) continue
            db.update(
                "INSERT INTO secret_audit (team_id, id, secret_id, secret_name, action, actor, actor_id, actor_name, detail, at) VALUES (:team, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Ulid.next(now), s.id, s.name, "shared", "agent", agentId, agentName, "in ${card.kind} ${card.id}", now,
            )
        }
    }

    /** The shares on each of [ids] of [kind], in the order they were attached. Cards with none are left out. */
    fun on(kind: String, ids: List<String>): Map<String, List<Shared>> {
        if (ids.isEmpty()) return emptyMap()
        val rows = ids.distinct().chunked(CHUNK).flatMap { chunk ->
            db.query(
                "SELECT card_id, secret_id, secret_name, agent_id FROM card_secrets WHERE team_id = :team AND card_kind = ? " +
                    "AND card_id IN (${chunk.joinToString(",") { "?" }}) ORDER BY seq",
                kind, *chunk.toTypedArray(),
            ) { Row(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }
        }
        if (rows.isEmpty()) return emptyMap()
        val secrets = byIds(rows.map { it.secretId }.distinct())
        val live = liveAgents(rows.map { it.agentId }.distinct())
        return rows.groupBy({ it.cardId }) { r ->
            val secret = secrets[r.secretId]
            Shared(r.name, r.secretId, r.agentId, secret != null && !secret.paused && r.agentId in live && mayRead(secret, r.agentId))
        }
    }

    /** The share named [name] on [card], or null when the card has none by that name. */
    fun find(card: CardRef, name: String): Shared? = on(card.kind, listOf(card.id))[card.id]?.firstOrNull { it.name == name }

    /** Whether [card] is there and not deleted. */
    fun cardLive(card: CardRef): Boolean {
        val table = when (card.kind) {
            "update" -> "updates"
            "message" -> "messages"
            else -> return false
        }
        return db.one("SELECT deleted_at FROM $table WHERE team_id = :team AND id = ?", card.id) { it.longOrNull("deleted_at") == null } == true
    }

    private class Row(val cardId: String, val secretId: String, val name: String, val agentId: String)

    private class Secret(val id: String, val allAgents: Boolean, val agentIds: Set<String>, val paused: Boolean)

    private fun byName(name: String): Secret? =
        db.one("SELECT id FROM secrets WHERE team_id = :team AND name = ?", name) { it.getString(1) }?.let { byIds(listOf(it))[it] }

    /** [ids] as secrets, in three queries a chunk however many there are; deleted ones are left out. */
    private fun byIds(ids: List<String>): Map<String, Secret> = ids.chunked(CHUNK).flatMap { chunk ->
        val marks = chunk.joinToString(",") { "?" }
        val scopes = db.query(
            "SELECT secret_id, agent_id FROM secret_agents WHERE team_id = :team AND secret_id IN ($marks)", *chunk.toTypedArray(),
        ) { it.getString(1) to it.getString(2) }.groupBy({ it.first }, { it.second })
        db.query("SELECT id, all_agents, paused_at FROM secrets WHERE team_id = :team AND id IN ($marks)", *chunk.toTypedArray()) {
            val id = it.getString(1)
            Secret(id, it.bool("all_agents"), scopes[id].orEmpty().toSet(), it.longOrNull("paused_at") != null)
        }
    }.associateBy { it.id }

    /** Which of [ids] are agents whose token still works. */
    private fun liveAgents(ids: List<String>): Set<String> = ids.chunked(CHUNK).flatMap { chunk ->
        db.query(
            "SELECT id FROM agents WHERE team_id = :team AND revoked_at IS NULL AND id IN (${chunk.joinToString(",") { "?" }})", *chunk.toTypedArray(),
        ) { it.getString(1) }
    }.toSet()

    private fun mayRead(secret: Secret, agentId: String) = secret.allAgents || agentId in secret.agentIds

    companion object {
        const val MAX = 8
        private const val CHUNK = 400
        private val NAME = Regex("^[A-Za-z_][A-Za-z0-9_.-]{0,99}$")

        /** A share as the owner's app reads it. */
        fun json(shared: Shared): JsonObject = buildJsonObject {
            put("name", shared.name)
            put("available", shared.available)
        }
    }
}
