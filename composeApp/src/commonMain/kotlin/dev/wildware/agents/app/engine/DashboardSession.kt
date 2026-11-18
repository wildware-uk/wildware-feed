package dev.wildware.agents.app.engine

import dev.wildware.agents.CardKinds
import dev.wildware.agents.CardRef
import dev.wildware.agents.Dashboard
import dev.wildware.agents.DashboardClient
import dev.wildware.agents.Feed
import dev.wildware.agents.OutboxQueue
import dev.wildware.agents.OwnerActions
import dev.wildware.agents.ProjectRef
import dev.wildware.agents.ReactionView
import dev.wildware.agents.ReadMarks
import dev.wildware.agents.RequestCard
import dev.wildware.agents.RequestKind
import dev.wildware.agents.StreamStatus
import dev.wildware.agents.ThreadLine
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.PendingFile
import dev.wildware.agents.app.feed.searchEntriesOf
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.SavedSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import dev.wildware.agents.FeedState as EngineFeed

/**
 * One signed-in dashboard, as the feed screen sees it: the engine's flows turned into [state],
 * and every [FeedEvent] turned into the engine call that does it. Changes go through the outbox,
 * so they survive having no signal and show in the feed once the server has them.
 */
@OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)
class DashboardSession(
    val saved: SavedSession,
    platform: AppPlatform,
    parent: CoroutineScope,
    clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    val client = DashboardClient(saved.server, saved.token, platform.httpEngine())
    private val outbox = OutboxQueue(client, platform.outbox, scope)
    /** The engine itself, for what lives outside the feed screen: the desktop tray and notifications. */
    val dashboard = Dashboard(client, scope, ReadMarks(platform.readMarks), outbox)
    private val actions = OwnerActions(outbox)
    private val mapper = FeedMapper(client.serverUrl, clock)

    private val selected = MutableStateFlow<String?>(null)
    private var feed: Feed = dashboard.everything
    private val feeds = MutableStateFlow(feed)

    /** The latest engine feed, for finding what an event is about. */
    private val latest: EngineFeed get() = feed.state.value

    val state: StateFlow<FeedState> = combine(
        combine(feeds.flatMapLatest { it.state }, dashboard.projects, dashboard.online, outbox.items, selected) { feed, projects, online, items, slug ->
            mapper.map(feed, projects, online, items, slug)
        },
        dashboard.everything.state,
        dashboard.projects,
    ) { shown, everything, projects ->
        // Search looks across every project's loaded cards, whichever feed is on screen.
        val all = if (shown.current.slug == null) shown else mapper.map(everything, projects, emptyList(), emptyList(), null)
        shown.copy(searchIndex = searchEntriesOf(all.sections))
    }.stateIn(scope, SharingStarted.Eagerly, mapper.map(feed.state.value, emptyList(), emptyList(), emptyList(), null))

    /** True once the dashboard has stopped taking this device's key. */
    val signedOut: StateFlow<Boolean> = dashboard.status
        .map { it == StreamStatus.SignedOut }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Marks every card on the all-projects feed read, and every waiting question: the tray's "Mark all read". */
    fun markAllRead() {
        val everything = dashboard.everything.state.value
        (everything.allCards.filter { it.unread > 0 }.map { it.id } +
            (dashboard.waiting.value + everything.waiting).filter { it.unread > 0 }.map { it.id })
            .distinct()
            .forEach(dashboard::markRead)
    }

    fun start() {
        outbox.start()
        dashboard.start()
    }

    fun stop() {
        dashboard.stop()
        outbox.stop()
        scope.cancel()
        client.close()
    }

    /** Does what the owner asked. Sign-out and screen-only events are handled elsewhere. */
    fun handle(event: FeedEvent) {
        when (event) {
            is FeedEvent.Answer -> request(event.questionId)?.let { card ->
                actions.answer(card.request, card.agentName, answerValue(card, event.choices))
            }
            is FeedEvent.AnswerText -> request(event.questionId)?.let { card ->
                actions.answer(card.request, card.agentName, JsonPrimitive(event.text))
            }
            is FeedEvent.DismissQuestion -> request(event.questionId)?.let { actions.dismiss(it.request, it.agentName) }
            is FeedEvent.React -> cardRef(event.targetId)?.let { actions.toggleReaction(it, event.emoji, reactionsOn(event.targetId)) }
            is FeedEvent.SendReply -> cardRef(event.threadId)?.let {
                actions.reply(it, event.text.ifBlank { null }, answers = event.answers, files = event.files.map(::upload))
            }
            is FeedEvent.Post -> postTarget()?.let { actions.post(it, event.text.ifBlank { null }, event.files.map(::upload)) }
            is FeedEvent.Delete -> cardRef(event.targetId)?.let { actions.delete(it) }
            is FeedEvent.OpenThread -> dashboard.markRead(event.cardId)
            is FeedEvent.MarkAllRead -> markAllRead()
            is FeedEvent.SelectProject -> select(event.slug)
            is FeedEvent.Refresh -> {
                dashboard.revive(reconnect = true)
                outbox.kick(ignoreBackoff = true)
                scope.launch { runCatching { dashboard.refresh() } }
            }
            is FeedEvent.LoadOlder -> scope.launch { runCatching { feed.loadOlder() } }
            is FeedEvent.RetrySend -> outbox.retryNow(event.outboxId)
            is FeedEvent.DropUnsent -> outbox.remove(event.outboxId)
            is FeedEvent.Reply, is FeedEvent.CopyText, is FeedEvent.CopyLink, is FeedEvent.MoveProject, is FeedEvent.SignOut, is FeedEvent.ToggleTheme -> Unit
        }
    }

    private fun select(slug: String?) {
        if (slug == feed.projectSlug) return
        val old = feed
        feed = dashboard.feed(slug)
        feeds.value = feed
        selected.value = slug
        old.close()
    }

    private fun upload(file: PendingFile) = dev.wildware.agents.Attachment(file.name, file.mimeType, file.bytes)

    /** New posts go to the project on screen. "All projects" has no single place to post. */
    private fun postTarget(): ProjectRef? {
        val slug = selected.value ?: return null
        val name = dashboard.projects.value.firstOrNull { it.project.slug == slug }?.project?.name ?: slug
        return ProjectRef(slug, name)
    }

    /** Every question in view: the feed's own, and those asked inside a thread. */
    private fun requests(): List<RequestCard> =
        dashboard.waiting.value + latest.waiting + allLines().flatMap { it.questions }

    private fun request(id: String): RequestCard? = requests().firstOrNull { it.id == id }

    private fun allLines(): List<ThreadLine> = latest.allCards.flatMap { card ->
        card.replies + ((card as? dev.wildware.agents.PostCard)?.post?.let(::listOf) ?: emptyList())
    } + latest.waiting.flatMap { it.discussion }

    /** What card or line an id names, as the server's write calls want it. */
    private fun cardRef(id: String): CardRef? =
        latest.card(id)?.card
            ?: request(id)?.card
            ?: allLines().firstOrNull { it.message.id == id }?.let { CardRef(CardKinds.Message, id) }

    private fun reactionsOn(id: String): List<ReactionView> =
        latest.card(id)?.reactions ?: allLines().firstOrNull { it.message.id == id }?.reactions.orEmpty()

    /** The answer as the server takes it for this kind of question. */
    private fun answerValue(card: RequestCard, choices: List<String>): JsonElement = when (card.request.requestKind) {
        RequestKind.Confirm -> JsonPrimitive(choices.firstOrNull() == FeedMapper.Yes)
        RequestKind.MultiChoice -> JsonArray(choices.map(::JsonPrimitive))
        else -> JsonPrimitive(choices.firstOrNull().orEmpty())
    }
}
