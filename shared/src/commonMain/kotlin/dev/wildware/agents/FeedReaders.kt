package dev.wildware.agents

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/*
 * The four readers behind the feed, ported from the website's stores: the timeline
 * (timeline.svelte.ts), the threads (threads.svelte.ts), the questions (requests.svelte.ts) and
 * presence (presence.svelte.ts). Two rules shape all of them:
 *
 * 1. Events carry ids, not data. An arrival is a reason to re-read, never a row to show. Re-reading
 *    and matching by id is what makes a doubled, replayed or out-of-order event harmless.
 * 2. Every read is stamped with the event seq it is good to. An event at or below it is already in
 *    hand, so it is dropped: the replay a reconnect brings costs nothing.
 *
 * All of them run on one thread (the engine's confined scope), so none of this state is locked.
 */

/**
 * One re-read per burst of events, and never two at once. A request while one is running is
 * remembered and run after it, because the running one was built before the event that asked.
 * "replace" wins over "merge": rebuilding from the server's state satisfies every merge too.
 */
internal class Refresher(private val scope: CoroutineScope, private val run: suspend (replace: Boolean) -> Unit) {
    private var queued: Boolean? = null
    private var running = false
    private var again: Boolean? = null

    fun request(replace: Boolean) {
        val current = queued
        if (current != null) {
            queued = current || replace
            return
        }
        queued = replace
        scope.launch {
            val mode = queued ?: return@launch
            queued = null
            refresh(mode)
        }
    }

    suspend fun refresh(replace: Boolean) {
        if (running) {
            again = (again ?: false) || replace
            return
        }
        running = true
        try {
            run(replace)
        } finally {
            running = false
        }
        val next = again ?: return
        again = null
        refresh(next)
    }

    fun cancel() {
        queued = null
        again = null
    }
}

/**
 * A child of [scope] that runs one step at a time on [scope]'s dispatcher, so state touched only
 * from it needs no locks. A failure in one job doesn't cancel the others.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun confinedScope(scope: CoroutineScope): CoroutineScope {
    val dispatcher = scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.Default
    return CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + dispatcher.limitedParallelism(1))
}

internal abstract class FeedReader(scope: CoroutineScope, protected val onChange: () -> Unit) {
    /** Where cache writes are scheduled: the engine's own thread. */
    protected val cacheScope: CoroutineScope = scope

    /** The newest event seq this reader's state accounts for. */
    var seq: Long = 0
        protected set

    /** Whether a read has ever landed. Until then this reader holds nothing. */
    var loaded: Boolean = false
        protected set

    /** The last read failed: the next time the stream comes back, read again. */
    var failed: Boolean = false
        protected set

    var loading: Boolean = false
        private set

    /**
     * What this reader holds came from the device's [FeedCache], and no read has landed yet. It is
     * shown, but [loaded] stays false: nothing counts it as the server's word (no seq, no "ready").
     */
    var cached: Boolean = false
        private set

    /** Where this reader keeps its last good state between launches, or null for none. */
    internal var cache: FeedCache? = null
    private var restoreTried = false

    /** This reader's name in the cache, or null when it keeps nothing. */
    protected open val cacheKey: String? = null

    /** Keeps what this reader holds now in [cache]. */
    protected open fun saveTo(cache: FeedCache, key: String) {}

    /** Takes back what [saveTo] kept. Returns false when there was nothing to take. */
    protected open fun restoreFrom(cache: FeedCache, key: String): Boolean = false

    protected abstract val watched: Set<String>

    protected val refresher = Refresher(scope) { replace -> tracked { load(replace) } }

    /** Runs one read, keeping [loading] and [failed] honest. A failed read keeps what is on screen. */
    protected suspend fun tracked(read: suspend () -> Unit) {
        restoreOnce()
        loading = true
        onChange()
        try {
            read()
            failed = false
            cached = false
            val store = cache
            val key = cacheKey
            if (store != null && key != null && loaded) saveTo(store, key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Keep what is on screen. Losing the data would be worse than showing it a little old.
            failed = true
        } finally {
            loading = false
            onChange()
        }
    }

    /**
     * Before the first read goes out, shows what the cache kept from last time. Its seq is not
     * taken: every event still counts, and the read that follows replaces it all.
     */
    private fun restoreOnce() {
        if (restoreTried) return
        restoreTried = true
        val store = cache ?: return
        val key = cacheKey ?: return
        if (loaded) return
        val restored = runCatching { restoreFrom(store, key) }.getOrDefault(false)
        if (!restored) return
        seq = 0
        loaded = false
        cached = true
        onChange()
    }

    /** Asks for a read of everything this reader holds. */
    protected open fun requestRead(replace: Boolean) = refresher.request(replace)

    protected abstract suspend fun load(replace: Boolean)

    /** Whether this event is this reader's business beyond its type, e.g. a reaction on its kind of card. */
    protected open fun alsoWatches(event: StreamEvent): Boolean = false

    open fun receive(event: StreamEvent) {
        if (event.type !in watched && !alsoWatches(event)) return
        if (event.type != StreamEvent.Resync && event.seq > 0 && event.seq <= seq) return
        handle(event)
    }

    protected open fun handle(event: StreamEvent) = requestRead(replace = event.type == StreamEvent.Resync)

    fun start() = requestRead(replace = true)

    fun refreshIfStale() {
        if (!loaded || failed) requestRead(replace = !loaded)
    }

    open suspend fun refreshNow(replace: Boolean) = refresher.refresh(replace)

    open fun stop() = refresher.cancel()
}

internal fun JsonObject?.string(key: String): String? = this?.get(key)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

internal fun JsonObject?.cardKind(): String? = (this?.get("card") as? JsonObject).string("kind")

/** The timeline: projects, a page of updates, tags, notes and agent names. Holds arrivals while the reader is scrolled away. */
internal class TimelineReader(
    private val client: DashboardClient,
    private val projectSlug: String?,
    private val limit: Int,
    scope: CoroutineScope,
    onChange: () -> Unit,
) : FeedReader(scope, onChange) {
    var projects: List<ProjectView> = emptyList(); private set
    /** Projects: named groups of channels. */
    var groups: List<GroupView> = emptyList(); private set
    var tags: List<TagView> = emptyList(); private set
    var cardTags: List<CardTagView> = emptyList(); private set
    var cardFields: List<CardFieldView> = emptyList(); private set
    var cardApps: List<AppView> = emptyList(); private set
    /** Newest first. What the feed shows. */
    var items: List<UpdateView> = emptyList(); private set
    /** Arrivals held back while the owner is scrolled away from the top. */
    var pending: List<UpdateView> = emptyList(); private set
    /** Ids that arrived live, so a card animates in once. */
    var arrived: List<String> = emptyList(); private set
    var agentNames: Map<String, String> = emptyMap(); private set
    var hasMore: Boolean = false; private set
    private var cursor: String? = null
    private var holding = false
    private var loadingOlder = false

    override val watched = setOf(
        "update.created", "update.updated", "update.deleted", "media.ready", "media.removed",
        "project.created", "project.updated", "group.created", "group.updated", "group.deleted", "tags.changed", "fields.changed", "apps.changed",
        "agent.renamed", AccessUpdated, StreamEvent.Resync,
    )

    // An emoji on an update rides on the update row, so it is a reason to re-read the page.
    override fun alsoWatches(event: StreamEvent) =
        event.type == "reaction.updated" && event.payload.cardKind() == CardKinds.Update

    override suspend fun load(replace: Boolean) = apply(client.snapshot(projectSlug, limit), replace)

    override val cacheKey = FeedCache.key("timeline", projectSlug)

    override fun saveTo(cache: FeedCache, key: String) = cache.save(
        key,
        FeedSnapshot(
            seq = seq,
            projects = projects, groups = groups, tags = tags, cardTags = cardTags, cardFields = cardFields, cardApps = cardApps,
            updates = UpdatesPage(pending + items, cursor, hasMore),
            agentNames = agentNames,
        ),
        FeedSnapshot.serializer(),
        cacheScope,
    )

    override fun restoreFrom(cache: FeedCache, key: String): Boolean {
        val kept = cache.read(key, FeedSnapshot.serializer()) ?: return false
        apply(kept, replace = true)
        return true
    }

    override fun handle(event: StreamEvent) {
        // One stream carries every project; a page showing one drops what isn't its business.
        // A picture that isn't on an update (the server says so with a null updateId) isn't either.
        if (elsewhere(event) || event.type in MediaEvents && notOnAnUpdate(event.payload)) {
            seq = maxOf(seq, event.seq)
            return
        }
        if (event.type == "update.deleted") {
            val id = event.payload.string("updateId") ?: return
            items = items.filter { it.id != id }
            pending = pending.filter { it.id != id }
            seq = maxOf(seq, event.seq)
            onChange()
            return
        }
        super.handle(event)
    }

    private fun notOnAnUpdate(payload: JsonObject?) = payload != null && "updateId" in payload && payload.string("updateId") == null

    /** An unknown project id is not "elsewhere": a project made since the page loaded must still re-read. */
    private fun elsewhere(event: StreamEvent): Boolean {
        val slug = projectSlug ?: return false
        val projectId = event.payload.string("projectId") ?: return false
        val scope = projects.firstOrNull { it.slug == slug } ?: return false
        return scope.id != projectId
    }

    fun hold(holding: Boolean) {
        this.holding = holding
        if (!holding) flush()
    }

    fun flush() {
        if (pending.isEmpty()) return
        val arrivals = pending
        pending = emptyList()
        insert(arrivals)
        onChange()
    }

    suspend fun loadOlder() {
        val from = cursor ?: return
        if (!hasMore || loadingOlder) return
        loadingOlder = true
        onChange()
        try {
            val page = client.olderUpdates(from, projectSlug, limit)
            page.agentNames?.let { agentNames = agentNames + it }
            val known = items.mapTo(HashSet()) { it.id }
            items = items + page.updates.items.filter { it.id !in known }
            cursor = page.updates.nextCursor
            hasMore = page.updates.hasMore
        } finally {
            loadingOlder = false
            onChange()
        }
    }

    val isLoadingOlder: Boolean get() = loadingOlder

    internal fun apply(snapshot: FeedSnapshot, replace: Boolean) {
        snapshot.projects?.let { projects = it }
        snapshot.groups?.let { groups = it }
        // Replaced whole, not merged: a tag retired on the server must leave every card.
        snapshot.tags?.let { tags = it }
        snapshot.cardTags?.let { cardTags = it }
        snapshot.cardFields?.let { cardFields = it }
        snapshot.cardApps?.let { cardApps = it }
        // Merged even on replace: a page that doesn't mention an agent isn't saying it lost its name.
        snapshot.agentNames?.let { agentNames = agentNames + it }
        // Adopted on replace (the answer to resync: a lower seq means the server restarted), raised on merge.
        seq = if (replace) snapshot.seq else maxOf(seq, snapshot.seq)
        loaded = true

        if (replace) {
            items = snapshot.updates.items
            pending = emptyList()
            arrived = emptyList()
            cursor = snapshot.updates.nextCursor
            hasMore = snapshot.updates.hasMore
            return
        }

        val incoming = snapshot.updates.items.associateBy { it.id }
        val known = items.mapTo(HashSet()) { it.id }
        val held = pending.mapTo(HashSet()) { it.id }
        items = items.map { incoming[it.id] ?: it }
        pending = pending.map { incoming[it.id] ?: it }
        val arrivals = snapshot.updates.items.filter { it.id !in known && it.id !in held }

        if (items.isEmpty() && pending.isEmpty()) {
            cursor = snapshot.updates.nextCursor
            hasMore = snapshot.updates.hasMore
        }
        if (arrivals.isEmpty()) return
        arrived = (arrived + arrivals.map { it.id }).takeLast(ArrivedMemory)
        if (holding) pending = arrivals + pending else insert(arrivals)
    }

    private fun insert(arrivals: List<UpdateView>) {
        val known = arrivals.mapTo(HashSet()) { it.id }
        items = (arrivals + items.filter { it.id !in known }).sortedByDescending { it.seq }
    }

    private companion object {
        const val ArrivedMemory = 200
    }
}

/** One thread the server can read on its own. */
internal sealed interface ThreadKey {
    /** An update's replies: `?update=`. */
    data class Update(val id: String) : ThreadKey

    /** The talk about a question: `?request=`. */
    data class Request(val id: String) : ThreadKey

    /** A post and the replies under it: `?replyTo=`. Empty once the post is deleted. Any other message answers with itself alone. */
    data class Post(val id: String) : ThreadKey

    /** A task's thread: `?task=`. */
    data class Task(val id: String) : ThreadKey
}

/**
 * Every thread in scope. Read whole when it starts and on `resync`; after that an event re-reads
 * only the thread it belongs to (`GET /api/messages?update=`, `?request=`, `?replyTo=` or `?task=`),
 * and only falls back to reading everything when it can't tell which thread that is (see [threadOf]).
 *
 * A thread read is not stamped onto [seq]: it accounts for one thread, and raising the cursor on
 * it would drop a later event for another thread that hasn't arrived yet.
 */
internal class ThreadsReader(
    private val client: DashboardClient,
    private val projectSlug: String?,
    private val projectId: () -> String?,
    private val scope: CoroutineScope,
    onChange: () -> Unit,
) : FeedReader(scope, onChange) {
    var snapshot: MessagesSnapshot = MessagesSnapshot(seq = 0); private set

    /** How many single-thread reads have run: tests and a debug screen read it. */
    var threadReads: Int = 0; private set

    override val watched = setOf(
        "message.created", "message.updated", "message.delivered", "message.deleted",
        "reaction.updated", "ack.updated", "media.ready", "media.removed", AccessUpdated, StreamEvent.Resync,
    )

    // Whole reads and thread reads go through one queue, so an older whole read can never land
    // on top of a newer thread read.
    private var wholeWanted = false
    private val wanted = LinkedHashSet<ThreadKey>()
    private var draining = false

    override suspend fun load(replace: Boolean) = apply(client.messages(projectSlug))

    override val cacheKey = FeedCache.key("threads", projectSlug)

    override fun saveTo(cache: FeedCache, key: String) = cache.save(key, snapshot, MessagesSnapshot.serializer(), scope)

    override fun restoreFrom(cache: FeedCache, key: String): Boolean {
        val kept = cache.read(key, MessagesSnapshot.serializer()) ?: return false
        // A cache written before project feeds kept to their own project may hold another's messages.
        val scope = projectSlug?.let { projectId() }
        apply(if (projectSlug == null || scope == null) kept else kept.copy(messages = kept.messages.filter { it.projectId == scope }))
        return true
    }

    override fun requestRead(replace: Boolean) {
        wholeWanted = true
        kick()
    }

    override suspend fun refreshNow(replace: Boolean) {
        wholeWanted = true
        if (draining) return
        draining = true
        try {
            drain()
        } finally {
            draining = false
        }
    }

    override fun stop() {
        wholeWanted = false
        wanted.clear()
    }

    override fun handle(event: StreamEvent) {
        if (event.type == StreamEvent.Resync) return requestRead(replace = true)
        if (event.type == "message.deleted") event.payload.string("messageId")?.let { adopted.remove(it) }
        // A project's feed leaves other projects' talk alone.
        val scopeId = projectSlug?.let { projectId() }
        val eventProject = event.payload.string("projectId")
        if (scopeId != null && eventProject != null && eventProject != scopeId) return
        when (val target = threadOf(event)) {
            Target.Nothing -> Unit
            Target.Everything -> requestRead(replace = false)
            is Target.Thread -> {
                wanted += target.key
                kick()
            }
        }
    }

    private companion object {
        val Anchors = listOf("updateId", "requestId", "replyTo", "taskId")
        val Oldest = compareBy<MessageView>({ it.createdAt }, { it.seq })
        const val MaxAdopted = 50
    }

    private sealed interface Target {
        data object Nothing : Target
        data object Everything : Target
        data class Thread(val key: ThreadKey) : Target
    }

    /**
     * Which thread an event is about. A message already held answers it from its own row; a new
     * one from its event, which names the thread (`updateId`, `requestId`, `replyTo`, `taskId`,
     * all null for a post). Anything else about a message this reader doesn't hold (a pin, an
     * emoji, an ack, possibly on a message whose own read is still on its way) reads that one
     * message: `?replyTo=<id>` answers with the message itself, and its replies if it is a post.
     */
    private fun threadOf(event: StreamEvent): Target {
        val payload = event.payload
        return when (event.type) {
            "message.created", "message.updated", "message.deleted", "message.delivered" -> {
                val id = payload.string("messageId") ?: return Target.Everything
                held(id) ?: fromPayload(payload)
            }
            "reaction.updated" -> {
                if (payload.cardKind() != CardKinds.Message) return Target.Nothing
                val id = payload.string("messageId") ?: (payload?.get("card") as? JsonObject).string("id") ?: return Target.Everything
                held(id) ?: alone(id)
            }
            "ack.updated" -> {
                val id = payload.string("messageId") ?: return Target.Nothing
                held(id) ?: alone(id)
            }
            "media.ready", "media.removed" -> {
                // Only a message's media: an update's or a question's is another reader's business.
                // A message not loaded yet gets its media with the read its own event asks for.
                val messageId = if (payload != null && "messageId" in payload) {
                    payload.string("messageId")
                } else {
                    // An older server doesn't say: look for the media among what is held.
                    val mediaId = payload.string("mediaId")
                    snapshot.media.entries.firstOrNull { (_, media) -> media.any { it.id == mediaId } }?.key
                }
                messageId?.let { held(it) ?: alone(it) } ?: Target.Nothing
            }
            else -> Target.Everything
        }
    }

    private fun held(messageId: String): Target? = snapshot.messages.firstOrNull { it.id == messageId }?.let(::anchorOf)

    /** Just that message (and its replies, if it is a post). */
    private fun alone(messageId: String): Target = Target.Thread(ThreadKey.Post(messageId))

    private fun anchorOf(message: MessageView): Target =
        Target.Thread(keyOf(message.id, message.updateId, message.requestId, message.replyTo, message.taskId))

    private fun fromPayload(payload: JsonObject?): Target {
        val messageId = payload.string("messageId")
        if (payload == null || messageId == null) return Target.Everything
        // A pin or status change on a message says only which message: read that message.
        if (Anchors.none { it in payload }) return alone(messageId)
        return Target.Thread(
            keyOf(messageId, payload.string("updateId"), payload.string("requestId"), payload.string("replyTo"), payload.string("taskId")),
        )
    }

    /** With no anchor at all, the message is a post: its own thread. */
    private fun keyOf(id: String, updateId: String?, requestId: String?, replyTo: String?, taskId: String?): ThreadKey = when {
        updateId != null -> ThreadKey.Update(updateId)
        requestId != null -> ThreadKey.Request(requestId)
        replyTo != null -> ThreadKey.Post(replyTo)
        taskId != null -> ThreadKey.Task(taskId)
        else -> ThreadKey.Post(id)
    }

    private fun kick() {
        if (draining) return
        draining = true
        scope.launch {
            try {
                drain()
            } finally {
                draining = false
            }
        }
    }

    private suspend fun drain() {
        while (wholeWanted || wanted.isNotEmpty()) {
            if (wholeWanted) {
                wholeWanted = false
                // A whole read covers every thread waiting to be read.
                wanted.clear()
                tracked { load(replace = true) }
                continue
            }
            // A project's feed that can't yet tell its own project's id can't sort a thread read's
            // messages by project: read its whole scope instead, which the server sorts.
            if (projectSlug != null && projectId() == null) {
                wanted.clear()
                wholeWanted = true
                continue
            }
            val key = wanted.first()
            wanted.remove(key)
            threadReads += 1
            tracked {
                val part = when (key) {
                    is ThreadKey.Update -> client.messages(updateId = key.id)
                    is ThreadKey.Request -> client.messages(requestId = key.id)
                    is ThreadKey.Post -> client.messages(replyTo = key.id)
                    is ThreadKey.Task -> client.messages(taskId = key.id)
                }
                merge(key, part)
            }
        }
    }

    internal fun apply(next: MessagesSnapshot) {
        snapshot = keepAdopted(next)
        seq = next.seq
        loaded = true
    }

    /**
     * What the owner just sent, as the dashboard answered the send: shown now, without waiting for
     * the stream. Held until a read includes it (or it is deleted), so a read that set off before the
     * send can't take it away again.
     */
    private val adopted = LinkedHashMap<String, MessageView>()

    fun adopt(message: MessageView) {
        adopted[message.id] = message
        while (adopted.size > MaxAdopted) adopted.remove(adopted.keys.first())
        if (snapshot.messages.none { it.id == message.id }) {
            snapshot = snapshot.copy(messages = (snapshot.messages + message).sortedWith(Oldest))
        }
    }

    private fun keepAdopted(next: MessagesSnapshot): MessagesSnapshot {
        if (adopted.isEmpty()) return next
        val read = next.messages.mapTo(HashSet()) { it.id }
        adopted.keys.removeAll { it in read }
        if (adopted.isEmpty()) return next
        return next.copy(messages = (next.messages + adopted.values).sortedWith(Oldest))
    }

    /** Swaps one thread for its fresh copy, leaving every other thread as it was. */
    private fun merge(key: ThreadKey, part: MessagesSnapshot) {
        fun inThread(message: MessageView) = when (key) {
            is ThreadKey.Update -> message.updateId == key.id
            is ThreadKey.Request -> message.requestId == key.id
            is ThreadKey.Post -> message.id == key.id || message.replyTo == key.id
            is ThreadKey.Task -> message.taskId == key.id
        }
        // A thread read isn't scoped to a project: some events (an ack, a finished picture) don't
        // say which project their message is in. A project's feed keeps only its own.
        val scope = projectSlug?.let { projectId() }
        val fresh = part.messages.filter { inThread(it) && (projectSlug == null || it.projectId == scope) }
        val replaced = snapshot.messages.filter(::inThread).mapTo(HashSet()) { it.id } + fresh.map { it.id }
        val current = snapshot
        snapshot = keepAdopted(current.copy(
            messages = (current.messages.filter { it.id !in replaced } + fresh).sortedWith(Oldest),
            acks = current.acks.filter { it.messageId !in replaced } + part.acks.filter { it.messageId in replaced },
            media = current.media.filterKeys { it !in replaced } + part.media.filterKeys { it in replaced },
            embeds = current.embeds.filterKeys { it !in replaced } + part.embeds.filterKeys { it in replaced },
            refs = current.refs.filterKeys { it !in replaced } + part.refs.filterKeys { it in replaced },
            blocks = current.blocks.filterKeys { it !in replaced } + part.blocks.filterKeys { it in replaced },
            secrets = current.secrets.filterKeys { it !in replaced } + part.secrets.filterKeys { it in replaced },
            deliveries = current.deliveries.filter { it.messageId !in replaced } + part.deliveries.filter { it.messageId in replaced },
            reactions = current.reactions.filter { it.messageId !in replaced } + part.reactions.filter { it.messageId in replaced },
        ))
    }
}

/** Every question waiting on the owner. Never scoped: a question belongs to the owner, not the page. */
internal class RequestsReader(
    private val client: DashboardClient,
    scope: CoroutineScope,
    onChange: () -> Unit,
) : FeedReader(scope, onChange) {
    var items: List<RequestView> = emptyList(); private set

    /** On a team: what was answered lately, with who answered. Empty on a team of one. */
    var answered: List<RequestView> = emptyList(); private set

    override val watched = setOf("request.created", "request.updated", "request.answered", AccessUpdated, StreamEvent.Resync)

    override fun alsoWatches(event: StreamEvent) =
        event.type == "reaction.updated" && event.payload.cardKind() == CardKinds.Request ||
            event.type in MediaEvents && (
                event.payload.string("requestId")?.let { id -> items.any { it.id == id } }
                    ?: event.payload.string("mediaId").let { id -> items.any { request -> request.media.any { it.id == id } } }
                ) ||
            pointsAtChanged(event)

    /**
     * A card a question points at changed: its video finished, it was edited or removed. The
     * question's summary of it is the server's, so read it again.
     */
    private fun pointsAtChanged(event: StreamEvent): Boolean {
        val refs = items.flatMap { it.refs }
        if (refs.isEmpty()) return false
        return when (event.type) {
            in MediaEvents -> event.payload.string("mediaId").let { id -> refs.any { it.media?.id == id } } ||
                listOf("messageId", "updateId").any { key -> event.payload.string(key)?.let { id -> refs.any { it.id == id } } == true }
            "message.updated", "message.deleted" -> event.payload.string("messageId").let { id -> refs.any { it.kind == CardKinds.Message && it.id == id } }
            "update.updated", "update.deleted" -> event.payload.string("updateId").let { id -> refs.any { it.kind == CardKinds.Update && it.id == id } }
            else -> false
        }
    }

    override fun handle(event: StreamEvent) {
        // Settled is settled: take it off at once rather than waiting for the re-read to say so.
        if (event.type == "request.answered") {
            val id = event.payload.string("requestId")
            if (id != null && items.any { it.id == id }) {
                items = items.filter { it.id != id }
                onChange()
            }
        }
        super.handle(event)
    }

    override suspend fun load(replace: Boolean) = apply(client.waitingRequests())

    override val cacheKey = "requests"

    override fun saveTo(cache: FeedCache, key: String) =
        cache.save(key, RequestsSnapshot(seq = seq, requests = items, answered = answered), RequestsSnapshot.serializer(), cacheScope)

    override fun restoreFrom(cache: FeedCache, key: String): Boolean {
        val kept = cache.read(key, RequestsSnapshot.serializer()) ?: return false
        apply(kept)
        return true
    }

    internal fun apply(next: RequestsSnapshot) {
        items = next.requests
        answered = next.answered
        seq = next.seq
        loaded = true
    }
}

/**
 * Agents asking to join, waiting on the owner. A decided or expired one leaves at once; anything
 * else re-reads. A dashboard from before pairing answers 404: the read fails and the list stays empty.
 */
internal class PairingsReader(
    private val client: DashboardClient,
    scope: CoroutineScope,
    onChange: () -> Unit,
) : FeedReader(scope, onChange) {
    var items: List<PairingView> = emptyList(); private set

    override val watched = setOf("pairing.created", "pairing.updated", StreamEvent.Resync)

    override fun handle(event: StreamEvent) {
        if (event.type == "pairing.updated" && event.payload.string("state") != "pending") {
            val id = event.payload.string("pairingId")
            if (id != null && items.any { it.id == id }) {
                items = items.filter { it.id != id }
                onChange()
            }
        }
        super.handle(event)
    }

    override suspend fun load(replace: Boolean) = apply(client.pairings())

    internal fun apply(next: PairingsSnapshot) {
        items = next.pairings.filter { it.state == "pending" }
        seq = next.seq
        loaded = true
    }
}

/** The bell's list. Re-read whenever one is added or the owner clears some, on this device or another. */
internal class NotificationsReader(
    private val client: DashboardClient,
    scope: CoroutineScope,
    onChange: () -> Unit,
) : FeedReader(scope, onChange) {
    var list: NotificationList = NotificationList(); private set

    override val watched = setOf("notification.created", "notifications.seen", AccessUpdated, StreamEvent.Resync)

    override suspend fun load(replace: Boolean) = apply(client.notifications())

    internal fun apply(next: NotificationsSnapshot) {
        list = NotificationList(next.notifications, next.unseen)
        seq = next.seq
        loaded = true
    }
}

/**
 * What the owner follows. Re-read whenever it changes, here or on another device, and after the owner
 * posts in a thread (which follows it). A choice made here shows at once, before the dashboard answers.
 */
internal class FollowsReader(
    private val client: DashboardClient,
    scope: CoroutineScope,
    onChange: () -> Unit,
) : FeedReader(scope, onChange) {
    var follows: Follows = Follows(); private set

    override val watched = setOf("subscriptions.updated", AccessUpdated, StreamEvent.Resync)

    override suspend fun load(replace: Boolean) = apply(client.follows())

    internal fun apply(next: FollowsSnapshot) {
        follows = Follows.of(next)
        seq = next.seq
        loaded = true
    }

    /** Shows a choice before the dashboard has it. The next read replaces it with the dashboard's word. */
    fun assume(change: (Follows) -> Follows) {
        follows = change(follows)
        onChange()
    }

    fun reread() = refresher.request(replace = true)
}

/** Who has beaten lately. Re-read on presence events and on a timer, since heartbeats publish nothing. */
internal class PresenceReader(
    private val client: DashboardClient,
    scope: CoroutineScope,
    onChange: () -> Unit,
) : FeedReader(scope, onChange) {
    var agents: List<LiveAgentView> = emptyList(); private set
    var names: Map<String, String> = emptyMap(); private set

    override val watched = setOf("agent.presence", StreamEvent.Resync)

    override suspend fun load(replace: Boolean) = apply(client.liveAgents())

    override val cacheKey = "agents"

    override fun saveTo(cache: FeedCache, key: String) =
        cache.save(key, AgentsSnapshot(seq = seq, agents = agents), AgentsSnapshot.serializer(), cacheScope)

    override fun restoreFrom(cache: FeedCache, key: String): Boolean {
        val kept = cache.read(key, AgentsSnapshot.serializer()) ?: return false
        apply(kept)
        return true
    }

    fun poll() = refresher.request(replace = false)

    internal fun apply(next: AgentsSnapshot) {
        agents = next.agents
        names = names + next.agents.associate { it.agentId to it.name }
        seq = next.seq
        loaded = true
    }

    companion object {
        /** An agent that hasn't beaten for this long is offline. The server's own window. */
        const val WindowMillis = 300_000L
    }
}

/**
 * A card's media changed under it: it finished processing (`media.ready`) or the owner's retention
 * took its bytes away (`media.removed`). Either way the card is read again, so a thumbnail becomes
 * the picture, or the "removed" placeholder, without a reload.
 */
internal val MediaEvents = setOf("media.ready", "media.removed")

/**
 * Who may see what changed (server spec/14): the event names nothing, so every reader that shows
 * channel content reads again, and what is now hidden leaves the screen.
 */
internal const val AccessUpdated = "access.updated"
