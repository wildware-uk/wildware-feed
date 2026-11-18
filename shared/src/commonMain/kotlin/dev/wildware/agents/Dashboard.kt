package dev.wildware.agents

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** The id a line still in the outbox goes by until the dashboard gives it its own. */
const val DraftPrefix = "outbox:"

/** The comments under a card, each with what is said about it. */
data class ThreadLine(
    val message: MessageView,
    /** The agent's name, or "You" for the owner. */
    val authorName: String,
    /** Who the line answers, when it answers one: the [answers] message's author. */
    val answersName: String?,
    val media: List<MediaView>,
    val embeds: List<EmbedView>,
    val reactions: List<ReactionView>,
    /** "thinking", "read", "done" from each agent. A "thinking" from an offline agent is left out. */
    val acks: List<AckView>,
    val deliveries: List<DeliveryView>,
    /** Questions an agent asked under this line. They are answered here, not at the top of the feed. */
    val questions: List<RequestCard>,
    /** The owner wrote it here and it is still on its way to the dashboard. */
    val sending: Boolean = false,
)

/** One card in the feed. */
sealed interface FeedCard {
    val id: String
    val card: CardRef
    val projectId: String?
    val createdAt: Long
    /** When something last happened on it: posted, replied to, or its work state changed. The feed sorts on this. */
    val activityAt: Long
    val pinned: Boolean
    val status: String?
    val replies: List<ThreadLine>
    /** Comments by agents the owner hasn't seen yet. */
    val unread: Int
    val reactions: List<ReactionView>
    val tags: List<TagView>
    val fields: List<CardFieldView>
    val apps: List<AppView>
}

data class UpdateCard(
    val update: UpdateView,
    val agentName: String,
    val projectName: String?,
    /** Arrived live: animate it in once. */
    val isNew: Boolean,
    override val activityAt: Long,
    override val replies: List<ThreadLine>,
    override val unread: Int,
    override val tags: List<TagView>,
    override val fields: List<CardFieldView>,
    override val apps: List<AppView>,
) : FeedCard {
    override val id get() = update.id
    override val card get() = CardRef(CardKinds.Update, update.id)
    override val projectId get() = update.projectId
    override val createdAt get() = update.createdAt
    override val pinned get() = update.pinned
    override val status get() = update.status
    override val reactions get() = update.reactions
}

/** A message written straight into the feed, by the owner or an agent. */
data class PostCard(
    val post: ThreadLine,
    val projectName: String?,
    override val activityAt: Long,
    override val replies: List<ThreadLine>,
    override val unread: Int,
    override val tags: List<TagView>,
    override val fields: List<CardFieldView>,
    override val apps: List<AppView>,
) : FeedCard {
    override val id get() = post.message.id
    override val card get() = CardRef(CardKinds.Message, post.message.id)
    override val projectId get() = post.message.projectId
    override val createdAt get() = post.message.createdAt
    override val pinned get() = post.message.pinned
    override val status get() = post.message.status
    override val reactions get() = post.reactions
}

/** A question an agent is stopped on, with the talk about it. */
data class RequestCard(
    val request: RequestView,
    val agentName: String,
    val projectName: String?,
    val discussion: List<ThreadLine>,
    val unread: Int,
    /** An answer or dismissal is in the outbox, waiting for signal. */
    val sending: Boolean,
    val tags: List<TagView>,
    val fields: List<CardFieldView>,
) {
    val id: String get() = request.id
    val card: CardRef get() = CardRef(CardKinds.Request, request.id)
}

/** Everything one feed shows: one project's, or every project's when [project] is null. */
data class FeedState(
    val projectSlug: String?,
    /** Null on the all-projects feed, and while the project list is loading. */
    val project: ProjectView?,
    val status: StreamStatus,
    /** The first read has landed. Before that the feed is empty because it doesn't know yet, not because there is nothing. */
    val loaded: Boolean,
    val loading: Boolean,
    val loadingOlder: Boolean,
    /** Questions for this feed's top, longest waiting first. Questions asked inside a thread are under their line instead. */
    val waiting: List<RequestCard>,
    /** Pinned cards, newest first, above everything else. */
    val pinned: List<FeedCard>,
    /** Everything else, newest activity first. */
    val cards: List<FeedCard>,
    /** Updates that arrived while the owner was scrolled down: the "N new" pill. */
    val newCount: Int,
    val hasMore: Boolean,
    /** The words this feed's projects use, for the tag picker. */
    val tags: List<TagView>,
    /**
     * Not [loaded] yet, but showing what this device kept from last time: the cards are there to
     * read while the first read is on its way.
     */
    val cached: Boolean = false,
) {
    /** Every card, pinned first, in order. */
    val allCards: List<FeedCard> get() = pinned + cards

    fun card(id: String): FeedCard? = allCards.firstOrNull { it.id == id }

    companion object {
        internal fun empty(slug: String?) =
            FeedState(slug, null, StreamStatus.Idle, false, false, false, emptyList(), emptyList(), emptyList(), 0, false, emptyList())
    }
}

/** A project for the sidebar, with its badges. */
data class ProjectSummary(
    val project: ProjectView,
    /** Questions waiting in this project, thread questions included. */
    val waiting: Int,
    /** Unseen agent comments on this project's loaded cards. */
    val unread: Int,
)

/**
 * The app's live copy of the dashboard. Build one per signed-in server, [start] it, and collect
 * its flows. One live connection serves every feed: [feed] gives one project's, or all of them.
 *
 * [scope] should live as long as the signed-in session. The engine runs all of its work one step
 * at a time on that scope's dispatcher, so the flows are safe to collect from any thread.
 */
class Dashboard internal constructor(
    val client: DashboardClient,
    scope: CoroutineScope,
    private val readMarks: ReadMarks,
    private val clock: () -> Long,
    private val pageSize: Int,
    streamFactory: (CoroutineScope) -> LiveStream,
    private val pollMillis: Long,
    private val outbox: OutboxQueue?,
    /** The feed as it last was, shown at once on the next launch. Null keeps nothing. */
    internal val cache: FeedCache? = null,
) {
    @OptIn(ExperimentalTime::class)
    constructor(
        client: DashboardClient,
        scope: CoroutineScope,
        readMarks: ReadMarks = ReadMarks(),
        outbox: OutboxQueue? = null,
        clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
        timing: StreamTiming = StreamTiming(),
        pageSize: Int = 50,
        cache: FeedCache? = null,
    ) : this(client, scope, readMarks, clock, pageSize, { LiveStream(client, it, timing) }, PollMillis, outbox, cache)

    private val confined: CoroutineScope = confinedScope(scope)

    val stream: LiveStream = streamFactory(confined)
    val status: StateFlow<StreamStatus> get() = stream.status

    private val requests = RequestsReader(client, confined, ::changed).also { it.cache = cache }
    private val presence = PresenceReader(client, confined, ::changed).also { it.cache = cache }
    private val feeds = mutableMapOf<String?, Feed>()
    private var timers: Job? = null
    private var now = clock()

    private val mutableWaiting = MutableStateFlow<List<RequestCard>>(emptyList())
    private val mutableOnline = MutableStateFlow<List<LiveAgentView>>(emptyList())
    private val mutableProjects = MutableStateFlow<List<ProjectSummary>>(emptyList())

    /** Every question waiting on the owner, longest waiting first, wherever it was asked. */
    val waiting: StateFlow<List<RequestCard>> = mutableWaiting.asStateFlow()

    /** Agents beating right now. */
    val online: StateFlow<List<LiveAgentView>> = mutableOnline.asStateFlow()

    /** The sidebar: every project (archived ones last), with its badges. */
    val projects: StateFlow<List<ProjectSummary>> = mutableProjects.asStateFlow()

    private val mutableReady = MutableStateFlow(false)

    /**
     * True once the questions and the all-projects feed have both been read. What is there at that
     * moment was already there: a notifier takes it as seen and alerts only for what comes after.
     */
    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()

    private var started = false

    /** The all-projects feed, always running while the dashboard is. */
    val everything: Feed = feed(null)

    /** Opens the live connection and reads everything. Safe to call twice. */
    fun start() {
        if (started) return
        started = true
        stream.floor = ::floor
        stream.start { event -> receive(event) }
        requests.start()
        presence.start()
        feeds.values.forEach { it.startReaders() }
        timers = confined.launch {
            launch {
                while (true) {
                    delay(pollMillis)
                    presence.poll()
                }
            }
            launch {
                var last = clock()
                while (true) {
                    delay(TickMillis)
                    now = clock()
                    // The clock jumped past the tick: the device slept, or the app was suspended. The
                    // connection that was open then is very likely dead, and nothing on it will say so
                    // until the heartbeat window runs out. Replace it now.
                    if (now - last > TickMillis + SleepMillis) stream.revive(reconnect = true, reread = false)
                    last = now
                    changed()
                }
            }
            // A read that failed, or never ran, is tried again when the connection comes back.
            launch {
                stream.status.drop(1).collect { status ->
                    if (status == StreamStatus.Live) readers().forEach { it.refreshIfStale() }
                    changed()
                }
            }
        }
        outbox?.let { queue ->
            queue.onSent = { item, result -> withContext(confinedDispatcher) { sent(item, result) } }
            confined.launch { queue.items.collect { changed() } }
        }
    }

    private val confinedDispatcher = confined.coroutineContext[ContinuationInterceptor] ?: confined.coroutineContext

    /** Outbox items already shown from the dashboard's answer, so their draft must go. */
    private val delivered = HashSet<String>()
    private var draftsFrom: List<OutboxItem>? = null
    private var drafts: List<OutboxItem> = emptyList()

    /** What the owner wrote that is still on its way: drawn in its thread straight away. */
    private fun drafts(): List<OutboxItem> {
        val items = outbox?.items?.value ?: return emptyList()
        if (items === draftsFrom) return drafts
        draftsFrom = items
        delivered.retainAll(items.mapTo(HashSet()) { it.id })
        drafts = items.filter { !it.gaveUp && it.id !in delivered && (it.kind == OutboxKind.Message || it.kind == OutboxKind.Ticket) }
        return drafts
    }

    private fun sent(item: OutboxItem, result: DeliveryResult) {
        val message = result.sent ?: return
        feeds.values.forEach { it.adopt(message) }
        delivered += item.id
        draftsFrom = null
        changed()
    }

    fun stop() {
        started = false
        cache?.close()
        outbox?.onSent = null
        stream.stop()
        timers?.cancel()
        timers = null
        readers().forEach { it.stop() }
    }

    /** Call when the app comes to the front (reconnect if it was away a while) or the network returns (always reconnect). */
    fun revive(reconnect: Boolean, reread: Boolean = true) = stream.revive(reconnect, reread)

    /**
     * One project's feed by slug, or every project's for null. The same slug gives the same feed.
     * [Feed.close] a project feed when its screen goes away; the all-projects feed stays.
     */
    fun feed(projectSlug: String?): Feed = feeds.getOrPut(projectSlug) {
        Feed(projectSlug, this, client, confined, pageSize).also { if (started) it.startReaders() }
    }

    internal fun release(feed: Feed) {
        if (feed.projectSlug == null) return
        feed.stopReaders()
        feeds.remove(feed.projectSlug)
    }

    /** Re-reads everything now, as a pull-to-refresh does. */
    suspend fun refresh() = withContext(confined.coroutineContext) {
        requests.refreshNow(replace = true)
        presence.refreshNow(replace = true)
        feeds.values.forEach { it.refreshNow() }
    }

    /** Marks a card's comments as seen: its unread badge clears. */
    fun markRead(cardId: String) {
        confined.launch {
            feeds.values.firstNotNullOfOrNull { it.commentsOn(cardId) }?.let { readMarks.markRead(cardId, it) }
            changed()
        }
    }

    private fun readers(): List<FeedReader> = listOf(requests, presence) + feeds.values.flatMap { it.readers }

    private suspend fun receive(event: StreamEvent) {
        requests.receive(event)
        presence.receive(event)
        feeds.values.toList().forEach { it.receive(event) }
    }

    /** The lowest seq every loaded reader accounts for: a reconnect never resumes from below it. */
    private fun floor(): Long = readers().filter { it.loaded }.minOfOrNull { it.seq } ?: 0

    private var publishQueued = false

    /** Something changed: work out the flows again, once per burst. */
    internal fun changed() {
        if (publishQueued) return
        publishQueued = true
        confined.launch {
            publishQueued = false
            publish()
        }
    }

    private fun publish() {
        val pending = drafts()
        feeds.values.forEach { it.drafts = pending }
        val names = names()
        val onlineAgents = presence.agents.filter { it.lastHeartbeatAt >= now - PresenceReader.WindowMillis }
        mutableOnline.value = onlineAgents
        val projectsById = everything.projects().associateBy { it.id }
        val sending = sendingRequestIds()
        val onlineIds = onlineAgents.mapTo(HashSet()) { it.agentId }
        val context = FeedContext(names, projectsById, sending, onlineIds, readMarks, requests.items)
        mutableWaiting.value = requests.items.map { context.requestCard(it, everything) }
        feeds.values.forEach { it.publish(context, stream.status.value, requests.loading) }
        mutableProjects.value = summaries(everything.state.value)
        if (!mutableReady.value && requests.loaded && everything.state.value.loaded) mutableReady.value = true
    }

    private fun summaries(all: FeedState): List<ProjectSummary> {
        val waitingByProject = requests.items.groupingBy { it.projectId }.eachCount()
        val unreadByProject = (all.allCards.map { it.projectId to it.unread } + all.waiting.map { it.request.projectId to it.unread })
            .groupBy({ it.first }, { it.second }).mapValues { (_, counts) -> counts.sum() }
        return everything.projects()
            .sortedBy { it.isArchived }
            .map { ProjectSummary(it, waitingByProject[it.id] ?: 0, unreadByProject[it.id] ?: 0) }
    }

    private fun names(): Map<String, String> = presence.names + feeds.values.fold(emptyMap()) { all, feed -> all + feed.agentNames() }

    private fun sendingRequestIds(): Set<String> =
        outbox?.items?.value.orEmpty().filter { !it.gaveUp }.mapNotNullTo(HashSet()) { it.requestId }

    internal companion object {
        const val PollMillis = 20_000L
        const val TickMillis = 5_000L

        /** A tick this much later than due means the device was asleep, not just busy. */
        const val SleepMillis = 30_000L
    }
}

/** What every feed needs to build its cards, worked out once per change. */
internal class FeedContext(
    private val names: Map<String, String>,
    val projectsById: Map<String, ProjectView>,
    private val sending: Set<String>,
    private val onlineIds: Set<String>,
    val readMarks: ReadMarks,
    val requests: List<RequestView>,
) {
    fun agentName(agentId: String): String = names[agentId] ?: "An agent"

    fun authorName(author: String): String =
        if (author == HumanAuthor) "You" else agentName(author.removePrefix(AgentPrefix))

    /** "thinking" is a claim about now, so it shows only while that agent is online. */
    fun acks(acks: List<AckView>): List<AckView> = acks.filter { it.state != "thinking" || it.agentId in onlineIds }

    fun requestCard(request: RequestView, feed: Feed): RequestCard {
        val discussion = feed.discussionOf(request.id, this)
        val messages = discussion.map { it.message }
        readMarks.seed(request.id, messages)
        val ref = CardRef(CardKinds.Request, request.id)
        return RequestCard(
            request = request,
            agentName = agentName(request.agentId),
            projectName = request.projectId?.let { projectsById[it]?.name },
            discussion = discussion,
            unread = readMarks.unread(request.id, messages),
            sending = request.id in sending,
            tags = feed.tagsOn(ref),
            fields = feed.fieldsOn(ref),
        )
    }
}

/** One feed: a project's, or every project's. Collect [state]. */
class Feed internal constructor(
    val projectSlug: String?,
    private val dashboard: Dashboard,
    client: DashboardClient,
    private val scope: CoroutineScope,
    pageSize: Int,
) {
    private val timeline = TimelineReader(client, projectSlug, pageSize, scope) { dashboard.changed() }
    private val threads = ThreadsReader(client, projectSlug, { timeline.projects.firstOrNull { it.slug == projectSlug }?.id }, scope) { dashboard.changed() }
    internal val readers: List<FeedReader> get() = listOf(timeline, threads)

    init {
        timeline.cache = dashboard.cache
        threads.cache = dashboard.cache
    }

    private val mutableState = MutableStateFlow(FeedState.empty(projectSlug))
    val state: StateFlow<FeedState> = mutableState.asStateFlow()

    /** Say whether the owner can see the top of the feed. While they can't, new updates wait in [FeedState.newCount]. */
    fun hold(holding: Boolean) {
        scope.launch { timeline.hold(holding) }
    }

    /** Shows the waiting new updates: what tapping the "N new" pill does. */
    fun flush() {
        scope.launch { timeline.flush() }
    }

    /** Reads one more page into the past. Does nothing at the end. */
    suspend fun loadOlder() = withContext(scope.coroutineContext) { timeline.loadOlder() }

    fun markRead(cardId: String) = dashboard.markRead(cardId)

    /** Lets go of a project feed whose screen has gone. The all-projects feed ignores this. */
    fun close() {
        scope.launch { dashboard.release(this@Feed) }
    }

    internal fun startReaders() = readers.forEach { it.start() }
    internal fun stopReaders() = readers.forEach { it.stop() }

    internal suspend fun refreshNow() {
        timeline.refreshNow(replace = true)
        threads.refreshNow(replace = true)
    }

    internal fun receive(event: StreamEvent) {
        timeline.receive(event)
        threads.receive(event)
    }

    internal fun projects(): List<ProjectView> = timeline.projects
    internal fun agentNames(): Map<String, String> = timeline.agentNames

    // --- Building cards ---------------------------------------------------------------------

    /** The owner's posts and replies still on their way, set before each publish. */
    internal var drafts: List<OutboxItem> = emptyList()

    private var index = ThreadIndex(MessagesSnapshot(seq = 0))
    private var indexedFrom: MessagesSnapshot? = null
    private var indexedDrafts: List<OutboxItem>? = null
    private var draftIds: Set<String> = emptySet()

    private fun indexed(): ThreadIndex {
        val snapshot = threads.snapshot
        if (indexedFrom !== snapshot || indexedDrafts !== drafts) {
            val lines = drafts.mapNotNull(::draftLine)
            draftIds = lines.mapTo(HashSet()) { it.id }
            index = ThreadIndex(if (lines.isEmpty()) snapshot else snapshot.copy(messages = snapshot.messages + lines))
            indexedFrom = snapshot
            indexedDrafts = drafts
        }
        return index
    }

    /** An outbox item as the line it will be once the dashboard has it. Null when it doesn't belong here. */
    private fun draftLine(item: OutboxItem): MessageView? {
        val thread = item.thread
        val slug = thread["project"]
        val projectId = slug?.let { s -> timeline.projects.firstOrNull { it.slug == s }?.id }
        if (slug != null && (projectId == null || projectSlug != null && slug != projectSlug)) return null
        return MessageView(
            id = DraftPrefix + item.id,
            projectId = projectId,
            updateId = thread["update"],
            taskId = thread["task"],
            replyTo = thread["replyTo"],
            requestId = thread["request"],
            answers = item.answers,
            author = HumanAuthor,
            body = item.text ?: item.files.joinToString(", ") { it.filename },
            createdAt = item.createdAtMillis,
        )
    }

    /** A post or reply the dashboard just took from the owner: shown now, not when the stream says so. */
    internal fun adopt(message: MessageView) {
        if (projectSlug != null) {
            val projectId = timeline.projects.firstOrNull { it.slug == projectSlug }?.id
            if (projectId == null || message.projectId != projectId) return
        }
        threads.adopt(message)
    }

    internal fun commentsOn(cardId: String): List<MessageView>? {
        val index = indexed()
        return when {
            timeline.items.any { it.id == cardId } -> index.byUpdate[cardId].orEmpty()
            index.byId[cardId]?.isPost == true -> index.byReply[cardId].orEmpty()
            index.byRequest.containsKey(cardId) -> index.byRequest[cardId].orEmpty()
            else -> null
        }
    }

    internal fun discussionOf(requestId: String, context: FeedContext): List<ThreadLine> =
        indexed().byRequest[requestId].orEmpty().map { line(it, context) }

    internal fun tagsOn(card: CardRef): List<TagView> {
        val byId = timeline.tags.associateBy { it.id }
        return timeline.cardTags.filter { it.card == card }.mapNotNull { byId[it.tagId] }
    }

    internal fun fieldsOn(card: CardRef): List<CardFieldView> = timeline.cardFields.filter { it.card == card }
    private fun appsOn(card: CardRef): List<AppView> = timeline.cardApps.filter { it.card == card }

    private fun line(message: MessageView, context: FeedContext): ThreadLine {
        val index = indexed()
        return ThreadLine(
            message = message,
            authorName = context.authorName(message.author),
            answersName = message.answers?.let { index.byId[it] }?.let { context.authorName(it.author) },
            media = index.snapshot.media[message.id].orEmpty(),
            embeds = index.snapshot.embeds[message.id].orEmpty(),
            reactions = index.reactions[message.id].orEmpty(),
            acks = context.acks(index.acks[message.id].orEmpty()),
            deliveries = index.deliveries[message.id].orEmpty(),
            questions = context.requests.filter { it.messageId == message.id }.map { context.requestCard(it, this) },
            sending = message.id in draftIds,
        )
    }

    internal fun publish(context: FeedContext, status: StreamStatus, requestsLoading: Boolean) {
        val index = indexed()
        val scoped = projectSlug != null
        val project = projectSlug?.let { slug -> timeline.projects.firstOrNull { it.slug == slug } }
        fun projectName(id: String?) = if (scoped) null else id?.let { context.projectsById[it]?.name }
        val marks = context.readMarks

        val arrived = timeline.arrived.toHashSet()
        val updateCards = timeline.items.map { update ->
            val comments = index.byUpdate[update.id].orEmpty()
            marks.seed(update.id, comments)
            val ref = CardRef(CardKinds.Update, update.id)
            UpdateCard(
                update = update,
                agentName = context.agentName(update.agentId),
                projectName = projectName(update.projectId),
                isNew = update.id in arrived,
                activityAt = lastActivityAt(update.createdAt, comments, update.statusAt),
                replies = comments.map { line(it, context) },
                unread = marks.unread(update.id, comments),
                tags = tagsOn(ref),
                fields = fieldsOn(ref),
                apps = appsOn(ref),
            )
        }
        val postCards = index.posts.map { post ->
            val comments = index.byReply[post.id].orEmpty()
            marks.seed(post.id, comments)
            val ref = CardRef(CardKinds.Message, post.id)
            PostCard(
                post = line(post, context),
                projectName = projectName(post.projectId),
                activityAt = lastActivityAt(post.createdAt, comments, post.statusAt),
                replies = comments.map { line(it, context) },
                unread = marks.unread(post.id, comments),
                tags = tagsOn(ref),
                fields = fieldsOn(ref),
                apps = appsOn(ref),
            )
        }
        val all: List<FeedCard> = updateCards + postCards
        val newestFirst = compareByDescending<FeedCard> { it.createdAt }.thenByDescending { it.id }
        val pinned = all.filter { it.pinned }.sortedWith(newestFirst)
        val cards = all.filter { !it.pinned }
            .sortedWith(compareByDescending<FeedCard> { it.activityAt }.thenByDescending { it.createdAt }.thenByDescending { it.id })

        // A slug that names no known project shows no questions, rather than every project's.
        val inScope = when {
            !scoped -> context.requests
            project != null -> context.requests.filter { it.projectId == project.id }
            else -> emptyList()
        }
        val waiting = inScope.filter { it.messageId == null }.map { context.requestCard(it, this) }

        mutableState.value = FeedState(
            projectSlug = projectSlug,
            project = project,
            status = status,
            loaded = timeline.loaded && threads.loaded,
            cached = (timeline.cached || timeline.loaded) && (threads.cached || threads.loaded) && !(timeline.loaded && threads.loaded),
            loading = timeline.loading || threads.loading || requestsLoading,
            loadingOlder = timeline.isLoadingOlder,
            waiting = waiting,
            pinned = pinned,
            cards = cards,
            newCount = timeline.pending.size,
            hasMore = timeline.hasMore,
            tags = timeline.tags,
        )
    }
}

/** A thread snapshot sorted into lookups once, rather than filtered per card. */
internal class ThreadIndex(val snapshot: MessagesSnapshot) {
    val byId = snapshot.messages.associateBy { it.id }
    val byUpdate = snapshot.messages.filter { it.updateId != null }.groupBy { it.updateId!! }
    val byReply = snapshot.messages.filter { it.replyTo != null }.groupBy { it.replyTo!! }
    val byRequest = snapshot.messages.filter { it.requestId != null }.groupBy { it.requestId!! }
    val posts = snapshot.messages.filter { it.isPost }
    val reactions = snapshot.reactions.filter { it.messageId != null }.groupBy { it.messageId!! }
    val acks = snapshot.acks.filter { it.messageId != null }.groupBy { it.messageId!! }
    val deliveries = snapshot.deliveries.groupBy { it.messageId }
}

/** When a card last had something happen: posted, a reply, or its work state changing. */
internal fun lastActivityAt(createdAt: Long, replies: List<MessageView>, statusAt: Long?): Long =
    maxOf(createdAt, replies.maxOfOrNull { it.createdAt } ?: createdAt, statusAt ?: createdAt)
