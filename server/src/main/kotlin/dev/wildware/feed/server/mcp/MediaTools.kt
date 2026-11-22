package dev.wildware.feed.server.mcp

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.DomainError
import dev.wildware.feed.server.domain.access
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.domain.conflict
import dev.wildware.feed.server.domain.invalid
import dev.wildware.feed.server.domain.notFound
import dev.wildware.feed.server.http.Wire
import dev.wildware.feed.server.media.MediaError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.long

/** create_upload and attach_media (spec/05-mcp.md §7.6-7.7, spec/07 §3a, §11). */
fun ToolRegistry.registerMediaTools(app: App) {
    register("create_upload") { call ->
        val rawMime = call.args.string("mime")
        val grant = translating {
            app.uploads.reserve(call.agent.id, call.args.string("filename"), rawMime, (call.args["bytes"] as JsonPrimitive).long)
        }
        ToolResult.ok(
            "Reserved $rawMime upload ${grant.mediaId}. PUT the raw bytes to ${grant.uploadUrl} " +
                "within 15 minutes, then pass ${grant.mediaId} to post_update as media_ids.",
            buildJsonObject {
                put("media_id", JsonPrimitive(grant.mediaId))
                put("upload_url", JsonPrimitive(grant.uploadUrl))
                put("expires_at", JsonPrimitive(Wire.isoOf(grant.expiresAt)))
                put("max_bytes", JsonPrimitive(grant.maxBytes))
            },
        )
    }

    register("attach_media") { call ->
        val updateId = call.args.string("update_id")
        val wanted = (call.args["media_ids"] as JsonArray).map { (it as JsonPrimitive).content }
        val ids = checkedMediaIds(wanted)
        val agentId = call.agent.id
        val update = app.db.one("SELECT agent_id, deleted_at FROM updates WHERE team_id = :team AND id = ?", updateId) {
            it.getString(1) to it.getLong(2).let { v -> if (it.wasNull()) null else v }
        }
        if (update == null || update.second != null || update.first != agentId) throw notFound("no such update: $updateId")
        // Adding to a card needs Post on its channel (spec/15).
        app.feed.access.requirePost(app.feed.updates.find(updateId)?.projectId ?: throw notFound("no such update: $updateId")) { notFound("no such update: $updateId") }
        val eligible = ids.filter { id ->
            app.db.one("SELECT agent_id, sha256 FROM media WHERE team_id = :team AND id = ?", id) { it.getString(1) to it.getString(2) }
                ?.let { (owner, sha) -> owner == agentId && sha.isNotEmpty() } == true
        }
        val attached = if (eligible.isEmpty()) emptyList() else attach(app, eligible, updateId, agentId)
        val skipped = ids.filter { it !in attached }
        ToolResult.ok(
            if (skipped.isEmpty()) "Attached ${attached.size} media to update $updateId."
            else "Attached ${attached.size} media to update $updateId; skipped ${skipped.size} that were not available to you.",
            buildJsonObject {
                put("update_id", JsonPrimitive(updateId))
                put("attached", JsonArray(attached.map(::JsonPrimitive)))
                put("skipped", JsonArray(skipped.map(::JsonPrimitive)))
            },
        )
    }
}

/** Point the eligible, unattached media at the update; the ids it took, in SQLite's RETURNING order. */
private fun attach(app: App, ids: List<String>, updateId: String, agentId: String): List<String> = app.db.query(
    "UPDATE media SET update_id = ? WHERE team_id = :team AND id IN (${ids.joinToString(", ") { "?" }}) " +
        "AND update_id IS NULL AND message_id IS NULL AND request_id IS NULL AND task_id IS NULL " +
        "AND (? IS NULL OR agent_id = ?) RETURNING id",
    updateId, *ids.toTypedArray(), agentId, agentId,
) { it.getString(1) }

private val ULID = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")

private fun checkedMediaIds(ids: List<String>): List<String> {
    val unique = ids.distinct()
    if (unique.isEmpty()) throw invalid("media_ids must name at least one upload")
    if (unique.size > 24) throw invalid("an update may carry at most 24 media items")
    for (id in unique) if (!ULID.matches(id)) throw invalid("not a media id: ${Wire.encode(JsonPrimitive(id))}")
    return unique
}

/** MediaError as a tool error: not_found and conflict keep their code, the rest are invalid_argument. */
private inline fun <T> translating(block: () -> T): T = try {
    block()
} catch (e: MediaError) {
    throw when (e.code) {
        "not_found" -> notFound(e.message)
        "conflict" -> conflict(e.message)
        else -> invalid(e.message)
    } as DomainError
}
