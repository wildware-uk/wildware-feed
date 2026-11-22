package dev.wildware.feed.server.domain

/**
 * An agent reading a whole thread (spec/04 §4.9, `get_messages` with `message_id`, `update_id` or
 * `request_id`): the card it hangs on, the first post and every reply, oldest first.
 *
 * Following a thread only brings what is said after the agent started following (`after_seq`), so
 * an agent brought in by an @mention halfway down would otherwise never see the post that started
 * it, nor the files on it. This read is how it catches up.
 *
 * Who may read: an agent that follows the thread (an @mention follows it, so does posting in it or
 * `subscribe`), is @mentioned anywhere in it, wrote in it, wrote its card, or hears its channel.
 * Other teams never: every query is the caller's team.
 *
 * Which lines: everything live in the thread, except a line that @names only other agents, which
 * stays theirs as it does everywhere ([Mentions.readable]). One exception, for the owner: when a
 * person @mentioned this agent in the thread, every line a person wrote there is shared with it
 * too, so "@b can you help @a" lets b read what the owner told a. A line one agent addressed only
 * to another agent stays between them. Lines held back are counted, never shown.
 */
class ThreadReads(private val feed: Feed) {
    private val db get() = feed.db
    private val subscriptions get() = Subscriptions(feed.db, feed.clock)

    /** The card a thread hangs on: the update, the question, or (for a feed post) the post itself. */
    class Card(
        val kind: String,
        val id: String,
        val projectId: String?,
        val author: String,
        val title: String?,
        val body: String?,
        val status: String?,
        val createdAt: Long?,
        /** A question's kind, detail and state; null on other cards. */
        val request: Request? = null,
    )

    class Request(val kind: String, val detail: String?, val state: String, val options: List<String>?)

    class Read(
        val thread: Subscriptions.Thread,
        val card: Card,
        val fields: List<FieldRow>,
        /** Oldest first; for a feed post the post itself comes first. */
        val messages: List<MessageRow>,
        /** Lines addressed only to other agents, left out. */
        val withheld: Int,
        /** Older lines left out because the thread is longer than [LIMIT]. */
        val earlier: Int,
    )

    fun read(agentId: String, card: CardRef): Read = feed.op {
        val me = AGENT + agentId
        val (thread, projectId) = subscriptions.threadAt(card)
        val head = card(thread, projectId)
        val all = lines(thread)
        if (!mayRead(agentId, thread, projectId, head, all)) {
            throw forbidden(
                "you don't follow that thread and nobody @mentioned you in it. Follow it with subscribe " +
                    "(the same id) to read it, or ask your owner to @mention you there.",
            )
        }
        // Brought in by a person: what people wrote here is shared with this agent.
        val byPerson = all.any { it.author == HUMAN && named(it.id, agentId) }
        val shown = all.filter { line ->
            line.author == me || Mentions.readable(db, line.id, line.author, me) || (byPerson && line.author == HUMAN)
        }
        val withheld = all.size - shown.size
        // A feed post's root stays first; the newest replies win when the thread is long.
        val root = shown.firstOrNull()?.takeIf { thread.kind == "message" && it.id == thread.id }
        val replies = if (root != null) shown.drop(1) else shown
        val kept = replies.takeLast(LIMIT)
        val fields = feed.fields.on(CardRef(thread.kind, thread.id))
        Read(thread, head, fields, listOfNotNull(root) + kept, withheld, replies.size - kept.size)
    }

    private fun mayRead(agentId: String, thread: Subscriptions.Thread, projectId: String?, head: Card, all: List<MessageRow>): Boolean {
        val me = AGENT + agentId
        if (subscriptions.threadState(me, thread) == true) return true
        if (head.author == me) return true
        if (all.any { it.author == me || named(it.id, agentId) }) return true
        return projectId != null && projectId in subscriptions.heardChannels(agentId)
    }

    private fun named(messageId: String, agentId: String): Boolean =
        db.one("SELECT 1 FROM message_mentions WHERE team_id = :team AND message_id = ? AND agent_id = ?", messageId, agentId) { true } == true

    private fun card(thread: Subscriptions.Thread, projectId: String?): Card = when (thread.kind) {
        "update" -> {
            val u = feed.updates.find(thread.id) ?: throw notFound("no such update: ${thread.id}")
            Card("update", u.id, u.projectId, AGENT + u.agentId, u.title, u.body, u.status, u.createdAt)
        }
        "request" -> db.one(
            "SELECT agent_id, kind, question, detail, state, options FROM approvals WHERE team_id = :team AND id = ?", thread.id,
        ) {
            val options = it.getString(6)?.let { raw ->
                runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonArray }.getOrNull()
                    ?.map { o -> (o as kotlinx.serialization.json.JsonPrimitive).content }
            }
            Card(
                "request", thread.id, projectId, AGENT + it.getString(1), it.getString(3), null, null, null,
                Request(it.getString(2), it.getString(4), it.getString(5), options),
            )
        } ?: throw notFound("no such request: ${thread.id}")
        else -> {
            val m = feed.messages.find(thread.id)?.takeIf { it.deletedAt == null } ?: throw notFound("no such message: ${thread.id}")
            val status = db.one("SELECT status FROM messages WHERE team_id = :team AND id = ?", m.id) { it.getString(1) }
            Card("message", m.id, m.projectId, m.author, null, null, status, m.createdAt)
        }
    }

    /** Every live line in [thread], oldest first: the same thread rule as [Subscriptions.threadOf]. */
    private fun lines(thread: Subscriptions.Thread): List<MessageRow> {
        val where = when (thread.kind) {
            "request" -> "m.request_id = ?"
            "update" -> "m.update_id = ? AND m.request_id IS NULL"
            else -> "(m.id = ? OR m.reply_to = ?) AND m.request_id IS NULL AND m.update_id IS NULL"
        }
        val args = if (thread.kind == "message") arrayOf<Any?>(thread.id, thread.id) else arrayOf<Any?>(thread.id)
        return db.query(
            "SELECT ${columns()} FROM messages AS m WHERE m.team_id = :team AND $where AND m.task_id IS NULL AND m.deleted_at IS NULL ORDER BY m.seq ASC",
            *args,
        ) { MessageRow(Rows.message(it)) }
    }

    private fun columns() = Rows.MESSAGE.split(", ").joinToString(", ") { "m.$it" }

    companion object {
        /** The most replies one read hands over; the post that started the thread always comes too. */
        const val LIMIT = 200
        private const val AGENT = "agent:"
        private const val HUMAN = "human"

        /**
         * The line an agent reads under a message that @mentions it in a thread it was not in before:
         * how to read the rest. Null when it already followed the thread, or the message starts it.
         */
        fun hint(db: dev.wildware.feed.server.db.Database, agentId: String, m: MessageRow): String? =
            hint(db, agentId, m.id, m.seq, m.updateId, m.requestId, m.replyTo, m.taskId)

        fun hint(
            db: dev.wildware.feed.server.db.Database, agentId: String,
            id: String, seq: Long, updateId: String?, requestId: String?, replyTo: String?, taskId: String?,
        ): String? {
            val thread = Subscriptions.threadOf(id, updateId, requestId, replyTo, taskId) ?: return null
            // The post that starts a thread is the whole thread so far.
            if (thread.kind == "message" && thread.id == id) return null
            val mentioned = db.one("SELECT 1 FROM message_mentions WHERE team_id = :team AND message_id = ? AND agent_id = ?", id, agentId) { true } == true
            if (!mentioned) return null
            // Following from this line on (or not at all): the agent has not seen what came before.
            val since = db.one(
                "SELECT after_seq FROM thread_subscriptions WHERE team_id = :team AND subscriber = ? AND kind = ? AND thread_id = ? AND unsubscribed_at IS NULL",
                AGENT + agentId, thread.kind, thread.id,
            ) { it.getLong(1) }
            if (since != null && since < seq) return null
            return threadHint(id)
        }

        /** How to read the whole thread [messageId] is in. */
        fun threadHint(messageId: String) =
            "(You were @mentioned in a thread you were not in. To read all of it — the post that started it, every " +
                "reply, the card's details and their files — call get_messages({message_id: \"$messageId\"}).)"
    }
}
