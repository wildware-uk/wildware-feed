package dev.wildware.agents.app.engine

import dev.wildware.agents.app.feed.PickBounds
import dev.wildware.agents.app.feed.SecretAsk
import dev.wildware.agents.FormFill
import dev.wildware.agents.app.feed.FieldsForm
import dev.wildware.agents.CardRefView
import dev.wildware.agents.CardKinds
import dev.wildware.agents.HumanAuthor
import dev.wildware.agents.app.feed.LinkedCard
import dev.wildware.agents.RequestConfig
import dev.wildware.agents.app.feed.CriticalNotice
import dev.wildware.agents.FeedCard
import dev.wildware.agents.LiveAgentView
import dev.wildware.agents.MediaView
import dev.wildware.agents.removedMediaLabel
import dev.wildware.agents.Outbox
import dev.wildware.agents.OutboxItem
import dev.wildware.agents.OutboxKind
import dev.wildware.agents.app.feed.Unsent
import dev.wildware.agents.app.feed.NotReady
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import dev.wildware.agents.PlainText
import dev.wildware.agents.PostCard
import dev.wildware.agents.ProjectSummary
import dev.wildware.agents.ReactionView
import dev.wildware.agents.RequestCard
import dev.wildware.agents.RequestKind
import dev.wildware.agents.StreamStatus
import dev.wildware.agents.ThreadLine
import dev.wildware.agents.SharedSecretView
import dev.wildware.agents.app.feed.SharedSecret
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
import io.ktor.http.encodeURLPathPart
import dev.wildware.agents.app.feed.LogoChoice
import dev.wildware.agents.app.feed.CardApp
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

/** Who writes a line from GitHub about a linked PR (server migration 079): the message's author. */
internal const val GitHubAuthor = "github"

/** GitHub, drawn as an author of thread lines: never online, never a teammate to open. */
internal val GitHubAgent = Agent(id = GitHubAuthor, name = "GitHub", role = "GitHub", presence = Presence.Offline, hue = 0)

/**
 * Turns what the engine knows into what the feed screen draws. Pure: the same inputs always give
 * the same screen, so it is tested without a server or a screen.
 */
@OptIn(ExperimentalTime::class)
/** How long the owner's line may sit with no agent before it says so: longer than any live round trip. */
internal const val UndeliveredAfterMillis = 15_000L

/** Ack states, furthest along first: an agent that said several is shown by the first. */
private val AckOrder = listOf("done", "thinking", "read")

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
        // Every agent the dashboard knows, offline and removed ones too, so a delivery, an ack or a
        // reaction by an agent that wrote no card here still shows its name (issue #268).
        val names = knownAgentNames + agentNames(feed) + online.associate { it.agentId to it.name }
        val agents = AgentDirectory(names, online.associateBy { it.agentId }, onlineIds, now(), agentAvatars)
        val media = if (selectedSlug == null) emptyList() else projectPictures(feed)
        val viewProjects = projects.map { it.toView(media) }
        val byId = viewProjects.associateBy { it.id }
        val current = when (selectedSlug) {
            null -> Project("all", "All channels", unread = projects.sumOf { it.unread }, waiting = projects.sumOf { it.waiting }, hue = 0)
            else -> viewProjects.firstOrNull { it.slug == selectedSlug }
                ?: Project(selectedSlug, feed.project?.name ?: selectedSlug, unread = 0, hue = hue(selectedSlug), slug = selectedSlug)
        }
        fun project(id: String?) = id?.let { byId[it] } ?: current

        val index = CardIndex(feed, outbox)
        val inScope = when {
            everyQuestion == null -> feed.waiting
            selectedSlug == null -> everyQuestion
            else -> everyQuestion.filter { it.request.projectId == current.id }
        }
        val sections = buildList {
            val waiting = inScope.map { it.toQuestion(agents, ::project, index) }
            if (waiting.isNotEmpty()) add(FeedSection("Waiting on you", waiting))
            val pinned = feed.pinned.mapNotNull { it.toItem(agents, ::project, index) }
            if (pinned.isNotEmpty()) add(FeedSection("Pinned", pinned))
            // On a team, a question someone answered stays, settled, by when it was answered (migration 055).
            // A form with fields stays for everyone, showing what was sent.
            val answered = feed.answered.filter { team.team || it.request.config?.fields != null }
                .map { (it.request.answeredAt ?: 0L) to it.toAnswered(agents, ::project, index) }
            val timeline = feed.cards.map { it.activityAt to it }
            (timeline + answered)
                .sortedByDescending { it.first }
                .groupBy { dayOf(it.first) }
                .forEach { (day, entries) ->
                    add(FeedSection(dayLabel(day), entries.mapNotNull { (_, entry) -> if (entry is FeedCard) entry.toItem(agents, ::project, index) else entry as FeedItem.Question }))
                }
        }
        val waiting = Outbox.waiting(outbox)
        val failed = Outbox.failed(outbox)
        return FeedState(
            owner = owner(),
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
            newCount = feed.newCount,
            tags = feed.tags.map { it.toCardTag() },
            critical = criticalNotices(inScope, feed),
            logoChoices = media.take(10).map { LogoChoice(it.id, image(it)) },
            lone = feed.lone?.toItem(agents, ::project, index),
        )
    }

    /** Waiting questions marked critical, then critical updates the owner hasn't read yet. */
    private fun criticalNotices(questions: List<RequestCard>, feed: EngineFeed): List<CriticalNotice> =
        questions.filter { it.request.priority == "critical" }.map { CriticalNotice(it.id, it.agentName, oneLine(it.request.question), question = true) } +
            feed.allCards.filterIsInstance<UpdateCard>()
                .filter { it.isNew && it.update.priority == "critical" }
                .map { CriticalNotice(it.id, it.agentName, oneLine(it.update.title?.takeIf(String::isNotBlank) ?: it.update.body), question = false) }

    private fun oneLine(markdown: String) = dev.wildware.agents.PlainText.fromMarkdown(markdown).lineSequence().firstOrNull().orEmpty()

    /** Every picture on the cards loaded, newest card first, each once. */
    private fun projectPictures(feed: EngineFeed): List<MediaView> = feed.allCards.asSequence()
        .flatMap { card ->
            val own = when (card) {
                is UpdateCard -> card.update.media
                is PostCard -> card.post.media
                else -> emptyList()
            }
            own.asSequence() + card.replies.asSequence().flatMap { it.media }
        }
        // Ready, and with something to show: removed media has no variants left.
        .filter { it.kind == "image" && it.isReady && it.variants.isNotEmpty() }
        .distinctBy { it.id }
        .toList()

    private fun agentNames(feed: EngineFeed): Map<String, String> = buildMap {
        feed.allCards.forEach { card ->
            if (card is UpdateCard) put(card.update.agentId, card.agentName)
            val lines = card.replies + ((card as? PostCard)?.post?.let(::listOf) ?: emptyList())
            lines.forEach { line -> line.message.agentId?.let { put(it, line.authorName) } }
        }
        feed.waiting.forEach { put(it.request.agentId, it.agentName) }
    }

    private fun dev.wildware.agents.TagView.toCardTag() = CardTag(id, name, colour, projectId)

    private fun ProjectSummary.toView(pictures: List<MediaView>): Project {
        val logoId = project.theme?.logoMediaId
        return Project(
            project.id, project.name, unread = unread, waiting = waiting, hue = hue(project.id), slug = project.slug,
            archived = project.isArchived, description = project.description,
            background = project.theme?.background, accent = project.theme?.accent, pinned = project.pinned,
            logo = logoId?.let { id -> logo(id, project.name, pictures.firstOrNull { it.id == id }) },
            logoMediaId = logoId,
            logoInsteadOfName = logoId != null && project.theme?.logoReplacesName == true,
            groupId = project.groupId,
            position = project.position,
        )
    }

    /**
     * A project's logo, as the website draws it: the 640-wide thumbnail, which the dashboard makes
     * for every picture. Its shape comes from the picture when it is on a card loaded here.
     */
    private fun logo(mediaId: String, projectName: String, media: MediaView?): Attachment {
        val w = media?.width
        val h = media?.height
        return Attachment(
            title = "$projectName logo",
            altText = projectName,
            aspect = if (w != null && h != null && w > 0 && h > 0) (w.toFloat() / h).coerceIn(0.5f, 6f) else 1f,
            url = serverUrl.trimEnd('/') + "/media/${mediaId.encodeURLPathPart()}/thumb-640",
        )
    }

    /**
     * Where each thread line is, so a question asked under one can say what it answers and jump there;
     * and each answer the dashboard refused, by question, so its card can say so.
     */
    private class CardIndex(feed: EngineFeed, outbox: List<OutboxItem>) {
        private val lines = HashMap<String, Pair<String, ThreadLine>>()
        private val refused = Outbox.failed(outbox).filter { it.requestId != null }.associateBy { it.requestId!! }

        /** The answer to [requestId] the dashboard refused, if it did. */
        fun refused(requestId: String): OutboxItem? = refused[requestId]

        init {
            (feed.allCards + listOfNotNull(feed.lone)).forEach { card ->
                val own = (card as? PostCard)?.post?.let(::listOf).orEmpty()
                (own + card.replies).forEach { lines[it.message.id] = card.id to it }
            }
        }

        /** The card a message is in, and the line itself. */
        fun find(messageId: String): Pair<String, ThreadLine>? = lines[messageId]
    }

    private fun FeedCard.toItem(agents: AgentDirectory, project: (String?) -> Project, index: CardIndex): FeedItem? = when (this) {
        is UpdateCard -> {
            val (title, body) = titleAndBody(update.title, update.body)
            val time = cardTime(createdAt, activityAt, update.editedAt)
            FeedItem.Update(
                id = id,
                time = time.first,
                timeDetail = time.second,
                agent = agents.agent(update.agentId),
                project = project(update.projectId),
                level = level(update.level),
                title = title,
                body = body,
                status = workStatus(update.status),
                attachments = update.media.tiles().map { tile(it) },
                sounds = update.media.sounds(),
                files = update.media.files(),
                embeds = update.embeds.embeds(),
                apps = apps.map { CardApp(it.name, it.title?.takeIf(String::isNotBlank), it.description?.takeIf(String::isNotBlank), it.url) },
                reactions = reactions(reactions),
                replies = replies(replies, unread, agents, project, index),
                unreadReplies = unread,
                tags = tags.map { it.toCardTag() },
                fields = fields.map { CardField(it.name, it.value) },
                high = update.priority == "high",
                critical = update.priority == "critical",
                pinned = pinned,
                edited = editedLabel(update.editedAt),
                activityAt = activityAt,
                refs = update.refs.linked(agents, project, update.projectId),
                blocks = update.blocks.cardBlocks(CardKinds.Update, update.id),
                secrets = update.secrets.shared(CardKinds.Update, update.id),
                prs = update.prs,
            )
        }
        is PostCard -> cardTime(createdAt, activityAt, post.message.editedAt).let { time -> FeedItem.Post(
            id = id,
            time = time.first,
            timeDetail = time.second,
            author = author(post, agents),
            project = project(projectId),
            body = post.message.body,
            attachments = post.media.tiles().map { tile(it) },
            sounds = post.media.sounds(),
            files = post.media.files(),
            embeds = post.embeds.embeds(),
            apps = apps.map { CardApp(it.name, it.title?.takeIf(String::isNotBlank), it.description?.takeIf(String::isNotBlank), it.url) },
            acks = acks(post, agents),
            replies = replies(replies, unread, agents, project, index),
            unreadReplies = unread,
            tags = tags.map { it.toCardTag() },
            fields = fields.map { CardField(it.name, it.value) },
            reactions = reactions(reactions),
            sending = post.sending,
            uploaded = post.uploaded,
            voice = post.message.isVoice,
            undoUntil = post.undoUntil,
            words = post.words,
            questions = post.questions.map { it.toQuestion(agents, project, index) },
            edited = editedLabel(post.message.editedAt),
            activityAt = activityAt,
            createdAt = createdAt,
            status = workStatus(status),
            pinned = pinned,
            // Work says where it has got to, which answers "has anybody got this" better.
            undelivered = post.message.status == null && undelivered(post),
            refs = post.refs.linked(agents, project, projectId),
            blocks = post.blocks.cardBlocks(CardKinds.Message, id),
            secrets = post.secrets.shared(CardKinds.Message, id),
        ) }
    }

    private fun RequestCard.toQuestion(agents: AgentDirectory, project: (String?) -> Project, index: CardIndex): FeedItem.Question {
        val options = request.options.orEmpty()
        // A picture question carries one picture per option; the engine says which is which.
        fun choice(option: String, primary: Boolean = false) =
            Choice(option, primary, image = request.pictureFor(option)?.let { media ->
                when {
                    media.isRemoved -> removedTile(media)
                    media.isReady -> image(media).copy(altText = option)
                    else -> null
                }
            })
        val (kind, choices) = when (request.requestKind) {
            RequestKind.Buttons -> QuestionKind.Buttons to options.mapIndexed { i, o -> choice(o, primary = i == 0) }
            // A yes to request_secret_access shares a secret, so it says so.
            RequestKind.Confirm -> QuestionKind.Buttons to if (request.config?.secretAccess != null) {
                listOf(Choice(Allow, primary = true), Choice(Deny))
            } else {
                listOf(Choice(Yes, primary = true), Choice(No))
            }
            RequestKind.Choice -> QuestionKind.Choice to options.map { choice(it) }
            RequestKind.MultiChoice -> QuestionKind.MultiChoice to options.map { choice(it) }
            RequestKind.Text -> QuestionKind.Text to emptyList()
            RequestKind.Form -> QuestionKind.Form to emptyList()
            RequestKind.Secret -> QuestionKind.Secret to emptyList()
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
            embeds = request.embeds.embeds(),
            form = if (kind == QuestionKind.Form && config?.fields == null) {
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
            fieldsForm = config?.fields?.takeIf { kind == QuestionKind.Form }?.let { FieldsForm(it, options, FormFill.understands(it)) },
            // A text question exists because something must be typed: at least one character unless the agent says.
            text = if (kind == QuestionKind.Text) {
                FormField(
                    placeholder = config?.placeholder.orEmpty(),
                    initial = config?.defaultValue.orEmpty(),
                    multiline = config?.multiline == true,
                    min = config?.min ?: 1,
                    max = config?.max,
                )
            } else {
                null
            },
            inReplyTo = request.messageId?.let { id ->
                index.find(id)?.second?.let { "${it.authorName}: ${PlainText.clip(PlainText.fromMarkdown(it.message.body), 80)}" }
                    ?: "Asked in a thread"
            },
            threadId = request.messageId?.let { index.find(it)?.first } ?: request.updateId,
            replies = replies(discussion, unread, agents, project, index),
            unreadReplies = unread,
            picks = if (kind == QuestionKind.MultiChoice) pickBounds(config, options.size) else PickBounds(),
            critical = request.priority == "critical",
            secretName = config?.secretName?.takeIf { kind == QuestionKind.Secret },
            secretFields = config?.secretFields?.takeIf { kind == QuestionKind.Secret }.orEmpty()
                .map { SecretAsk(it.name, it.label, it.hint, it.multiline, it.optional) },
            // As the website does: a choice question opens on the agent's default, when it is one of the options.
            preset = config?.defaultValue?.takeIf { kind == QuestionKind.Choice && it in options },
            unsent = index.refused(id)?.let(::unsent),
            edited = editedLabel(request.editedAt),
            tags = tags.map { it.toCardTag() },
            fields = fields.map { CardField(it.name, it.value) },
            reactions = reactions(request.reactions),
            pinned = request.pinned,
            refs = request.refs.linked(agents, project, request.projectId),
            blocks = request.blocks.cardBlocks(CardKinds.Request, request.id),
        )
    }

    /** A question someone answered, settled: who answered ("you" for this person) and what they said. */
    private fun RequestCard.toAnswered(agents: AgentDirectory, project: (String?) -> Project, index: CardIndex): FeedItem.Question {
        val by = when (val who = person(request.answeredByPerson)) {
            is Author.Person -> who.name
            else -> "you"
        }
        val at = request.answeredAt ?: 0L
        return toQuestion(agents, project, index).copy(
            answered = FeedItem.Answered(
                by,
                answerSaid(request.answer, access = request.config?.secretAccess != null),
                if (at > 0) relativeLabel(at) else "",
                values = request.config?.fields?.let { FormFill.said(it, FormFill.valuesOf(request.answer)) }.orEmpty(),
            ),
            sending = false,
            unsent = null,
        )
    }

    private fun replies(
        lines: List<ThreadLine>,
        unread: Int,
        agents: AgentDirectory,
        project: (String?) -> Project,
        index: CardIndex,
    ): List<Reply> {
        // The engine counts unread comments per card; they are the newest ones not written by the owner.
        val unreadIds = lines.filter { author(it, agents) !is Author.Owner }.takeLast(unread).mapTo(HashSet()) { it.message.id }
        val byId = lines.associateBy { it.message.id }
        return lines.map { line ->
            Reply(
                id = line.message.id,
                author = author(line, agents),
                time = relativeLabel(line.message.createdAt),
                timeDetail = exactTime(line.message.createdAt),
                body = line.message.body,
                reactions = reactions(line.reactions),
                unread = line.message.id in unreadIds,
                sending = line.sending,
                uploaded = line.uploaded,
                voice = line.message.isVoice,
                assignment = line.message.source == "assignment" || line.message.source == "status" || line.message.source == GitHubAuthor,
                undoUntil = line.undoUntil,
                words = line.words,
                questions = line.questions.map { it.toQuestion(agents, project, index) },
                undelivered = undelivered(line),
                attachments = line.media.tiles().map { tile(it) },
                sounds = line.media.sounds(),
                files = line.media.files(),
                embeds = line.embeds.embeds(),
                acks = acks(line, agents),
                edited = editedLabel(line.message.editedAt),
                // Only a line in this same thread: the card's own head needs no saying.
                answering = line.message.answers?.let(byId::get)?.let { author(it, agents).let { a -> if (a is Author.Owner) "you" else a.name } },
                refs = line.refs.linked(agents, project, line.message.projectId),
                blocks = line.blocks.cardBlocks(CardKinds.Message, line.message.id),
                secrets = line.secrets.shared(CardKinds.Message, line.message.id),
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

    /** The people of the team; names show on what others wrote once there is more than one (migration 055). */
    var team: dev.wildware.agents.app.feed.TeamState = dev.wildware.agents.app.feed.TeamState()

    /** Agents' pictures, by agent id: media ids from the dashboard's agent list. */
    var agentAvatars: Map<String, String> = emptyMap()

    /** Every agent's name, by agent id, online or not, removed ones too: the dashboard's whole agent list. */
    var knownAgentNames: Map<String, String> = emptyMap()

    /** You, with your picture when you have one. */
    private fun owner(): Author.Owner = team.me?.avatarMediaId?.let { Author.Owner(OwnerAuthor.name, it) } ?: OwnerAuthor

    private fun author(line: ThreadLine, agents: AgentDirectory): Author = when {
        // A line from GitHub about a linked PR (server migration 079): GitHub's, not a person's.
        line.message.author == GitHubAuthor -> Author.AgentAuthor(GitHubAgent)
        else -> line.message.agentId?.let { Author.AgentAuthor(agents.agent(it, line.authorName)) } ?: person(line.message.personId)
    }

    /** You, or another person by name ("Sam", "Sam (left)") on a team. */
    private fun person(id: String?): Author {
        val known = team
        if (!known.team || id == null) return owner()
        if (id == (known.me?.id ?: dev.wildware.agents.FounderId)) return owner()
        val row = known.people.firstOrNull { it.id == id }
        val name = row?.let { if (it.removed) "${it.name} (left)" else it.name } ?: "Someone"
        return Author.Person(name, row?.handle ?: "", hue(id), row?.avatarMediaId)
    }

    /** Whether a person's reaction is yours. */
    private fun mine(view: ReactionView): Boolean =
        view.isOwner && (!team.team || view.personId == (team.me?.id ?: dev.wildware.agents.FounderId))

    /**
     * Each agent once, by the furthest thing it said: done, then thinking, then read. A delivery
     * shows only while its agent has said nothing, as the website does: any ack means it arrived.
     */
    private fun acks(line: ThreadLine, agents: AgentDirectory): Acks {
        val said = line.acks.groupBy { it.agentId }.mapValues { (_, all) -> all.map { it.state }.toSet() }
        fun saying(state: String) = said.filterValues { states ->
            state in states && AckOrder.takeWhile { it != state }.none { it in states }
        }.keys.map { agents.agent(it) }
        return Acks(
            done = saying("done"),
            thinking = saying("thinking"),
            read = saying("read"),
            delivered = line.deliveries.map { it.agentId }.distinct().filter { it !in said }.map { agents.agent(it) },
        )
    }

    private fun reactions(views: List<ReactionView>): List<Reaction> =
        views.groupBy { it.emoji }.map { (emoji, all) -> Reaction(emoji, all.size, mine = all.any(::mine), spoken = emoji) }

    /** The secrets an agent attached to a card, as chips. */
    private fun List<SharedSecretView>.shared(kind: String, cardId: String): List<SharedSecret> =
        map { SharedSecret(kind, cardId, it.name, it.available) }

    /** The blocks on a card, with who you are, so the screen knows your tick and your vote. */
    private fun List<dev.wildware.agents.BlockView>.cardBlocks(kind: String, id: String): List<dev.wildware.agents.app.feed.CardBlock> {
        if (isEmpty()) return emptyList()
        val me = team.me?.id ?: dev.wildware.agents.FounderId
        return map { dev.wildware.agents.app.feed.CardBlock(kind, id, it, me, team.team) }
    }

    /** The cards a card points at, drawn small: [here] is the pointing card's project, which they don't repeat. */
    private fun List<CardRefView>.linked(agents: AgentDirectory, project: (String?) -> Project, here: String?): List<LinkedCard> = map { ref ->
        val what = when (ref.kind) {
            CardKinds.Update -> "update"
            CardKinds.Request -> "question"
            else -> if (ref.updateId == null && ref.replyTo == null && ref.requestId == null) "post" else "reply"
        }
        val home = ref.projectId?.let { project(it) }
        // A removed message no longer says which thread it was in.
        if (ref.deleted) return@map LinkedCard(ref.kind, ref.id, removed = true, what = if (ref.kind == CardKinds.Message) "message" else what)
        val author = ref.author
        val who = when {
            author == HumanAuthor -> "You"
            author != null && author.startsWith("agent:") -> agents.agent(author.removePrefix("agent:"), ref.authorName).name
            else -> ref.authorName.orEmpty()
        }
        val tile = ref.media?.let { listOf(it).tiles().firstOrNull() }?.let { tile(it) }
        val note = when {
            tile != null && ref.mediaCount > 1 -> "+${ref.mediaCount - 1} more"
            tile == null && ref.mediaCount == 1 -> ref.media?.filename?.takeIf { it.isNotBlank() } ?: "1 file"
            tile == null && ref.mediaCount > 1 -> "${ref.mediaCount} files"
            else -> null
        }
        LinkedCard(
            kind = ref.kind,
            id = ref.id,
            who = who,
            what = what,
            where = home?.name?.takeIf { ref.projectId != here && here != null },
            title = ref.title?.takeIf { it.isNotBlank() },
            text = ref.text?.takeIf { it.isNotBlank() },
            media = tile,
            mediaNote = note,
            projectSlug = home?.slug,
        )
    }

    private fun List<MediaView>.images() = filter { it.kind == "image" && it.isReady }

    /**
     * Pictures and videos share the grid, as on the website. What is still being made, what failed
     * and what was removed each keep their place, as a placeholder saying so.
     */
    private fun List<MediaView>.tiles() = filter { (it.kind == "image" || it.kind == "video") && (!it.isReady || it.isRemoved || it.variants.isNotEmpty()) }

    private fun url(media: MediaView, variant: String) = serverUrl.trimEnd('/') + "/" + media.path(variant)

    private fun aspectOf(media: MediaView): Float {
        val w = media.width
        val h = media.height
        return if (w != null && h != null && w > 0 && h > 0) (w.toFloat() / h).coerceIn(0.5f, 3f) else 16f / 9f
    }

    /** Removed media, in the slot its picture took: no address at all, so nothing tries to load. */
    private fun removedTile(media: MediaView): Attachment {
        val days = media.removed?.afterDays ?: 0
        return Attachment(
            title = media.filename ?: if (media.kind == "video") "Video" else "Image",
            altText = removedMediaLabel(days),
            aspect = aspectOf(media),
            removedAfterDays = days,
        )
    }

    /** A picture or video the dashboard hasn't made yet, or couldn't: no address, so nothing loads. */
    private fun notReadyTile(media: MediaView): Attachment {
        val why = notReady(media)
        return Attachment(
            title = media.filename ?: if (media.kind == "video") "Video" else "Image",
            altText = why.words,
            aspect = aspectOf(media),
            notReady = why,
        )
    }

    private fun tile(media: MediaView): Attachment {
        if (media.isRemoved) return removedTile(media)
        if (!media.isReady) return notReadyTile(media)
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
                original = original(media),
            ),
        )
    }

    /**
     * The upload itself, as a file to save: `?download=1` has the dashboard send it as a download
     * under its own name. Null when there is no original to send.
     */
    private fun original(media: MediaView): FileAttachment? {
        if ("original" !in media.variants) return null
        val kind = if (media.kind == "video") "video" else "image"
        val ext = MediaLabels.extension(media.mime, media.mime.substringAfter('/', "bin").substringBefore(';').trim())
        // Its own name with the extension of the type its bytes were checked to be, as the server
        // names it: a video called clip.hta saves as clip.mp4, never as something that would run.
        val given = media.filename?.trim()?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotEmpty() }
        val stem = given?.let { if ('.' in it) it.substringBeforeLast('.') else it }?.trimEnd('.', ' ')?.takeIf { it.isNotEmpty() }
        val name = "${stem ?: kind}.$ext"
        return FileAttachment(media.id, url(media, "original") + "?download=1", name, MediaLabels.size(media.bytes), media.mime)
    }

    /**
     * Every sound, in the order it was attached. One still being made, or that never will be, keeps
     * its place as a row saying so, as a picture does in the grid.
     */
    private fun List<MediaView>.sounds() = filter { it.kind == "audio" && (it.isRemoved || !it.isReady || "original" in it.variants) }
        .map { media ->
            val removed = media.removed
            val name = media.filename?.trim()?.takeIf { it.isNotEmpty() }
            when {
                removed != null -> Sound(media.id, "", media.durationMs, media.mime, removedAfterDays = removed.afterDays, name = name)
                !media.isReady -> Sound(media.id, "", media.durationMs, media.mime, name = name, notReady = notReady(media))
                else -> Sound(media.id, url(media, "original"), media.durationMs, media.mime, name = name)
            }
        }

    /** Every file, in the order it was attached; one not ready yet keeps its place, as a sound does. */
    private fun List<MediaView>.files() = filter { it.kind == "file" && (it.isRemoved || !it.isReady || "original" in it.variants) }
        .map { media ->
            val removed = media.removed
            val ready = removed == null && media.isReady
            FileAttachment(
                media.id,
                if (ready) url(media, "original") else "",
                MediaLabels.fileName(media.filename),
                MediaLabels.size(media.bytes),
                media.mime,
                removedAfterDays = removed?.afterDays,
                notReady = if (removed == null && !media.isReady) notReady(media) else null,
            )
        }

    /**
     * A picture or video for the Media tab: as the viewer shows it, and with the grid's smaller
     * thumbnail where the dashboard made one.
     */
    fun mediaTabTile(media: MediaView): Pair<Attachment, Attachment> {
        val shown = tile(media)
        val small = if (media.kind == "image" && shown.url != null && "thumb-640" in media.variants) shown.copy(url = url(media, "thumb-640")) else shown
        return shown to small
    }

    /** A file or sound for the Media tab's list: a download, or a placeholder saying why there is none. */
    fun mediaTabFile(media: MediaView): FileAttachment {
        val removed = media.removed
        val ready = removed == null && media.isReady && "original" in media.variants
        return FileAttachment(
            media.id,
            if (ready) url(media, "original") else "",
            MediaLabels.fileName(media.filename),
            MediaLabels.size(media.bytes),
            media.mime,
            removedAfterDays = removed?.afterDays,
            notReady = if (removed == null && !media.isReady) notReady(media) else null,
        )
    }

    private fun notReady(media: MediaView) = when (media.status) {
        "failed" -> NotReady.Failed
        dev.wildware.agents.UploadingStatus -> NotReady.Uploading
        else -> NotReady.Processing
    }

    private fun List<dev.wildware.agents.EmbedView>.embeds() = map { dev.wildware.agents.app.feed.Embed(dev.wildware.agents.EmbedLook.resolve(it), serverUrl) }

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
            original = original(media),
            fullUrl = if ("original" in media.variants) url(media, "original") else null,
            width = w?.takeIf { it > 0 },
            height = h?.takeIf { it > 0 },
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

    /**
     * How long ago, as the website says it: "now", "45s ago", "5m ago", "3h ago", "2d ago", and past a
     * month the day itself, when "how long" has stopped being the question. Kept fresh by the engine's tick.
     */
    fun relativeLabel(at: Long): String {
        val seconds = (now() - at) / 1000
        return when {
            // A phone's clock a few seconds ahead must not read as "in 3 seconds".
            seconds < 10 -> "now"
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86_400 -> "${seconds / 3600}h ago"
            seconds < 30 * 86_400 -> "${seconds / 86_400}d ago"
            else -> exactDay(at)
        }
    }

    /**
     * A card's time: how long ago it last changed (a reply, its work state, an edit), as "updated 5m
     * ago" when that was after it was posted; and the whole instants behind it, to hover or hear.
     */
    fun cardTime(createdAt: Long, activityAt: Long, editedAt: Long?): Pair<String, String> {
        val updatedAt = maxOf(createdAt, activityAt, editedAt ?: 0)
        return if (updatedAt > createdAt) {
            "updated ${relativeLabel(updatedAt)}" to "Posted ${exactTime(createdAt)} · updated ${exactTime(updatedAt)}"
        } else {
            relativeLabel(createdAt) to "Posted ${exactTime(createdAt)}"
        }
    }

    /** The whole instant, as the website writes it: "Monday 3 August 2026 at 14:05". */
    fun exactTime(millis: Long): String {
        val then = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
        return "${exactDay(millis)} at ${then.hour.toString().padStart(2, '0')}:${then.minute.toString().padStart(2, '0')}"
    }

    private fun exactDay(millis: Long): String {
        val day = dayOf(millis)
        return "${day.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }} ${day.day} ${monthName(day)} ${day.year}"
    }

    /** "3 August 2026": a day, short enough for a line of small print. */
    fun shortDay(millis: Long): String = dayOf(millis).let { "${it.day} ${monthName(it)} ${it.year}" }

    private fun monthName(day: LocalDate) = day.month.name.lowercase().replaceFirstChar { it.uppercase() }

    /** Stated, never silent: a card that was rewritten says when. Null when it never was. */
    fun editedLabel(editedAt: Long?): String? = editedAt?.takeIf { it > 0 }?.let { "Edited ${exactTime(it)}" }

    private fun dayOf(millis: Long): LocalDate = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date

    fun dayLabel(day: LocalDate): String {
        val today = dayOf(now())
        val daysAgo = today.toEpochDays() - day.toEpochDays()
        return when {
            daysAgo == 0L -> "Today"
            daysAgo == 1L -> "Yesterday"
            daysAgo in 2..6 -> day.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
            // An older year says so: "3 August" alone would read as this year's.
            day.year != today.year -> "${day.day} ${monthName(day)} ${day.year}"
            else -> "${day.day} ${monthName(day)}"
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
        private val avatars: Map<String, String>,
    ) {
        fun agent(id: String, fallbackName: String? = null): Agent {
            val live = online[id]
            return Agent(
                id = id,
                name = names[id] ?: fallbackName ?: RemovedAgentName,
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
                avatarMediaId = avatars[id],
            )
        }
    }

    companion object {
        /** An agent the dashboard no longer has at all: deleted, so its name is gone too. */
        const val RemovedAgentName = "Removed agent"
        const val Yes = "Yes"
        const val Allow = "Allow"
        const val Deny = "Deny"
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

/**
 * How many a multi-choice question takes, as the website enforces it: the agent's min (at least one
 * when it set none) to its max (all of them when it set none), both kept within the options.
 */
internal fun pickBounds(config: RequestConfig?, options: Int): PickBounds {
    val min = (config?.min ?: 1).coerceIn(0, options)
    val max = (config?.max ?: options).coerceIn(min, options)
    return PickBounds(min, max, bounded = min != 1 || max < options)
}

/**
 * What an answered question's answer said, in words: yes or no, the option or options picked, the
 * words typed, a form's action. Null for a secret (only its name is kept) or an answer it can't read.
 */
internal fun answerSaid(answer: kotlinx.serialization.json.JsonElement?, access: Boolean = false): String? {
    val body = answer as? JsonObject ?: return null
    if ((body["kind"] as? JsonPrimitive)?.contentOrNull == "secret") return null
    val said = when (val value = body["value"]) {
        is JsonPrimitive -> if (value.isString) value.contentOrNull else value.booleanOrNull?.let {
            when {
                access -> if (it) "Allowed" else "Denied"
                it -> FeedMapper.Yes
                else -> FeedMapper.No
            }
        }
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(", ")
        is JsonObject -> listOfNotNull((value["action"] as? JsonPrimitive)?.contentOrNull, (value["text"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null }).joinToString(": ")
        else -> null
    }
    return said?.let { PlainText.clip(it.replace(Regex("\\s+"), " ").trim(), 120) }?.ifBlank { null }
}

/**
 * An answer the dashboard refused, as its card shows it: why, what the owner said, and any words they
 * typed, to put back in the box. The answer is JSON as it was sent: words, picks, yes or no, or a form's action and text.
 */
internal fun unsent(item: OutboxItem): Unsent {
    val value = item.value?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
    val typed = when (value) {
        is JsonPrimitive -> value.contentOrNull?.takeIf { value.isString }
        is JsonObject -> (value["text"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null }
        else -> null
    } ?: item.answer?.typedText
    val said = when {
        item.kind == OutboxKind.Dismiss -> "Not now"
        value is JsonPrimitive && !value.isString -> if (value.booleanOrNull == false) FeedMapper.No else FeedMapper.Yes
        value is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(", ")
        value is JsonObject -> listOfNotNull((value["action"] as? JsonPrimitive)?.contentOrNull, typed).joinToString(": ")
        else -> typed ?: item.answer?.actionId
    }
    return Unsent(item.id, item.lastError ?: "The dashboard refused it.", said?.ifBlank { null }, typed)
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

/** An ISO-8601 instant as milliseconds, or null when it isn't one. */
@OptIn(ExperimentalTime::class)
internal fun isoMillis(iso: String): Long? = runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrNull()
