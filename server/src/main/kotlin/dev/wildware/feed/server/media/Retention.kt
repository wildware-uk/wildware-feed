package dev.wildware.feed.server.media

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Dialect
import dev.wildware.feed.server.domain.invalid
import dev.wildware.feed.server.events.Bus
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Media retention (migration 048): the owner chooses how many days media of which kinds is kept;
 * once a night, older media loses its files and is marked removed. Its card keeps a note that it
 * was removed, and after how many days.
 *
 * Kept whatever its age: media on a pinned card, on a card with an open request, and project logos.
 * A removal deletes the files first and marks the row after, in one transaction with its
 * derivatives, so a run cut short by a restart is simply finished by the next one.
 */
class Retention(
    private val db: Database,
    private val bus: Bus,
    private val paths: MediaPaths,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Settings(val days: Int?, val kinds: List<String>) {
        fun json(): JsonObject = buildJsonObject {
            put("days", days?.let(::JsonPrimitive) ?: JsonNull)
            putJsonArray("kinds") { kinds.forEach { add(JsonPrimitive(it)) } }
        }
    }

    fun settings(): Settings {
        val stored = setting(SETTING) ?: return Settings(null, KINDS)
        val o = Wire.json.parseToJsonElement(stored).jsonObject
        val days = (o["days"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toIntOrNull()
        val kinds = o["kinds"]?.jsonArray?.map { (it as JsonPrimitive).content } ?: KINDS
        return Settings(days, kinds)
    }

    /** Checks and keeps the owner's choice: `{days: 1..3650 | null, kinds?: [...]}`. */
    fun set(body: JsonObject): Settings {
        val rawDays = body["days"]
        val days = when {
            rawDays == null || rawDays is JsonNull -> null
            rawDays is JsonPrimitive && !rawDays.isString && rawDays.doubleOrNull?.let { it % 1.0 == 0.0 && it in 1.0..3650.0 } == true ->
                rawDays.doubleOrNull!!.toInt()
            else -> throw invalid("days must be a whole number of days from 1 to 3650, or null for never")
        }
        val kinds = when (val raw = body["kinds"]) {
            null, is JsonNull -> settings().kinds
            is JsonArray -> raw.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString && p.content in KINDS }?.content ?: throw kindsError() }
                .toSet().let { chosen -> KINDS.filter { it in chosen } }
            else -> throw kindsError()
        }
        if (kinds.isEmpty()) throw invalid("choose at least one kind of media")
        val settings = Settings(days, kinds)
        put(SETTING, Wire.encode(settings.json()))
        return settings
    }

    private fun kindsError() = invalid("kinds must be a list of: ${KINDS.joinToString(", ")}")

    /** Whether tonight's run is due: inside the night window and not run in the last 12 hours, or not run for 36. */
    fun due(now: Long = clock()): Boolean {
        if (settings().days == null) return false
        val last = setting(LAST_RUN)?.toLongOrNull()
        val hour = ((now / 3_600_000L) % 24).toInt()
        val night = hour in NIGHT_HOURS
        return when {
            last == null -> night
            now - last >= CATCH_UP_MS -> true
            else -> night && now - last >= MIN_GAP_MS
        }
    }

    /** The scheduler's hook: runs when [due]. */
    fun tick() {
        if (due()) run()
    }

    /** Removes everything expired now; how many media it removed. Safe to call any time, and again. */
    fun run(): Int {
        val settings = settings()
        val now = clock()
        put(LAST_RUN, now.toString())
        val days = settings.days ?: return 0
        val cutoff = now - days * DAY_MS
        var removed = 0
        while (true) {
            val batch = candidates(settings.kinds, cutoff)
            if (batch.isEmpty()) break
            for (id in batch) if (remove(id, days, now)) removed++
            if (batch.size < BATCH) break
        }
        return removed
    }

    private fun candidates(kinds: List<String>, cutoff: Long): List<String> = db.query(
        """SELECT m.id FROM media m
            WHERE m.team_id = :team AND m.removed_at IS NULL AND m.sha256 <> '' AND m.status IN ('ready', 'failed')
              AND m.created_at < ? AND m.kind IN (${kinds.joinToString(", ") { "?" }})
              AND NOT EXISTS (SELECT 1 FROM updates u WHERE u.id = m.update_id AND u.pinned = 1)
              AND NOT EXISTS (SELECT 1 FROM messages g WHERE g.id = m.message_id AND g.pinned = 1)
              AND NOT EXISTS (SELECT 1 FROM tasks t WHERE t.id = m.task_id AND t.pinned = 1)
              AND NOT EXISTS (SELECT 1 FROM approvals a WHERE a.id = m.request_id AND (a.pinned = 1 OR a.state = 'pending'))
              AND NOT EXISTS (SELECT 1 FROM approvals a WHERE a.team_id = :team AND a.state = 'pending'
                              AND ((m.update_id IS NOT NULL AND a.update_id = m.update_id)
                                OR (m.message_id IS NOT NULL AND a.message_id = m.message_id)))
              AND m.id NOT IN (${logoIds()})
            ORDER BY m.seq LIMIT $BATCH""",
        cutoff, *kinds.toTypedArray(),
    ) { it.getString(1) }

    private fun logoIds() = if (db.dialect == Dialect.SQLITE)
        "SELECT json_extract(theme, '$.logoMediaId') FROM projects WHERE team_id = :team AND theme IS NOT NULL AND json_extract(theme, '$.logoMediaId') IS NOT NULL"
    else
        "SELECT theme::jsonb ->> 'logoMediaId' FROM projects WHERE team_id = :team AND theme IS NOT NULL AND theme::jsonb ->> 'logoMediaId' IS NOT NULL"

    /** Files first, then the row: a crash in between leaves a row that the next run finishes. */
    private fun remove(id: String, days: Int, now: Long): Boolean {
        paths.store.deletePrefix(paths.folder(id))
        val payload = db.transaction { _ ->
            db.update("DELETE FROM derivatives WHERE team_id = :team AND media_id = ?", id)
            db.one(
                "UPDATE media SET removed_at = ?, removed_after_days = ? WHERE team_id = :team AND id = ? AND removed_at IS NULL " +
                    "RETURNING id, update_id, message_id, request_id, task_id, kind",
                now, days, id,
            ) { rs ->
                fun s(i: Int): JsonElement = rs.getString(i)?.let(::JsonPrimitive) ?: JsonNull
                buildJsonObject {
                    put("mediaId", s(1)); put("updateId", s(2)); put("messageId", s(3)); put("requestId", s(4)); put("taskId", s(5)); put("kind", s(6))
                    put("afterDays", days)
                }
            }
        } ?: return false
        bus.publish("media.removed", payload)
        return true
    }

    private fun setting(key: String): String? = db.one("SELECT value FROM server_settings WHERE team_id = :team AND key = ?", key) { it.getString(1) }

    private fun put(key: String, value: String) {
        db.update(
            "INSERT INTO server_settings (team_id, key, value, updated_at) VALUES (:team, ?, ?, ?) " +
                "ON CONFLICT (team_id, key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at",
            key, value, clock(),
        )
    }

    companion object {
        val KINDS = listOf("image", "video", "audio", "file")
        const val SETTING = "media_retention"
        const val LAST_RUN = "media_retention.last_run"
        private const val DAY_MS = 86_400_000L
        private const val BATCH = 200
        /** 02:00 to 05:00 UTC. */
        private val NIGHT_HOURS = 2..4
        private const val MIN_GAP_MS = 12 * 3_600_000L
        private const val CATCH_UP_MS = 36 * 3_600_000L
        const val CHECK_INTERVAL_MS = 10 * 60_000L
    }
}
