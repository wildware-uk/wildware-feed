package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonObject

/**
 * `saveAppState` (TS `src/domain/apps.ts:387`, spec/03 §8): the owner's per-showing app state.
 * No bus event.
 */
class AppState(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    /** Store [state] (an object, or null to forget it) and answer the view id and the state as read back. */
    fun save(viewId: String, state: JsonObject?): Pair<String, JsonObject?> {
        val notFoundView = { notFound("no app view ${Wire.encode(kotlinx.serialization.json.JsonPrimitive(viewId))}") }
        val card = db.one("SELECT update_id, message_id, request_id FROM app_views WHERE id = ?", viewId) { rs ->
            rs.getString(1)?.takeIf { it.isNotEmpty() }?.let { "update" to it }
                ?: rs.getString(2)?.takeIf { it.isNotEmpty() }?.let { "message" to it }
                ?: ("request" to rs.getString(3))
        } ?: throw notFoundView()
        val (kind, cardId) = card
        val live = when (kind) {
            "update" -> db.one("SELECT deleted_at FROM updates WHERE id = ?", cardId) { it.getObject(1) == null }
            "message" -> db.one("SELECT deleted_at FROM messages WHERE id = ?", cardId) { it.getObject(1) == null }
            else -> db.one("SELECT 1 FROM approvals WHERE id = ?", cardId) { true }
        }
        if (live != true) throw notFound("no such $kind: $cardId")

        val text = state?.let {
            val encoded = Wire.encode(Js.normalize(it))
            if (encoded.length > STATE_MAX_LENGTH) throw invalid("state may be at most $STATE_MAX_LENGTH characters of JSON")
            encoded
        }
        val stored = db.one(
            "UPDATE app_views SET state = ?, updated_at = ? WHERE id = ? RETURNING app_id, state", text, clock(), viewId,
        ) { it.getString(1) to it.getString(2) } ?: throw notFoundView()
        val appExists = db.one("SELECT 1 FROM apps WHERE id = ?", stored.first) { true } == true
        check(appExists) { "app view $viewId names a missing app" }
        return viewId to stored.second?.let(::readObject)
    }

    /** Stored JSON back as an object, `{}` for anything that does not parse to one. */
    private fun readObject(text: String): JsonObject =
        runCatching { Js.parse(text) }.getOrNull() as? JsonObject ?: JsonObject(emptyMap())

    companion object {
        const val STATE_MAX_LENGTH = 20_000
    }
}
