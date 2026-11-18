package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Ulid
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Messages: posting, threading, editing, unsending, and an agent reading what is new
 * (spec/02 §4, §7.6-7.7, spec/05 §7.22, §7.24-7.26; TS `src/domain/messages.ts`).
 */
class Messages(private val feed: Feed) {
    private val db get() = feed.db

    /** Optional keys are null when left out; an empty string is still "given" where the TS checks `!= null`. */
    class Post(
        val author: String,
        val body: String,
        val project: String? = null,
        val updateId: String? = null,
        val replyTo: String? = null,
        val answers: String? = null,
        val requestId: String? = null,
        val mediaIds: List<String>? = null,
        val embeds: List<FeedAttachments.EmbedInput>? = null,
        val apps: List<FeedAttachments.AppInput>? = null,
    )

    class Page(val messages: List<MessageRow>, val cursor: String, val unread: Long, val markedRead: Boolean)

    class Quoted(val id: String, val author: String, val body: String, val truncated: Boolean)

    fun find(id: String): MessageRow? = db.one("SELECT ${Rows.MESSAGE} FROM messages WHERE id = ?", id) { MessageRow(Rows.message(it)) }

    private fun agentExists(id: String) = db.one("SELECT id FROM agents WHERE id = ?", id) { it.getString(1) } != null

    /** `assertAuthor`: an agent author must exist. */
    private fun checkAuthor(author: String) {
        if (author == HUMAN) return
        val agentId = author.removePrefix(AGENT_PREFIX)
        if (!agentExists(agentId)) throw notFound("no such agent: $agentId")
    }

    fun post(input: Post): MessageRow = feed.op {
        val body = requiredText(input.body, "body", BODY_MAX)
        checkAuthor(input.author)
        val author = input.author

        val answered = if (!input.answers.isNullOrEmpty()) find(input.answers) else null
        if (!input.answers.isNullOrEmpty() && (answered == null || answered.deletedAt != null)) {
            throw notFound("no such message: ${input.answers}")
        }
        if (input.replyTo != null && input.updateId != null) {
            throw invalid("a reply answers a message, or hangs off an update, not both")
        }
        val requestId = input.requestId ?: answered?.requestId
        if (input.requestId != null && (input.updateId != null || input.replyTo != null)) {
            throw invalid("a message belongs to a request`s discussion, or to a card, not both")
        }
        val requestProject: Pair<Boolean, String?>? = requestId?.let { id ->
            db.one("SELECT project_id FROM approvals WHERE id = ?", id) { true to it.getString(1) }
                ?: throw notFound("no such request: $id")
        }
        val request = requestProject != null

        val named = input.replyTo
        val target = named?.let { find(it) ?: throw notFound("no such message: $it") }

        val updateId = if (request) null else input.updateId ?: answered?.updateId ?: target?.updateId
        val inThread = target != null && target.updateId != null
        val parent = when {
            inThread -> null
            target?.replyTo != null -> find(target.replyTo!!)
            else -> target
        }
        val anchor = parent?.id ?: if (answered != null && !inThread) {
            answered.replyTo ?: if (answered.updateId == null) answered.id else null
        } else {
            null
        }

        val namedProject = if (!input.project.isNullOrEmpty()) feed.projects.resolve(input.project).id else null
        val anchored = when {
            request -> requestProject!!.second
            parent != null -> parent.projectId
            else -> anchorProject(updateId) ?: answered?.projectId
        }
        if (anchored != null && namedProject != null && anchored != namedProject) {
            throw invalid("that update, task or message belongs to a different project")
        }
        val projectId = anchored ?: namedProject

        val mediaIds = if (input.mediaIds.isNullOrEmpty()) emptyList() else FeedAttachments.assertAttachableToMessage(db, input.mediaIds, author)
        val embeds = if (input.embeds.isNullOrEmpty()) emptyList() else FeedAttachments.checkedEmbeds(db, input.embeds)
        val apps = if (input.apps.isNullOrEmpty()) emptyList() else FeedAttachments.checkedApps(db, input.apps)

        if (answered != null && !sameThread(answered, updateId, anchor, requestId)) {
            throw invalid("that message is in a different thread")
        }
        val answersId = answered?.id ?: if (inThread) target?.id else null

        val id = Ulid.next()
        db.update(
            "INSERT INTO messages (id, project_id, update_id, task_id, author, body, created_at, reply_to, answers, request_id) " +
                "VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, ?)",
            id, projectId, updateId, author, body, feed.now(), if (request) null else anchor, answersId, requestId,
        )
        val message = find(id)!!
        Mentions.record(db, message.id, message.body)

        val agentId = if (author.startsWith(AGENT_PREFIX)) author.removePrefix(AGENT_PREFIX).ifEmpty { null } else null
        if (mediaIds.isNotEmpty()) FeedAttachments.attachMedia(db, mediaIds, "message_id", id, "author", author)
        if (embeds.isNotEmpty()) FeedAttachments.attachEmbeds(db, embeds, "message_id", id, agentId, author, feed.now())
        if (apps.isNotEmpty()) FeedAttachments.attachApps(db, apps, CardRef("message", id), agentId, author, feed.now())

        feed.publish("message.created", buildJsonObject {
            put("messageId", message.id)
            put("projectId", message.projectId)
            put("author", message.author)
            threadKeys(message)
        })
        message
    }

    /** Only the author may edit a live message; its mentions are worked out again. */
    fun edit(messageId: String, by: String, body: String): MessageRow = feed.op {
        val message = find(messageId)
        if (message == null || message.deletedAt != null) throw notFound("no such message: $messageId")
        checkAuthor(by)
        if (by != message.author) throw invalid("that message was posted by somebody else")
        val clean = requiredText(body, "body", BODY_MAX)
        db.update("UPDATE messages SET body = ?, edited_at = ? WHERE id = ? AND deleted_at IS NULL", clean, feed.now(), message.id)
        val saved = find(message.id)!!
        Mentions.record(db, saved.id, saved.body)
        feed.publish("message.updated", buildJsonObject {
            put("messageId", saved.id)
            put("projectId", saved.projectId)
            put("author", saved.author)
            threadKeys(saved)
        })
        saved
    }

    /**
     * Soft delete. The owner may unsend anything, an agent only its own; a feed post takes its live
     * replies with it. Already deleted is a quiet success for anyone.
     */
    fun delete(messageId: String, by: String): MessageRow = feed.op {
        val message = find(messageId) ?: throw notFound("no such message: $messageId")
        if (message.deletedAt != null) return@op message
        checkAuthor(by)
        if (by != HUMAN && by != message.author) throw invalid("that message was posted by somebody else")
        val at = feed.now()
        val replies = if (message.replyTo == null) {
            db.query("SELECT id FROM messages WHERE reply_to = ? AND deleted_at IS NULL ORDER BY seq ASC", message.id) { it.getString(1) }
        } else {
            emptyList()
        }
        db.update("UPDATE messages SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL", at, message.id)
        for (reply in replies) db.update("UPDATE messages SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL", at, reply)
        feed.publish("message.deleted", buildJsonObject {
            put("messageId", message.id)
            put("projectId", message.projectId)
            put("replies", replies.size)
            threadKeys(message)
        })
        find(message.id)!!
    }

    /** `answeredMessage`: the live line this one answers or replies to, quoted to 240 characters. */
    fun quoted(message: MessageRow): Quoted? {
        val parentId = (message.answers ?: message.replyTo)?.takeIf { it.isNotEmpty() } ?: return null
        val parent = find(parentId) ?: return null
        if (parent.deletedAt != null) return null
        val full = parent.body
        val truncated = full.length > QUOTED_MAX
        return Quoted(parent.id, parent.author, if (truncated) JsText.trimEnd(full.substring(0, QUOTED_MAX)) + "…" else full, truncated)
    }

    /** The agent's name for an `agent:<id>` author (revoked agents too), else null. */
    fun authorName(author: String): String? {
        if (!author.startsWith(AGENT_PREFIX)) return null
        val id = author.removePrefix(AGENT_PREFIX).ifEmpty { return null }
        return db.one("SELECT name FROM agents WHERE id = ?", id) { it.getString(1) }
    }

    // ---- reading (get_messages) ------------------------------------------------------------------

    /** The projects an agent works in: its updates, its tasks, its live messages. Null means "none yet, so all". */
    fun projectsForAgent(agentId: String): List<String>? {
        val ids = db.query(
            "SELECT DISTINCT project_id FROM updates WHERE agent_id = ? UNION " +
                "SELECT DISTINCT project_id FROM tasks WHERE agent_id = ? AND project_id IS NOT NULL UNION " +
                "SELECT DISTINCT project_id FROM messages WHERE author = ? AND project_id IS NOT NULL AND deleted_at IS NULL",
            agentId, agentId, AGENT_PREFIX + agentId,
        ) { it.getString(1) }
        return ids.ifEmpty { null }
    }

    private class Scope(val projectIds: List<String>?, val reader: String?, val mentionsAnywhere: Boolean)

    /** `readerScope`: the project filter and the @mention audience, as SQL and its parameters. */
    private fun readerScope(scope: Scope, params: MutableList<Any?>): String {
        var inProjects = ""
        val ids = scope.projectIds
        if (ids != null) {
            inProjects = if (ids.isEmpty()) "0" else "m.project_id IN (${ids.joinToString(", ") { "?" }})"
        }
        if (scope.reader == null) {
            if (inProjects.isEmpty()) return ""
            params.addAll(ids!!)
            return " AND $inProjects"
        }
        val namesAnyone = "EXISTS (SELECT 1 FROM message_mentions AS mm WHERE mm.message_id = m.id)"
        val namesReader = "EXISTS (SELECT 1 FROM message_mentions AS mm WHERE mm.message_id = m.id AND mm.agent_id = ?)"
        params.add(scope.reader)
        val audience = " AND (NOT $namesAnyone OR $namesReader)"
        if (inProjects.isEmpty()) return audience
        params.addAll(ids!!)
        if (!scope.mentionsAnywhere) return "$audience AND $inProjects"
        params.add(scope.reader)
        return "$audience AND ($inProjects OR $namesReader)"
    }

    private fun listAfter(afterSeq: Long, excludeAuthor: String, limit: Int, scope: Scope): List<MessageRow> {
        if (scope.projectIds?.isEmpty() == true && !scope.mentionsAnywhere) return emptyList()
        val params = mutableListOf<Any?>(afterSeq, excludeAuthor)
        val where = readerScope(scope, params)
        params.add(limit)
        return db.query(
            "SELECT ${columns()} FROM messages AS m WHERE m.seq > ? AND m.author <> ? AND m.deleted_at IS NULL$where ORDER BY m.seq ASC LIMIT ?",
            *params.toTypedArray(),
        ) { MessageRow(Rows.message(it)) }
    }

    private fun unreadWhere(agentId: String, excludeAuthor: String, scope: Scope, within: String?, params: MutableList<Any?>): String {
        params.add(agentId)
        params.add(excludeAuthor)
        var where = " FROM messages AS m LEFT JOIN read_cursors AS rc ON rc.agent_id = ? AND rc.project_id = coalesce(m.project_id, '') " +
            "WHERE m.seq > coalesce(rc.last_seen_message_seq, 0) AND m.deleted_at IS NULL AND m.author <> ?"
        where += readerScope(Scope(scope.projectIds, agentId, scope.mentionsAnywhere), params)
        if (within != null) {
            where += " AND coalesce(m.project_id, '') = ?"
            params.add(within)
        }
        return where
    }

    private fun listUnread(agentId: String, excludeAuthor: String, limit: Int, scope: Scope, within: String? = null): List<MessageRow> {
        if (scope.projectIds?.isEmpty() == true && !scope.mentionsAnywhere) return emptyList()
        val params = mutableListOf<Any?>()
        val where = unreadWhere(agentId, excludeAuthor, scope, within, params)
        params.add(limit)
        return db.query("SELECT ${columns()}$where ORDER BY m.seq ASC LIMIT ?", *params.toTypedArray()) { MessageRow(Rows.message(it)) }
    }

    /** `countUnreadMessages`: the agent's own scope, mentions anywhere, never its own lines. */
    fun countUnread(agentId: String): Long {
        val scope = Scope(projectsForAgent(agentId), agentId, true)
        val params = mutableListOf<Any?>()
        val where = unreadWhere(agentId, AGENT_PREFIX + agentId, scope, null, params)
        return db.one("SELECT count(*) AS n$where", *params.toTypedArray()) { it.getLong(1) } ?: 0
    }

    /**
     * `readMessages`: unread messages (or everything after [since]), oldest first, 50 at a time; with
     * [markRead] the per-project read cursors move forward and `messages.read` is published.
     */
    fun read(agentId: String, since: String?, project: String?, markRead: Boolean?): Page = feed.op {
        if (!agentExists(agentId)) throw notFound("no such agent: $agentId")
        val mine = AGENT_PREFIX + agentId
        val limit = PAGE
        val projectId = if (!project.isNullOrEmpty()) feed.projects.resolve(project).id else null
        val worksIn = projectsForAgent(agentId)
        val scopeIds = if (projectId == null) worksIn else listOf(projectId)
        val sinceSeq = decodeCursor(since)
        val mentionsAnywhere = projectId == null
        val scope = Scope(scopeIds, agentId, mentionsAnywhere)

        val messages = if (sinceSeq != null && sinceSeq != 0L) {
            listAfter(sinceSeq, mine, limit, scope)
        } else {
            listUnread(agentId, mine, limit, scope)
        }

        val mark = markRead ?: true
        if (mark) {
            val moved = markPageRead(agentId, mine, limit, scope, sinceSeq, messages)
            if (moved > 0) feed.publish("messages.read", buildJsonObject { put("agentId", agentId); put("cursor", moved) })
        }
        Page(messages, (messages.lastOrNull()?.seq ?: sinceSeq ?: 0L).toString(), countUnread(agentId), mark)
    }

    private fun markPageRead(agentId: String, mine: String, limit: Int, scope: Scope, since: Long?, messages: List<MessageRow>): Long {
        val last = messages.lastOrNull()?.seq ?: return 0
        val covered = scope.projectIds ?: messages.map { it.projectId ?: NO_PROJECT }.distinct()
        val projects = LinkedHashSet(covered + messages.map { it.projectId ?: NO_PROJECT })
        val handed = messages.map { it.id }.toSet()
        var moved = 0L
        for (projectId in projects) {
            val to = if (since == null) {
                last
            } else {
                val skipped = listUnread(agentId, mine, limit, scope, within = projectId).firstOrNull { it.id !in handed }
                minOf(last, skipped?.let { it.seq - 1 } ?: last)
            }
            if (to <= 0) continue
            db.update(
                "INSERT INTO read_cursors (id, agent_id, project_id, last_seen_message_seq) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT (agent_id, project_id) DO UPDATE SET last_seen_message_seq = " +
                    db.greatest("read_cursors.last_seen_message_seq", "excluded.last_seen_message_seq"),
                Ulid.next(), agentId, projectId, to,
            )
            val cursor = db.one("SELECT last_seen_message_seq FROM read_cursors WHERE agent_id = ? AND project_id = ?", agentId, projectId) { it.getLong(1) }!!
            moved = maxOf(moved, cursor)
        }
        return moved
    }

    private fun decodeCursor(cursor: String?): Long? {
        if (cursor.isNullOrEmpty()) return null
        if (!Regex("^[0-9]+$").matches(cursor)) throw invalid("since is not a cursor this server issued")
        return cursor.toBigInteger().min(Long.MAX_VALUE.toBigInteger()).toLong()
    }

    private fun anchorProject(updateId: String?): String? {
        if (updateId == null) return null
        val update = feed.updates.find(updateId)
        if (update == null || update.deletedAt != null) throw notFound("no such update: $updateId")
        return update.projectId
    }

    private fun sameThread(answered: MessageRow, updateId: String?, replyTo: String?, requestId: String?): Boolean {
        if (replyTo != null && answered.id == replyTo) return true
        if (requestId != null || answered.requestId != null) return answered.requestId == requestId
        return answered.updateId == updateId && answered.replyTo == replyTo
    }

    private fun JsonObjectBuilder.threadKeys(message: MessageRow) {
        for ((key, value) in message.threadKeys()) put(key, value)
    }

    private fun columns() = Rows.MESSAGE.split(", ").joinToString(", ") { "m.$it" }

    companion object {
        const val BODY_MAX = 10_000
        const val PAGE = 50
        const val QUOTED_MAX = 240
        const val HUMAN = "human"
        const val AGENT_PREFIX = "agent:"
        const val NO_PROJECT = ""
    }
}
