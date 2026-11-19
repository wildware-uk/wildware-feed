package dev.wildware.agents.app.engine

import dev.wildware.agents.UpdateCard
import kotlinx.coroutines.flow.distinctUntilChanged
import dev.wildware.agents.CardKinds
import dev.wildware.agents.CardRef
import dev.wildware.agents.Dashboard
import dev.wildware.agents.FeedCache
import dev.wildware.agents.DraftPrefix
import dev.wildware.agents.DashboardClient
import dev.wildware.agents.Feed
import dev.wildware.agents.Outbox
import dev.wildware.agents.OutboxQueue
import dev.wildware.agents.OwnerActions
import dev.wildware.agents.ProjectRef
import dev.wildware.agents.ReactionView
import dev.wildware.agents.ReadMarks
import dev.wildware.agents.RequestCard
import dev.wildware.agents.RequestKind
import dev.wildware.agents.StreamStatus
import dev.wildware.agents.ThreadLine
import dev.wildware.agents.app.feed.Author
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.MarkdownCache
import dev.wildware.agents.app.feed.Threaded
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.PendingFile
import dev.wildware.agents.app.feed.searchEntriesOf
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.SavedSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import dev.wildware.agents.AlertPrefs
import dev.wildware.agents.app.feed.AlertSettings
import dev.wildware.agents.DashboardException
import dev.wildware.agents.MediaRetention
import dev.wildware.agents.app.feed.RetentionSettings
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
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
    private val outboxChanged = platform.outboxChanged
    private val foreground = platform.foreground
    /** The engine itself, for what lives outside the feed screen: the desktop tray and notifications. */
    val dashboard = Dashboard(
        client, scope, ReadMarks(platform.readMarks), outbox,
        // Opens onto the feed as it last was on this device, for this dashboard and key only.
        cache = FeedCache(platform.feedCache, FeedCache.ownerOf(client.serverUrl, client.authorizationHeader)),
    )
    private val actions = OwnerActions(outbox)
    private val mapper = FeedMapper(client.serverUrl, clock)

    private val mutableAlerts = MutableStateFlow(AlertSettings())

    /** Which alerts this device gets, read from the dashboard when the session starts. */
    val alerts: StateFlow<AlertSettings> = mutableAlerts

    private val mutableRetention = MutableStateFlow(RetentionSettings())

    /** How long the dashboard keeps media, read when the session starts. */
    val retention: StateFlow<RetentionSettings> = mutableRetention

    private val selected = MutableStateFlow<String?>(null)
    private var feed: Feed = dashboard.everything
    private val feeds = MutableStateFlow(feed)

    /** The latest engine feed, for finding what an event is about. */
    private val latest: EngineFeed get() = feed.state.value

    /** Who the @ list offers, worked out only when the agents or their activity change. */
    private val mentionables = combine(dashboard.agentNames, dashboard.online, dashboard.everything.state) { names, online, everything ->
        val lastActive = HashMap<String, Long>()
        fun saw(agentId: String?, at: Long) {
            if (agentId != null && at > (lastActive[agentId] ?: Long.MIN_VALUE)) lastActive[agentId] = at
        }
        everything.allCards.forEach { card ->
            if (card is UpdateCard) saw(card.update.agentId, card.update.createdAt)
            card.replies.forEach { saw(it.message.agentId, it.message.createdAt) }
        }
        Mentionables.of(names, online, lastActive)
    }.distinctUntilChanged()

    val state: StateFlow<FeedState> = combine(
        combine(
            combine(feeds.flatMapLatest { it.state }, dashboard.waiting, ::Pair),
            dashboard.projects,
            dashboard.online,
            outbox.items,
            selected,
        ) { (feed, questions), projects, online, items, slug ->
            // Every waiting question, thread ones included: the engine's feed leaves those out of its top.
            mapper.map(feed, projects, online, items, slug, everyQuestion = questions)
        },
        dashboard.everything.state,
        dashboard.projects,
        dashboard.notifications,
        combine(mutableAlerts, mutableRetention, mentionables, ::Triple),
    ) { shown, everything, projects, notifications, (alerts, retention, mentionable) ->
        // Search looks across every project's loaded cards, whichever feed is on screen.
        val all = if (shown.current.slug == null) shown else mapper.map(everything, projects, emptyList(), emptyList(), null)
        // Parse what the feed will draw before handing it over, here off the UI thread, so a card
        // that scrolls into view (or arrives) finds its markdown ready instead of parsing mid-frame.
        shown.copy(searchIndex = searchEntriesOf(all.sections), bell = mapper.bell(notifications), alerts = alerts, retention = retention, mentionable = mentionable).also { MarkdownCache.warm(markdownIn(it).asIterable()) }
    }.stateIn(scope, SharingStarted.Eagerly, mapper.map(feed.state.value, emptyList(), emptyList(), emptyList(), null))

    /** True once the dashboard has stopped taking this device's key. */
    val signedOut: StateFlow<Boolean> = dashboard.status
        .map { it == StreamStatus.SignedOut }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private suspend fun loadAlerts() {
        mutableAlerts.value = try {
            AlertSettings(client.alertPrefs(), loaded = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Throwable) {
            AlertSettings(problem = "Couldn't read this device's alert settings. The dashboard may need updating.")
        }
    }

    /**
     * Shows the new choice at once and saves it. A save that fails puts the old choice back and says
     * so, so the screen never claims a setting the dashboard doesn't have.
     */
    private fun saveAlerts(prefs: AlertPrefs?) {
        val before = mutableAlerts.value
        if (!before.loaded) return
        mutableAlerts.value = before.copy(prefs = prefs, problem = null)
        scope.launch {
            mutableAlerts.value = try {
                AlertSettings(client.setAlertPrefs(prefs), loaded = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Throwable) {
                before.copy(problem = "Couldn't save that. Check the connection and try again.")
            }
        }
    }

    /**
     * Reads how long the dashboard keeps media. A dashboard that refuses (not this person's to see,
     * or too old to have it) hides the setting rather than showing an error.
     */
    private suspend fun loadRetention() {
        mutableRetention.value = try {
            RetentionSettings(client.mediaRetention(), loaded = true, allowed = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: DashboardException) {
            if (refused.status in RetentionHidden) RetentionSettings() else RetentionSettings(allowed = true, problem = "Couldn't read how long media is kept. ${refused.message}")
        } catch (failed: Throwable) {
            RetentionSettings(allowed = true, problem = "Couldn't read how long media is kept. Check the connection and try again.")
        }
    }

    /**
     * Saves how long the dashboard keeps media. Not shown until the dashboard has it: this one
     * deletes things, so the screen never claims a setting that isn't so. A refusal shows the
     * dashboard's own words.
     */
    private fun saveRetention(setting: MediaRetention) {
        val before = mutableRetention.value
        if (!before.allowed || !before.loaded || before.saving) return
        mutableRetention.value = before.copy(saving = true, problem = null)
        scope.launch {
            mutableRetention.value = try {
                before.copy(setting = client.setMediaRetention(setting), saves = before.saves + 1, problem = null)
            } catch (cancelled: CancellationException) {
                mutableRetention.value = before
                throw cancelled
            } catch (refused: DashboardException) {
                before.copy(problem = refused.message)
            } catch (failed: Throwable) {
                before.copy(problem = "Couldn't save that. Check the connection and try again.")
            }
        }
    }

    /**
     * A change to a project, made straight away rather than queued: it needs the dashboard's say
     * (a name can be taken or too long), so [done] hears null once it is made or the reason it wasn't.
     */
    private fun changeProject(done: (String?) -> Unit = {}, change: suspend () -> Unit) {
        scope.launch {
            val problem = try {
                change()
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: DashboardException) {
                refused.message ?: "The dashboard said no."
            } catch (failed: Throwable) {
                "Couldn't reach the dashboard. Check the connection and try again."
            }
            done(problem)
        }
    }

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
        scope.launch { loadAlerts() }
        scope.launch { loadRetention() }
        // Another process queued, sent or dropped something: show it, and send what is new.
        scope.launch { outboxChanged.collect { outbox.reload() } }
        scope.launch { foreground.collect { resume() } }
    }

    private fun markdownIn(state: FeedState): Sequence<String> = state.sections.asSequence().flatMap { it.items.asSequence() }.flatMap { item ->
        val own = when (item) {
            is FeedItem.Update -> sequenceOf(item.body)
            is FeedItem.Post -> if (item.author is Author.Owner) emptySequence() else sequenceOf(item.body)
            is FeedItem.Question -> sequenceOf(item.body)
        }
        own + ((item as? Threaded)?.replies?.asSequence()?.map { it.body } ?: emptySequence())
    }

    /**
     * The app is back in front: the connection it held while away is likely dead, so open a fresh
     * one (the server replays what was missed) and send anything waiting.
     */
    fun resume() {
        dashboard.revive(reconnect = true, reread = false)
        outbox.kick(ignoreBackoff = true)
    }

    /**
     * Tries to send everything waiting, for up to [timeout]: before the app quits to update. What
     * doesn't go stays on disk and goes when the app is back.
     */
    suspend fun flushOutbox(timeout: Duration = 5.seconds) {
        outbox.kick(ignoreBackoff = true)
        withTimeoutOrNull(timeout) {
            outbox.items.first { items -> items.all { it.gaveUp || it.attempts > 0 } }
        }
    }

    fun stop() {
        dashboard.stop()
        outbox.stop()
        scope.cancel()
        client.close()
    }

    /** Does what the owner asked. Sign-out and screen-only events are handled elsewhere. */
    fun handle(event: FeedEvent) {
        // A new answer replaces one the dashboard refused: the refused one is not sent as well.
        when (event) {
            is FeedEvent.Answer -> dropRefused(event.questionId)
            is FeedEvent.AnswerText -> dropRefused(event.questionId)
            is FeedEvent.AnswerForm -> dropRefused(event.questionId)
            is FeedEvent.DismissQuestion -> dropRefused(event.questionId)
            else -> Unit
        }
        when (event) {
            is FeedEvent.Answer -> request(event.questionId)?.let { card ->
                actions.answer(card.request, card.agentName, answerValue(card, event.choices))
            }
            is FeedEvent.AnswerText -> request(event.questionId)?.let { card ->
                actions.answer(card.request, card.agentName, JsonPrimitive(event.text))
            }
            is FeedEvent.AnswerForm -> request(event.questionId)?.let { card ->
                actions.answer(card.request, card.agentName, buildJsonObject {
                    put("action", event.action)
                    put("text", event.text.trim())
                })
            }
            is FeedEvent.DismissQuestion -> request(event.questionId)?.let { actions.dismiss(it.request, it.agentName) }
            is FeedEvent.React -> cardRef(event.targetId)?.let { actions.toggleReaction(it, event.emoji, reactionsOn(event.targetId)) }
            is FeedEvent.SendReply -> cardRef(event.threadId)?.let {
                actions.reply(it, event.text.ifBlank { null }, answers = event.answers, files = event.files.map(::upload))
            }
            is FeedEvent.Post -> postTarget()?.let { actions.post(it, event.text.ifBlank { null }, event.files.map(::upload)) }
            is FeedEvent.Delete -> cardRef(event.targetId)?.let { actions.delete(it) }
            is FeedEvent.Tag -> cardRef(event.targetId)?.let { actions.tag(it, event.tag, event.on, event.create) }
            is FeedEvent.SetStatus -> cardRef(event.targetId)?.let { actions.setStatus(it, event.status?.wire) }
            is FeedEvent.Pin -> cardRef(event.targetId)?.let { actions.pin(it, event.pinned) }
            is FeedEvent.SetField -> cardRef(event.targetId)?.let { actions.setField(it, event.name, event.value) }
            is FeedEvent.OpenThread -> dashboard.markRead(event.cardId)
            is FeedEvent.MarkAllRead -> markAllRead()
            is FeedEvent.OpenNotification -> dashboard.markNotificationsSeen(listOf(event.id))
            is FeedEvent.ClearNotifications -> dashboard.markNotificationsSeen(null)
            is FeedEvent.SetAlertPrefs -> saveAlerts(event.prefs)
            is FeedEvent.ReadMediaRetention -> scope.launch { loadRetention() }
            is FeedEvent.SetMediaRetention -> saveRetention(event.setting)
            is FeedEvent.SelectProject -> select(event.slug)
            is FeedEvent.CreateProject -> changeProject(event.done) {
                val made = client.createProject(event.name.trim(), event.description?.trim())
                dashboard.refresh()
                select(made.slug)
            }
            is FeedEvent.EditProject -> changeProject(event.done) {
                client.renameProject(event.slug, event.name.trim(), event.description?.trim())
                dashboard.refresh()
            }
            is FeedEvent.SetProjectLogo -> changeProject {
                client.setProjectLogo(event.slug, event.mediaId)
                dashboard.refresh()
            }
            is FeedEvent.SetLogoInsteadOfName -> changeProject {
                client.setLogoInsteadOfName(event.slug, event.instead)
                dashboard.refresh()
            }
            is FeedEvent.PinProject -> changeProject {
                client.setProjectPinned(event.slug, event.pinned)
                dashboard.refresh()
            }
            is FeedEvent.ArchiveProject -> changeProject {
                client.setProjectArchived(event.slug, event.archived)
                dashboard.refresh()
            }
            is FeedEvent.SetProjectColours -> changeProject {
                client.setProjectColours(event.slug, event.background, event.accent)
                dashboard.refresh()
            }
            is FeedEvent.Refresh -> {
                dashboard.revive(reconnect = true)
                outbox.kick(ignoreBackoff = true)
                scope.launch { runCatching { dashboard.refresh() } }
            }
            is FeedEvent.LoadOlder -> scope.launch { runCatching { feed.loadOlder() } }
            is FeedEvent.RetrySend -> outbox.retryNow(event.outboxId)
            is FeedEvent.DropUnsent -> outbox.remove(event.outboxId)
            is FeedEvent.ChooseStatus, is FeedEvent.Reply, is FeedEvent.OpenDetails, is FeedEvent.EditTags, is FeedEvent.CopyText, is FeedEvent.SelectText, is FeedEvent.CopyLink, is FeedEvent.MoveProject, is FeedEvent.SignOut, is FeedEvent.ToggleTheme -> Unit
        }
    }

    /** The owner is away from the top of the feed, so new updates wait for them. Carried to the next feed shown. */
    private var holding = false

    /**
     * Whether the owner is away from the top of the feed. While they are, new updates wait (counted in
     * [FeedState.newCount]) instead of arriving above what they are reading, as on the website.
     */
    fun holdArrivals(away: Boolean) {
        holding = away
        feed.hold(away)
    }

    private fun select(slug: String?) {
        if (slug == feed.projectSlug) return
        val old = feed
        feed = dashboard.feed(slug)
        feed.hold(holding)
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

    private fun dropRefused(requestId: String) {
        Outbox.failed(outbox.items.value).filter { it.requestId == requestId }.forEach { outbox.remove(it.id) }
    }

    private fun request(id: String): RequestCard? = requests().firstOrNull { it.id == id }

    private fun allLines(): List<ThreadLine> = latest.allCards.flatMap { card ->
        card.replies + ((card as? dev.wildware.agents.PostCard)?.post?.let(::listOf) ?: emptyList())
    } + latest.waiting.flatMap { it.discussion }

    /** What card or line an id names, as the server's write calls want it. */
    private fun cardRef(id: String): CardRef? =
        // A line still in the outbox has no id on the dashboard yet: nothing can be done to it there.
        if (id.startsWith(DraftPrefix)) null else latest.card(id)?.card
            ?: request(id)?.card
            ?: allLines().firstOrNull { it.message.id == id }?.let { CardRef(CardKinds.Message, id) }

    private fun reactionsOn(id: String): List<ReactionView> =
        latest.card(id)?.reactions ?: request(id)?.request?.reactions
            ?: allLines().firstOrNull { it.message.id == id }?.reactions.orEmpty()

    /** The answer as the server takes it for this kind of question. */
    private fun answerValue(card: RequestCard, choices: List<String>): JsonElement = when (card.request.requestKind) {
        RequestKind.Confirm -> JsonPrimitive(choices.firstOrNull() == FeedMapper.Yes)
        RequestKind.MultiChoice -> JsonArray(choices.map(::JsonPrimitive))
        else -> JsonPrimitive(choices.firstOrNull().orEmpty())
    }
}

/** Answers that mean "not for this person" or "this dashboard has no such setting": the setting stays hidden. */
private val RetentionHidden = setOf(401, 403, 404)
