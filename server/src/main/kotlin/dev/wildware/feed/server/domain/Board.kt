package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.longOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The board (migration 070): every card with a work state in some channels, in a column per state.
 * Work is still a state a card is in; the board is only another way to look at it.
 *
 * Done and Cancelled share the last column. A finished card leaves the board when someone removes it
 * (`board_hidden_at`, for everyone in the team; the card itself stays in the feed) or once it has been
 * finished longer than the team's auto-remove age. Putting it back (`board_kept_at`) starts that age
 * again. A new work state clears both.
 */
class Board(private val feed: Feed) {
    private val db get() = feed.db

    /** Which channels a board shows: [projectIds] null is every channel the viewer can read. */
    class Scope(val projectIds: List<String>?)

    /** A column's place in the list after its last card: when that card moved, then its id. */
    data class Cursor(val movedAt: Long, val id: String) {
        override fun toString() = "$movedAt:$id"

        companion object {
            fun parse(text: String?): Cursor? {
                if (text.isNullOrBlank()) return null
                val (at, id) = text.split(":", limit = 2).takeIf { it.size == 2 } ?: throw invalid("after is not a board cursor")
                return Cursor(at.toLongOrNull() ?: throw invalid("after is not a board cursor"), id)
            }
        }
    }

    /** The scope for a channel or a project reference; neither is every channel. Hidden ones answer as missing. */
    fun scope(channel: String?, group: String?): Scope = when {
        !channel.isNullOrBlank() -> Scope(listOf(feed.projects.resolve(channel).id))
        !group.isNullOrBlank() -> {
            val g = feed.groups.resolve(group)
            Scope(db.query("SELECT id FROM projects WHERE team_id = :team AND group_id = ?${feed.access.notHidden("id")}", g.id) { it.getString(1) })
        }
        else -> Scope(null)
    }

    /**
     * The board for [scope]: each column's first [limit] cards, newest move first. With [column] only
     * that column, after [after]. [removed] also lists the finished cards taken off the board, marked.
     * [assigned] (migration 074) keeps only the cards assigned to that principal, or with [UNASSIGNED]
     * the cards assigned to nobody.
     */
    fun read(scope: Scope, column: String? = null, after: Cursor? = null, limit: Int = PAGE, removed: Boolean = false, assigned: String? = null): JsonObject {
        if (column != null && column !in COLUMNS) throw invalid("column must be one of: ${COLUMNS.joinToString(", ")}")
        val days = autoRemoveDays()
        val columns = (if (column != null) listOf(column) else COLUMNS).map { col ->
            val page = cards(scope, col, after.takeIf { column != null }, limit.coerceIn(1, PAGE_MAX), removed, days, assigned)
            buildJsonObject {
                put("status", col)
                put("cards", JsonArray(page.rows.map { it.json }))
                put("more", page.more)
                put("after", page.rows.lastOrNull()?.let { Cursor(it.movedAt, it.id).toString() })
            }
        }
        return buildJsonObject {
            put("columns", JsonArray(columns))
            put("autoRemoveDays", days?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    private class Row(val id: String, val movedAt: Long, val json: JsonObject)
    private class Page(val rows: List<Row>, val more: Boolean)

    private fun cards(scope: Scope, column: String, after: Cursor?, limit: Int, removed: Boolean, days: Int?, assigned: String?): Page {
        val ids = scope.projectIds
        if (ids != null && ids.isEmpty()) return Page(emptyList(), false)
        val states = if (column == DONE) FINISHED else listOf(column)
        val marks = states.joinToString(", ") { "?" }
        val where = StringBuilder("team_id = :team AND status IN ($marks) AND deleted_at IS NULL AND created_at >= :since")
        val args = ArrayList<Any?>(states)
        if (ids != null) {
            where.append(" AND project_id IN (${ids.joinToString(", ") { "?" }})")
            args.addAll(ids)
        }
        where.append(feed.access.notHidden("project_id"))
        when (assigned) {
            null -> Unit
            UNASSIGNED -> where.append(" AND ").append(Assignees.unassignedSql("id"))
            else -> {
                where.append(" AND ").append(Assignees.assignedToSql("id"))
                args.add(assigned)
            }
        }
        // The finished cards still on the board: not removed, and finished (or put back) recently enough.
        val cutoff = days?.let { feed.now() - it * DAY_MS }
        if (column == DONE && !removed) {
            where.append(" AND board_hidden_at IS NULL")
            if (cutoff != null) {
                where.append(" AND COALESCE(board_kept_at, status_at, created_at) >= ?")
                args.add(cutoff)
            }
        }
        val moved = "COALESCE(status_at, created_at)"
        val keyset = after?.let { " AND ($moved < ? OR ($moved = ? AND id < ?))" } ?: ""
        val keyArgs = after?.let { listOf(it.movedAt, it.movedAt, it.id) }.orEmpty()
        val select = "id, project_id, status, status_at, created_at, body, board_hidden_at, board_kept_at, $moved AS moved_at"
        val found = db.query(
            "SELECT 'update' AS kind, $select, title, agent_id AS author, NULL AS user_id FROM updates WHERE $where$keyset " +
                "UNION ALL SELECT 'message' AS kind, $select, NULL AS title, author, user_id FROM messages WHERE $where$keyset " +
                "ORDER BY moved_at DESC, id DESC LIMIT ?",
            *(args + keyArgs + args + keyArgs).toTypedArray(), limit + 1,
        ) { rs ->
            Found(
                kind = rs.getString("kind"), id = rs.getString("id"), projectId = rs.getString("project_id"),
                status = rs.getString("status"), statusAt = rs.longOrNull("status_at"), createdAt = rs.getLong("created_at"),
                body = rs.getString("body") ?: "", title = rs.getString("title"),
                author = rs.getString("author"), userId = rs.getString("user_id"),
                hiddenAt = rs.longOrNull("board_hidden_at"), keptAt = rs.longOrNull("board_kept_at"), movedAt = rs.getLong("moved_at"),
            )
        }
        val page = found.take(limit)
        return Page(describe(page, cutoff), found.size > limit)
    }

    private class Found(
        val kind: String, val id: String, val projectId: String?, val status: String, val statusAt: Long?, val createdAt: Long,
        val body: String, val title: String?, val author: String?, val userId: String?, val hiddenAt: Long?, val keptAt: Long?, val movedAt: Long,
    )

    /** Each card's face: what it is, where, who is on it, and what is waiting in its thread. Read in a few queries for the page. */
    private fun describe(cards: List<Found>, cutoff: Long?): List<Row> {
        if (cards.isEmpty()) return emptyList()
        val updates = cards.filter { it.kind == "update" }.map { it.id }
        val messages = cards.filter { it.kind == "message" }.map { it.id }
        val channels = cards.mapNotNull { it.projectId }.distinct()
        val channel = if (channels.isEmpty()) emptyMap() else db.query(
            "SELECT id, slug, name FROM projects WHERE team_id = :team AND id IN (${marks(channels)})", *channels.toTypedArray(),
        ) { it.getString(1) to (it.getString(2) to it.getString(3)) }.toMap()

        // Replies: how many, when the last came, and the last agent to write one.
        class Thread(var count: Int = 0, var lastAt: Long? = null, var lastAgent: String? = null, var lastAgentAt: Long = Long.MIN_VALUE)
        val threads = HashMap<String, Thread>()
        fun replies(anchor: String, ids: List<String>) {
            if (ids.isEmpty()) return
            db.query(
                "SELECT $anchor, author, created_at FROM messages WHERE team_id = :team AND $anchor IN (${marks(ids)}) AND deleted_at IS NULL",
                *ids.toTypedArray(),
            ) { Triple(it.getString(1), it.getString(2), it.getLong(3)) }.forEach { (card, author, at) ->
                val t = threads.getOrPut(card) { Thread() }
                t.count++
                if (at > (t.lastAt ?: Long.MIN_VALUE)) t.lastAt = at
                if (author.startsWith("agent:") && at >= t.lastAgentAt) {
                    t.lastAgent = author.removePrefix("agent:")
                    t.lastAgentAt = at
                }
            }
        }
        replies("update_id", updates)
        replies("reply_to", messages)

        // Questions still waiting on someone, on the card or anywhere in its thread.
        val waiting = HashMap<String, Int>()
        fun waits(sql: String, ids: List<String>) {
            if (ids.isEmpty()) return
            db.query(sql, *ids.toTypedArray()) { it.getString(1) }.forEach { waiting.merge(it, 1, Int::plus) }
        }
        waits(
            "SELECT COALESCE(a.update_id, m.update_id) FROM approvals AS a LEFT JOIN messages AS m ON m.team_id = a.team_id AND m.id = a.message_id " +
                "WHERE a.team_id = :team AND a.state = 'pending' AND COALESCE(a.update_id, m.update_id) IN (${marks(updates)})",
            updates,
        )
        waits(
            "SELECT COALESCE(m.reply_to, m.id) FROM approvals AS a JOIN messages AS m ON m.team_id = a.team_id AND m.id = a.message_id " +
                "WHERE a.team_id = :team AND a.state = 'pending' AND COALESCE(m.reply_to, m.id) IN (${marks(messages)})",
            messages,
        )

        // Agents saying they are thinking about it right now.
        val thinking = HashMap<String, MutableList<String>>()
        fun acks(anchor: String, ids: List<String>) {
            if (ids.isEmpty()) return
            db.query(
                "SELECT $anchor, agent_id FROM acknowledgements WHERE team_id = :team AND state = 'thinking' AND $anchor IN (${marks(ids)}) ORDER BY updated_at DESC",
                *ids.toTypedArray(),
            ) { it.getString(1) to it.getString(2) }.forEach { (card, agent) -> thinking.getOrPut(card) { mutableListOf() }.add(agent) }
        }
        acks("update_id", updates)
        acks("message_id", messages)

        // Names, for everyone the cards mention.
        val agentIds = (cards.mapNotNull { c -> c.author?.takeIf { c.kind == "update" } } +
            cards.mapNotNull { c -> c.author?.takeIf { it.startsWith("agent:") }?.removePrefix("agent:") } +
            threads.values.mapNotNull { it.lastAgent } + thinking.values.flatten()).distinct()
        val agentName = if (agentIds.isEmpty()) emptyMap() else db.query(
            "SELECT id, name FROM agents WHERE team_id = :team AND id IN (${marks(agentIds)})", *agentIds.toTypedArray(),
        ) { it.getString(1) to it.getString(2) }.toMap()
        val people = HashMap<String, String?>()
        fun personName(id: String?): String? = id?.let { m ->
            people.getOrPut(m) {
                db.one("SELECT u.name FROM memberships AS m JOIN users AS u ON u.id = m.user_id WHERE m.team_id = :team AND m.id = ?", m) { it.getString(1) }
            }
        }

        val canPost = channels.associateWith { feed.access.level(it).rank >= ChannelAccess.Level.POST.rank }
        val assignees = feed.assignees.let { a -> a.forCards("update", updates) + a.forCards("message", messages) }
        return cards.map { c ->
            val thread = threads[c.id]
            val agentAuthor = if (c.kind == "update") c.author else c.author?.takeIf { it.startsWith("agent:") }?.removePrefix("agent:")
            // Who is on it: agents thinking about it now; else the update's agent, the last agent in
            // its thread, or whoever wrote it.
            val on = thinking[c.id].orEmpty().distinct().take(WHO_MAX).map { agentJson(it, agentName[it]) }.ifEmpty {
                val agent = agentAuthor ?: thread?.lastAgent
                when {
                    agent != null -> listOf(agentJson(agent, agentName[agent]))
                    c.author == Messages.HUMAN -> listOf(buildJsonObject {
                        put("kind", "person")
                        put("id", c.userId ?: People.FOUNDER)
                        put("name", personName(c.userId) ?: "")
                    })
                    else -> emptyList()
                }
            }
            // Off the board: someone removed it, or it has been finished longer than the team keeps cards.
            val expired = c.hiddenAt == null && c.status in FINISHED && cutoff != null && (c.keptAt ?: c.statusAt ?: c.createdAt) < cutoff
            val title = c.title?.takeIf { it.jsTrim().isNotEmpty() }?.let { Reads.firstLine(it) } ?: Reads.firstLine(c.body)
            val (slug, name) = c.projectId?.let { channel[it] } ?: (null to null)
            Row(c.id, c.movedAt, buildJsonObject {
                put("card", buildJsonObject { put("kind", c.kind); put("id", c.id) })
                put("projectId", c.projectId)
                put("channel", buildJsonObject { put("slug", slug); put("name", name) })
                put("status", c.status)
                put("statusAt", c.statusAt)
                put("createdAt", c.createdAt)
                put("movedAt", c.movedAt)
                put("title", Reads.cut(title, TITLE_MAX))
                put("author", if (c.kind == "update") "agent:${c.author}" else c.author)
                put("on", JsonArray(on))
                put("assignees", JsonArray(assignees[c.id].orEmpty().map { it.json }))
                put("replies", thread?.count ?: 0)
                put("lastReplyAt", thread?.lastAt)
                put("waiting", waiting[c.id] ?: 0)
                put("removed", c.hiddenAt != null || expired)
                put("removedBy", if (c.hiddenAt != null) "someone" else if (expired) "age" else null)
                put("canMove", c.projectId == null || canPost[c.projectId] == true)
            })
        }
    }

    private fun agentJson(id: String, name: String?) = buildJsonObject { put("kind", "agent"); put("id", id); put("name", name ?: "Removed agent") }

    /** Takes a finished card off the board, or ([removed] false) puts it back. Needs Post on its channel. */
    fun setRemoved(card: CardRef, removed: Boolean): Boolean = feed.op {
        if (card.kind !in listOf("update", "message")) throw invalid("a ${card.kind} is never on the board")
        val row = feed.cards.resolveToWrite(card)
        if (row.status !in FINISHED) throw invalid("only a finished card (done or cancelled) can be taken off the board")
        val now = feed.now()
        if (removed) {
            db.update("UPDATE ${card.table} SET board_hidden_at = ?, board_kept_at = NULL WHERE team_id = :team AND id = ?", now, card.id)
        } else {
            db.update("UPDATE ${card.table} SET board_hidden_at = NULL, board_kept_at = ? WHERE team_id = :team AND id = ?", now, card.id)
        }
        feed.cards.announce(card, row)
        removed
    }

    /** How many days a finished card stays on the board; null is for ever. 7 until the team chooses. */
    fun autoRemoveDays(): Int? {
        val stored = db.one("SELECT value FROM server_settings WHERE team_id = :team AND key = ?", SETTING) { it.getString(1) }
            ?: return DEFAULT_DAYS
        return if (stored == NEVER) null else stored.toIntOrNull()?.takeIf { it in DAY_CHOICES } ?: DEFAULT_DAYS
    }

    /** Sets it: one of [DAY_CHOICES], or null for never. */
    fun setAutoRemoveDays(days: Int?): Int? = feed.op {
        if (days != null && days !in DAY_CHOICES) throw invalid("autoRemoveDays must be one of ${DAY_CHOICES.joinToString(", ")}, or null for never")
        db.update(
            "INSERT INTO server_settings (team_id, key, value, updated_at) VALUES (:team, ?, ?, ?) " +
                "ON CONFLICT (team_id, key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at",
            SETTING, days?.toString() ?: NEVER, feed.now(),
        )
        feed.publish("board.settings", buildJsonObject { put("autoRemoveDays", days?.let(::JsonPrimitive) ?: JsonNull) })
        days
    }

    companion object {
        const val DONE = "done"
        const val UNASSIGNED = "none"
        val COLUMNS = listOf("todo", "in_progress", "blocked", DONE)
        val FINISHED = listOf("done", "cancelled")
        val DAY_CHOICES = listOf(1, 3, 7, 30)
        const val DEFAULT_DAYS = 7
        const val PAGE = 50
        const val PAGE_MAX = 200
        const val TITLE_MAX = 140
        const val WHO_MAX = 3
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val SETTING = "board.auto_remove_days"
        private const val NEVER = "never"

        private fun marks(ids: List<*>) = ids.joinToString(", ") { "?" }
    }
}

val Feed.board: Board get() = Board(this)
