package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Ulid

/**
 * What each subscriber follows (migration 052): projects and threads. A subscriber is a principal,
 * spelled as a message author is: `agent:<id>` for an agent, `human` for the owner (team members
 * will be `member:<id>`).
 *
 * For an agent it decides which messages reach it, so a bridge no longer has to be told its projects:
 * - Posting in a project follows it; posting or replying in a thread follows the thread.
 * - Being @mentioned in a thread follows it (`@here` and `@everyone` for the agents they reached).
 * - An agent that has never followed a project hears every project, as a new agent always has.
 * - Only `unsubscribe` stops following. Posting there again follows it again.
 *
 * For the owner it decides which alerts (phone push and the bell) are worth raising; each phone's
 * own alert preferences still decide whether that phone rings, see [ownerHears]:
 * - Each project has a level: `all` (the default), `replies` (only threads the owner follows, and
 *   questions) or `muted`.
 * - The owner follows a thread by posting or replying in it, or by choice, and can unfollow one.
 *
 * A thread is an update (its comments), a request (its discussion), or a feed post with its replies
 * (kind `message`, keyed by the post). An agent following only ever hears messages newer than the
 * moment it started (`after_seq`), so following never turns old history into unread messages.
 */
class Subscriptions(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    data class Thread(val kind: String, val id: String)

    data class ProjectFollow(val projectId: String, val slug: String, val name: String, val since: Long)

    data class ThreadFollow(val thread: Thread, val projectId: String?, val since: Long)

    /** The owner's own choices: a level per project, and threads followed (true) or unfollowed (false). */
    data class OwnerChoices(val levels: List<Pair<String, String>>, val threads: List<Pair<Thread, Boolean>>)

    /** Which messages reach an agent, besides the @mention rule (a message naming agents reaches only them). */
    sealed interface Reach {
        /** Every project. */
        data object All : Reach

        /** What the agent follows, plus mentions of it. */
        data object Followed : Reach

        /** These projects; with [widened], also the threads the agent follows and mentions of it anywhere. */
        data class Projects(val ids: List<String>, val widened: Boolean = true) : Reach
    }

    // ---- agents: what reaches them ----------------------------------------------------------------

    /** The projects the agent follows now, or null when it has never followed one (then it hears them all). */
    fun followedProjects(agentId: String): List<String>? {
        val rows = db.query(
            "SELECT project_id, unsubscribed_at IS NULL FROM project_subscriptions WHERE subscriber = ? ORDER BY seq", agent(agentId),
        ) { it.getString(1) to it.getBoolean(2) }
        if (rows.isEmpty()) return null
        return rows.filter { it.second }.map { it.first }
    }

    /** [Reach.Followed] made concrete: [Reach.All] for an agent that has never followed a project. */
    fun resolve(agentId: String, reach: Reach): Reach =
        if (reach == Reach.Followed && followedProjects(agentId) == null) Reach.All else reach

    /**
     * SQL (for messages aliased `m`) that is true when [reach] takes the message to [agentId], or ""
     * for every message. Appends its parameters to [args]. The @mention audience rule is the caller's.
     */
    fun reachSql(agentId: String, reach: Reach, args: MutableList<Any?>): String = when (val r = resolve(agentId, reach)) {
        Reach.All -> ""
        Reach.Followed -> {
            args += agent(agentId); args += agent(agentId); args += agentId
            " AND ($FOLLOWS_PROJECT OR $FOLLOWS_THREAD OR $NAMES_READER)"
        }
        is Reach.Projects -> {
            val inProjects = if (r.ids.isEmpty()) "0 = 1" else "m.project_id IN (${r.ids.joinToString(", ") { "?" }})"
            args.addAll(r.ids)
            if (r.widened) {
                args += agent(agentId); args += agentId
                " AND ($inProjects OR $FOLLOWS_THREAD OR $NAMES_READER)"
            } else {
                " AND $inProjects"
            }
        }
    }

    // ---- following and unfollowing (any subscriber) -----------------------------------------------

    /** The newest message's seq: following from now reaches only messages after it. */
    fun now(): Long = db.one("SELECT coalesce(max(seq), 0) FROM messages") { it.getLong(1) } ?: 0

    /**
     * [who] follows a project, hearing messages after [afterSeq]. Already following keeps its start.
     * Following again after unsubscribing starts from now, so the time away does not come back as unread.
     */
    fun followProject(who: String, projectId: String, afterSeq: Long = now()) {
        db.update(
            "INSERT INTO project_subscriptions (id, subscriber, project_id, level, after_seq, created_at) VALUES (?, ?, ?, 'all', ?, ?) " +
                "ON CONFLICT (subscriber, project_id) DO UPDATE SET " +
                "after_seq = CASE WHEN project_subscriptions.unsubscribed_at IS NULL THEN project_subscriptions.after_seq ELSE ? END, " +
                "created_at = CASE WHEN project_subscriptions.unsubscribed_at IS NULL THEN project_subscriptions.created_at ELSE excluded.created_at END, " +
                "unsubscribed_at = NULL",
            Ulid.next(clock()), who, projectId, afterSeq, clock(), maxOf(afterSeq, now()),
        )
    }

    /** [who] follows a thread, as [followProject] does a project. True when that is new (or follows again). */
    fun followThread(who: String, thread: Thread, projectId: String?, afterSeq: Long = now()): Boolean {
        val before = threadState(who, thread)
        db.update(
            "INSERT INTO thread_subscriptions (id, subscriber, kind, thread_id, project_id, after_seq, created_at) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (subscriber, kind, thread_id) DO UPDATE SET " +
                "after_seq = CASE WHEN thread_subscriptions.unsubscribed_at IS NULL THEN thread_subscriptions.after_seq ELSE excluded.after_seq END, " +
                "created_at = CASE WHEN thread_subscriptions.unsubscribed_at IS NULL THEN thread_subscriptions.created_at ELSE excluded.created_at END, " +
                "unsubscribed_at = NULL",
            Ulid.next(clock()), who, thread.kind, thread.id, projectId, afterSeq, clock(),
        )
        return before != true
    }

    /** Stop following a project; false when it was not followed. */
    fun unfollowProject(who: String, projectId: String): Boolean = db.update(
        "UPDATE project_subscriptions SET unsubscribed_at = ? WHERE subscriber = ? AND project_id = ? AND unsubscribed_at IS NULL",
        clock(), who, projectId,
    ) > 0

    /** Stop following a thread; false when it was not followed. */
    fun unfollowThread(who: String, thread: Thread): Boolean = db.update(
        "UPDATE thread_subscriptions SET unsubscribed_at = ? WHERE subscriber = ? AND kind = ? AND thread_id = ? AND unsubscribed_at IS NULL",
        clock(), who, thread.kind, thread.id,
    ) > 0

    /** True when [who] follows [thread], false when it unfollowed it, null when it never followed it. */
    fun threadState(who: String, thread: Thread): Boolean? = db.one(
        "SELECT unsubscribed_at IS NULL FROM thread_subscriptions WHERE subscriber = ? AND kind = ? AND thread_id = ?", who, thread.kind, thread.id,
    ) { it.getBoolean(1) }

    fun projects(who: String): List<ProjectFollow> = db.query(
        "SELECT ps.project_id, p.slug, p.name, ps.created_at FROM project_subscriptions AS ps JOIN projects AS p ON p.id = ps.project_id " +
            "WHERE ps.subscriber = ? AND ps.unsubscribed_at IS NULL ORDER BY ps.seq",
        who,
    ) { ProjectFollow(it.getString(1), it.getString(2), it.getString(3), it.getLong(4)) }

    /** The threads [who] follows, newest first, [limit] at most. */
    fun threads(who: String, limit: Int): List<ThreadFollow> = db.query(
        "SELECT kind, thread_id, project_id, created_at FROM thread_subscriptions WHERE subscriber = ? AND unsubscribed_at IS NULL " +
            "ORDER BY created_at DESC, seq DESC LIMIT ?",
        who, limit,
    ) { ThreadFollow(Thread(it.getString(1), it.getString(2)), it.getString(3), it.getLong(4)) }

    fun countThreads(who: String): Int =
        db.one("SELECT count(*) FROM thread_subscriptions WHERE subscriber = ? AND unsubscribed_at IS NULL", who) { it.getInt(1) } ?: 0

    /** The live agents following [thread], by id and name, oldest follower first. */
    fun followers(thread: Thread): List<Pair<String, String>> = db.query(
        "SELECT a.id, a.name FROM thread_subscriptions AS ts JOIN agents AS a ON ts.subscriber = 'agent:' || a.id " +
            "WHERE ts.kind = ? AND ts.thread_id = ? AND ts.unsubscribed_at IS NULL AND a.revoked_at IS NULL ORDER BY ts.seq",
        thread.kind, thread.id,
    ) { it.getString(1) to it.getString(2) }

    /**
     * After a message is posted or edited: its author follows the thread (an agent the project too),
     * and every agent it mentions follows the thread. A thread is heard from after this message; the
     * message itself reaches the mentioned agents as a mention. True when the owner newly follows.
     */
    fun afterPost(message: MessageRow, mentioned: List<String>): Boolean {
        val thread = threadOf(message)
        val author = message.author
        val authorId = author.takeIf { it.startsWith(AGENT) }?.removePrefix(AGENT)?.ifEmpty { null }
        var ownerFollowed = false
        if (authorId != null) {
            // From the start, as working in a project always has: what the agent has not read yet stays unread.
            message.projectId?.let { followProject(author, it, afterSeq = 0) }
            thread?.let { followThread(author, it, message.projectId, message.seq) }
        } else if (author == OWNER && thread != null) {
            ownerFollowed = followThread(OWNER, thread, message.projectId, message.seq)
        }
        if (thread != null) for (id in mentioned) if (id != authorId) followThread(agent(id), thread, message.projectId, message.seq)
        return ownerFollowed
    }

    // ---- the owner --------------------------------------------------------------------------------

    /** The owner's level for a project: `all` unless they chose otherwise. */
    fun ownerLevel(projectId: String?): String = projectId?.let {
        db.one("SELECT level FROM project_subscriptions WHERE subscriber = ? AND project_id = ?", OWNER, it) { rs -> rs.getString(1) }
    } ?: ALL

    fun setOwnerLevel(projectId: String, level: String) {
        if (level !in LEVELS) throw invalid("level must be one of: ${LEVELS.joinToString(", ")}")
        db.update(
            "INSERT INTO project_subscriptions (id, subscriber, project_id, level, after_seq, created_at) VALUES (?, ?, ?, ?, 0, ?) " +
                "ON CONFLICT (subscriber, project_id) DO UPDATE SET level = excluded.level, unsubscribed_at = NULL",
            Ulid.next(clock()), OWNER, projectId, level, clock(),
        )
    }

    /** Follow or unfollow a thread for the owner. True when that changed anything. */
    fun setOwnerFollow(thread: Thread, projectId: String?, following: Boolean): Boolean = if (following) {
        followThread(OWNER, thread, projectId)
    } else {
        val before = threadState(OWNER, thread)
        if (before == null) {
            db.update(
                "INSERT INTO thread_subscriptions (id, subscriber, kind, thread_id, project_id, after_seq, created_at, unsubscribed_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Ulid.next(clock()), OWNER, thread.kind, thread.id, projectId, now(), clock(), clock(),
            )
            true
        } else {
            unfollowThread(OWNER, thread)
        }
    }

    fun ownerChoices(): OwnerChoices = OwnerChoices(
        levels = db.query("SELECT project_id, level FROM project_subscriptions WHERE subscriber = ? ORDER BY seq", OWNER) {
            it.getString(1) to it.getString(2)
        },
        threads = db.query("SELECT kind, thread_id, unsubscribed_at IS NULL FROM thread_subscriptions WHERE subscriber = ? ORDER BY seq", OWNER) {
            Thread(it.getString(1), it.getString(2)) to it.getBoolean(3)
        },
    )

    /**
     * Whether an alert is one the owner follows, before any phone's own preferences. [kind] is
     * `update` (a new card), `message` (a line by an agent in [thread]), `request` (a question for
     * the owner) or anything else (always heard). [toOwner]: the message answers the owner, or is in
     * a thread they spoke in before following existed. [critical] alerts are always heard: whether
     * one pages is each phone's own choice.
     *
     * - A thread the owner follows is heard, even in a muted project; one they unfollowed is not.
     * - `all`: everything else. `replies`: questions, and messages [toOwner] (or naming them with
     *   `@you` or `@owner`). `muted`: nothing else.
     */
    fun ownerHears(kind: String, projectId: String?, thread: Thread?, toOwner: Boolean = false, critical: Boolean = false): Boolean {
        if (critical) return true
        if (kind != "update" && kind != "message" && kind != "request") return true
        val state = thread?.let { threadState(OWNER, it) }
        if (state == true) return true
        if (state == false) return false
        return when (ownerLevel(projectId)) {
            MUTED -> false
            REPLIES -> kind == "request" || (kind == "message" && toOwner)
            else -> true
        }
    }

    /** [ownerHears] for a new card. */
    fun ownerHearsUpdate(projectId: String?, priority: String?): Boolean =
        ownerHears("update", projectId, null, critical = priority == CRITICAL)

    /** [ownerHears] for an agent's message; [toOwner] as [ownerHears] says, and `@you` or `@owner` in it counts too. */
    fun ownerHearsMessage(messageId: String, toOwner: Boolean): Boolean {
        val (thread, projectId) = runCatching { threadAt(CardRef("message", messageId)) }.getOrNull() ?: (null to null)
        val (project, body) = db.one("SELECT project_id, body FROM messages WHERE id = ?", messageId) { it.getString(1) to it.getString(2) } ?: (null to "")
        return ownerHears("message", projectId ?: project, thread, toOwner = toOwner || OWNER_MENTION.containsMatchIn(body))
    }

    /** [ownerHears] for a question waiting on the owner. */
    fun ownerHearsRequest(requestId: String, projectId: String?, priority: String?): Boolean =
        ownerHears("request", projectId, Thread("request", requestId), critical = priority == CRITICAL)

    // ---- threads ----------------------------------------------------------------------------------

    /**
     * The thread a card is in, with its project: an update's comments, a request's discussion, or
     * the feed post a message is in (the post itself, or the update or request it hangs off).
     */
    fun threadAt(card: CardRef): Pair<Thread, String?> = when (card.kind) {
        "update" -> db.one("SELECT project_id FROM updates WHERE id = ? AND deleted_at IS NULL", card.id) { Thread("update", card.id) to it.getString(1) }
            ?: throw notFound("no such update: ${card.id}")
        "request" -> db.one("SELECT project_id FROM approvals WHERE id = ?", card.id) { Thread("request", card.id) to it.getString(1) }
            ?: throw notFound("no such request: ${card.id}")
        else -> {
            val m = db.one(
                "SELECT id, update_id, request_id, reply_to, task_id, project_id FROM messages WHERE id = ? AND deleted_at IS NULL", card.id,
            ) { threadOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5)) to it.getString(6) }
                ?: throw notFound("no such message: ${card.id}")
            val thread = m.first ?: throw invalid("that message is on an old task, which has no thread to follow")
            thread to m.second
        }
    }

    /** A line to recognise a thread by: the update's title (or text), the question, or the post. */
    fun title(thread: Thread): String? {
        val text = when (thread.kind) {
            "update" -> db.one("SELECT coalesce(title, body) FROM updates WHERE id = ?", thread.id) { it.getString(1) }
            "request" -> db.one("SELECT question FROM approvals WHERE id = ?", thread.id) { it.getString(1) }
            else -> db.one("SELECT body FROM messages WHERE id = ?", thread.id) { it.getString(1) }
        } ?: return null
        val line = text.replace(Regex("\\s+"), " ").trim()
        return if (line.length > TITLE_MAX) line.take(TITLE_MAX).trimEnd() + "…" else line
    }

    companion object {
        private const val AGENT = "agent:"
        const val OWNER = "human"
        const val ALL = "all"
        const val REPLIES = "replies"
        const val MUTED = "muted"
        val LEVELS = listOf(ALL, REPLIES, MUTED)
        private const val CRITICAL = "critical"

        /** The owner named in a message, as the apps read it: `@you` or `@owner`. */
        private val OWNER_MENTION = Regex("(?<![\\w@])@(you|owner)\\b", RegexOption.IGNORE_CASE)
        const val LIST_LIMIT = 100
        private const val TITLE_MAX = 80

        fun agent(id: String) = AGENT + id

        /** The thread a message belongs to: its request, its update, or its feed post. Null for an old task's thread. */
        fun threadOf(m: MessageRow): Thread? = threadOf(m.id, m.updateId, m.requestId, m.replyTo, m.taskId)

        fun threadOf(id: String, updateId: String?, requestId: String?, replyTo: String?, taskId: String?): Thread? = when {
            taskId != null -> null
            requestId != null -> Thread("request", requestId)
            updateId != null -> Thread("update", updateId)
            else -> Thread("message", replyTo ?: id)
        }

        private const val NAMES_READER = "EXISTS (SELECT 1 FROM message_mentions AS mm WHERE mm.message_id = m.id AND mm.agent_id = ?)"

        private const val FOLLOWS_PROJECT = "EXISTS (SELECT 1 FROM project_subscriptions AS ps WHERE ps.subscriber = ? " +
            "AND ps.project_id = m.project_id AND ps.unsubscribed_at IS NULL AND m.seq > ps.after_seq)"

        /** The same thread rule as [threadOf], in SQL. */
        private const val FOLLOWS_THREAD = "EXISTS (SELECT 1 FROM thread_subscriptions AS ts WHERE ts.subscriber = ? " +
            "AND ts.unsubscribed_at IS NULL AND m.seq > ts.after_seq AND m.task_id IS NULL " +
            "AND ts.kind = CASE WHEN m.request_id IS NOT NULL THEN 'request' WHEN m.update_id IS NOT NULL THEN 'update' ELSE 'message' END " +
            "AND ts.thread_id = CASE WHEN m.request_id IS NOT NULL THEN m.request_id WHEN m.update_id IS NOT NULL THEN m.update_id " +
            "ELSE coalesce(m.reply_to, m.id) END)"
    }
}
