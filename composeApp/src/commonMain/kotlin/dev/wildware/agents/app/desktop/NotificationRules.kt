package dev.wildware.agents.app.desktop

import dev.wildware.agents.AlertPrefs
import dev.wildware.agents.FeedCard
import dev.wildware.agents.FeedState
import dev.wildware.agents.MessageView
import dev.wildware.agents.PlainText
import dev.wildware.agents.PostCard
import dev.wildware.agents.RequestCard
import dev.wildware.agents.ThreadLine
import dev.wildware.agents.UpdateCard

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
)

/**
 * Which changes deserve a desktop notification, following the phone's push rules:
 *
 * - every new question an agent asks;
 * - an agent's message that answers the owner: it answers one of the owner's lines, sits under
 *   the owner's post, or lands in an update, task or question thread the owner has written in
 *   (the server's own "replied to you" rule);
 * - an agent's message that mentions the owner: `@you` or `@owner`;
 * - an update an agent marked critical (other updates stay in the feed and under the bell).
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
    ): List<DesktopNotice> {
        if (!ready) return emptyList()
        val threads = threadsOf(feed, waiting)
        if (!baselined) {
            baselined = true
            waiting.forEach { seen += requestKey(it.id) }
            feed.allCards.filterIsInstance<UpdateCard>().forEach { seen += updateKey(it.id) }
            threads.forEach { thread -> thread.lines.forEach { seen += messageKey(it.message.id) } }
            watermark = threads.flatMap { it.lines }.maxOfOrNull { it.message.createdAt } ?: Long.MIN_VALUE
            return emptyList()
        }

        val notices = mutableListOf<DesktopNotice>()
        for (card in waiting) {
            if (!seen.add(requestKey(card.id))) continue
            val request = card.request
            val slug = slugOf(request.projectId)
            if (!AlertPrefs.allows(prefs, AlertPrefs.Request, priority = request.priority, agentId = request.agentId, projectSlug = slug)) continue
            val page = AlertPrefs.pages(prefs, request.priority, request.agentId, slug)
            notices += DesktopNotice(requestKey(card.id), title(card.agentName, card.projectName), plain(request.question), card.id, critical = page)
        }
        for (card in feed.allCards.filterIsInstance<UpdateCard>()) {
            if (!seen.add(updateKey(card.id))) continue
            val update = card.update
            if (update.priority != AlertPrefs.Critical) continue
            val slug = slugOf(update.projectId)
            if (!AlertPrefs.allows(prefs, AlertPrefs.Update, update.level, update.priority, update.agentId, slug)) continue
            val text = update.title?.takeIf { it.isNotBlank() } ?: update.body
            notices += DesktopNotice(updateKey(card.id), title(card.agentName, card.projectName), plain(text), card.id, critical = AlertPrefs.pages(prefs, update.priority, update.agentId, slug))
        }
        for (thread in threads) {
            for (line in thread.lines) {
                val message = line.message
                if (!seen.add(messageKey(message.id))) continue
                if (message.createdAt < watermark) continue
                if (message.agentId == null) continue
                if (!answersOwner(message, thread) && !mentionsOwner(message.body)) continue
                if (!AlertPrefs.allows(prefs, AlertPrefs.Message)) continue
                notices += DesktopNotice(messageKey(message.id), title(line.authorName, thread.projectName), plain(message.body), thread.cardId)
            }
        }
        watermark = maxOf(watermark, threads.flatMap { it.lines }.maxOfOrNull { it.message.createdAt } ?: Long.MIN_VALUE)
        return notices
    }

    /** One card's conversation: the card's own post line (if a post) and its replies. */
    private class Thread(val cardId: String, val projectName: String?, val head: MessageView?, val lines: List<ThreadLine>, val ownerWroteHere: Boolean)

    private fun threadsOf(feed: FeedState, waiting: List<RequestCard>): List<Thread> {
        val fromCards = feed.allCards.map { card -> cardThread(card) }
        val fromQuestions = (waiting + feed.waiting).distinctBy { it.id }.map { question ->
            Thread(question.id, question.projectName, null, question.discussion, question.discussion.any { it.message.isOwner })
        }
        return fromCards + fromQuestions
    }

    private fun cardThread(card: FeedCard): Thread = when (card) {
        is PostCard -> Thread(
            card.id,
            card.projectName,
            card.post.message,
            listOf(card.post) + card.replies,
            card.post.message.isOwner,
        )
        is UpdateCard -> Thread(card.id, card.projectName, null, card.replies, card.replies.any { it.message.isOwner })
    }

    private fun answersOwner(message: MessageView, thread: Thread): Boolean {
        val answered = message.answers?.let { id -> thread.lines.firstOrNull { it.message.id == id }?.message ?: thread.head?.takeIf { it.id == id } }
        if (answered != null) return answered.isOwner
        // The post itself is never an answer; a reply under the owner's post is.
        if (thread.head?.id == message.id) return false
        return thread.ownerWroteHere
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
        private fun messageKey(id: String) = "message-$id"
        private fun updateKey(id: String) = "update-$id"
    }
}
