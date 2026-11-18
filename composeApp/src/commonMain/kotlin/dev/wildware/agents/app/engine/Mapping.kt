package dev.wildware.agents.app.engine

import dev.wildware.agents.FeedCard
import dev.wildware.agents.LiveAgentView
import dev.wildware.agents.MediaView
import dev.wildware.agents.Outbox
import dev.wildware.agents.OutboxItem
import dev.wildware.agents.PlainText
import dev.wildware.agents.PostCard
import dev.wildware.agents.ProjectSummary
import dev.wildware.agents.ReactionView
import dev.wildware.agents.RequestCard
import dev.wildware.agents.RequestKind
import dev.wildware.agents.StreamStatus
import dev.wildware.agents.ThreadLine
import dev.wildware.agents.UpdateCard
import dev.wildware.agents.app.feed.Acks
import dev.wildware.agents.app.feed.Agent
import dev.wildware.agents.app.feed.Attachment
import dev.wildware.agents.app.feed.Author
import dev.wildware.agents.app.feed.Choice
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedSection
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.Level
import dev.wildware.agents.app.feed.LinkStatus
import dev.wildware.agents.app.feed.OutboxSummary
import dev.wildware.agents.app.feed.Presence
import dev.wildware.agents.app.feed.Project
import dev.wildware.agents.app.feed.QuestionKind
import dev.wildware.agents.app.feed.Reaction
import dev.wildware.agents.app.feed.Reply
import dev.wildware.agents.app.feed.WorkStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import dev.wildware.agents.FeedState as EngineFeed

/** The owner, as the screen shows them. The dashboard has no name for its owner. */
internal val OwnerAuthor = Author.Owner("You")

/**
 * Turns what the engine knows into what the feed screen draws. Pure: the same inputs always give
 * the same screen, so it is tested without a server or a screen.
 */
@OptIn(ExperimentalTime::class)
internal class FeedMapper(
    private val serverUrl: String,
    private val now: () -> Long,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    fun map(
        feed: EngineFeed,
        projects: List<ProjectSummary>,
        online: List<LiveAgentView>,
        outbox: List<OutboxItem>,
        selectedSlug: String?,
    ): FeedState {
        val onlineIds = online.mapTo(HashSet()) { it.agentId }
        val names = agentNames(feed) + online.associate { it.agentId to it.name }
        val agents = AgentDirectory(names, online.associateBy { it.agentId }, onlineIds)
        val viewProjects = projects.map { it.toView() }
        val byId = viewProjects.associateBy { it.id }
        val current = when (selectedSlug) {
            null -> Project("all", "All projects", unread = projects.sumOf { it.unread }, waiting = projects.sumOf { it.waiting }, hue = 0)
            else -> viewProjects.firstOrNull { it.slug == selectedSlug }
                ?: Project(selectedSlug, feed.project?.name ?: selectedSlug, unread = 0, hue = hue(selectedSlug), slug = selectedSlug)
        }
        fun project(id: String?) = id?.let { byId[it] } ?: current

        val sections = buildList {
            val waiting = feed.waiting.map { it.toQuestion(agents, ::project) }
            if (waiting.isNotEmpty()) add(FeedSection("Waiting on you", waiting))
            val pinned = feed.pinned.mapNotNull { it.toItem(agents, ::project) }
            if (pinned.isNotEmpty()) add(FeedSection("Pinned", pinned))
            feed.cards
                .groupBy { dayOf(it.activityAt) }
                .forEach { (day, cards) -> add(FeedSection(dayLabel(day), cards.mapNotNull { it.toItem(agents, ::project) })) }
        }
        val waiting = Outbox.waiting(outbox)
        val failed = Outbox.failed(outbox)
        return FeedState(
            owner = OwnerAuthor,
            projects = viewProjects,
            agents = online.map { agents.agent(it.agentId) },
            current = current,
            sections = sections,
            openThread = null,
            totalUnread = projects.sumOf { it.unread },
            link = feed.status.toLink(),
            outbox = OutboxSummary(
                waiting = waiting.size,
                failed = failed.size,
                failedId = failed.firstOrNull()?.id,
                failedMessage = failed.firstOrNull()?.lastError,
            ),
            loaded = feed.loaded,
            loading = feed.loading,
            hasMore = feed.hasMore,
        )
    }

    private fun agentNames(feed: EngineFeed): Map<String, String> = buildMap {
        feed.allCards.forEach { card ->
            if (card is UpdateCard) put(card.update.agentId, card.agentName)
            val lines = card.replies + ((card as? PostCard)?.post?.let(::listOf) ?: emptyList())
            lines.forEach { line -> line.message.agentId?.let { put(it, line.authorName) } }
        }
        feed.waiting.forEach { put(it.request.agentId, it.agentName) }
    }

    private fun ProjectSummary.toView() =
        Project(project.id, project.name, unread = unread, waiting = waiting, hue = hue(project.id), slug = project.slug)

    private fun FeedCard.toItem(agents: AgentDirectory, project: (String?) -> Project): FeedItem? = when (this) {
        is UpdateCard -> {
            val (title, body) = titleAndBody(update.title, update.body)
            FeedItem.Update(
                id = id,
                time = timeLabel(createdAt),
                agent = agents.agent(update.agentId),
                project = project(update.projectId),
                level = level(update.level),
                title = title,
                body = body,
                status = workStatus(update.status),
                attachment = update.media.firstImage()?.let { image(it) },
                reactions = reactions(reactions),
                replies = replies(replies, unread, agents),
                unreadReplies = unread,
                tags = tags.map { it.name },
            )
        }
        is PostCard -> FeedItem.Post(
            id = id,
            time = timeLabel(createdAt),
            author = author(post, agents),
            project = project(projectId),
            body = post.message.body,
            acks = acks(this, agents),
            replies = replies(replies, unread, agents),
            unreadReplies = unread,
            reactions = reactions(reactions),
        )
    }

    private fun RequestCard.toQuestion(agents: AgentDirectory, project: (String?) -> Project): FeedItem.Question {
        val options = request.options.orEmpty()
        // A picture question carries one picture per option; the engine says which is which.
        fun choice(option: String, primary: Boolean = false) =
            Choice(option, primary, image = request.pictureFor(option)?.takeIf { it.isReady }?.let { image(it).copy(altText = option) })
        val (kind, choices) = when (request.requestKind) {
            RequestKind.Buttons -> QuestionKind.Buttons to options.mapIndexed { i, o -> choice(o, primary = i == 0) }
            RequestKind.Confirm -> QuestionKind.Buttons to listOf(Choice(Yes, primary = true), Choice(No))
            RequestKind.Choice -> QuestionKind.Choice to options.map { choice(it) }
            RequestKind.MultiChoice -> QuestionKind.MultiChoice to options.map { choice(it) }
            RequestKind.Text -> QuestionKind.Text to emptyList()
            RequestKind.Form, null -> QuestionKind.Unsupported to emptyList()
        }
        return FeedItem.Question(
            id = id,
            time = "",
            agent = agents.agent(request.agentId),
            project = project(request.projectId),
            prompt = request.question,
            body = request.detail.orEmpty(),
            choices = choices,
            allowsText = kind == QuestionKind.Text,
            expiresIn = expiresIn(request.expiresAt),
            kind = kind,
            sending = sending,
            // Only the question's own pictures: those on options show in the option grid instead.
            attachment = request.cardMedia.firstImage()?.let { image(it) },
        )
    }

    private fun replies(lines: List<ThreadLine>, unread: Int, agents: AgentDirectory): List<Reply> {
        // The engine counts unread comments per card; they are the newest ones not written by the owner.
        val unreadIds = lines.filter { !it.message.isOwner }.takeLast(unread).mapTo(HashSet()) { it.message.id }
        return lines.map { line ->
            Reply(
                id = line.message.id,
                author = author(line, agents),
                time = timeLabel(line.message.createdAt),
                body = line.message.body,
                reactions = reactions(line.reactions),
                unread = line.message.id in unreadIds,
            )
        }
    }

    private fun author(line: ThreadLine, agents: AgentDirectory): Author =
        line.message.agentId?.let { Author.AgentAuthor(agents.agent(it, line.authorName)) } ?: OwnerAuthor

    private fun acks(post: PostCard, agents: AgentDirectory): Acks {
        val acks = post.post.acks
        return Acks(
            read = acks.filter { it.state == "read" || it.state == "done" }.map { agents.agent(it.agentId) }.distinct(),
            thinking = acks.filter { it.state == "thinking" }.map { agents.agent(it.agentId) }.distinct(),
        )
    }

    private fun reactions(views: List<ReactionView>): List<Reaction> =
        views.groupBy { it.emoji }.map { (emoji, all) -> Reaction(emoji, all.size, mine = all.any { it.isOwner }, spoken = emoji) }

    private fun List<MediaView>.firstImage() = firstOrNull { it.kind == "image" && it.isReady }

    private fun image(media: MediaView): Attachment {
        val variant = if ("thumb-1600" in media.variants) "thumb-1600" else "original"
        val w = media.width
        val h = media.height
        val aspect = if (w != null && h != null && w > 0 && h > 0) (w.toFloat() / h).coerceIn(0.5f, 3f) else 16f / 9f
        return Attachment(
            title = media.filename ?: "Image",
            altText = media.filename ?: "Attached image",
            aspect = aspect,
            url = serverUrl.trimEnd('/') + "/" + media.path(variant),
        )
    }

    /** An update with no title takes its first line as one, so every card has a headline. */
    private fun titleAndBody(title: String?, body: String): Pair<String, String> {
        if (!title.isNullOrBlank()) return title to body
        val lines = body.trim().lines()
        val first = lines.firstOrNull { it.isNotBlank() } ?: return "Update" to ""
        val rest = lines.dropWhile { it != first }.drop(1).joinToString("\n").trim()
        return PlainText.clip(PlainText.fromMarkdown(first), 140) to rest
    }

    fun timeLabel(millis: Long): String {
        val then = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
        val today = dayOf(now())
        return when (val day = then.date) {
            today -> "${then.hour.toString().padStart(2, '0')}:${then.minute.toString().padStart(2, '0')}"
            else -> dayLabel(day).let { if (it == "Today") "" else it }
        }
    }

    private fun dayOf(millis: Long): LocalDate = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date

    fun dayLabel(day: LocalDate): String {
        val today = dayOf(now())
        val daysAgo = today.toEpochDays() - day.toEpochDays()
        return when {
            daysAgo == 0L -> "Today"
            daysAgo == 1L -> "Yesterday"
            daysAgo in 2..6 -> day.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
            else -> "${day.day} ${day.month.name.lowercase().replaceFirstChar { it.uppercase() }}"
        }
    }

    /** "42 min", "3 h", "2 days": how long a question waits before it times out. */
    fun expiresIn(expiresAt: Long): String {
        if (expiresAt <= 0) return ""
        val minutes = ((expiresAt - now()) / 60_000).coerceAtLeast(0)
        return when {
            minutes < 1 -> "under a minute"
            minutes < 60 -> "$minutes min"
            minutes < 48 * 60 -> "${minutes / 60} h"
            else -> "${minutes / (60 * 24)} days"
        }
    }

    private class AgentDirectory(
        private val names: Map<String, String>,
        private val online: Map<String, LiveAgentView>,
        private val onlineIds: Set<String>,
    ) {
        fun agent(id: String, fallbackName: String? = null): Agent {
            val live = online[id]
            return Agent(
                id = id,
                name = names[id] ?: fallbackName ?: "An agent",
                role = "Agent",
                presence = if (id in onlineIds) Presence.Online else Presence.Offline,
                hue = hue(id),
                doing = live?.host?.let { "On $it" },
            )
        }
    }

    companion object {
        const val Yes = "Yes"
        const val No = "No"
    }
}

/** A colour slot that stays the same for the same id on every device. */
internal fun hue(id: String): Int = abs(id.fold(7) { acc, c -> acc * 31 + c.code } % 6)

internal fun level(wire: String): Level = when (wire) {
    "success" -> Level.Success
    "warn", "warning" -> Level.Warning
    "error" -> Level.Error
    else -> Level.Info
}

internal fun workStatus(wire: String?): WorkStatus? = when (wire) {
    "todo" -> WorkStatus.Todo
    "in_progress" -> WorkStatus.InProgress
    "blocked" -> WorkStatus.Blocked
    "done" -> WorkStatus.Done
    "cancelled" -> WorkStatus.Cancelled
    else -> null
}

internal fun StreamStatus.toLink(): LinkStatus = when (this) {
    StreamStatus.Live -> LinkStatus.Live
    StreamStatus.Connecting -> LinkStatus.Connecting
    StreamStatus.Offline -> LinkStatus.Reconnecting
    StreamStatus.Idle, StreamStatus.SignedOut -> LinkStatus.Offline
}
