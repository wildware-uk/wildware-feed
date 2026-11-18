package dev.wildware.feed.server.push

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
    private val base get() = app.config.publicBaseUrl

    private fun agentName(id: String?) = id?.let { db.one("SELECT name FROM agents WHERE id = ?", it) { rs -> rs.getString(1) } }
    private fun slug(projectId: String?) = projectId?.let { db.one("SELECT slug FROM projects WHERE id = ?", it) { rs -> rs.getString(1) } }

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

    class Update(val message: PushMessage, val projectId: String?, val body: String)

    fun update(updateId: String): Update? {
        data class Row(val agentId: String?, val projectId: String?, val title: String?, val body: String, val deletedAt: Long?)
        val row = db.one("SELECT agent_id, project_id, title, body, deleted_at FROM updates WHERE id = ?", updateId) {
            Row(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getLong(5).let { v -> if (it.wasNull()) null else v })
        } ?: return null
        if (row.deletedAt != null) return null
        return Update(
            PushMessage(
                title = row.title ?: "${agentName(row.agentId) ?: "An agent"} posted an update",
                body = PlainText.notification(row.body),
                url = projectUrl(row.projectId, updateId),
                tag = "update-$updateId",
            ),
            row.projectId, row.body,
        )
    }

    class Reply(val message: PushMessage, val projectId: String?, val body: String)

    /** An agent's message; null for the owner's own words. */
    fun reply(messageId: String): Reply? {
        data class Row(val author: String, val projectId: String?, val body: String, val replyTo: String?, val updateId: String?, val taskId: String?)
        val row = db.one("SELECT author, project_id, body, reply_to, update_id, task_id FROM messages WHERE id = ?", messageId) {
            Row(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6))
        } ?: return null
        if (!row.author.startsWith("agent:")) return null
        val name = agentName(row.author.removePrefix("agent:")) ?: "An agent"
        val answered = repliesToOwner(row.replyTo, row.updateId, row.taskId)
        return Reply(
            PushMessage(
                title = if (answered) "$name replied to you" else "$name commented",
                body = PlainText.notification(row.body),
                url = projectUrl(row.projectId, messageId),
                tag = "message-$messageId",
            ),
            row.projectId, row.body,
        )
    }

    /** Does this message answer the owner: a reply to their post, or a line in a thread they spoke in? */
    private fun repliesToOwner(replyTo: String?, updateId: String?, taskId: String?): Boolean {
        if (replyTo != null) return db.one("SELECT author FROM messages WHERE id = ?", replyTo) { it.getString(1) } == "human"
        val (column, value) = when {
            updateId != null -> "update_id" to updateId
            taskId != null -> "task_id" to taskId
            else -> return false
        }
        return db.one("SELECT 1 FROM messages WHERE $column = ? AND author = 'human' AND deleted_at IS NULL LIMIT 1", value) { 1 } != null
    }
}
