package dev.wildware.agents.app.desktop

import dev.wildware.agents.AlertPrefs
import dev.wildware.agents.FeedCard
import dev.wildware.agents.FounderId
import dev.wildware.agents.FeedState
import dev.wildware.agents.Follows
import dev.wildware.agents.NotifyLevel
import dev.wildware.agents.MessageView
import dev.wildware.agents.PairingView
import dev.wildware.agents.PlainText
import dev.wildware.agents.PostCard
import dev.wildware.agents.RequestCard
import dev.wildware.agents.ThreadLine
import dev.wildware.agents.UpdateCard
import dev.wildware.agents.app.feed.TeamState
import dev.wildware.agents.app.sound.Sound

/**
 * One notification (desktop, or Android while the app runs). [focusId] is the card to open the
 * app at when it is clicked, or null for none.
 */
data class DesktopNotice(
    val key: String,
    val title: String,
    val body: String,
    val focusId: String?,
    /** Marked critical and allowed to page this device: Android's "Critical" channel, through Do Not Disturb. */
    val critical: Boolean = false,
    /** The sound it goes with ([Sound]): its kind's, or [Sound.Critical] for anything marked critical. Null for the app's own notices. */
    val sound: Sound? = null,
)

/**
 * Which changes deserve a desktop notification, following the phone's push rules:
 *
 * - every new question an agent asks;
 * - an agent's message that answers the owner: it answers one of the owner's lines, sits under
 *   the owner's post, or lands in an update, task or question thread the owner has written in
 *   (the server's own "replied to you" rule);
 * - an agent's message that mentions the owner: `@you` or `@owner`;
 * - on a team (migration 055), another person's message by the same rules as an agent's, with
 *   `@their-handle` counting as a mention; what this person wrote themselves never notifies them.
 *   "The owner" above is this device's person: a line a teammate wrote is theirs, not this person's.
 * - an update an agent marked critical (other updates stay in the feed and under the bell);
 * - an agent asking to join, whatever the device's choice of alerts: it waits on the owner.
 *
 * What the owner follows comes first ([Follows]): a thread they unfollowed is quiet, one they chose to
 * follow announces every agent's reply, and a muted project announces only what is critical.
 *
 * Something marked critical is flagged [DesktopNotice.critical] where the device's choice lets it
 * page ([AlertPrefs.pages]).
 *
 * The title is the agent and the project, the body plain text cut to [maxBody] characters.
 * Nothing that was already there when the dashboard was first read is announced: the first
 * [next] call with `ready` true only takes note. Messages older than the newest one seen then
 * (a page of history being loaded) are never announced either.
 */
class NotificationRules(private val maxBody: Int = 140) {
    private var baselined = false
    private val seen = HashSet<String>()
    private var watermark = Long.MIN_VALUE

    /** What changed since the last call that deserves a notice. [prefs] is the device's choice of alerts; null is all. */
    fun next(
        ready: Boolean,
        waiting: List<RequestCard>,
        feed: FeedState,
        prefs: AlertPrefs? = null,
        /** A project's slug by its id, for a device that lets only some projects page it. */
        slugOf: (String?) -> String? = { null },
        /** Agents asking to join. */
        joining: List<PairingView> = emptyList(),
        /** What the owner follows. */
        follows: Follows = Follows(),
        /** Who this device's person is, and the team's names. A team of one: every person's line is theirs. */
        team: TeamState = TeamState(),
    ): List<DesktopNotice> {
        if (!ready) return emptyList()
        val me = Me(team)
        val threads = threadsOf(feed, waiting, me)
        if (!baselined) {
            baselined = true
            waiting.forEach { seen += requestKey(it.id) }
            joining.forEach { seen += pairingKey(it.id) }
            feed.allCards.filterIsInstance<UpdateCard>().forEach { seen += updateKey(it.id) }
            threads.forEach { thread -> thread.lines.forEach { seen += messageKey(it.message.id) } }
            watermark = threads.flatMap { it.lines }.maxOfOrNull { it.message.createdAt } ?: Long.MIN_VALUE
            return emptyList()
        }

        val notices = mutableListOf<DesktopNotice>()
        for (pairing in joining) {
            if (!seen.add(pairingKey(pairing.id))) continue
            val from = pairing.host?.let { "From $it. " }.orEmpty()
            notices += DesktopNotice(pairingKey(pairing.id), "${pairing.name} wants to join", "${from}Code ${pairing.code}. Allow or deny it in the app.", null, sound = Sound.Question)
        }
        for (card in waiting) {
            if (!seen.add(requestKey(card.id))) continue
            val request = card.request
            val slug = slugOf(request.projectId)
            val chosen = follows.chosen("request", card.id)
            val muted = follows.level(request.projectId) == NotifyLevel.Muted
            if (request.priority != AlertPrefs.Critical && (chosen == false || (chosen == null && muted))) continue
            if (!AlertPrefs.allows(prefs, AlertPrefs.Request, priority = request.priority, agentId = request.agentId, projectSlug = slug)) continue
            val page = AlertPrefs.pages(prefs, request.priority, request.agentId, slug)
            notices += DesktopNotice(requestKey(card.id), title(card.agentName, card.projectName), plain(request.question), card.id, critical = page, sound = if (request.priority == AlertPrefs.Critical) Sound.Critical else Sound.Question)
        }
        for (card in feed.allCards.filterIsInstance<UpdateCard>()) {
            if (!seen.add(updateKey(card.id))) continue
            val update = card.update
            if (update.priority != AlertPrefs.Critical) continue
            val slug = slugOf(update.projectId)
            if (!AlertPrefs.allows(prefs, AlertPrefs.Update, update.level, update.priority, update.agentId, slug)) continue
            val text = update.title?.takeIf { it.isNotBlank() } ?: update.body
            notices += DesktopNotice(updateKey(card.id), title(card.agentName, card.projectName), plain(text), card.id, critical = AlertPrefs.pages(prefs, update.priority, update.agentId, slug), sound = Sound.Critical)
        }
        for (thread in threads) {
            for (line in thread.lines) {
                val message = line.message
                if (!seen.add(messageKey(message.id))) continue
                if (message.createdAt < watermark) continue
                // Your own words never tell you about themselves; a teammate's do, as an agent's would.
                if (me.wrote(message)) continue
                if (message.agentId == null && !message.isOwner) continue
                val chosen = follows.chosen(thread.kind, thread.cardId)
                if (chosen == false) continue
                if (chosen == null && follows.level(thread.projectId) == NotifyLevel.Muted) continue
                if (chosen == null && !answersMe(message, thread, me) && !mentionsOwner(message.body) && !me.named(message.body)) continue
                if (!AlertPrefs.allows(prefs, AlertPrefs.Message)) continue
                val author = if (message.agentId != null) line.authorName else me.nameOf(message.personId)
                notices += DesktopNotice(messageKey(message.id), title(author, thread.projectName), plain(message.body), thread.cardId, sound = Sound.Reply)
            }
        }
        watermark = maxOf(watermark, threads.flatMap { it.lines }.maxOfOrNull { it.message.createdAt } ?: Long.MIN_VALUE)
        return notices
    }

    /** One card's conversation: the card's own post line (if a post) and its replies. */
    private class Thread(
        val cardId: String,
        val projectName: String?,
        val head: MessageView?,
        val lines: List<ThreadLine>,
        /** This device's person wrote in it. */
        val meWroteHere: Boolean,
        /** The thread's kind and project, as the owner's [Follows] know it. */
        val kind: String,
        val projectId: String?,
    )

    private fun threadsOf(feed: FeedState, waiting: List<RequestCard>, me: Me): List<Thread> {
        val fromCards = feed.allCards.map { card -> cardThread(card, me) }
        val fromQuestions = (waiting + feed.waiting).distinctBy { it.id }.map { question ->
            Thread(question.id, question.projectName, null, question.discussion, question.discussion.any { me.wrote(it.message) }, "request", question.request.projectId)
        }
        return fromCards + fromQuestions
    }

    private fun cardThread(card: FeedCard, me: Me): Thread = when (card) {
        is PostCard -> Thread(
            card.id,
            card.projectName,
            card.post.message,
            listOf(card.post) + card.replies,
            me.wrote(card.post.message),
            "message",
            card.projectId,
        )
        is UpdateCard -> Thread(card.id, card.projectName, null, card.replies, card.replies.any { me.wrote(it.message) }, "update", card.projectId)
    }

    /** The server's "replied to you", for this device's person: it answers their line, or sits in a thread they wrote in. */
    private fun answersMe(message: MessageView, thread: Thread, me: Me): Boolean {
        val answered = message.answers?.let { id -> thread.lines.firstOrNull { it.message.id == id }?.message ?: thread.head?.takeIf { it.id == id } }
        if (answered != null) return me.wrote(answered)
        // The post itself is never an answer; a reply under their post is.
        if (thread.head?.id == message.id) return false
        return thread.meWroteHere
    }

    /** This device's person, as far as the lines they read can tell (migration 055). */
    private class Me(private val team: TeamState) {
        private val id = team.me?.id ?: FounderId
        private val handle = team.me?.handle?.takeIf { team.team && it.isNotBlank() }
        private val mention = handle?.let { Regex("(?<![\\w@])@${Regex.escape(it)}(?![\\w-])", RegexOption.IGNORE_CASE) }

        /** A person's line that is theirs: every person's line, on a team of one. */
        fun wrote(message: MessageView): Boolean = message.isOwner && (!team.team || message.personId == id)

        /** The line names them by their handle. */
        fun named(body: String): Boolean = mention?.containsMatchIn(body) == true

        fun nameOf(personId: String?): String = team.people.firstOrNull { it.id == personId }?.name ?: "Someone"
    }

    private fun title(agent: String, project: String?) = if (project.isNullOrBlank()) agent else "$agent · $project"

    private fun plain(markdown: String): String {
        val text = PlainText.fromMarkdown(markdown).replace(Regex("[ \t]+"), " ").trim()
        return if (text.length <= maxBody) text else text.take(maxBody).trimEnd() + "…"
    }

    companion object {
        private val OwnerMention = Regex("(?<![\\w@])@(you|owner)\\b", RegexOption.IGNORE_CASE)

        fun mentionsOwner(body: String): Boolean = OwnerMention.containsMatchIn(body)

        private fun requestKey(id: String) = "request-$id"
        private fun pairingKey(id: String) = "pairing-$id"
        private fun messageKey(id: String) = "message-$id"
        private fun updateKey(id: String) = "update-$id"
    }
}
