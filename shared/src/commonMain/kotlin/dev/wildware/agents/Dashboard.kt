package dev.wildware.agents

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** The id a line still in the outbox goes by until the dashboard gives it its own. */
const val DraftPrefix = "outbox:"

/** The status of a file on a line still in the outbox: it is on this device, going up. Never the server's. */
const val UploadingStatus = "uploading"

/** What the dashboard will call a file of type [mime]: "image", "video", "audio" or "file". */
fun mediaKindOf(mime: String): String = when (mime.substringBefore(';').trim().lowercase()) {
    "image/png", "image/jpeg", "image/webp", "image/gif" -> "image"
    "video/mp4", "video/webm", "video/quicktime" -> "video"
    "audio/mpeg", "audio/mp4", "audio/ogg", "audio/wav", "audio/x-m4a", "audio/m4a", "audio/x-wav", "audio/wave", "audio/vorbis", "audio/opus" -> "audio"
    else -> "file"
}

/** A sent voice note's unsure words, and the text they were placed in: once the text changes otherwise, they no longer fit. */
internal data class SentWords(val text: String, val words: List<VoiceWord>)

/** How many sent voice notes keep their unsure words: the newest. */
private const val MostSentWords = 50

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
    /** While [sending]: how much of its files have gone up, 0 to 1. Null when none are going up now. */
    val uploaded: Float? = null,
    /** Other cards the line points at. */
    val refs: List<CardRefView> = emptyList(),
    /** Blocks drawn on the line, newest first-hand (docs/BLOCKS.md). */
    val blocks: List<BlockView> = emptyList(),
    /** While [sending]: a voice note waiting for an Undo until then (epoch millis). Null when it can't be undone. */
    val undoUntil: Long? = null,
    /** The owner's own voice note, waiting or sent from this device: the words the phone wasn't sure of, to offer others. */
    val words: List<VoiceWord> = emptyList(),
    /** Secrets an agent attached to the line, by name (migration 066). */
    val secrets: List<SharedSecretView> = emptyList(),
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
    /**
     * On a team (migration 055): this feed's questions answered lately, newest answer first, each
     * saying who answered. Only those answered since the oldest card read, so they sit among them.
     */
    val answered: List<RequestCard> = emptyList(),
    /**
     * The card open on its own ([Feed.openAlone]): one the feed hasn't paged to, read with its
     * thread by itself; or the feed's own card when it holds that one. Never among [cards].
     */
    val lone: FeedCard? = null,
) {
    /** Every card, pinned first, in order. */
    val allCards: List<FeedCard> get() = pinned + cards

    fun card(id: String): FeedCard? = allCards.firstOrNull { it.id == id } ?: lone?.takeIf { it.id == id }

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
    private val bell = NotificationsReader(client, confined, ::changed)
    private val joining = PairingsReader(client, confined, ::changed)
    private val follows = FollowsReader(client, confined, ::changed)
    private val blocks = BlocksOverlay(client, confined, ::changed, ::heldBlocks)
    /**
     * Every open feed, by project slug. The app opens and closes feeds from its own thread while the
     * engine's thread reads them, so the map is never changed in place: each change swaps in a new one.
     */
    private val openFeeds = MutableStateFlow<Map<String?, Feed>>(emptyMap())
    private val feeds: Map<String?, Feed> get() = openFeeds.value
    private var timers: Job? = null
    private var now = clock()

    private val mutableWaiting = MutableStateFlow<List<RequestCard>>(emptyList())
    private val mutableOnline = MutableStateFlow<List<LiveAgentView>>(emptyList())
    private val mutableProjects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    private val mutableGroups = MutableStateFlow<List<GroupView>>(emptyList())

    /** Projects: named groups of channels, by name. Each channel says which it is in ([ProjectView.groupId]). */
    val groups: StateFlow<List<GroupView>> = mutableGroups.asStateFlow()

    /** Every question waiting on the owner, longest waiting first, wherever it was asked. */
    val waiting: StateFlow<List<RequestCard>> = mutableWaiting.asStateFlow()

    /** Agents beating right now. */
    val online: StateFlow<List<LiveAgentView>> = mutableOnline.asStateFlow()

    private val mutableAgentNames = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Every agent the dashboard has named, by id, online or not: who a message can @mention. */
    val agentNames: StateFlow<Map<String, String>> = mutableAgentNames.asStateFlow()

    /** The sidebar: every project (archived ones last), with its badges. */
    val projects: StateFlow<List<ProjectSummary>> = mutableProjects.asStateFlow()

    private val mutableNotifications = MutableStateFlow(NotificationList())

    private val mutablePairings = MutableStateFlow<List<PairingView>>(emptyList())

    /** Agents asking to join, oldest first, each waiting on the owner to allow or deny it. */
    val pairings: StateFlow<List<PairingView>> = mutablePairings.asStateFlow()

    /** The bell: what the owner was told about, newest first, and how many are new. */
    val notifications: StateFlow<NotificationList> = mutableNotifications.asStateFlow()

    private val mutableFollows = MutableStateFlow(Follows())

    /** What the owner follows: which projects and threads raise their alerts. */
    val followed: StateFlow<Follows> = mutableFollows.asStateFlow()

    /**
     * Ticks or unticks one line of a checklist block on [card]. Shown at once; the server's answer
     * replaces it, and a refusal puts it back.
     */
    fun checkBlockItem(card: CardRef, blockId: String, itemId: String, done: Boolean) {
        val part = "item:$itemId"
        confined.launch {
            blocks.expect(card, blockId, part) { block -> block.copy(items = block.items.map { if (it.id == itemId) it.copy(done = done) else it }) }
            try {
                val answered = client.checkBlockItem(card, blockId, itemId, done)
                blocks.answered(card, answered, heldBlocks(card).orEmpty())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Refused or offline: the line goes back to how the server has it.
            } finally {
                blocks.settle(card, blockId, part)
            }
        }
    }

    /** Votes on a poll block on [card]: [options] replaces your vote; none takes it back. Shown at once. */
    fun voteBlock(card: CardRef, blockId: String, options: List<String>, me: String? = null) {
        confined.launch {
            blocks.expect(card, blockId, "vote") { block -> block.withVote(options, me) }
            try {
                val answered = client.voteBlock(card, blockId, options)
                blocks.answered(card, answered, heldBlocks(card).orEmpty())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Refused (the poll closed) or offline: the vote goes back to how the server has it.
            } finally {
                blocks.settle(card, blockId, "vote")
            }
        }
    }

    /** The blocks on [card] as the open feeds and questions last read them, or null when none holds it. */
    private fun heldBlocks(card: CardRef): List<BlockView>? = when (card.kind) {
        CardKinds.Request -> (requests.items + requests.answered).firstOrNull { it.id == card.id }?.blocks
        else -> feeds.values.firstNotNullOfOrNull { it.blocksRead(card) }
    }

    /** Follows or unfollows the thread [card] is in (`update`, `message` or `request`). Shown at once. */
    fun followThread(card: CardRef, following: Boolean) {
        confined.launch {
            follows.assume { it.withThread(card.kind, card.id, following) }
            runCatching { client.followThread(card, following) }
            follows.reread()
        }
    }

    /** Sets how much of a channel alerts the owner; null follows its project. Shown at once. */
    fun setProjectNotify(projectId: String, slug: String, level: NotifyLevel?) {
        confined.launch {
            follows.assume { it.withLevel(projectId, level) }
            runCatching { client.setProjectNotify(slug, level) }
            follows.reread()
        }
    }

    /** Sets how much of a project (a group of channels) alerts the owner; null clears it. Shown at once. */
    fun setGroupNotify(groupId: String, slug: String, level: NotifyLevel?) {
        confined.launch {
            follows.assume { it.withGroupLevel(groupId, level) }
            runCatching { client.setGroupNotify(slug, level) }
            follows.reread()
        }
    }

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
        bell.start()
        joining.start()
        follows.start()
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
            confined.launch { queue.uploads.collect { changed() } }
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
        drafts = items.filter { !it.gaveUp && it.id !in delivered && (it.kind == OutboxKind.Message || it.kind == OutboxKind.Ticket || it.kind == OutboxKind.Edit) }
        return drafts
    }

    /**
     * The unsure words of voice notes sent from here, by message id, with the words they were
     * placed in: kept on this device while the app runs, so a word can still be swapped once the
     * note is up. Never sent anywhere.
     */
    private val sentWords = LinkedHashMap<String, SentWords>()

    /**
     * Settles an unsure word in the owner's own sent voice note: [choice] in its place, or null to
     * keep it. Gives back the note's new words for an edit, or null when nothing changed.
     */
    fun chooseSentWord(messageId: String, index: Int, choice: String?): String? {
        val kept = sentWords[messageId] ?: return null
        val chosen = VoiceWords.choose(kept.text, kept.words, index, choice)
        sentWords[messageId] = SentWords(chosen.text, chosen.words)
        changed()
        return chosen.text.takeIf { it != kept.text }
    }

    private fun sent(item: OutboxItem, result: DeliveryResult) {
        val message = result.sent ?: return
        if (item.words.isNotEmpty() && message.body == item.text) {
            sentWords[message.id] = SentWords(message.body, item.words)
            while (sentWords.size > MostSentWords) sentWords.remove(sentWords.keys.first())
        }
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
    fun feed(projectSlug: String?): Feed {
        feeds[projectSlug]?.let { return it }
        val made = Feed(projectSlug, this, client, confined, pageSize)
        val feed = openFeeds.updateAndGet { if (projectSlug in it) it else it + (projectSlug to made) }.getValue(projectSlug)
        if (feed === made && started) made.startReaders()
        return feed
    }

    internal fun release(feed: Feed) {
        if (feed.projectSlug == null) return
        feed.stopReaders()
        openFeeds.update { if (it[feed.projectSlug] === feed) it - feed.projectSlug else it }
    }

    /** Re-reads everything now, as a pull-to-refresh does. */
    suspend fun refresh() = withContext(confined.coroutineContext) {
        requests.refreshNow(replace = true)
        presence.refreshNow(replace = true)
        bell.refreshNow(replace = true)
        joining.refreshNow(replace = true)
        follows.refreshNow(replace = true)
        feeds.values.forEach { it.refreshNow() }
    }

    /**
     * Marks [ids] seen, or all of them for null. The list is read again afterwards rather than
     * edited here, so a clear that failed never shows as done: the bell stays lit.
     */
    fun markNotificationsSeen(ids: List<String>?) {
        confined.launch {
            val done = try {
                client.markNotificationsSeen(ids)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                false
            }
            if (done) bell.refreshNow(replace = true)
        }
    }

    /**
     * Marks a card's comments as seen: its unread badge clears. The dashboard hears of it too, a
     * moment later with any others opened meanwhile, so the bell and every device's notifications
     * about the card clear ([clearedNotices]).
     */
    fun markRead(cardId: String) {
        confined.launch {
            feeds.values.firstNotNullOfOrNull { it.commentsOn(cardId) }?.let { readMarks.markRead(cardId, it) }
            changed()
            cardsSeen += cardId
            if (cardsSeenSend?.isActive != true) cardsSeenSend = confined.launch { sendCardsSeen() }
        }
    }

    /** Lights card [cardId] up again from its newest reply by someone else: the menu's "Mark unread". */
    fun markUnread(cardId: String) {
        confined.launch {
            feeds.values.firstNotNullOfOrNull { it.commentsOn(cardId) }?.let { readMarks.markUnread(cardId, it) }
            changed()
        }
    }

    /** Cards opened and not yet told to the dashboard. */
    private val cardsSeen = LinkedHashSet<String>()
    private var cardsSeenSend: Job? = null

    private suspend fun sendCardsSeen() {
        delay(CardsSeenBatchMillis)
        val cards = cardsSeen.toList()
        cardsSeen.clear()
        // Only a convenience: a card that fails stays lit in the bell, where the owner can clear it.
        for (chunk in cards.chunked(MaxClearTags)) {
            try {
                client.markCardsSeen(chunk)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
            }
        }
    }

    private val mutableCleared = MutableSharedFlow<List<String>>(extraBufferCapacity = 64)

    /**
     * Tags of notifications the owner has dealt with, on this device or another: read, answered,
     * allowed, denied, withdrawn or deleted. A device takes the notifications it showed with those
     * tags off its screen.
     */
    val clearedNotices: SharedFlow<List<String>> = mutableCleared.asSharedFlow()

    private val mutableBoardChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    /**
     * Something that can move a card on the board happened: a card made, changed or removed, a
     * question asked or settled, an agent starting to think, the board's own setting, who can see
     * what. The board reads itself again when it hears this.
     */
    val boardChanges: SharedFlow<Unit> = mutableBoardChanges.asSharedFlow()

    private fun readers(): List<FeedReader> = listOf(requests, presence, bell, joining, follows) + feeds.values.flatMap { it.readers }

    private suspend fun receive(event: StreamEvent) {
        if (event.type == ClearedEvent) clearedTags(event)?.let { mutableCleared.tryEmit(it) }
        if (event.type in BoardEvents) mutableBoardChanges.tryEmit(Unit)
        requests.receive(event)
        presence.receive(event)
        bell.receive(event)
        joining.receive(event)
        follows.receive(event)
        blocks.receive(event)
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
        val uploads = outbox?.uploads?.value.orEmpty()
        // One look at the open feeds, so one opened meanwhile waits for the next round whole.
        val open = feeds.values
        val words = sentWords.toMap()
        open.forEach {
            it.drafts = pending
            it.uploads = uploads
            it.sentWords = words
        }
        val names = names()
        if (mutableAgentNames.value != names) mutableAgentNames.value = names
        val onlineAgents = presence.agents.filter { it.lastHeartbeatAt >= now - PresenceReader.WindowMillis }
        mutableOnline.value = onlineAgents
        val projectsById = everything.projects().associateBy { it.id }
        val sending = sendingRequestIds()
        val onlineIds = onlineAgents.mapTo(HashSet()) { it.agentId }
        fun withBlocks(request: RequestView) = request.copy(blocks = blocks.on(CardRef(CardKinds.Request, request.id), request.blocks))
        val context = FeedContext(
            names, projectsById, sending, onlineIds, readMarks, requests.items.map(::withBlocks), requests.answered.map(::withBlocks), blocks::on,
        )
        mutableWaiting.value = context.requests.map { context.requestCard(it, everything) }
        open.forEach { it.publish(context, stream.status.value, requests.loading) }
        mutableProjects.value = summaries(everything.state.value)
        mutableNotifications.value = bell.list
        if (mutablePairings.value != joining.items) mutablePairings.value = joining.items
        val groupOf = everything.projects().mapNotNull { p -> p.groupId?.let { p.id to it } }.toMap()
        val followed = follows.follows.copy(groupOf = groupOf)
        if (mutableFollows.value != followed) mutableFollows.value = followed
        // As the dashboard lists them: not arranged yet first, by name, then the arranged ones in place.
        val groups = everything.groups().sortedWith(compareBy({ it.position != null }, { it.position ?: 0L }, { it.name.lowercase() }))
        if (mutableGroups.value != groups) mutableGroups.value = groups
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

        /** Cards opened within this long of each other are told to the dashboard together. */
        const val CardsSeenBatchMillis = 1_000L

        /** `notifications.cleared {tags}`: notifications the owner has dealt with somewhere. */
        const val ClearedEvent = "notifications.cleared"

        fun clearedTags(event: StreamEvent): List<String>? =
            (event.payload?.get("tags") as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?.takeIf { it.isNotEmpty() }
    }
}

/** What every feed needs to build its cards, worked out once per change. */
/** The bell: what the owner was told about, newest first, and how many of them are new. */
data class NotificationList(
    val items: List<NotificationView> = emptyList(),
    val unseen: Int = 0,
)

internal class FeedContext(
    private val names: Map<String, String>,
    val projectsById: Map<String, ProjectView>,
    private val sending: Set<String>,
    private val onlineIds: Set<String>,
    val readMarks: ReadMarks,
    val requests: List<RequestView>,
    /** Answered lately, on a team. */
    val answered: List<RequestView> = emptyList(),
    /** A card's blocks as read, with anything newer over them ([BlocksOverlay]). */
    val blocks: (CardRef, List<BlockView>) -> List<BlockView> = { _, read -> read },
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
    private val client: DashboardClient,
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
        if (aloneRead != null && (event.type == StreamEvent.Resync || mentionsAlone(event.payload, 0))) rereadAlone()
    }

    // --- A card on its own ------------------------------------------------------------------

    /** The card open on its own, by id; null when none is. */
    private var aloneId: String? = null

    /** That card and its thread as read by itself; null while this feed holds the card. */
    private var aloneRead: CardThread? = null
    private var aloneReading = false
    private var aloneStale = false
    private var aloneIndex: ThreadIndex? = null
    private var aloneIndexFrom: Pair<CardThread, ThreadIndex>? = null

    /** The thread lines of the card on its own as last built, for marking it read. */
    private var aloneComments: List<MessageView>? = null

    /**
     * Opens the card [id] is (or is in: a reply's id names its card) on its own, in [FeedState.lone]:
     * the card this feed holds, else the card and its thread read by themselves. Never pages the
     * feed. Answers the card's id, or null when the dashboard has no such card for this viewer.
     */
    @Throws(Exception::class)
    suspend fun openAlone(id: String): String? = withContext(scope.coroutineContext) {
        heldCardOf(id)?.let { held ->
            aloneId = held
            aloneRead = null
            dashboard.changed()
            return@withContext held
        }
        val read = client.thread(id) ?: return@withContext null
        aloneId = read.card.id
        aloneRead = read
        dashboard.changed()
        read.card.id
    }

    /** Puts the card on its own away again. */
    fun closeAlone() {
        scope.launch {
            if (aloneId == null) return@launch
            aloneId = null
            aloneRead = null
            aloneIndex = null
            aloneIndexFrom = null
            aloneComments = null
            dashboard.changed()
        }
    }

    /** The card in this feed that is [id] or holds it as a line. */
    private fun heldCardOf(id: String): String? {
        val shown = mutableState.value
        shown.allCards.firstOrNull { it.id == id }?.let { return it.id }
        return shown.allCards.firstOrNull { card -> card.replies.any { it.message.id == id } }?.id
    }

    /** Whether a live event names the card on its own or a line in its thread, anywhere in its payload. */
    private fun mentionsAlone(element: kotlinx.serialization.json.JsonElement?, depth: Int): Boolean {
        val read = aloneRead ?: return false
        return when (element) {
            is kotlinx.serialization.json.JsonPrimitive -> element.isString && (element.content == read.card.id || read.thread.messages.any { it.id == element.content })
            is kotlinx.serialization.json.JsonObject -> depth < 3 && element.values.any { mentionsAlone(it, depth + 1) }
            is kotlinx.serialization.json.JsonArray -> depth < 3 && element.any { mentionsAlone(it, depth + 1) }
            else -> false
        }
    }

    /** Reads the card on its own again, once at a time; a change meanwhile reads it once more after. */
    private fun rereadAlone() {
        if (aloneReading) {
            aloneStale = true
            return
        }
        val id = aloneId ?: return
        aloneReading = true
        scope.launch {
            try {
                do {
                    aloneStale = false
                    val read = runCatching { client.thread(id) }
                    if (aloneId != id || aloneRead == null) break
                    read.onSuccess { found ->
                        // Gone, or hidden from here now: it closes.
                        if (found == null) aloneId = null
                        aloneRead = found
                        dashboard.changed()
                    }
                } while (aloneStale)
            } finally {
                aloneReading = false
            }
        }
    }

    /**
     * The thread index for the card on its own: what was read with it, with this feed's own lines
     * over it (newer, and the owner's replies still on their way).
     */
    private fun aloneIndexOf(read: CardThread, feed: ThreadIndex): ThreadIndex {
        aloneIndex?.takeIf { aloneIndexFrom?.let { it.first === read && it.second === feed } == true }?.let { return it }
        val own = read.thread
        val live = feed.snapshot
        val liveIds = live.messages.mapTo(HashSet()) { it.id }
        val merged = MessagesSnapshot(
            seq = maxOf(own.seq, live.seq),
            messages = (own.messages.filter { it.id !in liveIds } + live.messages).sortedBy { it.createdAt },
            acks = own.acks.filter { it.messageId !in liveIds } + live.acks,
            media = own.media + live.media,
            embeds = own.embeds + live.embeds,
            deliveries = own.deliveries.filter { it.messageId !in liveIds } + live.deliveries,
            reactions = own.reactions.filter { it.messageId !in liveIds } + live.reactions,
            refs = own.refs + live.refs,
            blocks = own.blocks + live.blocks,
            secrets = own.secrets + live.secrets,
        )
        return ThreadIndex(merged).also {
            aloneIndex = it
            aloneIndexFrom = read to feed
        }
    }

    /** The card on its own, built as the feed builds its own. Null when the read holds no such card. */
    private fun aloneCard(read: CardThread, context: FeedContext, feed: ThreadIndex): FeedCard? {
        val index = aloneIndexOf(read, feed)
        val marks = context.readMarks
        val ref = read.card
        val tagsById = (timeline.tags + read.feed.tags.orEmpty()).associateBy { it.id }
        val tags = read.feed.cardTags.orEmpty().filter { it.card == ref }.mapNotNull { tagsById[it.tagId] }
        val fields = read.feed.cardFields.orEmpty().filter { it.card == ref }
        val apps = read.feed.cardApps.orEmpty().filter { it.card == ref }
        fun projectName(id: String?) = id?.let { context.projectsById[it]?.name }
        return when (ref.kind) {
            CardKinds.Update -> {
                val update = read.feed.updates.items.firstOrNull { it.id == ref.id } ?: return null
                val comments = index.byUpdate[update.id].orEmpty()
                aloneComments = comments
                marks.seed(update.id, comments)
                val blocks = context.blocks(ref, update.blocks)
                UpdateCard(
                    update = if (blocks === update.blocks) update else update.copy(blocks = blocks),
                    agentName = read.feed.agentNames?.get(update.agentId) ?: context.agentName(update.agentId),
                    projectName = projectName(update.projectId),
                    isNew = false,
                    activityAt = lastActivityAt(update.createdAt, comments, update.statusAt),
                    replies = comments.map { line(it, context, index) },
                    unread = marks.unread(update.id, comments),
                    tags = tags,
                    fields = fields,
                    apps = apps,
                )
            }
            CardKinds.Message -> {
                val post = index.byId[ref.id] ?: return null
                val comments = index.byReply[post.id].orEmpty()
                aloneComments = comments
                marks.seed(post.id, comments)
                PostCard(
                    post = line(post, context, index),
                    projectName = projectName(post.projectId),
                    activityAt = lastActivityAt(post.createdAt, comments, post.statusAt),
                    replies = comments.map { line(it, context, index) },
                    unread = marks.unread(post.id, comments),
                    tags = tags,
                    fields = fields,
                    apps = apps,
                )
            }
            else -> null
        }
    }

    internal fun projects(): List<ProjectView> = timeline.projects
    internal fun groups(): List<GroupView> = timeline.groups
    internal fun agentNames(): Map<String, String> = aloneRead?.feed?.agentNames.orEmpty() + timeline.agentNames

    // --- Building cards ---------------------------------------------------------------------

    /** The owner's posts and replies still on their way, set before each publish. */
    internal var drafts: List<OutboxItem> = emptyList()

    /** How far each of those has got with its files, by outbox id; set with [drafts]. */
    internal var uploads: Map<String, Float> = emptyMap()

    /** Unsure words of the owner's sent voice notes, by message id; set with [drafts]. */
    internal var sentWords: Map<String, SentWords> = emptyMap()

    private var index = ThreadIndex(MessagesSnapshot(seq = 0))
    private var indexedFrom: MessagesSnapshot? = null
    private var indexedDrafts: List<OutboxItem>? = null
    private var draftIds: Set<String> = emptySet()

    /** The owner's edits still on their way, by the message they change. */
    private var pendingEdits: Map<String, OutboxItem> = emptyMap()

    /** Voice notes still waiting for an Undo: the line's id to when the wait ends. */
    private var draftHolds: Map<String, Long> = emptyMap()

    /** Those notes' unsure words, by line id. */
    private var draftWords: Map<String, List<VoiceWord>> = emptyMap()

    private fun indexed(): ThreadIndex {
        val snapshot = threads.snapshot
        if (indexedFrom !== snapshot || indexedDrafts !== drafts) {
            val lines = drafts.filter { it.kind != OutboxKind.Edit }.mapNotNull(::draftLine)
            // An edit on its way shows at once; the newest wins.
            pendingEdits = drafts.filter { it.kind == OutboxKind.Edit && it.text != null }.associate { it.card?.id.orEmpty() to it }
            draftIds = lines.mapTo(HashSet()) { it.id }
            draftHolds = drafts.filter { it.holdUntilMillis > 0 && DraftPrefix + it.id in draftIds }.associate { DraftPrefix + it.id to it.holdUntilMillis }
            draftWords = drafts.filter { it.words.isNotEmpty() && DraftPrefix + it.id in draftHolds }.associate { DraftPrefix + it.id to it.words }
            // A draft's files show where they will be, saying they are going up, until the dashboard has them.
            val media = drafts.filter { it.files.isNotEmpty() && DraftPrefix + it.id in draftIds }.associate { item ->
                DraftPrefix + item.id to item.files.mapIndexed { i, file ->
                    MediaView(
                        id = "${DraftPrefix}${item.id}/$i",
                        messageId = DraftPrefix + item.id,
                        kind = mediaKindOf(file.mimeType),
                        mime = file.mimeType,
                        status = UploadingStatus,
                        filename = file.filename,
                    )
                }
            }
            index = ThreadIndex(
                if (lines.isEmpty()) snapshot else snapshot.copy(messages = snapshot.messages + lines, media = snapshot.media + media),
            )
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
            // Its files show under it, by name, so the words are only what was written.
            body = item.text.orEmpty(),
            createdAt = item.createdAtMillis,
            source = item.source,
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
            cardId == aloneId -> aloneComments
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

    /** The blocks on [card] as this feed last read them, or null when it doesn't hold the card. */
    internal fun blocksRead(card: CardRef): List<BlockView>? = when (card.kind) {
        CardKinds.Update -> timeline.items.firstOrNull { it.id == card.id }?.blocks
        CardKinds.Message -> indexed().snapshot.blocks[card.id] ?: indexed().byId[card.id]?.let { emptyList() }
        else -> null
    }

    private fun appsOn(card: CardRef): List<AppView> = timeline.cardApps.filter { it.card == card }

    private fun line(stored: MessageView, context: FeedContext, index: ThreadIndex = indexed()): ThreadLine {
        val edit = pendingEdits[stored.id]
        val message = if (edit == null) stored else stored.copy(body = edit.text.orEmpty(), editedAt = edit.createdAtMillis)
        return ThreadLine(
            message = message,
            authorName = context.authorName(message.author),
            answersName = message.answers?.let { index.byId[it] }?.let { context.authorName(it.author) },
            media = index.snapshot.media[message.id].orEmpty(),
            embeds = index.snapshot.embeds[message.id].orEmpty(),
            refs = index.snapshot.refs[message.id].orEmpty(),
            blocks = context.blocks(CardRef(CardKinds.Message, message.id), index.snapshot.blocks[message.id].orEmpty()),
            reactions = index.reactions[message.id].orEmpty(),
            acks = context.acks(index.acks[message.id].orEmpty()),
            deliveries = index.deliveries[message.id].orEmpty(),
            questions = context.requests.filter { it.messageId == message.id }.map { context.requestCard(it, this) },
            sending = message.id in draftIds,
            uploaded = if (message.id in draftIds) uploads[message.id.removePrefix(DraftPrefix)] else null,
            undoUntil = draftHolds[message.id],
            words = if (message.id in draftHolds) draftWords[message.id].orEmpty()
            else sentWords[message.id]?.takeIf { it.text == message.body }?.words.orEmpty(),
            secrets = index.snapshot.secrets[message.id].orEmpty(),
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
            val blocks = context.blocks(ref, update.blocks)
            UpdateCard(
                update = if (blocks === update.blocks) update else update.copy(blocks = blocks),
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
        // Answered ones sit among the cards by when they were answered: none from before the oldest card read.
        val oldest = if (timeline.hasMore) cards.minOfOrNull { it.activityAt } else null
        val answered = context.answered
            .filter { !scoped || (project != null && it.projectId == project.id) }
            .filter { oldest == null || (it.answeredAt ?: 0) >= oldest }
            .map { context.requestCard(it, this) }

        // The card on its own: this feed's own when it holds it, else the one read by itself.
        val lone = aloneId?.let { id -> all.firstOrNull { it.id == id } ?: aloneRead?.let { aloneCard(it, context, index) } }

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
            answered = answered,
            lone = lone,
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

/** The live events that can change what the board shows. */
internal val BoardEvents = setOf(
    "update.created", "update.updated", "update.deleted", "message.created", "message.updated", "message.deleted",
    "request.created", "request.updated", "request.answered", "ack.updated", "board.settings", "project.updated", AccessUpdated, StreamEvent.Resync,
)
