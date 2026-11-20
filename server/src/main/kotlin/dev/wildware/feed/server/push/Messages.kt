package dev.wildware.feed.server.push

import dev.wildware.feed.server.domain.People
import dev.wildware.feed.server.App
import dev.wildware.feed.server.media.Serve

/**
 * What a notification says (TS `src/domain/push.ts`: `requestMessage`, `updateMessage`,
 * `replyMessage`). Web Push is gone, but the iOS alerts and the Live Activity's push-to-start use
 * these same words.
 */
data class PushMessage(val title: String, val body: String, val url: String, val tag: String)

class PushMessages(private val app: App) {
    private val db get() = app.db
    private val base get() = app.teams.address()

    private fun agentName(id: String?) = id?.let { db.one("SELECT name FROM agents WHERE team_id = :team AND id = ?", it) { rs -> rs.getString(1) } }
    private fun slug(projectId: String?) = projectId?.let { db.one("SELECT slug FROM projects WHERE team_id = :team AND id = ?", it) { rs -> rs.getString(1) } }

    private fun projectUrl(projectId: String?, focus: String?): String {
        val where = if (focus.isNullOrEmpty()) "" else "?focus=${Serve.encodeURIComponent(focus)}"
        val slug = slug(projectId) ?: return "$base/$where"
        return "$base/projects/$slug$where"
    }

    /** The owner is asked something; null unless the request is still pending. */
    fun request(requestId: String): PushMessage? {
        val request = app.requests.find(requestId) ?: return null
        if (request.state != "pending") return null
        val who = agentName(request.agentId) ?: "An agent"
        val slug = slug(request.projectId)
        return PushMessage(
            title = "$who is waiting on you",
            body = PlainText.notification(request.question),
            url = if (slug != null) "$base/projects/$slug?focus=${request.id}" else "$base/",
            tag = "request-${request.id}",
        )
    }

    /** An agent asks to join: who, from where, and the code it was given, to check it by. */
    fun pairing(pairing: dev.wildware.feed.server.domain.Pairings.Pairing): PushMessage {
        val asker = pairing.askedBy?.let { agentName(it) }
        val from = listOfNotNull(pairing.host?.let { "from $it" }, asker?.let { "asked by $it" }).joinToString(", ")
        return PushMessage(
            title = "${pairing.name} wants to join",
            body = (if (from.isEmpty()) "" else "${from.replaceFirstChar { it.uppercase() }}. ") + "Code ${pairing.code}. Open the app to allow or deny it.",
            url = "$base/",
            tag = "pairing-${pairing.id}",
        )
    }

    class Update(val message: PushMessage, val projectId: String?, val body: String, val level: String?, val priority: String?, val agentId: String? = null)

    fun update(updateId: String): Update? {
        data class Row(val agentId: String?, val projectId: String?, val title: String?, val body: String, val deletedAt: Long?, val level: String?, val priority: String?)
        val row = db.one("SELECT agent_id, project_id, title, body, deleted_at, level, priority FROM updates WHERE team_id = :team AND id = ?", updateId) {
            Row(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getLong(5).let { v -> if (it.wasNull()) null else v }, it.getString(6), it.getString(7))
        } ?: return null
        if (row.deletedAt != null) return null
        return Update(
            PushMessage(
                title = row.title ?: "${agentName(row.agentId) ?: "An agent"} posted an update",
                body = PlainText.notification(row.body),
                url = projectUrl(row.projectId, updateId),
                tag = "update-$updateId",
            ),
            row.projectId, row.body, row.level, row.priority, row.agentId,
        )
    }

    /** [actor]: the person who wrote it (migration 055), who is never alerted about it; null for an agent. */
    class Reply(val message: PushMessage, val projectId: String?, val body: String, val answered: Boolean, val actor: String? = null, val actorName: String? = null)

    /**
     * A message, as [member] is told of it: an agent's, or on a team another person's (migration
     * 055). Null for a team of one's own words.
     */
    fun reply(messageId: String, member: String = People.FOUNDER): Reply? {
        data class Row(val author: String, val projectId: String?, val body: String, val replyTo: String?, val updateId: String?, val taskId: String?, val userId: String?)
        val row = db.one("SELECT author, project_id, body, reply_to, update_id, task_id, user_id FROM messages WHERE team_id = :team AND id = ?", messageId) {
            Row(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getString(7))
        } ?: return null
        val person = if (row.author == "human") row.userId ?: People.FOUNDER else null
        val name = when {
            person != null -> People.teamName(db, person) ?: return null
            row.author.startsWith("agent:") -> agentName(row.author.removePrefix("agent:")) ?: "An agent"
            else -> return null
        }
        val answered = repliesTo(member, row.replyTo, row.updateId, row.taskId)
        return Reply(
            PushMessage(
                title = if (answered) "$name replied to you" else "$name commented",
                body = PlainText.notification(row.body),
                url = projectUrl(row.projectId, messageId),
                tag = "message-$messageId",
            ),
            row.projectId, row.body, answered, person, name.takeIf { person != null },
        )
    }

    /** Does this message answer [member]: a reply to their post, or a line in a thread they spoke in? */
    private fun repliesTo(member: String, replyTo: String?, updateId: String?, taskId: String?): Boolean {
        if (replyTo != null) {
            return db.one("SELECT author, user_id FROM messages WHERE team_id = :team AND id = ?", replyTo) { it.getString(1) to it.getString(2) }
                ?.let { (author, user) -> author == "human" && (user ?: People.FOUNDER) == member } == true
        }
        val (column, value) = when {
            updateId != null -> "update_id" to updateId
            taskId != null -> "task_id" to taskId
            else -> return false
        }
        return db.one(
            "SELECT 1 FROM messages WHERE team_id = :team AND $column = ? AND author = 'human' AND coalesce(user_id, ?) = ? AND deleted_at IS NULL LIMIT 1",
            value, People.FOUNDER, member,
        ) { 1 } != null
    }
}
