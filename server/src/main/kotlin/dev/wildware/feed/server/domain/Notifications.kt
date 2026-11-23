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
 * The bell (spec/03 §5, spec/08 notifications): a row for every agent update, message and request,
 * written by a bus listener, and the owner marking them seen. Each person has their own bell
 * (migration 055): a row per person who follows what it is about, never for what they did themselves.
 *
 * It also says which notifications on a person's devices are done with (spec/08 §6.9): a
 * `notifications.cleared {tags}` event names them by the tag their alert carried, when they read
 * them; or, for everyone, when a question is answered or lost, a pairing settles, or the thing is
 * deleted. An event for one person carries their `memberId`.
 */
class Notifications(private val db: Database, private val bus: Bus, private val clock: () -> Long = System::currentTimeMillis) {
    /** What this work's viewer can't read stays out of their bell (spec/15). */
    private val access get() = ChannelAccess(db, clock)

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
        /** A message that answers the person it is for, or is in a thread they spoke in. */
        val toOwner: Boolean = false,
        /** The person who wrote it, who is never told about their own message. */
        val authorMember: String? = null,
    )

    /** Listen for the events that ring the bell or settle what it rang for; call the result to stop. */
    fun startRecorder(): () -> Unit = bus.subscribeEveryTeam(::onEvent)

    private fun onEvent(event: AppEvent) {
        val id = { key: String -> (event.payload[key] as? JsonPrimitive)?.content }
        try {
            when (event.type) {
                "update.created" -> id("updateId")?.let { u -> recordForEach { forUpdate(u) } }
                "message.created" -> id("messageId")?.let { m -> recordForEach { member -> forMessage(m, member) } }
                "request.created" -> id("requestId")?.let { r -> recordForEach { forRequest(r) } }
                // A question answered, timed out, cancelled or withdrawn: everyone's alert and bell row are done.
                "request.answered" -> id("requestId")?.let { markCardsSeen(listOf(it), also = listOf(requestTag(it)), member = null) }
                // A deleted line, post or update: nothing left to read, for anyone.
                "message.deleted" -> id("messageId")?.let { markCardsSeen(listOf(it), member = null) }
                "update.deleted" -> id("updateId")?.let { markCardsSeen(listOf(it), member = null) }
                // Allowed, denied, collected or expired: nothing left to decide.
                "pairing.updated" -> id("pairingId")?.takeIf { id("state") != "pending" }?.let { publishCleared(listOf(pairingTag(it)), null) }
            }
        } catch (e: Exception) {
            // The write it is about has already happened; a missing bell row must not undo it.
            System.err.println("recording a notification failed: $e")
        }
    }

    /** One row for each person who follows what [build] describes for them, except whoever wrote it. */
    private fun recordForEach(build: (String) -> New?) {
        for (member in db.query("SELECT id FROM memberships WHERE team_id = :team AND removed_at IS NULL ORDER BY seq") { it.getString(1) }) {
            val input = build(member) ?: continue
            if (input.authorMember == member) continue
            if (followed(input, People.subscriber(member))) record(input, member)
        }
    }

    /** Whether [who] follows what this is about (migration 052): a muted channel rings no bell. */
    private fun followed(input: New, who: String): Boolean {
        // Nobody hears about a channel they can't read (spec/15).
        if (!ChannelAccess(db, clock).canRead(who, input.projectId)) return false
        val subs = Subscriptions(db, clock)
        return when {
            input.updateId != null -> subs.hearsUpdate(who, input.projectId, priorityOf("updates", input.updateId))
            input.requestId != null -> subs.hearsRequest(who, input.requestId, input.projectId, priorityOf("approvals", input.requestId))
            input.messageId != null -> subs.hearsMessage(who, input.messageId, input.toOwner)
            else -> true
        }
    }

    private fun priorityOf(table: String, id: String): String? = db.one("SELECT priority FROM $table WHERE team_id = :team AND id = ?", id) { it.getString(1) }

    /** Insert one row (once per target and person) and publish `notification.created` for a new one. */
    fun record(input: New, member: String = People.FOUNDER): String? {
        val id = Ulid.next(clock())
        val inserted = db.one(
            "INSERT INTO notifications (team_id, id, kind, project_id, update_id, message_id, request_id, agent_id, title, body, created_at, member_id) " +
                "VALUES (:team, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING RETURNING id, kind, project_id",
            id, input.kind, input.projectId, input.updateId, input.messageId, input.requestId, input.agentId, input.title, input.body, clock(), member,
        ) { Triple(it.getString(1), it.getString(2), it.getString(3)) } ?: return null
        bus.publish("notification.created", buildJsonObject {
            put("notificationId", inserted.first)
            put("kind", inserted.second)
            put("projectId", inserted.third)
            whose(member)
        })
        return inserted.first
    }

    private fun agentName(agentId: String?): String =
        agentId?.let { db.one("SELECT name FROM agents WHERE team_id = :team AND id = ?", it) { rs -> rs.getString(1) } } ?: "An agent"

    fun forUpdate(updateId: String): New? {
        val row = db.one("SELECT id, project_id, agent_id, title, body, deleted_at FROM updates WHERE team_id = :team AND id = ?", updateId) {
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

    /**
     * A line, as [member] would be told of it: by an agent, or (migration 055) by another person on
     * the team. "Replied to you" when it answers them or is in a thread they spoke in.
     */
    fun forMessage(messageId: String, member: String = People.FOUNDER): New? {
        val row = db.one(
            "SELECT id, project_id, update_id, task_id, author, body, reply_to, deleted_at, user_id, source FROM messages WHERE team_id = :team AND id = ?", messageId,
        ) { rs -> (1..10).map { rs.getObject(it)?.toString() } } ?: return null
        val id = row[0]!!
        val projectId = row[1]
        val updateId = row[2]
        val taskId = row[3]
        val author = row[4]
        val body = row[5]
        val replyTo = row[6]
        if (row[7] != null || author == null) return null
        val (agentId, who, person) = when {
            author == HUMAN -> Triple(null, People.teamName(db, row[8] ?: People.FOUNDER) ?: return null, row[8] ?: People.FOUNDER)
            author.startsWith("agent:") && author.length > "agent:".length -> author.removePrefix("agent:").let { Triple(it, agentName(it), null) }
            else -> return null
        }
        // A line saying who was assigned (migration 074) rings only for the people it assigned.
        // A line saying assigned work moved is for the agents it names, not anyone's bell.
        if (row[9] == Messages.STATUS_LINE) return null
        if (row[9] == Messages.ASSIGNMENT) {
            val title = assignedTitle(id, member) ?: return null
            return New(
                kind = "reply", projectId = projectId, messageId = id, agentId = agentId,
                title = "$who assigned you", body = title, toOwner = true, authorMember = person,
            )
        }
        val toOwner = repliesTo(member, replyTo, updateId, taskId)
        return New(
            kind = "reply",
            projectId = projectId,
            messageId = id,
            agentId = agentId,
            title = if (toOwner) "$who replied to you" else "$who commented",
            body = firstLine(body ?: ""),
            toOwner = toOwner,
            authorMember = person,
        )
    }

    /** The title of the card that assignment line [lineId] assigned [member] to, or null when it did not. */
    private fun assignedTitle(lineId: String, member: String): String? {
        val card = db.one(
            "SELECT card_kind, card_id FROM card_assignees WHERE team_id = :team AND message_id = ? AND principal = ?", lineId, People.subscriber(member),
        ) { CardRef(it.getString(1), it.getString(2)) } ?: return null
        return Assignees(Feed(db, bus, clock)).cardTitle(card)
    }

    /**
     * `repliesToOwner` (TS `src/domain/push.ts:525`), per person: a reply to [member]'s post, or in a
     * thread they spoke in.
     */
    private fun repliesTo(member: String, replyTo: String?, updateId: String?, taskId: String?): Boolean {
        if (replyTo != null) {
            return db.one("SELECT author, user_id FROM messages WHERE team_id = :team AND id = ?", replyTo) { it.getString(1) to it.getString(2) }
                ?.let { (author, user) -> author == HUMAN && (user ?: People.FOUNDER) == member } == true
        }
        val (column, value) = when {
            updateId != null -> "update_id" to updateId
            taskId != null -> "task_id" to taskId
            else -> return false
        }
        return db.one(
            "SELECT 1 FROM messages WHERE team_id = :team AND $column = ? AND deleted_at IS NULL AND author = ? AND coalesce(user_id, ?) = ? LIMIT 1",
            value, HUMAN, People.FOUNDER, member,
        ) { true } == true
    }

    fun forRequest(requestId: String): New? {
        val row = db.one("SELECT id, project_id, agent_id, question FROM approvals WHERE team_id = :team AND id = ?", requestId) {
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

    /**
     * Mark [member]'s rows seen: these, or every unseen one when [ids] is null; publishes
     * `notifications.seen` when any changed.
     */
    fun markSeen(ids: List<String>?, member: String = People.FOUNDER): Int {
        val at = clock()
        val changed = when {
            ids == null -> db.query("UPDATE notifications SET seen_at = ? WHERE team_id = :team AND seen_at IS NULL AND member_id = ?${access.notHidden("project_id")} RETURNING $SEEN_COLUMNS", at, member, row = ::tagOf)
            ids.isEmpty() -> emptyList()
            else -> ids.distinct().chunked(CHUNK).flatMap { chunk ->
                db.query(
                    "UPDATE notifications SET seen_at = ? WHERE team_id = :team AND seen_at IS NULL AND member_id = ? AND id IN (${marks(chunk)})${access.notHidden("project_id")} RETURNING $SEEN_COLUMNS",
                    at, member, *chunk.toTypedArray(), row = ::tagOf,
                )
            }
        }
        announce(changed, member = member)
        return changed.size
    }

    /**
     * Mark seen everything [member]'s bell holds about these cards (spec/03 §5.3): an update or
     * request card, a post, or a single line, and every line in their threads. They opened them on
     * some device. [member] null is everyone's bell (the thing settled or went away). [also] are
     * more tags to clear whatever changed, such as a question that settled.
     */
    fun markCardsSeen(cards: List<String>, also: List<String> = emptyList(), member: String? = People.FOUNDER): Int {
        val at = clock()
        val whose = if (member == null) "" else " AND member_id = ?"
        val changed = cards.distinct().chunked(CHUNK).flatMap { chunk ->
            val m = marks(chunk)
            db.query(
                "UPDATE notifications SET seen_at = ? WHERE team_id = :team AND seen_at IS NULL$whose${access.notHidden("project_id")} AND (" +
                    "update_id IN ($m) OR request_id IN ($m) OR message_id IN ($m) OR message_id IN (" +
                    "SELECT id FROM messages WHERE team_id = :team AND (update_id IN ($m) OR reply_to IN ($m) OR request_id IN ($m) OR task_id IN ($m)))" +
                    ") RETURNING $SEEN_COLUMNS",
                at, *listOfNotNull(member).toTypedArray(), *List(7) { chunk }.flatten().toTypedArray(), row = ::tagOf,
            )
        }
        announce(changed, also, member)
        return changed.size
    }

    /**
     * [changed] are the rows an `UPDATE … RETURNING` marked, in no promised order (Postgres returns
     * them as it finds them), so their tags go out oldest bell row first, then [also].
     */
    private fun announce(changed: List<Seen>, also: List<String> = emptyList(), member: String?) {
        if (member == null) {
            // Everyone's rows: each person whose bell changed hears it, and every device clears.
            for ((who, rows) in changed.groupBy { it.member }) {
                bus.publish("notifications.seen", buildJsonObject { put("count", rows.size); whose(who) })
            }
            publishCleared(changed.sortedBy { it.seq }.mapNotNull { it.tag } + also, null)
            return
        }
        if (changed.isNotEmpty()) bus.publish("notifications.seen", buildJsonObject { put("count", changed.size); whose(member) })
        publishCleared(changed.sortedBy { it.seq }.mapNotNull { it.tag }, member)
        publishCleared(also, null)
    }

    /**
     * `notifications.cleared {tags}`: these alerts are done with on [member]'s devices, or on every
     * device when null. Nothing when there are none.
     */
    private fun publishCleared(tags: List<String>, member: String?) {
        val distinct = tags.distinct()
        if (distinct.isEmpty()) return
        bus.publish("notifications.cleared", buildJsonObject {
            put("tags", JsonArray(distinct.map(::JsonPrimitive)))
            whose(member)
        })
    }

    /**
     * `memberId` on an event that is one person's (their bell, their alerts), so only their apps
     * and phones act on it. Only on a team: a team of one's events read as they always have.
     */
    private fun kotlinx.serialization.json.JsonObjectBuilder.whose(member: String?) {
        if (member != null && (db.one("SELECT count(*) FROM memberships WHERE team_id = :team") { it.getLong(1) } ?: 0) > 1) put("memberId", member)
    }

    /**
     * Which of these alert tags are done with (spec/03 §5.4), for a device catching up on what it
     * still shows: a question or pairing no longer waiting (or gone), an update or line deleted, or
     * one whose bell row the owner has seen. An update or line this dashboard never had is not
     * done with (it may be the app's own notice, such as `update-check`), nor is a tag of any
     * other shape.
     */
    fun handled(tags: List<String>, member: String = People.FOUNDER): List<String> {
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
                db.query(sql(marks(chunk)), *chunk.toTypedArray(), member) { it.getString(1) }.forEach { done += "$kind-$it" }
            }
        }
        stillOpen("request", { "SELECT id FROM approvals WHERE team_id = :team AND state = 'pending' AND expires_at > ? AND id IN ($it)" }, now)
        stillOpen("pairing", { "SELECT id FROM agent_pairings WHERE team_id = :team AND state = 'pending' AND expires_at > ? AND id IN ($it)" }, now)
        // Seen means seen by [member]: their own bell row.
        finished("update", {
            "SELECT u.id FROM updates AS u WHERE u.team_id = :team AND u.id IN ($it) AND (u.deleted_at IS NOT NULL OR EXISTS " +
                "(SELECT 1 FROM notifications AS n WHERE n.update_id = u.id AND n.kind = 'update' AND n.seen_at IS NOT NULL AND n.member_id = ?))"
        })
        finished("message", {
            "SELECT m.id FROM messages AS m WHERE m.team_id = :team AND m.id IN ($it) AND (m.deleted_at IS NOT NULL OR EXISTS " +
                "(SELECT 1 FROM notifications AS n WHERE n.message_id = m.id AND n.seen_at IS NOT NULL AND n.member_id = ?))"
        })
        return tags.distinct().filter { it in done }
    }

    fun unseen(member: String = People.FOUNDER): Long =
        db.one("SELECT count(*) AS n FROM notifications WHERE team_id = :team AND seen_at IS NULL AND member_id = ?${access.notHidden("project_id")}", member) { it.getLong(1) } ?: 0

    companion object {
        const val HUMAN = "human"

        /** At most this many ids in one `IN (…)`. */
        private const val CHUNK = 200

        /** What an `UPDATE … RETURNING` gives [tagOf]. */
        private const val SEEN_COLUMNS = "seq, kind, update_id, message_id, member_id"

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
            member = rs.getString(5),
        )

        /** A bell row just marked seen: where it sits in the bell, the alert tag it clears, if any, and whose it is. */
        private class Seen(val seq: Long, val tag: String?, val member: String?)

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
