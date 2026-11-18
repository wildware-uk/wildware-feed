package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.events.Bus
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Agent sessions and presence (TS `src/domain/sessions.ts`, spec/08 presence): an agent is online
 * while one of its open sessions has beaten within the last 90 s. `agent.presence` fires only on a
 * change; going quiet publishes nothing (spec/04 §1.6).
 */
class Sessions(private val db: Database, private val bus: Bus, private val work: AgentWork, private val clock: () -> Long = System::currentTimeMillis) {
    data class Session(val id: String, val agentId: String, val startedAt: Long, val lastHeartbeatAt: Long, val endedAt: Long?)

    fun register(agentId: String, meta: JsonObject?): Session {
        val at = clock()
        val clean = cleanMeta(meta)
        val wasOnline = onlineAt(agentId, at)
        val id = Ulid.next(at)
        db.update(
            "INSERT INTO sessions (id, agent_id, started_at, last_heartbeat_at, meta) VALUES (?, ?, ?, ?, ?)",
            id, agentId, at, at, clean?.toString(),
        )
        if (!wasOnline) announce(agentId, id, true)
        return find(id)!!
    }

    fun heartbeat(sessionId: String, agentId: String): AgentWork.Counts {
        val at = clock()
        val session = own(sessionId, agentId)
        if (session.endedAt != null) throw ended(session.id)
        val wasOnline = onlineAt(session.agentId, at)
        if (db.update("UPDATE sessions SET last_heartbeat_at = ? WHERE id = ? AND ended_at IS NULL", at, session.id) != 1) throw ended(session.id)
        if (!wasOnline) announce(session.agentId, session.id, true)
        return work.countWork(session.agentId)
    }

    /** The session, and whether this call ended it. */
    fun end(sessionId: String, agentId: String): Pair<Session, Boolean> {
        val at = clock()
        val session = own(sessionId, agentId)
        if (session.endedAt != null) return session to false
        val wasOnline = onlineAt(session.agentId, at)
        db.update("UPDATE sessions SET ended_at = ? WHERE id = ? AND ended_at IS NULL", at, session.id)
        if (wasOnline && !onlineAt(session.agentId, at)) announce(session.agentId, session.id, false)
        return find(session.id)!! to true
    }

    /** Close sessions idle for 10 minutes; announce agents that went offline because of it. */
    fun sweep(idleMs: Long = SESSION_IDLE_MS) {
        val at = clock()
        val onlineBefore = db.query(
            "SELECT DISTINCT agent_id FROM sessions WHERE ended_at IS NULL AND last_heartbeat_at >= ?", at - PRESENCE_WINDOW_MS,
        ) { it.getString(1) }.toSet()
        val closed = db.query(
            "UPDATE sessions SET ended_at = ? WHERE ended_at IS NULL AND last_heartbeat_at < ? RETURNING agent_id", at, at - idleMs,
        ) { it.getString(1) }.toSet()
        for (agentId in closed) {
            if (agentId !in onlineBefore || onlineAt(agentId, at)) continue
            announce(agentId, null, false)
        }
    }

    fun find(id: String): Session? = db.one("SELECT * FROM sessions WHERE id = ?", id) {
        Session(it.getString("id"), it.getString("agent_id"), it.getLong("started_at"), it.getLong("last_heartbeat_at"),
            it.getLong("ended_at").let { v -> if (it.wasNull()) null else v })
    }

    private fun onlineAt(agentId: String, at: Long) = db.one(
        "SELECT 1 FROM sessions WHERE agent_id = ? AND ended_at IS NULL AND last_heartbeat_at >= ? LIMIT 1", agentId, at - PRESENCE_WINDOW_MS,
    ) { 1 } != null

    private fun announce(agentId: String, sessionId: String?, online: Boolean) {
        bus.publish("agent.presence", buildJsonObject {
            put("agentId", JsonPrimitive(agentId))
            put("sessionId", sessionId?.let(::JsonPrimitive) ?: JsonNull)
            put("online", JsonPrimitive(online))
        })
    }

    private fun own(sessionId: String, agentId: String): Session {
        val session = find(sessionId) ?: throw notFound("no such session: $sessionId")
        if (session.agentId != agentId) throw invalid("session belongs to another agent")
        return session
    }

    private fun ended(id: String) = conflict("session $id has ended; call register_session to start a new one")

    /** host ≤200, cwd ≤1000, model ≤100, trimmed; blank ones dropped; nothing left is null. */
    private fun cleanMeta(meta: JsonObject?): JsonObject? {
        if (meta == null) return null
        val cleaned = LinkedHashMap<String, JsonPrimitive>()
        for ((field, max) in listOf("host" to 200, "cwd" to 1000, "model" to 100)) {
            val raw = meta[field] ?: continue
            if (raw is JsonNull) continue
            if (raw !is JsonPrimitive || !raw.isString) throw invalid("meta.$field must be a string")
            val text = raw.content.jsTrim()
            if (text.length > max) throw invalid("meta.$field must be at most $max characters")
            if (text.isNotEmpty()) cleaned[field] = JsonPrimitive(text)
        }
        return if (cleaned.isEmpty()) null else JsonObject(cleaned)
    }

    companion object {
        const val HEARTBEAT_INTERVAL_S = 30
        const val PRESENCE_WINDOW_MS = 90_000L
        const val SESSION_IDLE_MS = 10 * 60_000L
        const val SWEEP_INTERVAL_MS = 60_000L
    }
}
