package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.events.AppEvent
import dev.wildware.feed.server.events.Bus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The bell (spec/03 §5, spec/08 notifications): a row for every agent update, agent message and
 * request, written by a bus listener, and the owner marking them seen.
 *
 * It also says which notifications on the owner's devices are done with (spec/08 §6.9): a
 * `notifications.cleared {tags}` event names them by the tag their alert carried, when the owner
 * reads them, answers or loses a question, settles a pairing, or the agent deletes the thing.
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

    /** Listen for the events that ring the bell or settle what it rang for; call the result to stop. */
    fun startRecorder(): () -> Unit = bus.subscribe(::onEvent)

    private fun onEvent(event: AppEvent) {
        val id = { key: String -> (event.payload[key] as? JsonPrimitive)?.content }
        try {
            when (event.type) {
                "update.created" -> id("updateId")?.let(::forUpdate)?.let(::recordIfFollowed)
                "message.created" -> id("messageId")?.let(::forMessage)?.let(::recordIfFollowed)
                "request.created" -> id("requestId")?.let(::forRequest)?.let(::recordIfFollowed)
                // A question answered, timed out, cancelled or withdrawn: its alert and bell row are done.
                "request.answered" -> id("requestId")?.let { markCardsSeen(listOf(it), also = listOf(requestTag(it))) }
                // A deleted line, post or update: nothing left to read.
                "message.deleted" -> id("messageId")?.let { markCardsSeen(listOf(it)) }
                "update.deleted" -> id("updateId")?.let { markCardsSeen(listOf(it)) }
                // Allowed, denied, collected or expired: nothing left to decide.
                "pairing.updated" -> id("pairingId")?.takeIf { id("state") != "pending" }?.let { publishCleared(listOf(pairingTag(it))) }
            }
        } catch (e: Exception) {
            // The write it is about has already happened; a missing bell row must not undo it.
            System.err.println("recording a notification failed: $e")
        }
    }

    private fun recordIfFollowed(input: New) {
        if (followed(input)) record(input)
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
            ids == null -> db.query("UPDATE notifications SET seen_at = ? WHERE seen_at IS NULL RETURNING $SEEN_COLUMNS", at, row = ::tagOf)
            ids.isEmpty() -> emptyList()
            else -> ids.distinct().chunked(CHUNK).flatMap { chunk ->
                db.query(
                    "UPDATE notifications SET seen_at = ? WHERE seen_at IS NULL AND id IN (${marks(chunk)}) RETURNING $SEEN_COLUMNS",
                    at, *chunk.toTypedArray(), row = ::tagOf,
                )
            }
        }
        announce(changed)
        return changed.size
    }

    /**
     * Mark seen everything the bell holds about these cards (spec/03 §5.3): an update or request
     * card, a post, or a single line, and every line in their threads. The owner opened them on some
     * device. [also] are more tags to clear whatever changed, such as a question that settled.
     */
    fun markCardsSeen(cards: List<String>, also: List<String> = emptyList()): Int {
        val at = clock()
        val changed = cards.distinct().chunked(CHUNK).flatMap { chunk ->
            val m = marks(chunk)
            db.query(
                "UPDATE notifications SET seen_at = ? WHERE seen_at IS NULL AND (" +
                    "update_id IN ($m) OR request_id IN ($m) OR message_id IN ($m) OR message_id IN (" +
                    "SELECT id FROM messages WHERE update_id IN ($m) OR reply_to IN ($m) OR request_id IN ($m) OR task_id IN ($m))" +
                    ") RETURNING $SEEN_COLUMNS",
                at, *List(7) { chunk }.flatten().toTypedArray(), row = ::tagOf,
            )
        }
        announce(changed, also)
        return changed.size
    }

    /**
     * [changed] are the rows an `UPDATE … RETURNING` marked, in no promised order (Postgres returns
     * them as it finds them), so their tags go out oldest bell row first, then [also].
     */
    private fun announce(changed: List<Seen>, also: List<String> = emptyList()) {
        if (changed.isNotEmpty()) bus.publish("notifications.seen", buildJsonObject { put("count", changed.size) })
        publishCleared(changed.sortedBy { it.seq }.mapNotNull { it.tag } + also)
    }

    /** `notifications.cleared {tags}`: these alerts are done with on every device. Nothing when there are none. */
    private fun publishCleared(tags: List<String>) {
        val distinct = tags.distinct()
        if (distinct.isEmpty()) return
        bus.publish("notifications.cleared", buildJsonObject { put("tags", JsonArray(distinct.map(::JsonPrimitive))) })
    }

    /**
     * Which of these alert tags are done with (spec/03 §5.4), for a device catching up on what it
     * still shows: a question or pairing no longer waiting (or gone), an update or line deleted, or
     * one whose bell row the owner has seen. An update or line this dashboard never had is not
     * done with (it may be the app's own notice, such as `update-check`), nor is a tag of any
     * other shape.
     */
    fun handled(tags: List<String>): List<String> {
        val now = clock()
        val byKind = tags.distinct().mapNotNull { tag -> TAG.matchEntire(tag)?.let { it.groupValues[1] to it.groupValues[2] } }
            .groupBy({ it.first }, { it.second })
        val done = HashSet<String>()
        /** [sql] lists, of the ids in its `IN`, those still waiting on the owner; the rest are done. */
        fun stillOpen(kind: String, sql: (String) -> String, vararg leading: Any?) {
            for (chunk in byKind[kind].orEmpty().chunked(CHUNK)) {
                val open = db.query(sql(marks(chunk)), *leading, *chunk.toTypedArray()) { it.getString(1) }.toSet()
                chunk.filterNot { it in open }.forEach { done += "$kind-$it" }
            }
        }
        /** [sql] lists, of the ids in its `IN`, those done with; the rest are not. */
        fun finished(kind: String, sql: (String) -> String) {
            for (chunk in byKind[kind].orEmpty().chunked(CHUNK)) {
                db.query(sql(marks(chunk)), *chunk.toTypedArray()) { it.getString(1) }.forEach { done += "$kind-$it" }
            }
        }
        stillOpen("request", { "SELECT id FROM approvals WHERE state = 'pending' AND expires_at > ? AND id IN ($it)" }, now)
        stillOpen("pairing", { "SELECT id FROM agent_pairings WHERE state = 'pending' AND expires_at > ? AND id IN ($it)" }, now)
        finished("update", {
            "SELECT u.id FROM updates AS u WHERE u.id IN ($it) AND (u.deleted_at IS NOT NULL OR EXISTS " +
                "(SELECT 1 FROM notifications AS n WHERE n.update_id = u.id AND n.kind = 'update' AND n.seen_at IS NOT NULL))"
        })
        finished("message", {
            "SELECT m.id FROM messages AS m WHERE m.id IN ($it) AND (m.deleted_at IS NOT NULL OR EXISTS " +
                "(SELECT 1 FROM notifications AS n WHERE n.message_id = m.id AND n.seen_at IS NOT NULL))"
        })
        return tags.distinct().filter { it in done }
    }

    fun unseen(): Long = db.one("SELECT count(*) AS n FROM notifications WHERE seen_at IS NULL") { it.getLong(1) } ?: 0

    companion object {
        const val HUMAN = "human"

        /** At most this many ids in one `IN (…)`. */
        private const val CHUNK = 200

        /** What an `UPDATE … RETURNING` gives [tagOf]. */
        private const val SEEN_COLUMNS = "seq, kind, update_id, message_id"

        /** An alert's tag (spec/08 §6.7): its kind and the id of what it is about. */
        private val TAG = Regex("^(request|pairing|update|message)-(.+)$")

        fun requestTag(id: String) = "request-$id"
        fun pairingTag(id: String) = "pairing-$id"

        /**
         * The alert tag a seen bell row clears: an update's or a line's. A question's alert stays
         * until the question is settled, since its buttons still answer it.
         */
        private fun tagOf(rs: java.sql.ResultSet) = Seen(
            seq = rs.getLong(1),
            tag = when (rs.getString(2)) {
                "update" -> rs.getString(3)?.let { "update-$it" }
                "reply" -> rs.getString(4)?.let { "message-$it" }
                else -> null
            },
        )

        /** A bell row just marked seen: where it sits in the bell, and the alert tag it clears, if any. */
        private class Seen(val seq: Long, val tag: String?)

        private fun marks(ids: List<*>) = ids.joinToString(", ") { "?" }

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
