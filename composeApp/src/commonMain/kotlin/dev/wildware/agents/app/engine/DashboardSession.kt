package dev.wildware.agents.app.engine

import dev.wildware.agents.channelMedia
import dev.wildware.agents.connectGitHub
import dev.wildware.agents.disconnectGitHub
import dev.wildware.agents.githubStatus
import dev.wildware.agents.UpdateCard
import kotlinx.coroutines.flow.distinctUntilChanged
import dev.wildware.agents.CardKinds
import dev.wildware.agents.OutboxTag
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
import dev.wildware.agents.app.feed.SharedSecret
import dev.wildware.agents.app.feed.ProjectGroup
import dev.wildware.agents.app.feed.MarkdownCache
import dev.wildware.agents.app.feed.Threaded
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.PendingFile
import dev.wildware.agents.app.feed.searchEntriesOf
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.SavedSession
import dev.wildware.agents.app.sound.SoundRules
import dev.wildware.agents.app.sound.SoundSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import dev.wildware.agents.AlertPrefs
import dev.wildware.agents.app.feed.AlertSettings
import dev.wildware.agents.app.feed.DeviceList
import dev.wildware.agents.app.feed.SecretAuditRow
import dev.wildware.agents.app.feed.SecretList
import dev.wildware.agents.app.feed.SecretRow
import dev.wildware.agents.SecretScope
import dev.wildware.agents.app.feed.DeviceRow
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
import kotlinx.coroutines.flow.asStateFlow
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
    private val platform: AppPlatform,
    parent: CoroutineScope,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** This team's own files on the device; null uses [platform]'s (the first team's). */
    teamFiles: dev.wildware.agents.app.session.TeamFiles? = null,
) {
    private val readMarks = teamFiles?.readMarks ?: platform.readMarks
    private val feedCache = teamFiles?.feedCache ?: platform.feedCache
    private val postTarget = teamFiles?.postTarget ?: platform.postTarget

    /** The cards folded away on this device, for this team. */
    val cardFolds: dev.wildware.agents.TextStore = teamFiles?.cardFolds ?: platform.cardFolds

    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    val client = DashboardClient(saved.server, saved.token, platform.httpEngine()).also { c ->
        // Any free-plan refusal, from any call: offer "Upgrade", and read the plan's use again.
        c.onPlanLimit = { hit ->
            mutableUpgrade.value = hit
            scope.launch { loadPlan() }
        }
        // A member, or an app that may not link to buying (an iPhone outside the US App Store), is
        // told what is full in plain words, with no offer and nowhere to go.
        c.rewordPlanLimit = { hit -> dev.wildware.agents.app.feed.Upsell.refusal(hit, offer = mayOffer(mutableTeam.value.me?.role)) }
    }
    private val outbox = OutboxQueue(client, platform.outbox, scope)
    private val outboxChanged = platform.outboxChanged
    private val foreground = platform.foreground
    /** The engine itself, for what lives outside the feed screen: the desktop tray and notifications. */
    val dashboard = Dashboard(
        client, scope, ReadMarks(readMarks), outbox,
        // Opens onto the feed as it last was on this device, for this dashboard and key only.
        cache = FeedCache(feedCache, FeedCache.ownerOf(client.serverUrl, client.authorizationHeader)),
    )
    private val actions = OwnerActions(outbox)
    private val mapper = FeedMapper(client.serverUrl, clock)

    /** The board, read while it is on screen and again whenever something on it may have moved. */
    private val board = BoardWatch(client, actions, scope, dashboard.boardChanges, clock)

    /** The Docs tab, read while it is on screen and again whenever one of its docs changes. */
    private val docs = DocsWatch(client, scope, dashboard.docChanges, mapper::relativeLabel)

    /** The Media tab: a channel's pictures, videos and files, read while it is on screen, a page at a time. */
    private val mediaTab = MediaWatch(
        read = { channel, filter, cursor -> client.channelMedia(channel, filter.kind, cursor) },
        scope = scope,
        tile = mapper::mediaTabTile,
        file = mapper::mediaTabFile,
        ago = mapper::relativeLabel,
    )

    /** The Assign picker while it is open; a save reads the board again. */
    private val assign = AssignWatch(client, scope) { board.reread() }

    private val mutableAlerts = MutableStateFlow(AlertSettings())

    /** Which moments make which sound. Touched only on [soundThread]. */
    private val soundRules = SoundRules()

    /** One thread at a time for [soundRules]: the owner's taps and the dashboard's changes take turns. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val soundThread = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)

    /** Tells [soundRules] something, in turn with the changes it is reading. */
    private fun sounds(change: () -> Unit) {
        if (platform.sounds.available) scope.launch(soundThread) { change() }
    }

    /** Which alerts this device gets, read from the dashboard when the session starts. */
    val alerts: StateFlow<AlertSettings> = mutableAlerts

    private val mutableRetention = MutableStateFlow(RetentionSettings())

    /** How long the dashboard keeps media, read when the session starts. */
    val retention: StateFlow<RetentionSettings> = mutableRetention

    private val mutablePlan = MutableStateFlow(dev.wildware.agents.app.feed.PlanState())

    private val mutableGitHub = MutableStateFlow(dev.wildware.agents.app.feed.GitHubSettings())

    /** Whether the app may link to buying a plan, now ([AppPlatform.buyingLinks]). */
    private val buyingLinks = platform.buyingLinks

    /** Apple's in-app purchase, on iPhone ([AppPlatform.appStore]); null elsewhere. */
    private val appStore = platform.appStore

    /**
     * Whether [role] may be offered Team here at all: an owner or admin, where the app may link to
     * buying, or where it sells Team itself (the App Store, on iPhone, on every storefront).
     */
    private fun mayOffer(role: String?) = (buyingLinks.value || appStore != null) && (role == "owner" || role == "admin")

    /** Who asked to buy or restore, waiting to hear how it went ([relayPurchases]). */
    @kotlin.concurrent.Volatile
    private var purchaseDone: ((String?) -> Unit)? = null

    /** The App Store token for this team, reading the plan first if it hasn't been. */
    private suspend fun appAccountToken(): String? = (mutablePlan.value.plan ?: run { loadPlan(); mutablePlan.value.plan })?.appAccountToken

    private fun buyTeam(productId: String, done: (String?) -> Unit) {
        val store = appStore ?: return done("Buying isn't possible in this app.")
        scope.launch {
            val token = appAccountToken() ?: return@launch done("This dashboard has no plan to buy.")
            purchaseDone = done
            store.buy(productId, token)
        }
    }

    private fun restorePurchases(done: (String?) -> Unit) {
        val store = appStore ?: return done("Buying isn't possible in this app.")
        purchaseDone = done
        store.restore()
    }

    /**
     * Sends each purchase, renewal or restore the App Store signs to the dashboard, which has the
     * billing service check it with Apple, and tells the App Store it is dealt with once the dashboard
     * has decided. One for another team (another token) is left for that team; one the dashboard
     * couldn't pass on yet comes back from the App Store next time the app starts.
     */
    private suspend fun relayPurchases(store: dev.wildware.agents.app.session.AppStorePurchases) {
        store.signed.collect { tx ->
            val token = appAccountToken() ?: return@collect
            if (tx.accountToken != null && !tx.accountToken.equals(token, ignoreCase = true)) return@collect
            val answer = try {
                client.relayAppStore(tx.jws)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Throwable) {
                null
            }
            if (answer?.settled == true) store.finish(tx.id)
            loadPlan()
            val done = purchaseDone ?: return@collect
            purchaseDone = null
            done(dev.wildware.agents.app.feed.Paywall.relayWords(answer?.status ?: 502, answer?.error, answer?.message))
        }
    }

    /** When this team last saw the popup and hid the files note, on this device. */
    private val upsellMarks = dev.wildware.agents.app.feed.UpsellMarks(platform.upsellMarks)

    /** The popup is up. */
    private val mutableUpsellPopup = MutableStateFlow(false)

    /** The files note was hidden less than a week ago. */
    private val mutableFilesHidden = MutableStateFlow(
        upsellMarks.filesHidden(saved.server)?.let { clock() - it in 0 until dev.wildware.agents.app.feed.Upsell.POPUP_EVERY_MS } == true,
    )

    /** The last free-plan refusal not yet put away: Settings and the feed offer "Upgrade" for it. */
    private val mutableUpgrade = MutableStateFlow<dev.wildware.agents.PlanLimitHit?>(null)

    private val mutableDevices = MutableStateFlow(DeviceList())
    private val mutableSecrets = MutableStateFlow(SecretList())
    private val mutableFeedback = MutableStateFlow(dev.wildware.agents.app.feed.FeedbackState())

    /** The people of the team, read when the session starts and when the People sheet opens. */
    private val mutableTeam = MutableStateFlow(dev.wildware.agents.app.feed.TeamState())

    /** Who this device's person is, and the team's people: local notices tell their words from a teammate's. */
    val team: StateFlow<dev.wildware.agents.app.feed.TeamState> = mutableTeam.asStateFlow()

    /** The open agent's OAuth connections. */
    private val mutableConnections = MutableStateFlow(dev.wildware.agents.app.feed.ConnectionList())

    /** Which agents hear the open channel or project. */
    private val mutableMembers = MutableStateFlow<Map<String, dev.wildware.agents.app.feed.MemberList>>(emptyMap())

    private fun members(target: String) = mutableMembers.value[target] ?: dev.wildware.agents.app.feed.MemberList(target)

    private fun putMembers(target: String, list: dev.wildware.agents.app.feed.MemberList) {
        mutableMembers.value = mutableMembers.value + (target to list)
    }

    /** Permissions panels and reach summaries read so far (server spec/15). */
    private val mutableAccess = MutableStateFlow(dev.wildware.agents.app.feed.AccessState())

    private fun panel(target: String) = mutableAccess.value.panels[target] ?: dev.wildware.agents.app.feed.AccessView(target)

    private fun putPanel(target: String, view: dev.wildware.agents.app.feed.AccessView) {
        mutableAccess.value = mutableAccess.value.copy(panels = mutableAccess.value.panels + (target to view))
    }

    /** A Permissions panel as the dashboard answered it, in the app's words. */
    private fun accessView(target: String, p: dev.wildware.agents.AccessPanel): dev.wildware.agents.app.feed.AccessView {
        fun row(e: dev.wildware.agents.AccessEntry) = dev.wildware.agents.app.feed.AccessRow(
            kind = e.kind, id = e.id, name = e.name, level = e.level,
            source = dev.wildware.agents.app.feed.levelSource(e.via, e.inherited, e.kind == "agent"),
            own = e.own, editable = e.editable,
        )
        return dev.wildware.agents.app.feed.AccessView(
            target = target, loaded = true, isPrivate = p.isPrivate, groupPrivate = p.groupPrivate, canManage = p.canManage,
            people = p.entries.filter { it.kind == "member" }.map(::row), agents = p.entries.filter { it.kind == "agent" }.map(::row),
        )
    }

    /** Reads a channel's or project's Permissions panel. */
    private suspend fun loadAccess(id: String, group: Boolean) {
        val target = dev.wildware.agents.app.feed.accessTarget(id, group)
        val read = try {
            accessView(target, client.access(id, group))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: DashboardException) {
            panel(target).copy(problem = "Couldn't read who has access. ${refused.message}")
        } catch (failed: Throwable) {
            panel(target).copy(problem = "Couldn't read who has access. Check the connection and try again.")
        }
        putPanel(target, read)
    }

    /** Changes the private switch or one person's or agent's level, then shows the panel as the dashboard answers it. */
    private fun changeAccess(id: String, group: Boolean, busy: String, change: suspend () -> dev.wildware.agents.AccessPanel) {
        val target = dev.wildware.agents.app.feed.accessTarget(id, group)
        val before = panel(target)
        if (before.busy != null) return
        putPanel(target, before.copy(busy = busy, problem = null))
        scope.launch {
            val after = try {
                accessView(target, change())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: DashboardException) {
                panel(target).copy(busy = null, problem = "Couldn't change it. ${refused.message}")
            } catch (failed: Throwable) {
                panel(target).copy(busy = null, problem = "Couldn't change it. Check the connection and try again.")
            }
            putPanel(target, after)
            // A project's panel and its channels' panels show each other's settings.
            mutableAccess.value.panels.keys.filter { it != target }.forEach { other ->
                val (kind, otherId) = other.split(":", limit = 2)
                loadAccess(otherId, kind == "group")
            }
            mutableAccess.value = mutableAccess.value.copy(reach = emptyMap())
        }
    }

    /** Reads what one person or agent can reach, for their page. */
    private suspend fun loadReach(id: String, agent: Boolean) {
        val target = dev.wildware.agents.app.feed.reachTarget(id, agent)
        val read = try {
            val rows = client.reachOf(id, agent)
            dev.wildware.agents.app.feed.ReachView(target, loaded = true, lines = rows.map { dev.wildware.agents.app.feed.ReachLine(it.slug, it.level) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: DashboardException) {
            dev.wildware.agents.app.feed.ReachView(target, problem = "Couldn't read what it can reach. ${refused.message}")
        } catch (failed: Throwable) {
            dev.wildware.agents.app.feed.ReachView(target, problem = "Couldn't read what it can reach. Check the connection and try again.")
        }
        mutableAccess.value = mutableAccess.value.copy(reach = mutableAccess.value.reach + (target to read))
    }

    /**
     * Every agent the dashboard knows, removed ones too. Offline agents join the agent list from here,
     * so any agent can be opened (to rename or remove it), and removed ones are kept out of the lists
     * and the @ list, though old cards still name them.
     */
    private val knownAgents = MutableStateFlow<List<dev.wildware.agents.AgentRecord>>(emptyList())
    private val removedAgents = knownAgents.map { all -> all.filter { it.revokedAt != null }.mapTo(HashSet()) { it.id } as Set<String> }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Agents' pictures, by id: cards, the @ list and the agent list show them. */
    private val agentAvatars = knownAgents.map { all -> all.mapNotNull { a -> a.avatarMediaId?.let { a.id to it } }.toMap() }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /**
     * Every agent's name, by id, online or not, removed ones too: what the dashboard's feeds name
     * and its agent list. Cards, acks and deliveries show these, never "An agent" (issue #268).
     */
    private val knownAgentNames = combine(dashboard.agentNames, knownAgents) { named, known -> known.associate { it.id to it.name } + named }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** What the state adds on top of the feed: settings, the post target, devices and the known agents. */
    private data class Side(val extras: Extras, val postTarget: String?, val devices: Triple<DeviceList, dev.wildware.agents.app.feed.SecretList, dev.wildware.agents.app.feed.FeedbackState>, val known: List<dev.wildware.agents.AgentRecord>,
        val followsAndGroups: Pair<dev.wildware.agents.Follows, List<dev.wildware.agents.GroupView>>,
    )

    /** This device's own key was revoked from its Devices sheet: it is signed out at once. */
    private val revokedHere = MutableStateFlow(false)

    /** What the sign-in screen says when this session ends by the person's own doing, such as deleting their account. */
    var signOutNotice: String? = null
        private set

    /** Allow or Deny on its way, or refused, per pairing; decided ones are hidden until the stream says so. */
    private data class JoinStatus(val busy: Boolean = false, val error: String? = null, val done: Boolean = false)
    private val joinStatus = MutableStateFlow<Map<String, JoinStatus>>(emptyMap())

    /** Agents asking to join, as the prompt shows them. */
    private val joining = combine(dashboard.pairings, joinStatus) { pairings, status ->
        pairings.filter { status[it.id]?.done != true }.map { p ->
            val s = status[p.id]
            dev.wildware.agents.app.feed.JoinRequest(
                id = p.id, name = p.name, code = p.code, host = p.host, cwd = p.cwd, address = p.address,
                askedBy = p.askedBy?.let { it.name ?: "another agent" }, expiresAt = p.expiresAt.takeIf { it > 0 },
                busy = s?.busy == true, error = s?.error,
            )
        }
    }

    private class Extras(
        val alerts: AlertSettings,
        val retention: RetentionSettings,
        val mentionable: List<dev.wildware.agents.app.feed.Mentionable>,
        val joining: List<dev.wildware.agents.app.feed.JoinRequest>,
        val connections: dev.wildware.agents.app.feed.ConnectionList,
        val members: Map<String, dev.wildware.agents.app.feed.MemberList>,
        val access: dev.wildware.agents.app.feed.AccessState,
    )

    private val selected = MutableStateFlow<String?>(null)
    private var feed: Feed = dashboard.everything
    private val feeds = MutableStateFlow(feed)

    /** The latest engine feed, for finding what an event is about. */
    private val latest: EngineFeed get() = feed.state.value

    /** Who the @ list offers, worked out only when the agents or their activity change. */
    private val mentionables = combine(dashboard.agentNames, dashboard.online, dashboard.everything.state, removedAgents, agentAvatars) { allNames, online, everything, removed, avatars ->
        val names = allNames.filterKeys { it !in removed }
        val lastActive = HashMap<String, Long>()
        fun saw(agentId: String?, at: Long) {
            if (agentId != null && at > (lastActive[agentId] ?: Long.MIN_VALUE)) lastActive[agentId] = at
        }
        everything.allCards.forEach { card ->
            if (card is UpdateCard) saw(card.update.agentId, card.update.createdAt)
            card.replies.forEach { saw(it.message.agentId, it.message.createdAt) }
        }
        Mentionables.of(names, online, lastActive).map { m -> m.id?.let(avatars::get)?.let { m.copy(avatarMediaId = it) } ?: m }
    }.distinctUntilChanged()

    /** The project last posted to from "All projects", or last opened: where the next one from there goes. */
    private val lastProject = MutableStateFlow(runCatching { postTarget.read()?.trim() }.getOrNull()?.ifEmpty { null })

    /** The offer to show, if any, with the popup and the files note. */
    private val upsell = combine(mutablePlan, mutableTeam, buyingLinks, mutableUpsellPopup, mutableFilesHidden) { plan, team, links, popup, hidden ->
        val offer = dev.wildware.agents.app.feed.Upsell.offerFor(plan.plan, team.me?.role, links || appStore != null)
        dev.wildware.agents.app.feed.UpsellState(offer, popup = popup && offer != null, storageHidden = hidden)
    }.distinctUntilChanged()

    val state: StateFlow<FeedState> = combine(
        combine(
            combine(feeds.flatMapLatest { it.state }, dashboard.waiting, combine(mutableTeam, agentAvatars, knownAgentNames, ::Triple), ::Triple),
            dashboard.projects,
            dashboard.online,
            outbox.items,
            selected,
        ) { (feed, questions, teamAndAvatars), projects, online, items, slug ->
            val (team, avatars, agentNames) = teamAndAvatars
            // Who wrote what, once there is more than one person (migration 055), and their pictures.
            mapper.team = team
            mapper.agentAvatars = avatars
            mapper.knownAgentNames = agentNames
            // Every waiting question, thread ones included: the engine's feed leaves those out of its top.
            mapper.map(feed, projects, online, items, slug, everyQuestion = questions).copy(team = team)
        },
        dashboard.everything.state,
        dashboard.projects,
        dashboard.notifications,
        // What the owner follows rides with the projects (groups) whose levels it reads.
        combine(combine(mutableAlerts, mutableRetention, mentionables, joining, combine(mutableConnections, mutableMembers, mutableAccess, ::Triple)) { a, r, m, j, (c, mm, ac) -> Extras(a, r, m, j, c, mm, ac) }, lastProject, combine(mutableDevices, mutableSecrets, mutableFeedback, ::Triple), knownAgents,
            combine(dashboard.followed, dashboard.groups, ::Pair), ::Side),
    ) { shown, everything, projects, notifications, (extras, postTarget, devicesAndSecrets, known, followsAndGroups) ->
        val (devices, secrets, feedback) = devicesAndSecrets
        val (follows, groups) = followsAndGroups
        val alerts = extras.alerts
        val retention = extras.retention
        val mentionable = extras.mentionable
        // Search looks across every project's loaded cards, whichever feed is on screen.
        val all = if (shown.current.slug == null) shown else mapper.map(everything, projects, emptyList(), emptyList(), null)
        // Parse what the feed will draw before handing it over, here off the UI thread, so a card
        // that scrolls into view (or arrives) finds its markdown ready instead of parsing mid-frame.
        // A card on its own is held by the every-channel feed, whichever feed is on screen.
        shown.copy(searchIndex = searchEntriesOf(all.sections), lone = all.lone, bell = mapper.bell(notifications), alerts = alerts, retention = retention, mentionable = mentionable, postTarget = postTarget, devices = devices, secrets = secrets, feedback = feedback, joining = extras.joining, connections = extras.connections, members = extras.members, access = extras.access,
            agents = withOffline(shown.agents, known), follows = follows,
            groups = groups.map { ProjectGroup(it.id, it.slug, it.name, accent = it.theme?.accent, archived = it.isArchived, position = it.position) }).also { MarkdownCache.warm(markdownIn(it).asIterable()) }
    }.let { feed ->
        combine(feed, board.state, assign.state, combine(mutablePlan, upsell, ::Pair), mutableUpgrade) { shown, onBoard, assigning, (plan, offer), upgrade ->
            shown.copy(board = onBoard, assign = assigning, plan = plan, upgrade = upgrade, upsell = offer)
        }.combine(docs.state) { shown, onDocs -> shown.copy(docs = onDocs) }
            .combine(mediaTab.state) { shown, onMedia -> shown.copy(media = onMedia) }
            .combine(mutableGitHub) { shown, github -> shown.copy(github = github) }
    }
        .stateIn(scope, SharingStarted.Eagerly, mapper.map(feed.state.value, emptyList(), emptyList(), emptyList(), null))

    /** True once the dashboard has stopped taking this device's key. */
    val signedOut: StateFlow<Boolean> = combine(dashboard.status, revokedHere) { status, revoked -> revoked || status == StreamStatus.SignedOut }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Reads the devices signed in as the owner. This one first, then the most recently used. */
    private suspend fun loadDevices() {
        mutableDevices.value = try {
            val rows = client.devices().map { d ->
                DeviceRow(
                    id = d.id,
                    name = d.name,
                    seen = d.lastSeenAt?.let(::isoMillis)?.let { "Last used ${mapper.relativeLabel(it)}" } ?: "Never used",
                    added = isoMillis(d.createdAt)?.let { "Added ${mapper.shortDay(it)}" } ?: "",
                    current = d.id == saved.deviceId,
                ) to (d.lastSeenAt?.let(::isoMillis) ?: 0L)
            }
            DeviceList(rows.sortedWith(compareByDescending<Pair<DeviceRow, Long>> { it.first.current }.thenByDescending { it.second }).map { it.first }, loaded = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: DashboardException) {
            mutableDevices.value.copy(problem = "Couldn't read the devices. ${refused.message}")
        } catch (failed: Throwable) {
            mutableDevices.value.copy(problem = "Couldn't read the devices. Check the connection and try again.")
        }
    }

    /**
     * Reads who this device belongs to and the people of the team, and the waiting invites for an
     * owner or admin. A server from before teams has no `/api/me`: then there is only you.
     */
    private suspend fun loadTeam() {
        mutableTeam.value = try {
            val me = client.me() ?: return run { mutableTeam.value = mutableTeam.value.copy(supported = false, loaded = true) }
            val people = client.people().map { p ->
                dev.wildware.agents.app.feed.PersonRow(
                    id = p.id, name = p.name, handle = p.handle, role = p.role, email = p.email,
                    you = p.id == me.person.id, removed = p.removed, founder = p.founder, online = p.online,
                    profileRole = p.profileRole, description = p.description, avatarMediaId = p.avatarMediaId,
                )
            }
            val manages = me.person.role == "owner" || me.person.role == "admin"
            val invites = if (!manages) emptyList() else client.invites().map { i ->
                dev.wildware.agents.app.feed.InviteRow(
                    id = i.id, role = i.role, email = i.email,
                    expires = isoMillis(i.expiresAt)?.let { "Expires ${mapper.shortDay(it)}" } ?: "",
                )
            }
            mutableTeam.value.copy(
                me = people.firstOrNull { it.you }, people = people, invites = invites, team = me.team || people.size > 1,
                supported = true, loaded = true, problem = null,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: DashboardException) {
            mutableTeam.value.copy(loaded = true, problem = "Couldn't read the team. ${refused.message}")
        } catch (failed: Throwable) {
            mutableTeam.value.copy(loaded = true, problem = "Couldn't read the team. Check the connection and try again.")
        }
    }

    /** One change to the team, then the team read again; [busy] marks what is being changed meanwhile. */
    private fun changeTeam(busy: String, what: String, call: suspend () -> Unit) {
        if (mutableTeam.value.busy != null) return
        mutableTeam.value = mutableTeam.value.copy(busy = busy, problem = null)
        scope.launch {
            val problem = try {
                call()
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: DashboardException) {
                "Couldn't $what. ${refused.message}"
            } catch (failed: Throwable) {
                "Couldn't $what. Check the connection and try again."
            }
            mutableTeam.value = mutableTeam.value.copy(busy = null)
            loadTeam()
            if (problem != null) mutableTeam.value = mutableTeam.value.copy(problem = problem)
        }
    }

    private fun handleTeam(event: FeedEvent) {
        when (event) {
            is FeedEvent.ReadPeople -> scope.launch { loadTeam() }
            is FeedEvent.InvitePerson -> changeTeam("invite", "make the invite") {
                val made = client.createInvite(event.role, event.email)
                mutableTeam.value = mutableTeam.value.copy(
                    created = dev.wildware.agents.app.feed.CreatedInviteRow(made.link, made.token, made.invite.role, made.invite.email),
                )
            }
            is FeedEvent.DismissInvite -> mutableTeam.value = mutableTeam.value.copy(created = null)
            is FeedEvent.RevokeInvite -> changeTeam(event.inviteId, "revoke the invite") { client.revokeInvite(event.inviteId) }
            is FeedEvent.SetRole -> changeTeam(event.personId, "change the role") { client.setRole(event.personId, event.role) }
            is FeedEvent.RemovePerson -> changeTeam(event.personId, "remove them") {
                client.removePerson(event.personId)
                // Leaving signs this device out with the rest.
                if (event.personId == mutableTeam.value.me?.id) revokedHere.value = true
            }
            is FeedEvent.Rename -> changeTeam("me", "rename you") { client.rename(event.name.trim()) }
            is FeedEvent.SetMyProfile -> changeNow(event.done) {
                client.setProfile(event.profileRole, event.description)
                loadTeam()
            }
            is FeedEvent.SetMyAvatar -> changeNow(event.done) {
                client.setAvatar(event.picture?.let { uploadAvatar(it) })
                loadTeam()
            }
            is FeedEvent.DeleteAccount -> changeTeam(dev.wildware.agents.app.feed.DeleteAccountBusy, "delete your account") {
                client.deleteAccount(event.password)
                signOutNotice = "Your account was deleted, and this device is signed out."
                revokedHere.value = true
            }
            else -> Unit
        }
    }

    /**
     * A picked picture made into a member's picture: cropped to its middle square, made small, and
     * sent up. Its id goes on the profile next. One that can't be read is refused with why.
     */
    private suspend fun uploadAvatar(picked: ByteArray): String {
        val square = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { dev.wildware.agents.app.feed.squareAvatarPng(picked) }
            ?: throw DashboardException(400, "unreadable_image", "That picture couldn't be read. Try a PNG or JPEG.")
        return client.uploadMedia(dev.wildware.agents.Attachment("avatar.png", "image/png", square)).id
    }

    /** Reads every agent the dashboard knows. A dashboard without the list leaves the agent list as it was: online agents only. */
    private suspend fun loadAgents() {
        try {
            knownAgents.value = client.agents()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Throwable) {
            // Nothing to add: the live agents still show.
        }
    }

    /** Reads the MCP clients signed in as [agentId] over OAuth, newest first. */
    private suspend fun loadConnections(agentId: String) {
        if (mutableConnections.value.agentId != agentId) mutableConnections.value = dev.wildware.agents.app.feed.ConnectionList(agentId)
        val read = try {
            val rows = client.agentConnections(agentId).map { c ->
                dev.wildware.agents.app.feed.ConnectionRow(
                    id = c.id,
                    client = c.clientName,
                    where = connectionPlace(c.redirectOrigin),
                    used = c.lastUsedAt?.let(::isoMillis)?.let { "Last used ${mapper.relativeLabel(it)}" } ?: "Never used",
                    added = isoMillis(c.createdAt)?.let { "Added ${mapper.shortDay(it)}" } ?: "",
                )
            }
            dev.wildware.agents.app.feed.ConnectionList(agentId, rows, loaded = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: DashboardException) {
            mutableConnections.value.copy(problem = "Couldn't read its connections. ${refused.message}")
        } catch (failed: Throwable) {
            mutableConnections.value.copy(problem = "Couldn't read its connections. Check the connection and try again.")
        }
        // The owner may have opened another agent meanwhile.
        if (mutableConnections.value.agentId == agentId) mutableConnections.value = read
    }

    /** Reads which agents hear a channel or project; the list is for that one only. */
    private suspend fun loadMembers(slug: String, group: Boolean) {
        val target = dev.wildware.agents.app.feed.memberTarget(slug, group)
        val read = try {
            val rows = client.agentMembers(slug, group).map { m ->
                dev.wildware.agents.app.feed.MemberRow(m.agentId, m.name, m.hears, memberNote(m, group))
            }
            members(target).copy(members = rows, loaded = true, problem = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: DashboardException) {
            members(target).copy(problem = "Couldn't read its agents. ${refused.message}")
        } catch (failed: Throwable) {
            members(target).copy(problem = "Couldn't read its agents. Check the connection and try again.")
        }
        putMembers(target, read)
    }

    /** Adds an agent to a channel or project, or removes it, then reads the list again. */
    private fun setMember(slug: String, group: Boolean, agentId: String, hears: Boolean) {
        val target = dev.wildware.agents.app.feed.memberTarget(slug, group)
        val before = members(target)
        if (before.busy != null) return
        // Shown at once; the dashboard's answer replaces it.
        putMembers(target, before.copy(busy = agentId, problem = null, members = before.members.map { if (it.agentId == agentId) it.copy(hears = hears) else it }))
        scope.launch {
            val problem = try {
                client.setAgentMember(slug, group, agentId, hears)
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: DashboardException) {
                "Couldn't change it. ${refused.message}"
            } catch (failed: Throwable) {
                "Couldn't change it. Check the connection and try again."
            }
            loadMembers(slug, group)
            putMembers(target, members(target).copy(busy = null, problem = problem ?: members(target).problem))
            // The channel's list and its project's can both change.
            mutableMembers.value.keys.filter { it != target }.forEach { other ->
                val (kind, otherSlug) = other.split(":", limit = 2)
                loadMembers(otherSlug, kind == "group")
            }
        }
    }

    /** Signs one client out of an agent, then reads the list again. */
    private fun revokeConnection(agentId: String, connectionId: String) {
        if (mutableConnections.value.removing != null) return
        mutableConnections.value = mutableConnections.value.copy(removing = connectionId, problem = null)
        scope.launch {
            try {
                client.revokeAgentConnection(agentId, connectionId)
                loadConnections(agentId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: DashboardException) {
                mutableConnections.value = mutableConnections.value.copy(problem = "Couldn't revoke it. ${refused.message}")
            } catch (failed: Throwable) {
                mutableConnections.value = mutableConnections.value.copy(problem = "Couldn't revoke it. Check the connection and try again.")
            }
            mutableConnections.value = mutableConnections.value.copy(removing = null)
        }
    }

    /**
     * Reads the dashboard's secrets (never their values), and [auditFor]'s audit when given. A
     * dashboard from before secrets answers 404: the way in hides.
     */
    private suspend fun loadSecrets(auditFor: String? = mutableSecrets.value.auditFor) {
        val before = mutableSecrets.value
        mutableSecrets.value = try {
            val snapshot = client.secrets()
            val audit = auditFor?.let { id -> client.secretAudit(id).map(::auditRow) } ?: before.audit
            SecretList(
                secrets = snapshot.secrets.map(::secretRow),
                loaded = true,
                enabled = snapshot.enabled,
                audit = audit,
                auditFor = auditFor ?: before.auditFor,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: DashboardException) {
            if (refused.status == 404) SecretList(available = false) else before.copy(problem = "Couldn't read the secrets. ${refused.message}")
        } catch (failed: Throwable) {
            before.copy(problem = "Couldn't read the secrets. Check the connection and try again.")
        }
    }

    /** Reads the team's feedback. A dashboard from before feedback answers 404: then there is no sheet to fill. */
    private suspend fun loadFeedback() {
        val before = mutableFeedback.value
        mutableFeedback.value = try {
            val list = client.feedback()
            dev.wildware.agents.app.feed.FeedbackState(
                items = list.feedback.map(::feedbackRow),
                recipient = list.recipient,
                sends = list.sends,
                areas = list.areas,
                loaded = true,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: DashboardException) {
            if (refused.status == 404) dev.wildware.agents.app.feed.FeedbackState(loaded = true, available = false) else before.copy(problem = "Couldn't read the feedback. ${refused.message}")
        } catch (failed: Throwable) {
            before.copy(problem = "Couldn't read the feedback. Check the connection and try again.")
        }
    }

    private fun feedbackRow(f: dev.wildware.agents.FeedbackItem): dev.wildware.agents.app.feed.FeedbackRow =
        dev.wildware.agents.app.feed.FeedbackRow(
            id = f.id,
            kind = f.kind,
            title = f.title,
            body = f.body,
            area = f.area,
            status = f.status,
            statusLabel = dev.wildware.agents.app.feed.feedbackStatusLabel(f.status),
            who = if (f.author.kind == "agent") "${f.author.name} (agent)" else f.author.name,
            time = mapper.relativeLabel(f.sentAt ?: f.createdAt),
        )

    private fun sendFeedback(draft: dev.wildware.agents.FeedbackDraft, done: (String?) -> Unit) {
        scope.launch {
            val problem = try {
                client.sendFeedback(draft)
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: DashboardException) {
                refused.message ?: "The dashboard said no."
            } catch (failed: Throwable) {
                "Couldn't reach the dashboard. Check the connection and try again."
            }
            loadFeedback()
            done(problem)
        }
    }

    private fun agentName(id: String): String? = knownAgentNames.value[id] ?: state.value.agents.firstOrNull { it.id == id }?.name

    private fun secretRow(s: dev.wildware.agents.SecretView): SecretRow {
        val names = s.agents.map { it.name ?: agentName(it.id) ?: "a removed agent" }
        return SecretRow(
            id = s.id,
            name = s.name,
            note = s.note,
            scope = when {
                s.allAgents -> "All agents"
                names.isEmpty() -> "No agent yet"
                else -> names.joinToString(", ")
            },
            allAgents = s.allAgents,
            agentIds = s.agents.map { it.id },
            used = s.lastUsedAt?.let(::isoMillis)?.let { "Used ${mapper.relativeLabel(it)}" } ?: "Never used",
            origin = s.createdBy.removePrefix("agent:").let { by ->
                if (by == s.createdBy) "Added by you" else "From ${agentName(by) ?: "a removed agent"}"
            },
            paused = s.pausedAt != null,
        )
    }

    private fun auditRow(e: dev.wildware.agents.SecretAuditEntry): SecretAuditRow {
        val who = when (e.actor.kind) {
            "owner" -> e.actor.name?.let { "on $it" } ?: "by you"
            "agent" -> "by ${e.actor.name ?: e.actor.id?.let(::agentName) ?: "an agent"}"
            else -> "by the dashboard"
        }
        val agent = e.actor.name ?: e.actor.id?.let(::agentName) ?: "an agent"
        val what = when (e.action) {
            "created" -> "Added $who"
            "url_issued" -> "Link given to $agent"
            "downloaded" -> "Fetched by $agent"
            "upload_url_issued" -> "Upload link given to $agent"
            "uploaded" -> "Uploaded by $agent"
            "revealed" -> "Shown $who"
            "copied" -> "Copied $who"
            "value_changed" -> "Value replaced $who"
            "scope_changed" -> "Now for ${e.detail ?: "different agents"}"
            "paused" -> "Paused $who"
            "resumed" -> "Resumed $who"
            "provided" -> "Given $who, as asked"
            "refused" -> "Refused: ${e.detail ?: "the link no longer works"}"
            "deleted" -> "Deleted $who"
            else -> e.action.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
        return SecretAuditRow(e.id, what, isoMillis(e.at)?.let(mapper::relativeLabel).orEmpty(), warning = e.action == "refused")
    }

    /** Runs a change to a secret, then reads the list again. [done] hears null, or why not. */
    private fun changeSecret(id: String?, done: (String?) -> Unit, change: suspend () -> Unit) {
        if (id != null) mutableSecrets.value = mutableSecrets.value.copy(busy = id)
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
            loadSecrets()
            mutableSecrets.value = mutableSecrets.value.copy(busy = null)
            done(problem)
        }
    }

    /** A value for the owner: handed to [result] and kept nowhere else. */
    private fun revealSecret(id: String, copy: Boolean, result: (String?, String?) -> Unit) {
        scope.launch {
            try {
                val revealed = client.revealSecret(id, copy)
                result(revealed.value, null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: DashboardException) {
                result(null, refused.message ?: "The dashboard said no.")
            } catch (failed: Throwable) {
                result(null, "Couldn't reach the dashboard. Check the connection and try again.")
            }
            loadSecrets()
        }
    }

    /** A secret an agent attached to a post: handed to [result] and kept nowhere else. */
    private fun revealSharedSecret(secret: SharedSecret, copy: Boolean, result: (String?, String?, Boolean) -> Unit) {
        scope.launch {
            try {
                val revealed = client.revealSharedSecret(secret.cardKind, secret.cardId, secret.name, copy)
                result(revealed.value, null, false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: DashboardException) {
                val gone = refused.status == 410 || refused.status == 404
                result(null, if (gone) "No longer available." else refused.message ?: "The dashboard said no.", gone)
            } catch (failed: Throwable) {
                result(null, "Couldn't reach the dashboard. Check the connection and try again.", false)
            }
        }
    }

    /** Revokes a device's key, then reads the list again. Revoking this device's own key signs it out. */
    private fun revokeDevice(id: String) {
        if (mutableDevices.value.removing != null) return
        mutableDevices.value = mutableDevices.value.copy(removing = id, problem = null)
        scope.launch {
            try {
                client.removeDevice(id)
                if (id == saved.deviceId) {
                    revokedHere.value = true
                    return@launch
                }
                loadDevices()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: DashboardException) {
                mutableDevices.value = mutableDevices.value.copy(problem = "Couldn't revoke it. ${refused.message}")
            } catch (failed: Throwable) {
                mutableDevices.value = mutableDevices.value.copy(problem = "Couldn't revoke it. Check the connection and try again.")
            }
            mutableDevices.value = mutableDevices.value.copy(removing = null)
        }
    }

    /**
     * Allow or Deny, straight to the dashboard rather than through the outbox: an agent waits on it,
     * and a name already taken has to come back to the owner to change. Decided, the prompt goes at
     * once; refused, it stays and says why.
     */
    private fun decideJoin(pairingId: String, call: suspend () -> Unit) {
        joinStatus.value = joinStatus.value + (pairingId to JoinStatus(busy = true))
        scope.launch {
            val problem = try {
                call()
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: DashboardException) {
                joinProblem(refused)
            } catch (failed: Throwable) {
                "Couldn't reach the dashboard. Check the connection and try again."
            }
            joinStatus.value = joinStatus.value + (pairingId to (if (problem == null) JoinStatus(done = true) else JoinStatus(error = problem)))
        }
    }

    private fun joinProblem(refused: DashboardException): String = when {
        refused.code == dev.wildware.agents.PlanLimitHit.Code -> refused.message ?: "The free plan has no room for another agent."
        refused.code == "name_taken" -> "Another agent is already called that. Choose another name."
        refused.status == 409 -> "This request was already decided, or it has expired."
        refused.status == 404 -> "This request is gone: it expired, or this dashboard can't pair agents yet."
        else -> refused.message ?: "The dashboard refused that."
    }

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
     * Puts the "Get more with Team" popup up when it is due ([Upsell.popupDue]): for an owner or
     * admin of a free team, at most once a week, never in a new team's first days, and never while a
     * question or an agent asking to join waits on them. Showing it starts the week's wait.
     */
    private suspend fun offerNowAndThen() {
        combine(upsell, dashboard.waiting, dashboard.pairings) { offer, questions, joining -> offer.offer to (questions.isNotEmpty() || joining.isNotEmpty()) }
            .distinctUntilChanged()
            .collect { (offer, busy) ->
                if (offer == null || mutableUpsellPopup.value) return@collect
                val now = clock()
                if (!dev.wildware.agents.app.feed.Upsell.popupDue(offer, now, upsellMarks.popupShown(saved.server), busy)) return@collect
                upsellMarks.markPopup(saved.server, now)
                mutableUpsellPopup.value = true
            }
    }

    /**
     * Reads the team's plan. A dashboard with none, or from before plans, or one that can't be
     * reached leaves it unknown: Settings then shows nothing about plans.
     */
    private suspend fun loadPlan() {
        val plan = try {
            client.plan()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Throwable) {
            null
        }
        mutablePlan.value = dev.wildware.agents.app.feed.PlanState(plan, loaded = true)
    }

    /**
     * Reads the team's GitHub (server migration 080). A dashboard without it (too old, or not set
     * up) leaves it unavailable, and Settings doesn't offer it.
     */
    private suspend fun loadGitHub() {
        mutableGitHub.value = try {
            mutableGitHub.value.copy(status = client.githubStatus(), loaded = true, problem = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Throwable) {
            dev.wildware.agents.app.feed.GitHubSettings(loaded = true)
        }
    }

    private suspend fun connectGitHub() {
        mutableGitHub.value = mutableGitHub.value.copy(busy = true, problem = null)
        mutableGitHub.value = try {
            mutableGitHub.value.copy(busy = false, connectUrl = client.connectGitHub())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: DashboardException) {
            mutableGitHub.value.copy(busy = false, problem = "Couldn't start connecting GitHub. ${refused.message}")
        } catch (failed: Throwable) {
            mutableGitHub.value.copy(busy = false, problem = "Couldn't start connecting GitHub. Check the connection and try again.")
        }
    }

    private suspend fun disconnectGitHub(id: Long) {
        mutableGitHub.value = mutableGitHub.value.copy(busy = true, problem = null)
        try {
            client.disconnectGitHub(id)
            loadGitHub()
            mutableGitHub.value = mutableGitHub.value.copy(busy = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Throwable) {
            val words = (failed as? DashboardException)?.message ?: "Check the connection and try again."
            mutableGitHub.value = mutableGitHub.value.copy(busy = false, problem = "Couldn't disconnect it. $words")
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
     * A change to a project or an agent, made straight away rather than queued: it needs the dashboard's say
     * (a name can be taken or too long), so [done] hears null once it is made or the reason it wasn't.
     */
    private fun changeNow(done: (String?) -> Unit = {}, change: suspend () -> Unit) {
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

    /** Marks every card and question in one channel read: its menu's "Mark read". */
    private fun markChannelRead(projectId: String) {
        val everything = dashboard.everything.state.value
        (everything.allCards.filter { it.projectId == projectId && it.unread > 0 }.map { it.id } +
            (dashboard.waiting.value + everything.waiting).filter { it.request.projectId == projectId && it.unread > 0 }.map { it.id })
            .distinct()
            .forEach(dashboard::markRead)
    }

    fun start() {
        outbox.start()
        dashboard.start()
        scope.launch { loadAlerts() }
        scope.launch { loadRetention() }
        scope.launch { loadTeam() }
        // The open channel's agents, for the members list: read whenever another channel opens.
        scope.launch { selected.collect { slug -> if (slug != null) loadMembers(slug, group = false) } }
        // Who of the team is online, for the members list: read again now and then.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(PEOPLE_POLL_MS)
                val known = mutableTeam.value
                if (!known.supported || !known.loaded || !known.team) continue
                val fresh = try {
                    client.people().associateBy { it.id }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failed: Throwable) {
                    continue
                }
                mutableTeam.value = mutableTeam.value.copy(people = mutableTeam.value.people.map { p -> fresh[p.id]?.let { p.copy(online = it.online) } ?: p })
            }
        }
        // Someone new wrote something (the first teammate, or a later one): read the team again, so
        // their words carry their name rather than showing as yours.
        scope.launch {
            dashboard.everything.state.map { feed ->
                feed.allCards.flatMap { card -> card.replies + listOfNotNull((card as? dev.wildware.agents.PostCard)?.post) }
                    .mapNotNullTo(HashSet()) { it.message.personId } as Set<String>
            }.distinctUntilChanged().collect { ids ->
                val known = mutableTeam.value
                if (known.supported && known.loaded && ids.any { id -> known.people.none { it.id == id } }) loadTeam()
            }
        }
        // Read now, and again when an agent comes online or goes: it may be one the list has not heard of.
        scope.launch { dashboard.online.map { list -> list.map { it.agentId }.toSet() }.distinctUntilChanged().collect { loadAgents() } }
        // A profile or name changed, here or elsewhere: read the agents or the people again.
        scope.launch {
            dashboard.memberChanges.collect { type ->
                if (type != "people.updated") loadAgents()
                if (type == "people.updated" || type == dev.wildware.agents.StreamEvent.Resync) {
                    val known = mutableTeam.value
                    if (known.supported && known.loaded) loadTeam()
                }
            }
        }
        // Early, so the account sheet knows whether this dashboard has secrets at all.
        scope.launch { loadSecrets(null) }
        // Another process queued, sent or dropped something: show it, and send what is new.
        scope.launch { outboxChanged.collect { outbox.reload() } }
        scope.launch { foreground.collect { resume() } }
        // The plan, for the notes that say a limit is near; read again on each return to the app.
        scope.launch { loadPlan() }
        scope.launch { foreground.collect { loadPlan() } }
        // An agent asking to join: read the plan's use, so the prompt can say when there's no room for it.
        scope.launch { dashboard.pairings.map { it.isNotEmpty() }.distinctUntilChanged().collect { if (it) loadPlan() } }
        scope.launch { offerNowAndThen() }
        // App Store purchases, renewals and restores (iPhone), on to the dashboard.
        appStore?.let { store -> scope.launch { relayPurchases(store) } }
        // Pushed notifications the owner dealt with, here or elsewhere, come off the lock screen.
        platform.notices?.let { notices -> scope.launch { NoticeCleanup(dashboard, notices, foreground).run() } }
        if (platform.sounds.available) scope.launch(soundThread) { playSounds() }
    }

    /** Plays a sound for each moment that makes one ([SoundRules]), as the dashboard changes. */
    private suspend fun playSounds() {
        combine(
            combine(dashboard.ready, dashboard.status, dashboard.waiting, ::Triple),
            dashboard.everything.state,
            outbox.items,
            combine(dashboard.pairings, team, dashboard.followed, ::Triple),
            alerts,
        ) { (ready, status, waiting), feed, items, (joining, team, follows), alerts ->
            val slugs = dashboard.projects.value.associate { it.project.id to it.project.slug }
            SoundSnapshot(clock(), ready, status == StreamStatus.Live, waiting, feed, items, alerts.prefs, { id -> id?.let(slugs::get) }, joining, follows, team)
        }.collect { snapshot -> platform.sounds.play(soundRules.next(snapshot, platform.systemSounds)) }
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
        // What comes in now is what was missed while away: taken in quietly.
        val now = clock()
        sounds { soundRules.hush(now) }
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

    /** [stop], once the outbox has finished writing: what waits to send can then be moved safely. */
    suspend fun stopAndSettle() {
        dashboard.stop()
        outbox.stopAndSettle()
        stop()
    }

    fun stop() {
        dashboard.stop()
        outbox.stop()
        scope.cancel()
        client.close()
    }

    /** Does what the owner asked. Sign-out and screen-only events are handled elsewhere. */
    fun handle(event: FeedEvent) {
        // The owner's own doings make no sound when they come back from the dashboard.
        val at = clock()
        when (event) {
            is FeedEvent.SetStatus -> sounds { soundRules.touched(event.targetId, at) }
            is FeedEvent.BoardMove -> sounds { soundRules.touched(event.id, at) }
            else -> Unit
        }
        // A new answer replaces one the dashboard refused: the refused one is not sent as well.
        when (event) {
            is FeedEvent.Answer -> dropRefused(event.questionId)
            is FeedEvent.AnswerText -> dropRefused(event.questionId)
            is FeedEvent.AnswerForm -> dropRefused(event.questionId)
            is FeedEvent.AnswerFields -> dropRefused(event.questionId)
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
            is FeedEvent.AnswerFields -> request(event.questionId)?.let { card -> actions.answer(card.request, card.agentName, event.value) }
            is FeedEvent.DismissQuestion -> request(event.questionId)?.let { actions.dismiss(it.request, it.agentName) }
            is FeedEvent.AllowAgent -> decideJoin(event.pairingId) { client.approvePairing(event.pairingId, event.name) }
            is FeedEvent.DenyAgent -> decideJoin(event.pairingId) { client.denyPairing(event.pairingId) }
            is FeedEvent.React -> cardRef(event.targetId)?.let { actions.toggleReaction(it, event.emoji, reactionsOn(event.targetId)) }
            is FeedEvent.CheckBlockItem -> dashboard.checkBlockItem(CardRef(event.cardKind, event.cardId), event.blockId, event.itemId, event.done)
            is FeedEvent.VoteBlock -> dashboard.voteBlock(CardRef(event.cardKind, event.cardId), event.blockId, event.options, mutableTeam.value.me?.id)
            is FeedEvent.SendReply -> cardRef(event.threadId)?.let {
                actions.reply(it, event.text.ifBlank { null }, answers = event.answers, files = event.files.map(::upload), voice = event.voice)
            }
            is FeedEvent.Post -> (event.project?.let(::projectRef) ?: postTarget())?.let {
                remember(it.slug)
                // A word made in the picker isn't on the dashboard yet: it is made as it goes on.
                val tags = event.tags.map { t -> if (t.id.startsWith("new:")) OutboxTag(t.name, create = true) else OutboxTag(t.id) }
                actions.post(it, event.text.ifBlank { null }, event.files.map(::upload), tags, voice = event.voice)
            }
            is FeedEvent.Delete -> cardRef(event.targetId)?.let { actions.delete(it) }
            is FeedEvent.SaveEdit -> cardRef(event.targetId)?.takeIf { it.kind == CardKinds.Message }?.let { actions.edit(it, event.text) }
            is FeedEvent.Tag -> cardRef(event.targetId)?.let { actions.tag(it, event.tag, event.on, event.create) }
            is FeedEvent.SetStatus -> cardRef(event.targetId)?.let { actions.setStatus(it, event.status?.wire) }
            is FeedEvent.ShowBoard -> board.show(event.scope)
            is FeedEvent.Docs -> docs.handle(event.action)
            is FeedEvent.Media -> mediaTab.handle(event.action)
            is FeedEvent.BoardMove -> board.move(event.kind, event.id, event.status)
            is FeedEvent.BoardRemove -> board.remove(event.kind, event.id, event.removed)
            is FeedEvent.BoardShowRemoved -> board.showRemoved(event.show)
            is FeedEvent.BoardAutoRemove -> board.setAutoRemove(event.days)
            is FeedEvent.BoardMore -> board.more(event.status)
            is FeedEvent.BoardAssigned -> board.filter(event.filter)
            is FeedEvent.BoardAttention -> board.attention(event.on)
            is FeedEvent.Assign -> cardRef(event.targetId)?.takeIf { it.kind != CardKinds.Request }?.let { card ->
                assign.open(card, (state.value.item(event.targetId) as? dev.wildware.agents.app.feed.Threaded)?.title.orEmpty())
            }
            is FeedEvent.AssignCard -> assign.open(CardRef(event.kind, event.id), event.title)
            is FeedEvent.SaveAssignees -> assign.save(event.principals)
            is FeedEvent.CloseAssign -> assign.close()
            is FeedEvent.Pin -> cardRef(event.targetId)?.let { actions.pin(it, event.pinned) }
            is FeedEvent.SetField -> cardRef(event.targetId)?.let { actions.setField(it, event.name, event.value) }
            is FeedEvent.OpenThread -> dashboard.markRead(event.cardId)
            is FeedEvent.MarkUnread -> dashboard.markUnread(event.targetId)
            is FeedEvent.MarkAllRead -> markAllRead()
            is FeedEvent.OpenNotification -> dashboard.markNotificationsSeen(listOf(event.id))
            is FeedEvent.ClearNotifications -> dashboard.markNotificationsSeen(null)
            is FeedEvent.SetAlertPrefs -> saveAlerts(event.prefs)
            is FeedEvent.ReadMediaRetention -> scope.launch { loadRetention() }
            is FeedEvent.ReadGitHub -> scope.launch { loadGitHub() }
            is FeedEvent.ConnectGitHub -> scope.launch { connectGitHub() }
            is FeedEvent.GitHubPageOpened -> mutableGitHub.value = mutableGitHub.value.copy(connectUrl = null)
            is FeedEvent.DisconnectGitHub -> scope.launch { disconnectGitHub(event.installationId) }
            is FeedEvent.ReadPlan -> scope.launch { loadPlan() }
            is FeedEvent.DismissUpgrade -> mutableUpgrade.value = null
            is FeedEvent.DismissUpsell -> {
                upsellMarks.markPopup(saved.server, clock())
                mutableUpsellPopup.value = false
            }
            is FeedEvent.HideFilesNote -> {
                upsellMarks.markFilesHidden(saved.server, clock())
                mutableFilesHidden.value = true
            }
            is FeedEvent.BuyTeam -> buyTeam(event.productId, event.done)
            is FeedEvent.RestorePurchases -> restorePurchases(event.done)
            is FeedEvent.ReadFeedback -> scope.launch { loadFeedback() }
            is FeedEvent.SendFeedback -> sendFeedback(event.draft, event.done)
            is FeedEvent.ReadDevices -> scope.launch { loadDevices() }
            is FeedEvent.ReadSecrets -> scope.launch { loadSecrets(event.auditFor ?: mutableSecrets.value.auditFor) }
            is FeedEvent.AddSecret -> changeSecret(null, event.done) {
                client.createSecret(event.name, event.value, event.note, SecretScope(event.allAgents, event.agentIds))
            }
            is FeedEvent.ChangeSecret -> changeSecret(event.secretId, event.done) {
                val scope = if (event.allAgents != null || event.agentIds != null) {
                    SecretScope(event.allAgents ?: false, event.agentIds.orEmpty())
                } else {
                    null
                }
                client.changeSecret(event.secretId, value = event.value, scope = scope, paused = event.paused)
            }
            is FeedEvent.DeleteSecret -> changeSecret(event.secretId, event.done) { client.deleteSecret(event.secretId) }
            is FeedEvent.RevealSecret -> revealSecret(event.secretId, event.copy, event.result)
            is FeedEvent.RevealSharedSecret -> revealSharedSecret(event.secret, event.copy, event.result)
            // Straight to the dashboard, never the outbox: a secret is never written to this device's disk.
            is FeedEvent.ProvideSecret -> changeNow(event.done) { client.provideSecret(event.questionId, event.value) }
            is FeedEvent.ProvideSecrets -> changeNow(event.done) { client.provideSecrets(event.questionId, event.values) }
            is FeedEvent.ReadPeople, is FeedEvent.InvitePerson, is FeedEvent.DismissInvite, is FeedEvent.RevokeInvite,
            is FeedEvent.SetRole, is FeedEvent.RemovePerson, is FeedEvent.Rename, is FeedEvent.SetMyProfile, is FeedEvent.SetMyAvatar,
            is FeedEvent.DeleteAccount -> handleTeam(event)
            is FeedEvent.RevokeDevice -> revokeDevice(event.deviceId)
            is FeedEvent.ReadConnections -> scope.launch { loadConnections(event.agentId) }
            is FeedEvent.ReadMembers -> scope.launch { loadMembers(event.slug, event.group) }
            is FeedEvent.SetMember -> setMember(event.slug, event.group, event.agentId, event.hears)
            is FeedEvent.ReadAccess -> scope.launch { loadAccess(event.id, event.group) }
            is FeedEvent.SetAccessPrivate -> changeAccess(event.id, event.group, "private") { client.setPrivate(event.id, event.group, event.isPrivate) }
            is FeedEvent.SetAccessLevel -> changeAccess(event.id, event.group, "${event.kind}:${event.who}") {
                client.setAccessLevel(event.id, event.group, event.kind, event.who, event.level)
            }
            is FeedEvent.ReadReach -> scope.launch { loadReach(event.id, event.agent) }
            is FeedEvent.RevokeConnection -> revokeConnection(event.agentId, event.connectionId)
            is FeedEvent.SetMediaRetention -> saveRetention(event.setting)
            is FeedEvent.SelectProject -> {
                // Another channel puts away a card open on its own: the screen did already.
                dashboard.everything.closeAlone()
                select(event.slug)
            }
            is FeedEvent.CreateProject -> changeNow(event.done) {
                val made = client.createProject(event.name.trim(), event.description?.trim())
                event.groupId?.let { client.setProjectGroup(made.slug, it) }
                dashboard.refresh()
                select(made.slug)
            }
            is FeedEvent.EditProject -> changeNow(event.done) {
                client.renameProject(event.slug, event.name.trim(), event.description?.trim())
                dashboard.refresh()
            }
            is FeedEvent.RemoveAgent -> changeNow(event.done) {
                client.removeAgent(event.agentId)
                knownAgents.value = knownAgents.value.map { if (it.id == event.agentId) it.copy(revokedAt = "now") else it }
                loadAgents()
                dashboard.refresh()
            }
            is FeedEvent.RenameAgent -> changeNow(event.done) {
                client.renameAgent(event.agentId, event.name.trim())
                loadAgents()
                dashboard.refresh()
            }
            is FeedEvent.SetAgentProfile -> changeNow(event.done) {
                client.setAgentProfile(event.agentId, event.profileRole, event.description)
                loadAgents()
            }
            is FeedEvent.SetAgentAvatar -> changeNow(event.done) {
                client.setAgentAvatar(event.agentId, event.picture?.let { uploadAvatar(it) })
                loadAgents()
            }
            is FeedEvent.SetProjectLogo -> changeNow {
                client.setProjectLogo(event.slug, event.mediaId)
                dashboard.refresh()
            }
            is FeedEvent.SetLogoInsteadOfName -> changeNow {
                client.setLogoInsteadOfName(event.slug, event.instead)
                dashboard.refresh()
            }
            is FeedEvent.FollowThread -> cardRef(event.targetId)?.let { dashboard.followThread(it, event.following) }
            is FeedEvent.SetProjectNotify -> dashboard.setProjectNotify(event.projectId, event.slug, event.level)
            is FeedEvent.CreateGroup -> changeNow(event.done) {
                client.createGroup(event.name.trim())
                dashboard.refresh()
            }
            is FeedEvent.RenameGroup -> changeNow(event.done) {
                client.renameGroup(event.slug, event.name.trim())
                dashboard.refresh()
            }
            is FeedEvent.SetGroupAccent -> changeNow {
                client.setGroupAccent(event.slug, event.accent)
                dashboard.refresh()
            }
            is FeedEvent.ArchiveGroup -> changeNow {
                client.setGroupArchived(event.slug, event.archived)
                dashboard.refresh()
            }
            is FeedEvent.DeleteGroup -> changeNow {
                client.deleteGroup(event.slug)
                dashboard.refresh()
            }
            is FeedEvent.ArrangeChannels -> changeNow(event.done) {
                client.arrangeChannels(event.slugs, event.groupId)
                dashboard.refresh()
            }
            is FeedEvent.ArrangeGroups -> changeNow(event.done) {
                client.arrangeGroups(event.slugs)
                dashboard.refresh()
            }
            is FeedEvent.MarkChannelRead -> markChannelRead(event.projectId)
            is FeedEvent.MoveChannel -> changeNow {
                client.setProjectGroup(event.slug, event.groupId)
                dashboard.refresh()
            }
            is FeedEvent.SetGroupNotify -> dashboard.setGroupNotify(event.groupId, event.slug, event.level)
            is FeedEvent.PinProject -> changeNow {
                client.setProjectPinned(event.slug, event.pinned)
                dashboard.refresh()
            }
            is FeedEvent.ArchiveProject -> changeNow {
                client.setProjectArchived(event.slug, event.archived)
                dashboard.refresh()
            }
            is FeedEvent.SetProjectColours -> changeNow {
                client.setProjectColours(event.slug, event.background, event.accent)
                dashboard.refresh()
            }
            is FeedEvent.Refresh -> {
                dashboard.revive(reconnect = true)
                outbox.kick(ignoreBackoff = true)
                scope.launch { runCatching { dashboard.refresh() } }
            }
            is FeedEvent.LoadOlder -> scope.launch { runCatching { feed.loadOlder() } }
            // Read by the every-channel feed, which hears every channel, so its thread stays live wherever the owner is.
            is FeedEvent.OpenAlone -> scope.launch {
                val id = try {
                    dashboard.everything.openAlone(event.id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                event.result(id)
            }
            is FeedEvent.CloseAlone -> dashboard.everything.closeAlone()
            is FeedEvent.RetrySend -> outbox.retryNow(event.outboxId)
            is FeedEvent.DropUnsent -> outbox.remove(event.outboxId)
            is FeedEvent.PauseUnsent -> outbox.pauseVoice(event.outboxId)
            is FeedEvent.ChooseWord -> outbox.chooseWord(event.outboxId, event.index, event.choice)
            is FeedEvent.ChooseSentWord -> dashboard.chooseSentWord(event.messageId, event.index, event.choice)?.let { text ->
                actions.edit(CardRef(CardKinds.Message, event.messageId), text)
            }
            is FeedEvent.ChooseStatus, is FeedEvent.EditMessage, is FeedEvent.Reply, is FeedEvent.OpenDetails, is FeedEvent.EditTags, is FeedEvent.FeedbackAbout, is FeedEvent.CopyText, is FeedEvent.SelectText, is FeedEvent.CopyLink, is FeedEvent.Quote, is FeedEvent.SignOut, is FeedEvent.ToggleTheme -> Unit
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

    /** Kept on the device, so "All projects" posts where the owner last worked, even after a restart. */
    private fun remember(slug: String) {
        if (lastProject.value == slug) return
        lastProject.value = slug
        runCatching { postTarget.write(slug) }
    }

    private fun select(slug: String?) {
        if (slug != null) remember(slug)
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
    private fun postTarget(): ProjectRef? = selected.value?.let(::projectRef)

    private fun projectRef(slug: String): ProjectRef {
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

    private fun allLines(): List<ThreadLine> = (latest.allCards + listOfNotNull(alone)).flatMap { card ->
        card.replies + ((card as? dev.wildware.agents.PostCard)?.post?.let(::listOf) ?: emptyList())
    } + latest.waiting.flatMap { it.discussion }

    /** The card open on its own, if one is: the every-channel feed holds it. */
    private val alone: dev.wildware.agents.FeedCard? get() = dashboard.everything.state.value.lone

    /** A card by id: in the feed on screen, or the one open on its own. */
    private fun cardById(id: String): dev.wildware.agents.FeedCard? = latest.card(id) ?: alone?.takeIf { it.id == id }

    /** What card or line an id names, as the server's write calls want it. */
    private fun cardRef(id: String): CardRef? =
        // A line still in the outbox has no id on the dashboard yet: nothing can be done to it there.
        if (id.startsWith(DraftPrefix)) null else cardById(id)?.card
            ?: request(id)?.card
            ?: allLines().firstOrNull { it.message.id == id }?.let { CardRef(CardKinds.Message, id) }

    private fun reactionsOn(id: String): List<ReactionView> =
        cardById(id)?.reactions ?: request(id)?.request?.reactions
            ?: allLines().firstOrNull { it.message.id == id }?.reactions.orEmpty()

    /** The answer as the server takes it for this kind of question. */
    private fun answerValue(card: RequestCard, choices: List<String>): JsonElement = when (card.request.requestKind) {
        RequestKind.Confirm -> JsonPrimitive(choices.firstOrNull().let { it == FeedMapper.Yes || it == FeedMapper.Allow })
        RequestKind.MultiChoice -> JsonArray(choices.map(::JsonPrimitive))
        else -> JsonPrimitive(choices.firstOrNull().orEmpty())
    }
}

/** Answers that mean "not for this person" or "this dashboard has no such setting": the setting stays hidden. */
private val RetentionHidden = setOf(401, 403, 404)

/**
 * Where a connection signed in from, in words: an app on the client's own computer for a loopback
 * address, the site for https, or the app whose links it used.
 */
internal fun connectionPlace(origin: String): String {
    val lower = origin.lowercase()
    val host = lower.substringAfter("://", "").substringBefore('/').substringBeforeLast(':').removePrefix("[").removeSuffix("]")
    return when {
        lower.startsWith("http://") && (host == "localhost" || host == "::1" || host.startsWith("127.")) -> "An app on its own computer"
        lower.startsWith("https://") -> host.ifEmpty { origin }
        lower.endsWith(":") -> "${origin.removeSuffix(":")}: links"
        else -> origin
    }
}

/**
 * The agent list: the live agents, then every other agent the dashboard knows that is not removed,
 * offline, most recently seen first. Removed agents are left out, live or not.
 */
internal fun withOffline(live: List<dev.wildware.agents.app.feed.Agent>, known: List<dev.wildware.agents.AgentRecord>): List<dev.wildware.agents.app.feed.Agent> {
    val removed = known.filter { it.revokedAt != null }.mapTo(HashSet()) { it.id }
    val byId = known.associateBy { it.id }
    // Profiles come only from the full list: the live one has none.
    val shown = live.filter { it.id !in removed }.map { a ->
        val record = byId[a.id] ?: return@map a
        if (record.profileRole == a.profileRole && record.description == a.description && record.avatarMediaId == a.avatarMediaId) a
        else a.copy(profileRole = record.profileRole, description = record.description, avatarMediaId = record.avatarMediaId)
    }
    val liveIds = shown.mapTo(HashSet()) { it.id }
    val offline = known
        .filter { it.revokedAt == null && it.id !in liveIds }
        .sortedByDescending { it.lastSeenAt ?: "" }
        .map {
            dev.wildware.agents.app.feed.Agent(
                it.id, it.name, "Agent", dev.wildware.agents.app.feed.Presence.Offline, hue(it.id),
                profileRole = it.profileRole, description = it.description, avatarMediaId = it.avatarMediaId,
            )
        }
    return shown + offline
}

/** Why an agent hears a channel or project, or that the owner removed it, in a few words. */
internal fun memberNote(m: dev.wildware.agents.AgentMember, group: Boolean): String = when {
    m.removed == "group" && !group -> "Removed with its project. Hears it only when @mentioned."
    m.removed != null -> "Removed. Hears it only when @mentioned."
    !m.hears -> "Not following it. Hears it only when @mentioned."
    m.via == "all" -> "Hears every channel"
    m.via == "group" -> "Through its project"
    m.via == "posted" -> "Posted here"
    else -> "Following it"
}

/** How often the members list reads who of the team is online. */
private const val PEOPLE_POLL_MS = 60_000L
