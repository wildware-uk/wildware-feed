package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.events.AppEvent
import dev.wildware.feed.server.events.Bus
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The bell (spec/03 §5, spec/08 notifications): a row for every agent update, agent message and
 * request, written by a bus listener, and the owner marking them seen.
 */
class Notifications(private val db: Database, private val bus: Bus, private val clock: () -> Long = System::currentTimeMillis) {
    /** What one notification will say, before it is written. */
    data class New(
        val kind: String,
        val projectId: String?,
        val updateId: String? = null,
        val messageId: String? = null,
        val requestId: String? = null,
        val agentId: String?,
        val title: String,
        val body: String,
        /** A message that answers the owner, or is in a thread they spoke in. */
        val toOwner: Boolean = false,
    )

    /** Listen for the three events that ring the bell; call the result to stop. */
    fun startRecorder(): () -> Unit = bus.subscribe(::onEvent)

    private fun onEvent(event: AppEvent) {
        val id = { key: String -> (event.payload[key] as? JsonPrimitive)?.content }
        try {
            val input = when (event.type) {
                "update.created" -> id("updateId")?.let(::forUpdate)
                "message.created" -> id("messageId")?.let(::forMessage)
                "request.created" -> id("requestId")?.let(::forRequest)
                else -> return
            }
            if (input != null && followed(input)) record(input)
        } catch (e: Exception) {
            // The write it is about has already happened; a missing bell row must not undo it.
            System.err.println("recording a notification failed: $e")
        }
    }

    /** Whether the owner follows what this is about (migration 052): a muted project rings no bell. */
    private fun followed(input: New): Boolean {
        val subs = Subscriptions(db, clock)
        return when {
            input.updateId != null -> subs.ownerHearsUpdate(input.projectId, priorityOf("updates", input.updateId))
            input.requestId != null -> subs.ownerHearsRequest(input.requestId, input.projectId, priorityOf("approvals", input.requestId))
            input.messageId != null -> subs.ownerHearsMessage(input.messageId, input.toOwner)
            else -> true
        }
    }

    private fun priorityOf(table: String, id: String): String? = db.one("SELECT priority FROM $table WHERE id = ?", id) { it.getString(1) }

    /** Insert one row (once per target) and publish `notification.created` for a new one. */
    fun record(input: New): String? {
        val id = Ulid.next(clock())
        val inserted = db.one(
            "INSERT INTO notifications (id, kind, project_id, update_id, message_id, request_id, agent_id, title, body, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING RETURNING id, kind, project_id",
            id, input.kind, input.projectId, input.updateId, input.messageId, input.requestId, input.agentId, input.title, input.body, clock(),
        ) { Triple(it.getString(1), it.getString(2), it.getString(3)) } ?: return null
        bus.publish("notification.created", buildJsonObject {
            put("notificationId", inserted.first)
            put("kind", inserted.second)
            put("projectId", inserted.third)
        })
        return inserted.first
    }

    private fun agentName(agentId: String?): String =
        agentId?.let { db.one("SELECT name FROM agents WHERE id = ?", it) { rs -> rs.getString(1) } } ?: "An agent"

    fun forUpdate(updateId: String): New? {
        val row = db.one("SELECT id, project_id, agent_id, title, body, deleted_at FROM updates WHERE id = ?", updateId) {
            listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getObject(6))
        } ?: return null
        if (row[5] != null) return null
        val agentId = row[2] as String
        return New(
            kind = "update",
            projectId = row[1] as String?,
            updateId = row[0] as String,
            agentId = agentId,
            title = row[3] as String? ?: "${agentName(agentId)} posted an update",
            body = firstLine(row[4] as String),
        )
    }

    fun forMessage(messageId: String): New? {
        val row = db.one(
            "SELECT id, project_id, update_id, task_id, author, body, reply_to, deleted_at FROM messages WHERE id = ?", messageId,
        ) { rs -> (1..8).map { rs.getObject(it)?.toString() } } ?: return null
        val id = row[0]!!
        val projectId = row[1]
        val updateId = row[2]
        val taskId = row[3]
        val author = row[4]
        val body = row[5]
        val replyTo = row[6]
        if (row[7] != null || author == HUMAN) return null
        if (author == null || !author.startsWith("agent:") || author.length == "agent:".length) return null
        val agentId = author.removePrefix("agent:")
        val who = agentName(agentId)
        val toOwner = repliesToOwner(replyTo, updateId, taskId)
        return New(
            kind = "reply",
            projectId = projectId,
            messageId = id,
            agentId = agentId,
            title = if (toOwner) "$who replied to you" else "$who commented",
            body = firstLine(body ?: ""),
            toOwner = toOwner,
        )
    }

    /** `repliesToOwner` (TS `src/domain/push.ts:525`): a reply to the owner's post, or in a thread they spoke in. */
    private fun repliesToOwner(replyTo: String?, updateId: String?, taskId: String?): Boolean {
        if (replyTo != null) return db.one("SELECT author FROM messages WHERE id = ?", replyTo) { it.getString(1) } == HUMAN
        val (column, value) = when {
            updateId != null -> "update_id" to updateId
            taskId != null -> "task_id" to taskId
            else -> return false
        }
        return db.one(
            "SELECT 1 FROM messages WHERE $column = ? AND deleted_at IS NULL AND author = ? LIMIT 1", value, HUMAN,
        ) { true } == true
    }

    fun forRequest(requestId: String): New? {
        val row = db.one("SELECT id, project_id, agent_id, question FROM approvals WHERE id = ?", requestId) {
            listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4))
        } ?: return null
        return New(
            kind = "request",
            projectId = row[1],
            requestId = row[0],
            agentId = row[2],
            title = "${agentName(row[2])} is waiting on you",
            body = firstLine(row[3]!!),
        )
    }

    /** Mark these seen, or every unseen one when [ids] is null; publishes `notifications.seen` when any changed. */
    fun markSeen(ids: List<String>?): Int {
        val at = clock()
        val changed = when {
            ids == null -> db.update("UPDATE notifications SET seen_at = ? WHERE seen_at IS NULL", at)
            ids.isEmpty() -> 0
            else -> db.update(
                "UPDATE notifications SET seen_at = ? WHERE seen_at IS NULL AND id IN (${ids.joinToString(", ") { "?" }})",
                at, *ids.toTypedArray(),
            )
        }
        if (changed > 0) bus.publish("notifications.seen", buildJsonObject { put("count", changed) })
        return changed
    }

    fun unseen(): Long = db.one("SELECT count(*) AS n FROM notifications WHERE seen_at IS NULL") { it.getLong(1) } ?: 0

    companion object {
        const val HUMAN = "human"

        /** `firstLine`: whitespace runs collapsed (JS `\s`), trimmed, cut at [max] UTF-16 units with `…`. */
        fun firstLine(body: String, max: Int = 140): String {
            val out = StringBuilder()
            var inSpace = false
            for (c in body) {
                if (c.isJsWhitespace()) {
                    if (!inSpace) out.append(' ')
                    inSpace = true
                } else {
                    out.append(c)
                    inSpace = false
                }
            }
            val flat = out.toString().jsTrim()
            if (flat.length <= max) return flat
            return flat.substring(0, max).jsTrimEnd() + "…"
        }

        private fun String.jsTrimEnd(): String {
            var end = length
            while (end > 0 && this[end - 1].isJsWhitespace()) end--
            return substring(0, end)
        }
    }
}
