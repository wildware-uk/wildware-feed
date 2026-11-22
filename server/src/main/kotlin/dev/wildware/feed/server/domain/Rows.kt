package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.longOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.ResultSet

/**
 * Rows as the TS server puts them on the wire: one mapper per `to<Thing>` in its `src/db` files,
 * keys in the same order. The column lists are the TS ones too.
 */
internal object Rows {
    const val PROJECT = "seq, id, slug, name, description, status, pinned, created_at, updated_at, theme, board, group_id, private"
    const val UPDATE = "seq, id, project_id, agent_id, session_id, title, body, level, pinned, created_at, deleted_at, edited_at, priority, task_id, status, status_at"
    const val MESSAGE = "seq, id, project_id, update_id, task_id, author, body, created_at, reply_to, deleted_at, answers, request_id, edited_at, pinned, status, status_at, user_id, source"
    const val ACK = "seq, id, agent_id, message_id, task_id, update_id, request_id, state, created_at, updated_at"
    const val REACTION = "seq, id, message_id, update_id, request_id, task_id, actor, emoji, created_at, user_id"
    const val TAG = "seq, id, project_id, name, slug, colour, agent_id, created_at"
    const val CARD_TAG = "seq, id, tag_id, update_id, message_id, request_id, author, created_at"
    const val FIELD = "seq, id, update_id, message_id, request_id, name, slug, value, author, created_at, updated_at"
    const val DELIVERY = "seq, id, message_id, agent_id, delivered_at, client_id"
    const val APPROVAL = "seq, id, agent_id, project_id, update_id, message_id, kind, question, detail, options, config, state, expires_at, decided_at, decided_value, answer, edited_at, pinned, priority, decided_by"
    const val MEDIA = "seq, id, agent_id, author, update_id, message_id, request_id, task_id, kind, mime, bytes, sha256, width, height, duration_ms, filename, status, created_at"
    const val EMBED = "seq, id, type_id, update_id, message_id, request_id, task_id, agent_id, author, data, source_url, created_at"
    const val NOTIFICATION = "seq, id, kind, project_id, update_id, message_id, request_id, agent_id, title, body, created_at, seen_at"
    const val VIEW = "seq, id, app_id, update_id, message_id, request_id, agent_id, author, data, state, title, created_at, updated_at"

    fun JsonObjectBuilder.str(rs: ResultSet, key: String, column: String) = put(key, rs.getString(column))
    fun JsonObjectBuilder.long(rs: ResultSet, key: String, column: String) = put(key, rs.longOrNull(column))
    fun JsonObjectBuilder.flag(rs: ResultSet, key: String, column: String) = put(key, rs.getInt(column) == 1)

    fun card(kind: String, id: String?): JsonObject = buildJsonObject { put("kind", kind); put("id", id) }

    /** Acks and reactions read their card message → update → request. */
    fun cardMessageFirst(rs: ResultSet): JsonObject {
        rs.getString("message_id")?.takeIf { it.isNotEmpty() }?.let { return card("message", it) }
        rs.getString("update_id")?.takeIf { it.isNotEmpty() }?.let { return card("update", it) }
        return card("request", rs.getString("request_id"))
    }

    /** Tags, fields and apps read theirs update → message → request. */
    fun cardUpdateFirst(rs: ResultSet): JsonObject {
        rs.getString("update_id")?.takeIf { it.isNotEmpty() }?.let { return card("update", it) }
        rs.getString("message_id")?.takeIf { it.isNotEmpty() }?.let { return card("message", it) }
        return card("request", rs.getString("request_id"))
    }

    fun json(rs: ResultSet, column: String): JsonElement = Js.parseOrNull(rs.getString(column)) ?: JsonNull

    fun project(rs: ResultSet): JsonObject = buildJsonObject {
        long(rs, "seq", "seq")
        str(rs, "id", "id")
        str(rs, "slug", "slug")
        str(rs, "name", "name")
        str(rs, "description", "description")
        str(rs, "status", "status")
        flag(rs, "pinned", "pinned")
        long(rs, "createdAt", "created_at")
        long(rs, "updatedAt", "updated_at")
        put("theme", json(rs, "theme"))
        put("board", json(rs, "board"))
        // Migration 053: the group (a project, in the app) this channel is in.
        str(rs, "groupId", "group_id")
        // Migration 067: only those listed can see it (spec/14).
        put("private", rs.getInt("private") == 1)
    }

    fun update(rs: ResultSet): JsonObject = buildJsonObject {
        long(rs, "seq", "seq")
        str(rs, "id", "id")
        str(rs, "projectId", "project_id")
        str(rs, "agentId", "agent_id")
        str(rs, "sessionId", "session_id")
        str(rs, "title", "title")
        str(rs, "body", "body")
        str(rs, "level", "level")
        flag(rs, "pinned", "pinned")
        long(rs, "createdAt", "created_at")
        long(rs, "deletedAt", "deleted_at")
        long(rs, "editedAt", "edited_at")
        str(rs, "priority", "priority")
        str(rs, "taskId", "task_id")
        str(rs, "status", "status")
        long(rs, "statusAt", "status_at")
    }

    fun message(rs: ResultSet): JsonObject = buildJsonObject {
        long(rs, "seq", "seq")
        str(rs, "id", "id")
        str(rs, "projectId", "project_id")
        str(rs, "updateId", "update_id")
        str(rs, "taskId", "task_id")
        str(rs, "author", "author")
        str(rs, "body", "body")
        long(rs, "createdAt", "created_at")
        str(rs, "replyTo", "reply_to")
        long(rs, "deletedAt", "deleted_at")
        str(rs, "answers", "answers")
        str(rs, "requestId", "request_id")
        long(rs, "editedAt", "edited_at")
        flag(rs, "pinned", "pinned")
        str(rs, "status", "status")
        long(rs, "statusAt", "status_at")
        person(rs, "userId", "user_id")
        // How it was written (migration 065): "voice" for a voice note; left out when typed.
        rs.getString("source")?.let { put("source", it) }
    }

    /**
     * Which person wrote, reacted or answered (migration 055), by membership id. Left out for the
     * founder, today's one owner, so a team of one reads exactly as before; a person's row without
     * it is the founder's.
     */
    private fun JsonObjectBuilder.person(rs: ResultSet, key: String, column: String) {
        rs.getString(column)?.takeIf { it.isNotEmpty() && it != People.FOUNDER }?.let { put(key, it) }
    }

    fun ack(rs: ResultSet): JsonObject = buildJsonObject {
        long(rs, "seq", "seq")
        str(rs, "id", "id")
        str(rs, "agentId", "agent_id")
        put("card", cardMessageFirst(rs))
        str(rs, "messageId", "message_id")
        str(rs, "taskId", "task_id")
        str(rs, "updateId", "update_id")
        str(rs, "requestId", "request_id")
        str(rs, "state", "state")
        long(rs, "createdAt", "created_at")
        long(rs, "updatedAt", "updated_at")
    }

    fun reaction(rs: ResultSet): JsonObject = buildJsonObject {
        long(rs, "seq", "seq")
        str(rs, "id", "id")
        put("card", cardMessageFirst(rs))
        str(rs, "messageId", "message_id")
        str(rs, "actor", "actor")
        str(rs, "emoji", "emoji")
        long(rs, "createdAt", "created_at")
        person(rs, "userId", "user_id")
    }

    fun tag(rs: ResultSet): JsonObject = buildJsonObject {
        long(rs, "seq", "seq")
        str(rs, "id", "id")
        str(rs, "projectId", "project_id")
        str(rs, "name", "name")
        str(rs, "slug", "slug")
        str(rs, "colour", "colour")
        str(rs, "agentId", "agent_id")
        long(rs, "createdAt", "created_at")
    }

    fun cardTag(rs: ResultSet): JsonObject = buildJsonObject {
        long(rs, "seq", "seq")
        str(rs, "id", "id")
        str(rs, "tagId", "tag_id")
        put("card", cardUpdateFirst(rs))
        str(rs, "author", "author")
        long(rs, "createdAt", "created_at")
    }

    fun field(rs: ResultSet): JsonObject = buildJsonObject {
        long(rs, "seq", "seq")
        str(rs, "id", "id")
        put("card", cardUpdateFirst(rs))
        str(rs, "name", "name")
        str(rs, "slug", "slug")
        str(rs, "value", "value")
        str(rs, "author", "author")
        long(rs, "createdAt", "created_at")
        long(rs, "updatedAt", "updated_at")
    }

    fun delivery(rs: ResultSet): JsonObject = buildJsonObject {
        long(rs, "seq", "seq")
        str(rs, "id", "id")
        str(rs, "messageId", "message_id")
        str(rs, "agentId", "agent_id")
        long(rs, "deliveredAt", "delivered_at")
        str(rs, "clientId", "client_id")
    }

    fun notification(rs: ResultSet): JsonObject = buildJsonObject {
        long(rs, "seq", "seq")
        str(rs, "id", "id")
        str(rs, "kind", "kind")
        str(rs, "projectId", "project_id")
        str(rs, "updateId", "update_id")
        str(rs, "messageId", "message_id")
        str(rs, "requestId", "request_id")
        str(rs, "agentId", "agent_id")
        str(rs, "title", "title")
        str(rs, "body", "body")
        long(rs, "createdAt", "created_at")
        long(rs, "seenAt", "seen_at")
    }

    /** `toRequest` (TS `src/domain/requests.ts`): the two settled states become `answered`. */
    fun request(rs: ResultSet): JsonObject {
        val rowState = rs.getString("state")
        val state = if (rowState in setOf("pending", "timeout", "cancelled", "withdrawn")) rowState else "answered"
        val stored = json(rs, "answer")
        val answer: JsonElement = when {
            stored.truthy() -> stored
            rowState == "approved" || rowState == "rejected" ->
                buildJsonObject { put("kind", "confirm"); put("value", rowState == "approved") }
            else -> JsonNull
        }
        return buildJsonObject {
            str(rs, "id", "id")
            long(rs, "seq", "seq")
            str(rs, "agentId", "agent_id")
            str(rs, "projectId", "project_id")
            str(rs, "updateId", "update_id")
            str(rs, "messageId", "message_id")
            str(rs, "kind", "kind")
            str(rs, "question", "question")
            str(rs, "detail", "detail")
            put("options", json(rs, "options"))
            put("config", json(rs, "config"))
            put("state", state)
            long(rs, "expiresAt", "expires_at")
            long(rs, "answeredAt", "decided_at")
            put("answer", answer)
            long(rs, "editedAt", "edited_at")
            flag(rs, "pinned", "pinned")
            // Migration 049. Only when it says something: a question is "medium" unless the agent
            // said otherwise, and leaving that out keeps every ordinary read as it always was.
            rs.getString("priority")?.takeIf { it != "medium" }?.let { put("priority", it) }
            if (rowState == "approved" || rowState == "rejected") person(rs, "answeredBy", "decided_by")
        }
    }

    /** One object with more keys on the end, in order. */
    fun JsonObject.plus(vararg extra: Pair<String, JsonElement>): JsonObject =
        JsonObject(LinkedHashMap<String, JsonElement>(this).apply { extra.forEach { (k, v) -> put(k, v) } })

    fun JsonElement?.str(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    val empty = JsonObject(emptyMap())
}
