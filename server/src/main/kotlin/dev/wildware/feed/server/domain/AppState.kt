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
        val card = db.one("SELECT update_id, message_id, request_id FROM app_views WHERE team_id = :team AND id = ?", viewId) { rs ->
            rs.getString(1)?.takeIf { it.isNotEmpty() }?.let { "update" to it }
                ?: rs.getString(2)?.takeIf { it.isNotEmpty() }?.let { "message" to it }
                ?: ("request" to rs.getString(3))
        } ?: throw notFoundView()
        val (kind, cardId) = card
        val live = when (kind) {
            "update" -> db.one("SELECT deleted_at FROM updates WHERE team_id = :team AND id = ?", cardId) { it.getObject(1) == null }
            "message" -> db.one("SELECT deleted_at FROM messages WHERE team_id = :team AND id = ?", cardId) { it.getObject(1) == null }
            else -> db.one("SELECT 1 FROM approvals WHERE team_id = :team AND id = ?", cardId) { true }
        }
        if (live != true) throw notFound("no such $kind: $cardId")
        // A card in a channel this viewer can't read answers as missing (spec/15).
        val table = when (kind) { "update" -> "updates"; "message" -> "messages"; else -> "approvals" }
        val channel = db.one("SELECT project_id FROM $table WHERE team_id = :team AND id = ?", cardId) { it.getString(1) }
        if (!ChannelAccess(db, clock).visible(channel)) throw notFound("no such $kind: $cardId")

        val text = state?.let {
            if (FeedAttachments.tooDeep(it)) throw invalid("state may nest at most ${FeedAttachments.APP_JSON_DEPTH_MAX} levels deep")
            val encoded = Wire.encode(Js.normalize(it))
            if (encoded.length > STATE_MAX_LENGTH) throw invalid("state may be at most $STATE_MAX_LENGTH characters of JSON")
            encoded
        }
        val stored = db.one(
            "UPDATE app_views SET state = ?, updated_at = ? WHERE team_id = :team AND id = ? RETURNING app_id, state", text, clock(), viewId,
        ) { it.getString(1) to it.getString(2) } ?: throw notFoundView()
        val appExists = db.one("SELECT 1 FROM apps WHERE team_id = :team AND id = ?", stored.first) { true } == true
        check(appExists) { "app view $viewId names a missing app" }
        return viewId to stored.second?.let(::readObject)
    }

    /** Stored JSON back as an object, `{}` for anything that does not parse to one or nests too deep. */
    private fun readObject(text: String): JsonObject =
        (runCatching { Js.parse(text) }.getOrNull() as? JsonObject)?.takeUnless(FeedAttachments::tooDeep) ?: JsonObject(emptyMap())

    companion object {
        const val STATE_MAX_LENGTH = 20_000
    }
}
