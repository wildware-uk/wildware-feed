package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Ulid

/**
 * What each subscriber follows (migration 052): projects and threads. A subscriber is a principal,
 * spelled as a message author is: `agent:<id>` for an agent, and `member:<membership id>` for a
 * person (migration 055; the one owner was `human` before).
 *
 * For an agent it decides which messages reach it, so a bridge no longer has to be told its projects:
 * - An agent hears only what it follows: no follows, no channel. A new agent hears nothing but
 *   messages that @mention it until it follows something. No state means "every channel".
 * - A top-level post in a channel (a new card, or a feed post that answers nothing) follows the
 *   channel. A reply in a thread follows only that thread, never its channel.
 * - Being @mentioned in a thread follows it (`@here` and `@everyone` for the agents they reached).
 * - Only `unsubscribe` stops following, and posting there never undoes it: only `subscribe` (or
 *   the owner adding it) does.
 * - The owner can add an agent to a channel or a group, or remove it (migration 060). A removal
 *   sticks: posting there does not follow it again, and its own `subscribe` is refused, until the
 *   owner adds it back. An @mention still reaches it.
 * - A bridge may also name channels (`?project=`): then it hears only those of the channels the
 *   agent follows ([Reach.bridge]), so a bridge set up for one channel never hears another's
 *   channel-wide messages. A thread it follows and an @mention of it still reach it anywhere.
 *
 * For each person it decides which alerts (phone push and the bell) are worth raising; each phone's
 * own alert preferences still decide whether that phone rings, see [hears]:
 * - Each project has a level: `all` (the default), `replies` (only threads the owner follows, and
 *   questions) or `muted`.
 * - A person follows a thread by posting or replying in it, by being @mentioned, or by choice, and
 *   can unfollow one.
 *
 * A thread is an update (its comments), a request (its discussion), or a feed post with its replies
 * (kind `message`, keyed by the post). An agent following only ever hears messages newer than the
 * moment it started (`after_seq`), so following never turns old history into unread messages.
 */
class Subscriptions(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    private val access get() = ChannelAccess(db, clock)
    data class Thread(val kind: String, val id: String)

    data class ProjectFollow(val projectId: String, val slug: String, val name: String, val since: Long, val chosen: Boolean = false)

    data class GroupFollow(val groupId: String, val slug: String, val name: String, val since: Long)

    /** A subscriber's own choice for a channel or group: following (with its level) or not. */
    data class Choice(val following: Boolean, val level: String, val chosen: Boolean)

    data class ThreadFollow(val thread: Thread, val projectId: String?, val since: Long)

    /**
     * The owner's own choices: a level per channel and per group (only those set), and threads
     * followed (true) or unfollowed (false).
     */
    data class OwnerChoices(
        val levels: List<Pair<String, String>>,
        val threads: List<Pair<Thread, Boolean>>,
        val groups: List<Pair<String, String>> = emptyList(),
    )

    /**
     * Which messages reach an agent, besides the @mention rule (a message an agent wrote naming
     * agents reaches only them; a person's @name reaches the channel too, [Mentions.directs]): what
     * it follows (channels, threads), of that only what is in [within] when that is given
     * (get_messages reading one channel), and mentions of it anywhere. With [threadsAnywhere]
     * (a bridge that named channels, [bridge]) [within] narrows only the channels it follows, never
     * the threads. A followed message in no channel passes [within] only with [channelless].
     */
    data class Reach(val within: List<String>? = null, val channelless: Boolean = true, val threadsAnywhere: Boolean = false) {
        companion object {
            /** What the agent follows, plus mentions of it. */
            val Followed = Reach()

            /**
             * What a bridge that named [within] hears: of the channels the agent follows, only those,
             * but every thread it follows and every mention of it, wherever they are. A bridge's list
             * narrows channel-wide traffic; it never hides a conversation the agent is in.
             */
            fun bridge(within: List<String>?) = Reach(within, threadsAnywhere = true)
        }
    }

    // ---- agents: what reaches them ----------------------------------------------------------------

    /**
     * The channels the agent hears now; none when it follows none. The most specific choice wins
     * (spec/12): a channel it chose, then the channel's group, then a channel it follows because it
     * posted there.
     */
    fun followedProjects(agentId: String): List<String> {
        val me = agent(agentId)
        val channels = db.query(
            "SELECT project_id, unsubscribed_at IS NULL, chosen FROM project_subscriptions WHERE team_id = :team AND subscriber = ? ORDER BY seq", me,
        ) { it.getString(1) to Choice(it.getBoolean(2), ALL, it.getInt(3) == 1) }
        val groups = db.query("SELECT group_id, unsubscribed_at IS NULL FROM group_subscriptions WHERE team_id = :team AND subscriber = ?", me) {
            it.getString(1) to it.getBoolean(2)
        }.toMap()
        val own = channels.toMap()
        val all = db.query("SELECT id, group_id FROM projects WHERE team_id = :team ORDER BY seq") { it.getString(1) to it.getString(2) }
        val heard = all.filter { (id, groupId) ->
            val choice = own[id]
            when {
                choice != null && choice.chosen -> choice.following
                groupId != null && groupId in groups -> groups.getValue(groupId)
                else -> choice?.following == true
            }
        }.map { it.first }.toSet()
        // Followed channels in the order they were followed, then those heard only through a group.
        // A channel the agent has no access to is never heard (spec/15), whatever it follows.
        val hidden = access.hiddenFor(me)
        return (channels.map { it.first }.filter { it in heard } + all.map { it.first }.filter { it in heard && it !in own }).filter { it !in hidden }
    }

    /** Every channel the agent hears now, by id: [followedProjects]. */
    fun heardChannels(agentId: String): List<String> = followedProjects(agentId)

    /**
     * Why the owner keeps [agentId] out of a channel, or null when nothing does: `channel` (removed
     * from it) or `group` (removed from its group, and not added back to the channel itself).
     */
    fun removedFrom(agentId: String, projectId: String): String? {
        val me = agent(agentId)
        val own = db.one(
            "SELECT removed_at IS NOT NULL, chosen = 1 AND unsubscribed_at IS NULL FROM project_subscriptions WHERE team_id = :team AND subscriber = ? AND project_id = ?", me, projectId,
        ) { it.getBoolean(1) to it.getBoolean(2) }
        if (own?.first == true) return "channel"
        if (own?.second == true) return null
        val inRemovedGroup = db.one(
            "SELECT 1 FROM group_subscriptions AS gs JOIN projects AS p ON p.group_id = gs.group_id " +
                "WHERE gs.team_id = :team AND gs.subscriber = ? AND p.id = ? AND gs.removed_at IS NOT NULL", me, projectId,
        ) { true } == true
        return if (inRemovedGroup) "group" else null
    }

    /** True when the owner removed [agentId] from the group. */
    fun removedFromGroup(agentId: String, groupId: String): Boolean = db.one(
        "SELECT 1 FROM group_subscriptions WHERE team_id = :team AND subscriber = ? AND group_id = ? AND removed_at IS NOT NULL", agent(agentId), groupId,
    ) { true } == true

    /** The channels whose work counts for the agent: [heardChannels]. */
    fun workChannels(agentId: String): List<String> = followedProjects(agentId)

    /**
     * SQL (for messages aliased `m`) that is true when [reach] takes the message to [agentId].
     * Appends its parameters to [args]. The @mention audience rule is the caller's ([audienceSql]).
     *
     * [oneMessage] picks the shape: for a query about a single message (`m.id = ?`) it checks each
     * rule with a small lookup; otherwise each rule is a set worked out once and probed per message,
     * since a lookup per message, over every message in the team, is what made counting slow.
     * Both say the same thing.
     */
    fun reachSql(agentId: String, reach: Reach, args: MutableList<Any?>, oneMessage: Boolean = false): String {
        val hears = if (oneMessage) {
            repeat(5) { args += agent(agentId) }
            HEARS_CHANNEL
        } else {
            repeat(2) { args += agent(agentId) }
            HEARS_CHANNEL_SET
        }
        val within = reach.within
        fun inChannels(): String {
            args.addAll(within!!)
            val named = if (within.isEmpty()) "0 = 1" else "m.project_id IN (${within.joinToString(", ") { "?" }})"
            return "(${if (reach.channelless) "m.project_id IS NULL OR " else ""}$named)"
        }
        // Parameters go into [args] in the order their placeholders appear.
        val narrowed = when {
            within == null -> "($hears OR " + followsThread(agentId, args, oneMessage) + ")"
            // A bridge's list narrows channel-wide traffic only: a thread the agent follows still reaches it.
            reach.threadsAnywhere -> "(($hears AND " + inChannels() + ") OR " + followsThread(agentId, args, oneMessage) + ")"
            else -> "(($hears OR " + followsThread(agentId, args, oneMessage) + ") AND " + inChannels() + ")"
        }
        // A message that @mentions the agent reaches it wherever it is, as a mention always has,
        // except in a channel it has no access to (spec/15): there nothing reaches it.
        return " AND ($narrowed OR " + namesReader(agentId, args, oneMessage) + ")" + access.notHiddenFor(agent(agentId), "m.project_id")
    }

    private fun followsThread(agentId: String, args: MutableList<Any?>, oneMessage: Boolean): String = if (oneMessage) {
        args += agent(agentId)
        FOLLOWS_THREAD
    } else {
        repeat(THREAD_SET_BRANCHES) { args += agent(agentId) }
        FOLLOWS_THREAD_SET
    }

    private fun namesReader(agentId: String, args: MutableList<Any?>, oneMessage: Boolean): String {
        args += agentId
        return if (oneMessage) NAMES_READER else NAMES_READER_SET
    }

    // ---- following and unfollowing (any subscriber) -----------------------------------------------

    /** The newest message's seq: following from now reaches only messages after it. */
    fun now(): Long = db.one("SELECT coalesce(max(seq), 0) FROM messages WHERE team_id = :team") { it.getLong(1) } ?: 0

    /**
     * [who] follows a project, hearing messages after [afterSeq]. Already following keeps its start.
     * Following again after unsubscribing starts from now, so the time away does not come back as unread.
     * [chosen]: on purpose (`subscribe`), so it beats the channel's group. Not [chosen] (posting
     * there) only ever starts a follow: it never undoes an unsubscribe, and never makes a row chosen.
     */
    fun followProject(who: String, projectId: String, afterSeq: Long = now(), chosen: Boolean = false) {
        // The owner keeps it out: posting there does not bring it back (`subscribe` is refused first).
        if (who.startsWith(AGENT) && removedFrom(who.removePrefix(AGENT), projectId) != null) return
        if (!chosen) {
            db.update(
                "INSERT INTO project_subscriptions (team_id, id, subscriber, project_id, level, after_seq, created_at, chosen) VALUES (:team, ?, ?, ?, 'all', ?, ?, 0) " +
                    "ON CONFLICT (subscriber, project_id) DO NOTHING",
                Ulid.next(clock()), who, projectId, afterSeq, clock(),
            )
            return
        }
        // Choosing a channel the agent was not hearing (it left the group) starts it from now too.
        val restart = who.startsWith(AGENT) && projectId !in followedProjects(who.removePrefix(AGENT))
        db.update(
            "INSERT INTO project_subscriptions (team_id, id, subscriber, project_id, level, after_seq, created_at, chosen) VALUES (:team, ?, ?, ?, 'all', ?, ?, ?) " +
                "ON CONFLICT (subscriber, project_id) DO UPDATE SET " +
                "chosen = CASE WHEN excluded.chosen = 1 THEN 1 ELSE project_subscriptions.chosen END, " +
                "after_seq = CASE WHEN project_subscriptions.unsubscribed_at IS NULL AND ${if (restart) "1 = 0" else "1 = 1"} " +
                "THEN project_subscriptions.after_seq ELSE ? END, " +
                "created_at = CASE WHEN project_subscriptions.unsubscribed_at IS NULL THEN project_subscriptions.created_at ELSE excluded.created_at END, " +
                "unsubscribed_at = NULL, by_owner = 0",
            Ulid.next(clock()), who, projectId, afterSeq, clock(), if (chosen) 1 else 0, maxOf(afterSeq, now()),
        )
    }

    /** [who] follows a group, so every channel in it, as [followProject] does a channel. */
    fun followGroup(who: String, groupId: String, afterSeq: Long = now()) {
        if (who.startsWith(AGENT) && removedFromGroup(who.removePrefix(AGENT), groupId)) return
        db.update(
            "INSERT INTO group_subscriptions (team_id, id, subscriber, group_id, level, after_seq, created_at) VALUES (:team, ?, ?, ?, 'all', ?, ?) " +
                "ON CONFLICT (subscriber, group_id) DO UPDATE SET " +
                "after_seq = CASE WHEN group_subscriptions.unsubscribed_at IS NULL THEN group_subscriptions.after_seq ELSE ? END, " +
                "created_at = CASE WHEN group_subscriptions.unsubscribed_at IS NULL THEN group_subscriptions.created_at ELSE excluded.created_at END, " +
                "unsubscribed_at = NULL, by_owner = 0",
            Ulid.next(clock()), who, groupId, afterSeq, clock(), maxOf(afterSeq, now()),
        )
    }

    /**
     * [who] stops following a group. The row stays, stamped, so the choice still beats the channels
     * the agent follows only because it posted there. False when it was not followed.
     */
    fun unfollowGroup(who: String, groupId: String): Boolean {
        val before = groupState(who, groupId)
        if (before == false) return false
        if (before == null) {
            db.update(
                "INSERT INTO group_subscriptions (team_id, id, subscriber, group_id, level, after_seq, created_at, unsubscribed_at) VALUES (:team, ?, ?, ?, 'all', ?, ?, ?)",
                Ulid.next(clock()), who, groupId, now(), clock(), clock(),
            )
            return false
        }
        db.update("UPDATE group_subscriptions SET unsubscribed_at = ? WHERE team_id = :team AND subscriber = ? AND group_id = ?", clock(), who, groupId)
        return true
    }

    /** True when [who] follows the group, false when it left it, null when it never chose. */
    fun groupState(who: String, groupId: String): Boolean? =
        db.one("SELECT unsubscribed_at IS NULL FROM group_subscriptions WHERE team_id = :team AND subscriber = ? AND group_id = ?", who, groupId) { it.getBoolean(1) }

    /** [who] follows a thread, as [followProject] does a project. True when that is new (or follows again). */
    fun followThread(who: String, thread: Thread, projectId: String?, afterSeq: Long = now()): Boolean {
        val before = threadState(who, thread)
        db.update(
            "INSERT INTO thread_subscriptions (team_id, id, subscriber, kind, thread_id, project_id, after_seq, created_at) VALUES (:team, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (subscriber, kind, thread_id) DO UPDATE SET " +
                "after_seq = CASE WHEN thread_subscriptions.unsubscribed_at IS NULL THEN thread_subscriptions.after_seq ELSE excluded.after_seq END, " +
                "created_at = CASE WHEN thread_subscriptions.unsubscribed_at IS NULL THEN thread_subscriptions.created_at ELSE excluded.created_at END, " +
                "unsubscribed_at = NULL",
            Ulid.next(clock()), who, thread.kind, thread.id, projectId, afterSeq, clock(),
        )
        return before != true
    }

    /**
     * Stop following a channel, on purpose, so it beats the channel's group. False when it was not
     * followed on its own. Still recorded then, so a group [who] follows no longer brings this channel.
     */
    fun unfollowProject(who: String, projectId: String): Boolean {
        val was = db.update(
            "UPDATE project_subscriptions SET unsubscribed_at = ?, chosen = 1 WHERE team_id = :team AND subscriber = ? AND project_id = ? AND unsubscribed_at IS NULL",
            clock(), who, projectId,
        ) > 0
        if (!was) {
            db.update(
                "INSERT INTO project_subscriptions (team_id, id, subscriber, project_id, level, after_seq, created_at, unsubscribed_at, chosen) " +
                    "VALUES (:team, ?, ?, ?, 'all', ?, ?, ?, 1) ON CONFLICT (subscriber, project_id) DO UPDATE SET chosen = 1",
                Ulid.next(clock()), who, projectId, now(), clock(), clock(),
            )
        }
        return was
    }

    /** Stop following a thread; false when it was not followed. */
    fun unfollowThread(who: String, thread: Thread): Boolean = db.update(
        "UPDATE thread_subscriptions SET unsubscribed_at = ? WHERE team_id = :team AND subscriber = ? AND kind = ? AND thread_id = ? AND unsubscribed_at IS NULL",
        clock(), who, thread.kind, thread.id,
    ) > 0

    /** True when [who] follows [thread], false when it unfollowed it, null when it never followed it. */
    fun threadState(who: String, thread: Thread): Boolean? = db.one(
        "SELECT unsubscribed_at IS NULL FROM thread_subscriptions WHERE team_id = :team AND subscriber = ? AND kind = ? AND thread_id = ?", who, thread.kind, thread.id,
    ) { it.getBoolean(1) }

    /** The channels [who] follows on their own (not through a group), in the order followed. */
    fun projects(who: String): List<ProjectFollow> = db.query(
        "SELECT ps.project_id, p.slug, p.name, ps.created_at, ps.chosen FROM project_subscriptions AS ps JOIN projects AS p ON p.id = ps.project_id " +
            "WHERE ps.team_id = :team AND ps.subscriber = ? AND ps.unsubscribed_at IS NULL${access.notHiddenFor(who, "ps.project_id")} ORDER BY ps.seq",
        who,
    ) { ProjectFollow(it.getString(1), it.getString(2), it.getString(3), it.getLong(4), it.getInt(5) == 1) }

    /** The channels [who] chose not to follow, by id. */
    fun leftProjects(who: String): List<String> = db.query(
        "SELECT project_id FROM project_subscriptions WHERE team_id = :team AND subscriber = ? AND unsubscribed_at IS NOT NULL AND chosen = 1${access.notHiddenFor(who, "project_id")} ORDER BY seq", who,
    ) { it.getString(1) }

    /** The groups [who] follows, in the order followed. */
    fun groups(who: String): List<GroupFollow> = db.query(
        "SELECT gs.group_id, g.slug, g.name, gs.created_at FROM group_subscriptions AS gs JOIN project_groups AS g ON g.id = gs.group_id " +
            "WHERE gs.team_id = :team AND gs.subscriber = ? AND gs.unsubscribed_at IS NULL${access.groupNotHiddenFor(who, "gs.group_id")} ORDER BY gs.seq",
        who,
    ) { GroupFollow(it.getString(1), it.getString(2), it.getString(3), it.getLong(4)) }

    /** The threads [who] follows, newest first, [limit] at most. */
    fun threads(who: String, limit: Int): List<ThreadFollow> = db.query(
        "SELECT kind, thread_id, project_id, created_at FROM thread_subscriptions WHERE team_id = :team AND subscriber = ? AND unsubscribed_at IS NULL" +
            "${access.notHiddenFor(who, "project_id")} ORDER BY created_at DESC, seq DESC LIMIT ?",
        who, limit,
    ) { ThreadFollow(Thread(it.getString(1), it.getString(2)), it.getString(3), it.getLong(4)) }

    fun countThreads(who: String): Int =
        db.one("SELECT count(*) FROM thread_subscriptions WHERE team_id = :team AND subscriber = ? AND unsubscribed_at IS NULL${access.notHiddenFor(who, "project_id")}", who) { it.getInt(1) } ?: 0

    /** The live agents following [thread], by id and name, oldest follower first. */
    fun followers(thread: Thread): List<Pair<String, String>> = db.query(
        "SELECT a.id, a.name FROM thread_subscriptions AS ts JOIN agents AS a ON ts.subscriber = 'agent:' || a.id " +
            "WHERE ts.team_id = :team AND ts.kind = ? AND ts.thread_id = ? AND ts.unsubscribed_at IS NULL AND a.revoked_at IS NULL ORDER BY ts.seq",
        thread.kind, thread.id,
    ) { it.getString(1) to it.getString(2) }

    /**
     * After a message is posted or edited: its author follows the thread, and every agent and person
     * it mentions follows the thread. An agent's top-level post in a channel (one that answers or
     * replies to nothing) follows the channel too, from this message on; a reply in a thread never
     * does, so answering in someone's thread never brings in their whole channel. A thread is heard
     * from after this message; the message itself reaches the mentioned agents as a mention. Answers
     * the people who newly follow, by membership id.
     */
    fun afterPost(message: MessageRow, mentioned: List<String>, mentionedPeople: List<String> = emptyList()): List<String> {
        val thread = threadOf(message)
        val author = message.author
        val authorId = author.takeIf { it.startsWith(AGENT) }?.removePrefix(AGENT)?.ifEmpty { null }
        val followed = ArrayList<String>()
        val person = if (author == HUMAN) message.userId else null
        if (authorId != null) {
            val topLevel = message.updateId == null && message.requestId == null && message.replyTo == null &&
                message.answers == null && message.taskId == null
            // From this message on, so following never turns the channel's old history into unread.
            if (topLevel) message.projectId?.let { followProject(author, it) }
            thread?.let { followThread(author, it, message.projectId, message.seq) }
        } else if (person != null && thread != null) {
            if (followThread(People.subscriber(person), thread, message.projectId, message.seq)) followed += person
        }
        if (thread != null) {
            for (id in mentioned) if (id != authorId) followThread(agent(id), thread, message.projectId, message.seq)
            for (id in mentionedPeople) if (id != person && followThread(People.subscriber(id), thread, message.projectId, message.seq)) followed += id
        }
        return followed.distinct()
    }

    // ---- the owner adds and removes agents (migration 060) -----------------------------------------

    /** Whether [agentId] hears the group: it follows it. */
    fun hearsGroup(agentId: String, groupId: String): Boolean = groupState(agent(agentId), groupId) == true

    /**
     * The owner adds [agentId] to a channel: it hears it from now, and a removal is lifted. A row the
     * agent had stays its own; a new one is the owner's. False when it already heard the channel.
     */
    fun ownerAddChannel(agentId: String, projectId: String): Boolean {
        if (projectId in heardChannels(agentId)) return false
        db.update(
            "INSERT INTO project_subscriptions (team_id, id, subscriber, project_id, level, after_seq, created_at, chosen, by_owner) VALUES (:team, ?, ?, ?, 'all', ?, ?, 1, 1) " +
                "ON CONFLICT (subscriber, project_id) DO UPDATE SET chosen = 1, unsubscribed_at = NULL, removed_at = NULL, " +
                "after_seq = excluded.after_seq, created_at = excluded.created_at",
            Ulid.next(clock()), agent(agentId), projectId, now(), clock(),
        )
        return true
    }

    /**
     * The owner removes [agentId] from a channel, and it sticks (see [removedFrom]). Threads it
     * followed there stop too, so from now on only what names it reaches it. False when it was removed already.
     */
    fun ownerRemoveChannel(agentId: String, projectId: String): Boolean {
        if (removedFrom(agentId, projectId) == "channel") return false
        val me = agent(agentId)
        db.transaction {
            db.update(
                "INSERT INTO project_subscriptions (team_id, id, subscriber, project_id, level, after_seq, created_at, unsubscribed_at, chosen, by_owner, removed_at) " +
                    "VALUES (:team, ?, ?, ?, 'all', ?, ?, ?, 1, 1, ?) " +
                    "ON CONFLICT (subscriber, project_id) DO UPDATE SET chosen = 1, unsubscribed_at = excluded.unsubscribed_at, removed_at = excluded.removed_at",
                Ulid.next(clock()), me, projectId, now(), clock(), clock(), clock(),
            )
            db.update(
                "UPDATE thread_subscriptions SET unsubscribed_at = ? WHERE team_id = :team AND subscriber = ? AND project_id = ? AND unsubscribed_at IS NULL",
                clock(), me, projectId,
            )
        }
        return true
    }

    /** The owner adds [agentId] to a group: it hears its channels (but any it was removed from on their own). False when it already did. */
    fun ownerAddGroup(agentId: String, groupId: String): Boolean {
        val me = agent(agentId)
        val removed = removedFromGroup(agentId, groupId)
        if (!removed && hearsGroup(agentId, groupId)) return false
        db.update(
            "INSERT INTO group_subscriptions (team_id, id, subscriber, group_id, level, after_seq, created_at, by_owner) VALUES (:team, ?, ?, ?, 'all', ?, ?, 1) " +
                "ON CONFLICT (subscriber, group_id) DO UPDATE SET unsubscribed_at = NULL, removed_at = NULL, after_seq = excluded.after_seq, created_at = excluded.created_at",
            Ulid.next(clock()), me, groupId, now(), clock(),
        )
        return true
    }

    /**
     * The owner removes [agentId] from a group, and it sticks: every channel in it goes quiet for the
     * agent, including ones it followed on their own, and the threads it followed there. The owner
     * can still add it to one channel of it. False when it was removed already.
     */
    fun ownerRemoveGroup(agentId: String, groupId: String): Boolean {
        if (removedFromGroup(agentId, groupId)) return false
        val me = agent(agentId)
        val inGroup = "SELECT id FROM projects WHERE team_id = :team AND group_id = ?"
        db.transaction {
            db.update(
                "INSERT INTO group_subscriptions (team_id, id, subscriber, group_id, level, after_seq, created_at, unsubscribed_at, by_owner, removed_at) " +
                    "VALUES (:team, ?, ?, ?, 'all', ?, ?, ?, 1, ?) " +
                    "ON CONFLICT (subscriber, group_id) DO UPDATE SET unsubscribed_at = excluded.unsubscribed_at, removed_at = excluded.removed_at",
                Ulid.next(clock()), me, groupId, now(), clock(), clock(), clock(),
            )
            // Its own follows in the group give way to the group again (chosen = 0), so adding the group back brings them all.
            db.update(
                "UPDATE project_subscriptions SET unsubscribed_at = ?, chosen = 0 WHERE team_id = :team AND subscriber = ? AND removed_at IS NULL " +
                    "AND unsubscribed_at IS NULL AND project_id IN ($inGroup)",
                clock(), me, groupId,
            )
            db.update(
                "UPDATE thread_subscriptions SET unsubscribed_at = ? WHERE team_id = :team AND subscriber = ? AND unsubscribed_at IS NULL AND project_id IN ($inGroup)",
                clock(), me, groupId,
            )
        }
        return true
    }

    // ---- people -----------------------------------------------------------------------------------

    /**
     * A person's level for a channel: the channel's own, else its group's, else `all` (spec/12:
     * the most specific setting wins). [who] is `member:<membership id>` ([People.subscriber]).
     */
    fun level(who: String, projectId: String?): String = projectId?.let { channelLevel(who, it) ?: groupLevelOf(who, it) } ?: ALL

    /** The level [who] set on this channel itself, or null when it follows its group. */
    fun channelLevel(who: String, projectId: String): String? = db.one(
        "SELECT level FROM project_subscriptions WHERE team_id = :team AND subscriber = ? AND project_id = ? AND unsubscribed_at IS NULL", who, projectId,
    ) { it.getString(1) }

    /** The level [who] set on a group, or null. */
    fun groupLevel(who: String, groupId: String): String? = db.one(
        "SELECT level FROM group_subscriptions WHERE team_id = :team AND subscriber = ? AND group_id = ? AND unsubscribed_at IS NULL", who, groupId,
    ) { it.getString(1) }

    private fun groupLevelOf(who: String, projectId: String): String? = db.one(
        "SELECT gs.level FROM group_subscriptions AS gs JOIN projects AS p ON p.group_id = gs.group_id " +
            "WHERE gs.team_id = :team AND gs.subscriber = ? AND p.id = ? AND gs.unsubscribed_at IS NULL",
        who, projectId,
    ) { it.getString(1) }

    /** Set [who]'s level on a channel; null clears it, so the channel follows its group again. */
    fun setLevel(who: String, projectId: String, level: String?) {
        if (level == null) {
            db.update("UPDATE project_subscriptions SET unsubscribed_at = ? WHERE team_id = :team AND subscriber = ? AND project_id = ? AND unsubscribed_at IS NULL", clock(), who, projectId)
            return
        }
        if (level !in LEVELS) throw invalid("level must be one of: ${LEVELS.joinToString(", ")}")
        db.update(
            "INSERT INTO project_subscriptions (team_id, id, subscriber, project_id, level, after_seq, created_at, chosen) VALUES (:team, ?, ?, ?, ?, 0, ?, 1) " +
                "ON CONFLICT (subscriber, project_id) DO UPDATE SET level = excluded.level, unsubscribed_at = NULL, chosen = 1",
            Ulid.next(clock()), who, projectId, level, clock(),
        )
    }

    /** Set [who]'s level on a group, for every channel in it without its own; null clears it. */
    fun setGroupLevel(who: String, groupId: String, level: String?) {
        if (level == null) {
            db.update("UPDATE group_subscriptions SET unsubscribed_at = ? WHERE team_id = :team AND subscriber = ? AND group_id = ? AND unsubscribed_at IS NULL", clock(), who, groupId)
            return
        }
        if (level !in LEVELS) throw invalid("level must be one of: ${LEVELS.joinToString(", ")}")
        db.update(
            "INSERT INTO group_subscriptions (team_id, id, subscriber, group_id, level, after_seq, created_at) VALUES (:team, ?, ?, ?, ?, 0, ?) " +
                "ON CONFLICT (subscriber, group_id) DO UPDATE SET level = excluded.level, unsubscribed_at = NULL",
            Ulid.next(clock()), who, groupId, level, clock(),
        )
    }

    /** Follow or unfollow a thread for [who]. True when that changed anything. */
    fun setFollow(who: String, thread: Thread, projectId: String?, following: Boolean): Boolean = if (following) {
        followThread(who, thread, projectId)
    } else {
        val before = threadState(who, thread)
        if (before == null) {
            db.update(
                "INSERT INTO thread_subscriptions (team_id, id, subscriber, kind, thread_id, project_id, after_seq, created_at, unsubscribed_at) " +
                    "VALUES (:team, ?, ?, ?, ?, ?, ?, ?, ?)",
                Ulid.next(clock()), who, thread.kind, thread.id, projectId, now(), clock(), clock(),
            )
            true
        } else {
            unfollowThread(who, thread)
        }
    }

    fun choices(who: String): OwnerChoices = OwnerChoices(
        levels = db.query("SELECT project_id, level FROM project_subscriptions WHERE team_id = :team AND subscriber = ? AND unsubscribed_at IS NULL${access.notHiddenFor(who, "project_id")} ORDER BY seq", who) {
            it.getString(1) to it.getString(2)
        },
        groups = db.query("SELECT group_id, level FROM group_subscriptions WHERE team_id = :team AND subscriber = ? AND unsubscribed_at IS NULL${access.groupNotHiddenFor(who, "group_id")} ORDER BY seq", who) {
            it.getString(1) to it.getString(2)
        },
        threads = db.query("SELECT kind, thread_id, unsubscribed_at IS NULL FROM thread_subscriptions WHERE team_id = :team AND subscriber = ?${access.notHiddenFor(who, "project_id")} ORDER BY seq", who) {
            Thread(it.getString(1), it.getString(2)) to it.getBoolean(3)
        },
    )

    /**
     * Whether an alert is one [who] follows, before any phone's own preferences. [kind] is `update`
     * (a new card), `message` (a line in [thread]), `request` (a question for the team) or anything
     * else (always heard). [toThem]: the message answers them, or is in a thread they spoke in
     * before following existed. [critical] alerts are always heard: whether one pages is each
     * phone's own choice.
     *
     * - A thread they follow is heard, even in a muted channel; one they unfollowed is not.
     * - `all`: everything else. `replies`: questions, and messages [toThem] (or naming them with
     *   `@you` or `@owner`). `muted`: nothing else.
     */
    fun hears(who: String, kind: String, projectId: String?, thread: Thread?, toThem: Boolean = false, critical: Boolean = false): Boolean {
        if (critical) return true
        if (kind != "update" && kind != "message" && kind != "request") return true
        val state = thread?.let { threadState(who, it) }
        if (state == true) return true
        if (state == false) return false
        return when (level(who, projectId)) {
            MUTED -> false
            REPLIES -> kind == "request" || (kind == "message" && toThem)
            else -> true
        }
    }

    /** [hears] for a new card. */
    fun hearsUpdate(who: String, projectId: String?, priority: String?): Boolean =
        hears(who, "update", projectId, null, critical = priority == CRITICAL)

    /** [hears] for a message; [toThem] as [hears] says, and `@you` or `@owner` in it counts too. */
    fun hearsMessage(who: String, messageId: String, toThem: Boolean): Boolean {
        val (thread, projectId) = runCatching { threadAt(CardRef("message", messageId)) }.getOrNull() ?: (null to null)
        val (project, body) = db.one("SELECT project_id, body FROM messages WHERE team_id = :team AND id = ?", messageId) { it.getString(1) to it.getString(2) } ?: (null to "")
        return hears(who, "message", projectId ?: project, thread, toThem = toThem || OWNER_MENTION.containsMatchIn(body))
    }

    /** [hears] for a question waiting on the team. */
    fun hearsRequest(who: String, requestId: String, projectId: String?, priority: String?): Boolean =
        hears(who, "request", projectId, Thread("request", requestId), critical = priority == CRITICAL)

    // ---- threads ----------------------------------------------------------------------------------

    /**
     * The thread a card is in, with its project: an update's comments, a request's discussion, or
     * the feed post a message is in (the post itself, or the update or request it hangs off).
     */
    fun threadAt(card: CardRef): Pair<Thread, String?> = rawThreadAt(card).also { (_, projectId) ->
        // A thread in a channel this work's viewer can't read answers as missing (spec/15).
        if (!access.visible(projectId)) throw notFound("no such ${card.kind}: ${card.id}")
    }

    private fun rawThreadAt(card: CardRef): Pair<Thread, String?> = when (card.kind) {
        "update" -> db.one("SELECT project_id FROM updates WHERE team_id = :team AND id = ? AND deleted_at IS NULL", card.id) { Thread("update", card.id) to it.getString(1) }
            ?: throw notFound("no such update: ${card.id}")
        "request" -> db.one("SELECT project_id FROM approvals WHERE team_id = :team AND id = ?", card.id) { Thread("request", card.id) to it.getString(1) }
            ?: throw notFound("no such request: ${card.id}")
        else -> {
            val m = db.one(
                "SELECT id, update_id, request_id, reply_to, task_id, project_id FROM messages WHERE team_id = :team AND id = ? AND deleted_at IS NULL", card.id,
            ) { threadOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5)) to it.getString(6) }
                ?: throw notFound("no such message: ${card.id}")
            val thread = m.first ?: throw invalid("that message is on an old task, which has no thread to follow")
            thread to m.second
        }
    }

    /** A line to recognise a thread by: the update's title (or text), the question, or the post. */
    fun title(thread: Thread, reader: String = HUMAN): String? {
        val text = when (thread.kind) {
            // A deleted update or message gives no title: its words went with it.
            "update" -> db.one("SELECT coalesce(title, body) FROM updates WHERE team_id = :team AND id = ? AND deleted_at IS NULL", thread.id) { it.getString(1) }
            "request" -> db.one("SELECT question FROM approvals WHERE team_id = :team AND id = ?", thread.id) { it.getString(1) }
            else -> db.one("SELECT body, author FROM messages WHERE team_id = :team AND id = ? AND deleted_at IS NULL", thread.id) {
                // A message that @named other agents is theirs to read: [reader] gets no title from it.
                it.getString(1).takeIf { _ -> Mentions.readable(db, thread.id, it.getString(2), reader) }
            }
        } ?: return null
        val line = text.replace(Regex("\\s+"), " ").trim()
        return if (line.length > TITLE_MAX) line.take(TITLE_MAX).trimEnd() + "…" else line
    }

    companion object {
        private const val AGENT = "agent:"
        private const val HUMAN = "human"
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

        /**
         * The @mention audience rule (for messages aliased `m`): a message an agent wrote that names
         * agents reaches only them ([Mentions.directs]); a person's @names widen it, never narrow it.
         * An assignment line reaches only the agents it names. One parameter, the reader's agent id.
         * See [reachSql] for [oneMessage].
         */
        fun audienceSql(oneMessage: Boolean = false): String {
            val (namesNone, namesReader) = if (oneMessage) {
                "NOT EXISTS (SELECT 1 FROM message_mentions AS mm WHERE mm.team_id = m.team_id AND mm.message_id = m.id)" to
                    "EXISTS (SELECT 1 FROM message_mentions AS mm WHERE mm.team_id = m.team_id AND mm.message_id = m.id AND mm.agent_id = ?)"
            } else {
                "m.id NOT IN (SELECT mm.message_id FROM message_mentions AS mm WHERE mm.team_id = :team)" to
                    "m.id IN (SELECT mm.message_id FROM message_mentions AS mm WHERE mm.team_id = :team AND mm.agent_id = ?)"
            }
            return "(($NOT_ASSIGNMENT AND (NOT ${Mentions.directsSql()} OR $namesNone)) OR $namesReader)"
        }

        /**
         * A line saying who was assigned, or that assigned work moved (migration 074), or a line from
         * GitHub (080), reaches only the agents it names: one that names none reaches no agent at all.
         */
        private const val NOT_ASSIGNMENT = "coalesce(m.source, '') NOT IN ('${Messages.ASSIGNMENT}', '${Messages.STATUS_LINE}', '${Messages.GITHUB}')"

        /** [NAMES_READER] as a set: the messages that name the agent. */
        private const val NAMES_READER_SET = "m.id IN (SELECT mm.message_id FROM message_mentions AS mm WHERE mm.team_id = :team AND mm.agent_id = ?)"

        /**
         * [HEARS_CHANNEL] as a set, twice the subscriber: each channel's start (its chosen row, else
         * its group's, else the row from posting there; none when that row is unsubscribed), and the
         * messages after it.
         */
        private const val HEARS_CHANNEL_SET = "m.id IN (SELECT hm.id FROM messages AS hm JOIN (" +
            "SELECT p.id AS project_id, CASE " +
            "WHEN ps.chosen = 1 THEN CASE WHEN ps.unsubscribed_at IS NULL THEN ps.after_seq END " +
            "WHEN gs.id IS NOT NULL THEN CASE WHEN gs.unsubscribed_at IS NULL THEN gs.after_seq END " +
            "WHEN ps.id IS NOT NULL AND ps.unsubscribed_at IS NULL THEN ps.after_seq END AS after_seq " +
            "FROM projects AS p " +
            "LEFT JOIN project_subscriptions AS ps ON ps.team_id = p.team_id AND ps.subscriber = ? AND ps.project_id = p.id " +
            "LEFT JOIN group_subscriptions AS gs ON gs.team_id = p.team_id AND gs.subscriber = ? AND gs.group_id = p.group_id " +
            "WHERE p.team_id = :team) AS h ON h.project_id = hm.project_id " +
            "WHERE hm.team_id = :team AND hm.seq > h.after_seq)"

        /**
         * [FOLLOWS_THREAD] as a set, one branch per kind of thread so each reads its own index:
         * a request's lines, an update's, a feed post's replies, and the post itself.
         */
        private val FOLLOWS_THREAD_SET = listOf(
            "request" to "tm.request_id = ts.thread_id",
            "update" to "tm.update_id = ts.thread_id AND tm.request_id IS NULL",
            "message" to "tm.reply_to = ts.thread_id AND tm.request_id IS NULL AND tm.update_id IS NULL",
            "message" to "tm.id = ts.thread_id AND tm.reply_to IS NULL AND tm.request_id IS NULL AND tm.update_id IS NULL",
        ).joinToString(" UNION ALL ", prefix = "m.id IN (", postfix = ")") { (kind, on) ->
            "SELECT tm.id FROM thread_subscriptions AS ts JOIN messages AS tm ON tm.team_id = ts.team_id AND $on " +
                "WHERE ts.team_id = :team AND ts.subscriber = ? AND ts.unsubscribed_at IS NULL AND ts.kind = '$kind' " +
                "AND tm.task_id IS NULL AND tm.seq > ts.after_seq"
        }
        private const val THREAD_SET_BRANCHES = 4

        private const val NAMES_READER = "EXISTS (SELECT 1 FROM message_mentions AS mm WHERE mm.team_id = m.team_id AND mm.message_id = m.id AND mm.agent_id = ?)"

        /**
         * The agent hears the message's channel ([followedProjects] in SQL, five times the subscriber):
         * a channel it chose, else the channel's group, else a channel it follows from posting there.
         */
        private const val HEARS_CHANNEL = "(EXISTS (SELECT 1 FROM project_subscriptions AS ps WHERE ps.team_id = m.team_id AND ps.subscriber = ? " +
            "AND ps.project_id = m.project_id AND ps.chosen = 1 AND ps.unsubscribed_at IS NULL AND m.seq > ps.after_seq) " +
            "OR (NOT EXISTS (SELECT 1 FROM project_subscriptions AS ps WHERE ps.team_id = m.team_id AND ps.subscriber = ? AND ps.project_id = m.project_id AND ps.chosen = 1) " +
            "AND CASE WHEN EXISTS (SELECT 1 FROM group_subscriptions AS gs JOIN projects AS gp ON gp.group_id = gs.group_id " +
            "WHERE gs.team_id = m.team_id AND gs.subscriber = ? AND gp.id = m.project_id) " +
            "THEN EXISTS (SELECT 1 FROM group_subscriptions AS gs JOIN projects AS gp ON gp.group_id = gs.group_id " +
            "WHERE gs.team_id = m.team_id AND gs.subscriber = ? AND gp.id = m.project_id AND gs.unsubscribed_at IS NULL AND m.seq > gs.after_seq) " +
            "ELSE EXISTS (SELECT 1 FROM project_subscriptions AS ps WHERE ps.team_id = m.team_id AND ps.subscriber = ? " +
            "AND ps.project_id = m.project_id AND ps.unsubscribed_at IS NULL AND m.seq > ps.after_seq) END))"

        /** The same thread rule as [threadOf], in SQL. */
        private const val FOLLOWS_THREAD = "EXISTS (SELECT 1 FROM thread_subscriptions AS ts WHERE ts.team_id = m.team_id AND ts.subscriber = ? " +
            "AND ts.unsubscribed_at IS NULL AND m.seq > ts.after_seq AND m.task_id IS NULL " +
            "AND ts.kind = CASE WHEN m.request_id IS NOT NULL THEN 'request' WHEN m.update_id IS NOT NULL THEN 'update' ELSE 'message' END " +
            "AND ts.thread_id = CASE WHEN m.request_id IS NOT NULL THEN m.request_id WHEN m.update_id IS NOT NULL THEN m.update_id " +
            "ELSE coalesce(m.reply_to, m.id) END)"
    }
}
