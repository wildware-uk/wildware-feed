package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.events.Bus
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * What is waiting for an agent: unread messages, open work, its pending requests; and handing
 * messages over on the agent stream (TS `src/domain/messages.ts`, `cards.ts`, `requests.ts`,
 * spec/04-stream-and-events.md §4.4-4.6).
 *
 * What reaches the agent is only what it follows on the server ([Subscriptions]): nothing from a
 * channel it does not follow. A bridge that names channels narrows that further ([within]), never widens it.
 */
class AgentWork(private val db: Database, private val bus: Bus, private val clock: () -> Long = System::currentTimeMillis) {
    data class Counts(val unreadMessages: Int, val openTasks: Int, val pendingApprovals: Int)

    /** A message as the agent stream hands it over. */
    data class Line(
        val seq: Long, val id: String, val projectId: String?, val updateId: String?, val taskId: String?,
        val replyTo: String?, val answers: String?, val requestId: String?, val author: String, val body: String,
        val createdAt: Long, val deletedAt: Long?,
        /** The person, for a `human` line (migration 055). */
        val userId: String? = null,
    )

    private val subscriptions = Subscriptions(db, clock)

    /** The projects the agent follows; none when it follows none. */
    fun projectsForAgent(agentId: String): List<String> = subscriptions.workChannels(agentId)

    /**
     * The heartbeat's and the agent stream's counts: unread and open work in what the agent follows,
     * pending requests everywhere. [within]: the channels a bridge named, which narrow all three.
     */
    fun countWork(agentId: String, within: List<String>? = null) = Counts(
        unreadMessages = countUnread(agentId, Subscriptions.Reach(within)),
        openTasks = countOpenWork(projectsForAgent(agentId).let { mine -> within?.let { w -> mine.filter { it in w } } ?: mine }),
        pendingApprovals = countPending(agentId, within),
    )

    private fun unreadWhere(agentId: String, reach: Subscriptions.Reach, args: MutableList<Any?>, oneMessage: Boolean = false): String {
        args += agentId
        args += "agent:$agentId"
        val base = """
            FROM messages AS m
            LEFT JOIN read_cursors AS rc ON rc.team_id = m.team_id AND rc.agent_id = ? AND rc.project_id = coalesce(m.project_id, '')
            WHERE m.team_id = :team
              AND m.seq > coalesce(rc.last_seen_message_seq, 0)
              AND m.deleted_at IS NULL
              AND m.author <> ?
              AND ${Subscriptions.audienceSql(oneMessage)}"""
        args += agentId
        return base + subscriptions.reachSql(agentId, reach, args, oneMessage)
    }

    /**
     * Whether message [messageId] is unread for the agent and reaches it (narrowed to [within] when a
     * bridge named channels): one cheap lookup, for the agent stream.
     */
    fun reaches(agentId: String, messageId: String, within: List<String>? = null): Boolean {
        val args = mutableListOf<Any?>()
        val where = unreadWhere(agentId, Subscriptions.Reach(within), args, oneMessage = true)
        args += messageId
        return db.one("SELECT 1 $where AND m.id = ?", *args.toTypedArray()) { 1 } != null
    }

    fun countUnread(agentId: String, reach: Subscriptions.Reach): Int {
        val args = mutableListOf<Any?>()
        val where = unreadWhere(agentId, reach, args)
        return db.one("SELECT count(*) $where", *args.toTypedArray()) { it.getInt(1) } ?: 0
    }

    /** The newest [limit] unread lines in scope (narrowed to [within] when a bridge named channels), oldest first. */
    fun unread(agentId: String, limit: Int, within: List<String>? = null): List<Line> {
        val args = mutableListOf<Any?>()
        val where = unreadWhere(agentId, Subscriptions.Reach(within), args)
        args += limit
        return db.query("SELECT ${LINE.joinToString(", ") { "m.$it" }} $where ORDER BY m.seq DESC LIMIT ?", *args.toTypedArray()) { line(it) }.reversed()
    }

    fun countOpenWork(projects: List<String>?): Int {
        if (projects != null && projects.isEmpty()) return 0
        val scope = projects?.let { "AND project_id IN (${it.joinToString(", ") { "?" }})" } ?: ""
        val args = OPEN + (projects ?: emptyList())
        val states = OPEN.joinToString(", ") { "?" }
        return db.one(
            "SELECT (SELECT COUNT(*) FROM updates WHERE team_id = :team AND status IN ($states) AND deleted_at IS NULL $scope) + " +
                "(SELECT COUNT(*) FROM messages WHERE team_id = :team AND status IN ($states) AND deleted_at IS NULL $scope)",
            *(args + args).toTypedArray(),
        ) { it.getInt(1) } ?: 0
    }

    /** Pending requests of the agent: all of them, or those in [projects] plus the project-less ones. */
    fun countPending(agentId: String, projects: List<String>?): Int {
        if (projects == null) {
            return db.one("SELECT COUNT(*) FROM approvals WHERE team_id = :team AND agent_id = ? AND state = 'pending'", agentId) { it.getInt(1) } ?: 0
        }
        val inProjects = if (projects.isEmpty()) "" else " OR project_id IN (${projects.joinToString(", ") { "?" }})"
        return db.one(
            "SELECT COUNT(*) FROM approvals WHERE team_id = :team AND agent_id = ? AND state = 'pending' AND (project_id IS NULL$inProjects)",
            agentId, *projects.toTypedArray(),
        ) { it.getInt(1) } ?: 0
    }

    /** Which of [ids] this agent's [clientId] has already been handed (none without a client id). */
    fun alreadyDelivered(agentId: String, clientId: String?, ids: List<String>): Set<String> {
        if (ids.isEmpty() || clientId == null) return emptySet()
        return db.query(
            "SELECT message_id FROM message_deliveries WHERE team_id = :team AND agent_id = ? AND client_id = ? AND message_id IN (${ids.joinToString(", ") { "?" }})",
            agentId, clientId, *ids.toTypedArray(),
        ) { it.getString(1) }.toSet()
    }

    /** Record that [ids] were handed over, publishing `message.delivered` for each new record. */
    fun markDelivered(agentId: String, clientId: String?, ids: List<String>) {
        for (id in ids) {
            val message = find(id) ?: continue
            if (message.deletedAt != null) continue
            if (!recordDelivery(id, agentId, clientId)) continue
            bus.publish("message.delivered", buildJsonObject {
                put("messageId", JsonPrimitive(id))
                put("agentId", JsonPrimitive(agentId))
                put("projectId", message.projectId?.let(::JsonPrimitive) ?: JsonNull)
                threadKeys(message).forEach { (k, v) -> put(k, v?.let(::JsonPrimitive) ?: JsonNull) }
            })
        }
    }

    private fun recordDelivery(messageId: String, agentId: String, clientId: String?): Boolean = db.transaction { _ ->
        if (clientId == null) {
            val already = db.one(
                "SELECT 1 FROM message_deliveries WHERE team_id = :team AND message_id = ? AND agent_id = ? AND client_id IS NULL", messageId, agentId,
            ) { 1 }
            if (already != null) return@transaction false
        }
        db.update(
            "INSERT INTO message_deliveries (team_id, id, message_id, agent_id, client_id, delivered_at) VALUES (:team, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (message_id, agent_id, client_id) DO NOTHING",
            Ulid.next(clock()), messageId, agentId, clientId, clock(),
        ) == 1
    }

    fun find(id: String): Line? = db.one("SELECT ${LINE.joinToString(", ")} FROM messages WHERE team_id = :team AND id = ?", id) { line(it) }

    /** `threadKeys` (TS): the message's own thread columns. */
    fun threadKeys(m: Line): List<Pair<String, String?>> = listOf(
        "updateId" to m.updateId,
        "replyTo" to m.replyTo,
        "requestId" to m.requestId,
        "taskId" to m.taskId,
    )

    /** An agent's name; a person's on a team (migration 055), else null for a person. */
    fun authorName(author: String, userId: String? = null): String? {
        if (author == "human") return People.teamName(db, userId)
        if (!author.startsWith("agent:")) return null
        return db.one("SELECT name FROM agents WHERE team_id = :team AND id = ?", author.removePrefix("agent:")) { it.getString(1) }
    }

    /** The line a message answers or replies to, quoted (240 characters at most), or null. */
    fun quoted(m: Line, reader: String): Triple<Line, String, Boolean>? {
        val parent = (m.answers ?: m.replyTo)?.let(::find) ?: return null
        if (parent.deletedAt != null) return null
        // A parent that @named other agents is theirs to read: [reader] gets the reply, not its quote.
        if (!Mentions.readable(db, parent.id, parent.author, reader)) return null
        val truncated = parent.body.length > 240
        val body = if (truncated) parent.body.substring(0, 240).trimEnd() + "…" else parent.body
        return Triple(parent, body, truncated)
    }

    /** Every line in [m]'s thread (TS `threadOf`), for "does this concern the agent". */
    fun threadOf(m: Line): List<Line> {
        if (m.updateId != null) {
            return db.query("SELECT ${LINE.joinToString(", ")} FROM messages WHERE team_id = :team AND update_id = ? AND deleted_at IS NULL ORDER BY seq DESC LIMIT 200", m.updateId) { line(it) }
        }
        val root = m.replyTo ?: m.id
        if (m.projectId == null) return emptyList()
        return db.query(
            "SELECT ${LINE.joinToString(", ")} FROM messages WHERE team_id = :team AND project_id = ? AND deleted_at IS NULL ORDER BY seq DESC LIMIT 200", m.projectId,
        ) { line(it) }.filter { it.id == root || it.replyTo == root }
    }

    private fun line(rs: java.sql.ResultSet) = Line(
        seq = rs.getLong("seq"), id = rs.getString("id"), projectId = rs.getString("project_id"), updateId = rs.getString("update_id"),
        taskId = rs.getString("task_id"), replyTo = rs.getString("reply_to"), answers = rs.getString("answers"),
        requestId = rs.getString("request_id"), author = rs.getString("author"), body = rs.getString("body"),
        createdAt = rs.getLong("created_at"), deletedAt = rs.getLong("deleted_at").let { if (rs.wasNull()) null else it },
        userId = rs.getString("user_id") ?: People.FOUNDER.takeIf { rs.getString("author") == "human" },
    )

    companion object {
        val OPEN = listOf("todo", "in_progress", "blocked")
        private val LINE = listOf("seq", "id", "project_id", "update_id", "task_id", "reply_to", "answers", "request_id", "author", "body", "created_at", "deleted_at", "user_id")
    }
}
