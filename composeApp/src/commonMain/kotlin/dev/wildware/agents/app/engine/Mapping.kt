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
import dev.wildware.agents.app.feed.AgentDetails
import dev.wildware.agents.NotificationList
import dev.wildware.agents.app.feed.Bell
import dev.wildware.agents.app.feed.BellItem
import dev.wildware.agents.app.feed.VideoSource
import dev.wildware.agents.app.feed.Sound
import dev.wildware.agents.app.feed.MediaLabels
import dev.wildware.agents.app.feed.FileAttachment
import dev.wildware.agents.app.feed.Acks
import dev.wildware.agents.app.feed.Agent
import dev.wildware.agents.app.feed.Attachment
import dev.wildware.agents.app.feed.Author
import dev.wildware.agents.app.feed.CardTag
import dev.wildware.agents.app.feed.CardField
import dev.wildware.agents.app.feed.Choice
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedSection
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.Level
import dev.wildware.agents.app.feed.LinkStatus
import dev.wildware.agents.app.feed.OutboxSummary
import dev.wildware.agents.app.feed.Presence
import dev.wildware.agents.app.feed.Project
import dev.wildware.agents.app.feed.FormField
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
/** How long the owner's line may sit with no agent before it says so: longer than any live round trip. */
internal const val UndeliveredAfterMillis = 15_000L

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
        /**
         * Every question waiting on the owner, wherever it was asked, longest waiting first. The
         * engine's feed leaves out questions asked inside a thread; they belong in Waiting on you too.
         */
        everyQuestion: List<RequestCard>? = null,
    ): FeedState {
        val onlineIds = online.mapTo(HashSet()) { it.agentId }
        val names = agentNames(feed) + online.associate { it.agentId to it.name }
        val agents = AgentDirectory(names, online.associateBy { it.agentId }, onlineIds, now())
        val viewProjects = projects.map { it.toView() }
        val byId = viewProjects.associateBy { it.id }
        val current = when (selectedSlug) {
            null -> Project("all", "All projects", unread = projects.sumOf { it.unread }, waiting = projects.sumOf { it.waiting }, hue = 0)
            else -> viewProjects.firstOrNull { it.slug == selectedSlug }
                ?: Project(selectedSlug, feed.project?.name ?: selectedSlug, unread = 0, hue = hue(selectedSlug), slug = selectedSlug)
        }
        fun project(id: String?) = id?.let { byId[it] } ?: current

        val threads = ThreadIndex(feed)
        val inScope = when {
            everyQuestion == null -> feed.waiting
            selectedSlug == null -> everyQuestion
            else -> everyQuestion.filter { it.request.projectId == current.id }
        }
        val sections = buildList {
            val waiting = inScope.map { it.toQuestion(agents, ::project, threads) }
            if (waiting.isNotEmpty()) add(FeedSection("Waiting on you", waiting))
            val pinned = feed.pinned.mapNotNull { it.toItem(agents, ::project, threads) }
            if (pinned.isNotEmpty()) add(FeedSection("Pinned", pinned))
            feed.cards
                .groupBy { dayOf(it.activityAt) }
                .forEach { (day, cards) -> add(FeedSection(dayLabel(day), cards.mapNotNull { it.toItem(agents, ::project, threads) })) }
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
            // Cards kept from last time are shown as loaded: the read that replaces them follows.
            loaded = feed.loaded || feed.cached,
            loading = feed.loading,
            hasMore = feed.hasMore,
            tags = feed.tags.map { it.toCardTag() },
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

    private fun dev.wildware.agents.TagView.toCardTag() = CardTag(id, name, colour, projectId)

    private fun ProjectSummary.toView() =
        Project(project.id, project.name, unread = unread, waiting = waiting, hue = hue(project.id), slug = project.slug, archived = project.isArchived)

    /** Where each thread line is, so a question asked under one can say what it answers and jump there. */
    private class ThreadIndex(feed: EngineFeed) {
        private val lines = HashMap<String, Pair<String, ThreadLine>>()

        init {
            feed.allCards.forEach { card ->
                val own = (card as? PostCard)?.post?.let(::listOf).orEmpty()
                (own + card.replies).forEach { lines[it.message.id] = card.id to it }
            }
        }

        /** The card a message is in, and the line itself. */
        fun find(messageId: String): Pair<String, ThreadLine>? = lines[messageId]
    }

    private fun FeedCard.toItem(agents: AgentDirectory, project: (String?) -> Project, threads: ThreadIndex): FeedItem? = when (this) {
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
                attachments = update.media.tiles().map { tile(it) },
                sounds = update.media.sounds(),
                files = update.media.files(),
                reactions = reactions(reactions),
                replies = replies(replies, unread, agents, project, threads),
                unreadReplies = unread,
                tags = tags.map { it.toCardTag() },
                fields = fields.map { CardField(it.name, it.value) },
                high = update.priority == "high",
                pinned = pinned,
            )
        }
        is PostCard -> FeedItem.Post(
            id = id,
            time = timeLabel(createdAt),
            author = author(post, agents),
            project = project(projectId),
            body = post.message.body,
            attachments = post.media.tiles().map { tile(it) },
            sounds = post.media.sounds(),
            files = post.media.files(),
            acks = acks(this, agents),
            replies = replies(replies, unread, agents, project, threads),
            unreadReplies = unread,
            tags = tags.map { it.toCardTag() },
            fields = fields.map { CardField(it.name, it.value) },
            reactions = reactions(reactions),
            sending = post.sending,
            questions = post.questions.map { it.toQuestion(agents, project, threads) },
            status = workStatus(status),
            pinned = pinned,
            // Work says where it has got to, which answers "has anybody got this" better.
            undelivered = post.message.status == null && undelivered(post),
        )
    }

    private fun RequestCard.toQuestion(agents: AgentDirectory, project: (String?) -> Project, threads: ThreadIndex): FeedItem.Question {
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
            RequestKind.Form -> QuestionKind.Form to emptyList()
            null -> QuestionKind.Unsupported to emptyList()
        }
        val config = request.config
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
            expiresAt = request.expiresAt.takeIf { it > 0 },
            kind = kind,
            sending = sending,
            // Only the question's own pictures: those on options show in the option grid instead.
            attachments = request.cardMedia.tiles().map { tile(it) },
            sounds = request.cardMedia.sounds(),
            files = request.cardMedia.files(),
            form = if (kind == QuestionKind.Form) {
                FormField(
                    label = config?.label?.takeIf { it.isNotBlank() },
                    placeholder = config?.placeholder.orEmpty(),
                    initial = config?.defaultValue.orEmpty(),
                    multiline = config?.multiline == true,
                    min = config?.min ?: 0,
                    max = config?.max,
                    actions = options,
                )
            } else {
                null
            },
            inReplyTo = request.messageId?.let { id ->
                threads.find(id)?.second?.let { "${it.authorName}: ${PlainText.clip(PlainText.fromMarkdown(it.message.body), 80)}" }
                    ?: "Asked in a thread"
            },
            threadId = request.messageId?.let { threads.find(it)?.first } ?: request.updateId,
        )
    }

    private fun replies(
        lines: List<ThreadLine>,
        unread: Int,
        agents: AgentDirectory,
        project: (String?) -> Project,
        threads: ThreadIndex,
    ): List<Reply> {
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
                sending = line.sending,
                questions = line.questions.map { it.toQuestion(agents, project, threads) },
                undelivered = undelivered(line),
                attachments = line.media.tiles().map { tile(it) },
                sounds = line.media.sounds(),
                files = line.media.files(),
            )
        }
    }

    /**
     * The owner's line reached no agent: nothing read, acknowledged or was handed it, and long
     * enough has passed ([UndeliveredAfterMillis]) that it isn't still on its way. Lines in the
     * outbox aren't sent yet, so they aren't asked.
     */
    private fun undelivered(line: ThreadLine): Boolean =
        line.message.isOwner && !line.sending && line.acks.isEmpty() && line.deliveries.isEmpty() &&
            now() - line.message.createdAt >= UndeliveredAfterMillis

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

    private fun List<MediaView>.images() = filter { it.kind == "image" && it.isReady }

    /** Pictures and videos share the grid, as on the website; what isn't ready yet stays hidden. */
    private fun List<MediaView>.tiles() = filter { (it.kind == "image" || it.kind == "video") && it.isReady && it.variants.isNotEmpty() }

    private fun url(media: MediaView, variant: String) = serverUrl.trimEnd('/') + "/" + media.path(variant)

    private fun tile(media: MediaView): Attachment {
        if (media.kind != "video") return image(media)
        val w = media.width
        val h = media.height
        val aspect = if (w != null && h != null && w > 0 && h > 0) (w.toFloat() / h).coerceIn(0.5f, 3f) else 16f / 9f
        val transcoded = "video" in media.variants
        return Attachment(
            title = media.filename ?: "Video",
            altText = media.filename ?: "Attached video",
            aspect = aspect,
            url = if ("poster" in media.variants) url(media, "poster") else null,
            video = VideoSource(
                url = url(media, if (transcoded) "video" else "original"),
                duration = MediaLabels.duration(media.durationMs),
                mime = if (transcoded) "video/mp4" else media.mime,
            ),
        )
    }

    private fun List<MediaView>.sounds() = filter { it.kind == "audio" && it.isReady && "original" in it.variants }
        .map { Sound(it.id, url(it, "original"), it.durationMs, it.mime) }

    private fun List<MediaView>.files() = filter { it.kind == "file" && it.isReady && "original" in it.variants }
        .map { FileAttachment(it.id, url(it, "original"), MediaLabels.fileName(it.filename), MediaLabels.size(it.bytes), it.mime) }

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

    /** The bell's rows, in the server's order (newest first). */
    fun bell(list: NotificationList): Bell = Bell(
        unseen = list.unseen,
        items = list.items.map {
            BellItem(
                id = it.id,
                title = it.title,
                body = it.body,
                project = it.projectName,
                time = timeLabel(it.createdAt),
                unread = it.seenAt == null,
                path = it.path,
            )
        },
    )

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
        private val now: Long,
    ) {
        fun agent(id: String, fallbackName: String? = null): Agent {
            val live = online[id]
            return Agent(
                id = id,
                name = names[id] ?: fallbackName ?: "An agent",
                role = "Agent",
                presence = if (id in onlineIds) Presence.Online else Presence.Offline,
                hue = hue(id),
                doing = live?.host?.takeIf { it.isNotBlank() }?.let { "On ${it.trim()}" },
                details = live?.let {
                    AgentDetails(
                        model = it.model?.trim()?.takeIf(String::isNotEmpty),
                        host = it.host?.trim()?.takeIf(String::isNotEmpty),
                        cwd = it.cwd?.trim()?.takeIf(String::isNotEmpty),
                        sessions = it.sessions,
                        lastSeen = heartbeatLabel(it.lastHeartbeatAt, now),
                    )
                },
            )
        }
    }

    companion object {
        const val Yes = "Yes"
        const val No = "No"
    }
}

/** "just now", "45s ago", "3m ago", as the website says how long since an agent's heartbeat. */
internal fun heartbeatLabel(lastHeartbeatAt: Long, now: Long): String {
    val seconds = maxOf(0L, kotlin.math.round((now - lastHeartbeatAt) / 1000.0).toLong())
    return when {
        seconds < 5 -> "just now"
        seconds < 60 -> "${seconds}s ago"
        else -> "${seconds / 60}m ago"
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
