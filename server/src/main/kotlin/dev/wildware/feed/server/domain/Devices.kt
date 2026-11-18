package dev.wildware.feed.server.domain

import dev.wildware.feed.server.auth.Tokens
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.db.longOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** The owner's phones and their keys (spec/03-owner-api-requests-devices.md §3, spec/01 §6). */
class Devices(private val db: Database, private val tokenSecret: String, private val clock: () -> Long = System::currentTimeMillis) {
    data class Device(
        val id: String,
        val name: String,
        val createdAt: Long,
        val lastSeenAt: Long?,
        val apnsToken: String?,
        val liveActivityToken: String?,
        val alertProjectIds: List<String>?,
    )

    fun mint(name: String): Pair<Device, String> {
        val clean = requiredText(name, "name", 100)
        val key = Tokens.random()
        val id = Ulid.next()
        val now = clock()
        db.update(
            "INSERT INTO owner_devices (id, name, token_hash, created_at) VALUES (?, ?, ?, ?)",
            id, clean, Tokens.hash(key, tokenSecret), now,
        )
        return find(id)!! to key
    }

    fun find(id: String): Device? = db.one("SELECT * FROM owner_devices WHERE id = ?", id) { row(it) }

    /** The device a key belongs to, stamping last-seen at most once a minute; null for any other key. */
    fun authenticate(key: String): Device? {
        val trimmed = key.jsTrim()
        if (!Tokens.wellFormed(trimmed)) return null
        val hash = Tokens.hash(trimmed, tokenSecret)
        val found = db.one("SELECT * FROM owner_devices WHERE token_hash = ?", hash) { it.getString("token_hash") to row(it) } ?: return null
        if (!Tokens.same(found.first, hash)) return null
        val now = clock()
        db.update(
            "UPDATE owner_devices SET last_seen_at = ? WHERE id = ? AND (last_seen_at IS NULL OR last_seen_at <= ?)",
            now, found.second.id, now - 60_000,
        )
        return found.second
    }

    fun list(): List<Device> = db.query("SELECT * FROM owner_devices ORDER BY seq") { row(it) }

    fun remove(id: String): Boolean = db.update("DELETE FROM owner_devices WHERE id = ?", id) == 1

    fun setApns(id: String, token: Any?, environment: Any?) {
        val clean = hexToken(token, "token")
        if (environment != "sandbox" && environment != "production") throw invalid("environment must be sandbox or production")
        db.transaction {
            requireDevice(id)
            db.update("UPDATE owner_devices SET apns_token = NULL WHERE apns_token = ? AND id != ?", clean, id)
            db.update("UPDATE owner_devices SET apns_token = ?, apns_environment = ?, failures = 0 WHERE id = ?", clean, environment as String, id)
        }
    }

    /** Each of [update] and [pushToStart]: absent (leave), null (clear) or a hex token. */
    fun setLiveActivity(id: String, update: Optional, pushToStart: Optional) {
        if (update is Optional.Absent && pushToStart is Optional.Absent) {
            throw invalid("send updateToken, pushToStartToken, or both (null clears one)")
        }
        val updateToken = (update as? Optional.Present)?.let { it.value?.let { v -> hexToken(v, "updateToken") } }
        val startToken = (pushToStart as? Optional.Present)?.let { it.value?.let { v -> hexToken(v, "pushToStartToken") } }
        db.transaction {
            requireDevice(id)
            if (update is Optional.Present) {
                if (updateToken != null) db.update("UPDATE owner_devices SET live_activity_token = NULL WHERE live_activity_token = ? AND id != ?", updateToken, id)
                db.update("UPDATE owner_devices SET live_activity_token = ? WHERE id = ?", updateToken, id)
            }
            if (pushToStart is Optional.Present) {
                if (startToken != null) db.update("UPDATE owner_devices SET push_to_start_token = NULL WHERE push_to_start_token = ? AND id != ?", startToken, id)
                db.update("UPDATE owner_devices SET push_to_start_token = ? WHERE id = ?", startToken, id)
            }
        }
    }

    /** null = every project; a list of slugs otherwise (empty means none). */
    fun setAlertProjects(id: String, projects: Any?, slugToId: (String) -> String?) {
        val ids: List<String>? = when (projects) {
            null -> null
            is List<*> -> {
                if (projects.any { it !is String }) throw invalid("projects must be an array of project slugs, or null")
                if (projects.size > 200) throw invalid("projects must name at most 200")
                val slugs = projects.map { (it as String).jsTrim().lowercase() }.distinct()
                val resolved = slugs.map { it to slugToId(it) }
                val unknown = resolved.filter { it.second == null }.map { it.first }
                if (unknown.isNotEmpty()) throw invalid("unknown projects: ${unknown.joinToString(", ")}")
                resolved.map { it.second!! }
            }
            else -> throw invalid("projects must be an array of project slugs, or null")
        }
        requireDevice(id)
        db.update(
            "UPDATE owner_devices SET alert_project_ids = ? WHERE id = ?",
            ids?.let { JsonArray(it.map(::JsonPrimitive)).toString() }, id,
        )
    }

    private fun requireDevice(id: String) = find(id) ?: throw notFound("no such device: $id")

    private fun hexToken(value: Any?, field: String): String {
        if (value !is String) throw invalid("$field must be a string")
        val trimmed = value.jsTrim()
        if (trimmed.isEmpty() || trimmed.length > 512 || !Regex("^[0-9a-fA-F]+$").matches(trimmed)) throw invalid("$field must be a hex string")
        return trimmed.lowercase()
    }

    private fun row(rs: java.sql.ResultSet) = Device(
        id = rs.getString("id"),
        name = rs.getString("name"),
        createdAt = rs.getLong("created_at"),
        lastSeenAt = rs.longOrNull("last_seen_at"),
        apnsToken = rs.getString("apns_token"),
        liveActivityToken = rs.getString("live_activity_token"),
        alertProjectIds = rs.getString("alert_project_ids")?.let { text ->
            (Json.parseToJsonElement(text) as? JsonArray)?.map { it.jsonPrimitive.content }
        },
    )

    sealed interface Optional {
        data object Absent : Optional
        data class Present(val value: Any?) : Optional
    }
}
