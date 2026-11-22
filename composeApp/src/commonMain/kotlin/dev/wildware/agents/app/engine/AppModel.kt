package dev.wildware.agents.app.engine

import dev.wildware.agents.HostCheck
import dev.wildware.agents.HostedAccount
import dev.wildware.agents.HostedLoginException
import dev.wildware.agents.HostedLoginFlow
import dev.wildware.agents.HostedTeam
import dev.wildware.agents.SignIn
import dev.wildware.agents.SignInException
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.SavedSession
import dev.wildware.agents.app.design.ThemeChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dev.wildware.agents.MemoryOutboxStorage
import dev.wildware.agents.MemoryTextStore
import dev.wildware.agents.app.demo.DemoServer
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.session.MemorySessionStore
import dev.wildware.agents.app.session.SavedTeam
import dev.wildware.agents.app.session.SavedTeams
import dev.wildware.agents.app.session.TeamList
import dev.wildware.agents.app.session.clearOutbox
import dev.wildware.agents.app.session.moveOutbox
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the demo's sessions think they are. Nothing is ever sent there: the demo answers itself. */
internal const val DemoAddress = "https://demo.example"

/** The project the demo opens on. */
internal const val DemoStartProject = "garden-game"

/** Which screen the app is on. */
sealed interface AppScreen {
    /**
     * Signed out. [notice] says why, when the app signed itself out. [adding]: signed in to a team
     * already, and signing in to another; "Back" returns to the open one.
     */
    data class SigningIn(val notice: String? = null, val adding: Boolean = false) : AppScreen

    /**
     * Signed in to the server on this computer, but it couldn't start, so the feed isn't opened
     * (nothing is sent to whatever holds its address). [problem] says why, fit to show.
     */
    data class LocalServerDown(val problem: String) : AppScreen

    /** The feed. [demo] when it runs on the built-in sample data rather than a dashboard. */
    data class Signed(val session: DashboardSession, val demo: Boolean = false) : AppScreen
}

/**
 * One team in the team switcher. [waiting]: questions waiting there, when known (the open team's
 * own count is in its feed). [link]: a team the web app opened in another tab, which it holds no
 * sign-in for; choosing it opens that tab again.
 */
data class TeamEntry(
    val key: String,
    val name: String,
    val server: String,
    val active: Boolean,
    val waiting: Int? = null,
    val local: Boolean = false,
    val link: Boolean = false,
)

/**
 * The app's top: which teams this device is signed in to, and which is open. A saved key opens
 * the feed straight away; signing in adds a team (or, signed in to none, the first); signing out
 * of a team forgets it and everything kept for it, and opens the next.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class AppModel(
    private val platform: AppPlatform,
    private val scope: CoroutineScope,
    /** The hosted "Log in"'s account service; empty for none. Tests point it at a fake. */
    accountService: String = dev.wildware.agents.app.signin.HostedLoginBuild.AccountService,
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    /** How often a team that isn't open is asked for its badge. */
    watchIntervalMillis: Long = TeamWatch.DefaultIntervalMillis,
) {
    private val mutableScreen = MutableStateFlow<AppScreen>(AppScreen.SigningIn())
    val screen: StateFlow<AppScreen> = mutableScreen.asStateFlow()

    val deviceName: String get() = platform.deviceName

    private var watcher: Job? = null

    private val teamList = TeamList(platform.sessions, platform.teamList, clock)

    /** The open team's key, and its live session once there is one. */
    private var activeKey: String? = null
    private var current: DashboardSession? = null

    private val watch = TeamWatch(platform.httpEngine, scope, platform.foreground, watchIntervalMillis)

    private val savedTeams = MutableStateFlow(SavedTeams())

    /** One change of open team at a time: each waits for the last to finish closing its team. */
    private val changing = Mutex()

    /** Runs [change] after any change of team already under way. */
    private fun changeTeams(change: suspend () -> Unit): Job = scope.launch { changing.withLock { change() } }

    /** Every team this device is signed in to, the open one marked, in the order they were added. */
    val teams: StateFlow<List<TeamEntry>> = combine(savedTeams, watch.waiting) { saved, waiting ->
        saved.teams.map { t ->
            TeamEntry(t.key, t.name, t.session.server, t.key == saved.active, if (t.key == saved.active) null else waiting[t.key], t.session.local)
        } + saved.links.map { l -> TeamEntry(LinkPrefix + l.server, l.name, l.server, active = false, link = true) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Questions asked in a team that isn't open, for the desktop's and Android's own notices. */
    val otherTeamQuestions: SharedFlow<TeamQuestion> = watch.questions

    private val mutableTheme = MutableStateFlow(
        platform.settings.read()?.trim()?.let { saved -> ThemeChoice.entries.firstOrNull { it.name == saved } } ?: ThemeChoice.System,
    )

    /** Light or dark, as the owner last picked. */
    val theme: StateFlow<ThemeChoice> = mutableTheme.asStateFlow()

    /** Flips between light and dark from whatever is showing now, and remembers it. */
    fun toggleTheme(showingDark: Boolean) {
        val next = if (showingDark) ThemeChoice.Light else ThemeChoice.Dark
        mutableTheme.value = next
        runCatching { platform.settings.write(next.name) }
    }

    /**
     * The hosted "Log in": email, code, team. Null in the web app, which a team's own server serves
     * (the account site signs a browser in there itself), and in a build with no account service.
     */
    val hostedLogin: HostedLoginFlow? =
        if (platform.homeServer == null && accountService.isNotBlank()) HostedLoginFlow(HostedAccount(accountService, platform.httpEngine), platform.httpEngine, scope)
        else null

    /** The web app: one team per tab, as each address keeps its own cookie. */
    val webApp: Boolean get() = platform.homeServer != null

    init {
        val saved = teamList.load()
        val home = platform.homeServer
        val first = saved.activeTeam ?: saved.teams.maxByOrNull { it.lastUsed }
        when {
            first != null -> changeTeams { openTeam(first) }
            home != null -> scope.launch { adoptCookie(home) }
        }
        scope.launch { watch.signedOut.collect { key -> changeTeams { forgetTeam(key, endDevice = false) } } }
        publishTeams()
    }

    /**
     * The web app with nothing saved may still be signed in: the account site's "Open" signs a
     * browser in with a ticket, which sets only the cookie. If the server knows this browser, its
     * feed opens; if not, the sign-in form stays.
     */
    private suspend fun adoptCookie(home: String) {
        if (!SignIn.cookieSignedIn(home, platform.httpEngine())) return
        // Signed in by hand while asking: that session stands.
        if (mutableScreen.value !is AppScreen.SigningIn || teamList.load().teams.isNotEmpty()) return
        addTeam(SavedSession(SignIn.normalise(home), SignIn.CookieSession, platform.deviceName))
    }

    /**
     * Closes the open team, if any: its live session stops and, once its outbox has finished
     * writing, what waits to send is put by for it.
     */
    private suspend fun closeCurrent() {
        watcher?.cancel()
        watcher = null
        val closing = current
        current = null
        closing?.stopAndSettle()
        activeKey?.let { key -> runCatching { moveOutbox(platform.outbox, platform.teamFiles(key).outbox) } }
        activeKey = null
    }

    /** Opens [team]: on this computer's server once it has started, else straight away. */
    private suspend fun openTeam(team: SavedTeam) {
        closeCurrent()
        val opened = teamList.activate(team.key) ?: return
        activeKey = opened.key
        // What waited to send for this team while another was open goes out now.
        runCatching { moveOutbox(platform.teamFiles(opened.key).outbox, platform.outbox) }
        publishTeams()
        if (opened.session.local && platform.localServer != null) scope.launch { openLocal(opened) } else start(opened)
    }

    /**
     * Starts the server on this computer, then opens [team] on it, at the address it is on now.
     * When it can't start, the feed stays shut ([AppScreen.LocalServerDown]) until [retryLocal].
     */
    private suspend fun openLocal(team: SavedTeam) {
        val host = platform.localServer ?: return start(team)
        val address = try {
            host.ensureRunning()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableScreen.value = AppScreen.LocalServerDown(e.message ?: "The server on this computer couldn't start.")
            return
        }
        if (activeKey != team.key) return
        val moved = if (address == team.session.server) team else team.copy(session = team.session.copy(server = address)).also { teamList.update(it.key, it.session) }
        start(moved)
    }

    /** "Try again" after the server on this computer couldn't start. */
    fun retryLocal() {
        val team = teamList.load().activeTeam?.takeIf { it.session.local } ?: return
        scope.launch { openLocal(team) }
    }

    /** Whether this app can run a server itself ("This computer"). */
    val canHostLocally: Boolean get() = platform.localServer != null

    /** Starts the server on this computer and signs in to it, with no password. */
    suspend fun signInHere(deviceName: String) {
        val host = platform.localServer ?: error("this app can't run a server")
        val signed = host.signInHere(deviceName)
        addTeam(SavedSession(signed.server, signed.token, signed.deviceName, signed.deviceId, local = true))
    }

    /** "Connect to a host", step 1: whether [address] is a Wildware Feed server, and how it signs in. */
    suspend fun checkHost(address: String): HostCheck = HostCheck.check(address, platform.httpEngine())

    /**
     * The hosted "Log in"'s last step: signs this device in to [team] and opens its feed, saved and
     * registered for alerts exactly as "Connect to a host" is. A team this device is in already
     * just opens.
     */
    @Throws(HostedLoginException::class, SignInException::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun joinHostedTeam(team: HostedTeam, deviceName: String) {
        val flow = hostedLogin ?: error("this app has no hosted Log in")
        teamList.find(team.address)?.let { known ->
            flow.startOver()
            changing.withLock { openTeam(known) }
            return
        }
        val signed = flow.join(team, deviceName)
        addTeam(SavedSession(signed.server, signed.token, signed.device.name, signed.device.id), team.name)
    }

    /** Signs in and opens the feed. Throws [SignInException] with what went wrong. */
    @Throws(SignInException::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun signIn(server: String, password: String, deviceName: String) {
        val signed = if (platform.homeServer != null) SignIn.signInWithCookie(server, password, deviceName, platform.httpEngine())
        else SignIn.signIn(server, password, deviceName, platform.httpEngine())
        addTeam(SavedSession(signed.server, signed.token, signed.device.name, signed.device.id))
    }

    /** A person's sign-in on a team server (migration 055): their email and password. */
    suspend fun signInWithEmail(server: String, email: String, password: String, deviceName: String) {
        val signed = if (platform.homeServer != null) SignIn.signInWithEmailCookie(server, email, password, deviceName, platform.httpEngine())
        else SignIn.signInWithEmail(server, email, password, deviceName, platform.httpEngine())
        addTeam(SavedSession(signed.server, signed.token, signed.device.name, signed.device.id))
    }

    /** Joins a team with an invite's link or code, as a new person, and opens the feed. */
    suspend fun acceptInvite(server: String, invite: String, name: String, email: String, password: String, deviceName: String) {
        val signed = SignIn.acceptInvite(server, invite, name, email, password, deviceName, platform.httpEngine(), cookie = platform.homeServer != null)
        addTeam(SavedSession(signed.server, signed.token, signed.device.name, signed.device.id))
    }

    /**
     * A new sign-in: added to the teams and opened. Signed in again to a team already here, it
     * takes that team's place, and the device it replaces is ended on the dashboard.
     */
    private suspend fun addTeam(saved: SavedSession, name: String? = null) = changing.withLock {
        closeCurrent()
        val (team, replaced) = teamList.add(saved, name)
        if (replaced != null && replaced.token != SignIn.CookieSession && replaced.token != saved.token) endDevice(replaced)
        openTeam(team)
    }

    /** Signed in to a team: shows sign-in again to add another, with the open team still running behind it. */
    fun startAddingTeam() {
        if ((mutableScreen.value as? AppScreen.Signed)?.demo == true) return
        hostedLogin?.startOver()
        mutableScreen.value = AppScreen.SigningIn(adding = true)
    }

    /** "Back" from adding a team: the open team's feed again. */
    fun cancelAddingTeam() {
        val session = current
        hostedLogin?.startOver()
        mutableScreen.value = if (session != null) AppScreen.Signed(session) else AppScreen.SigningIn()
    }

    /** Opens the team [key]. A web team in another tab is opened by the screen, not here. */
    fun switchTeam(key: String) {
        if (key == activeKey && current != null) {
            current?.let { mutableScreen.value = AppScreen.Signed(it) }
            return
        }
        val team = teamList.load().teams.firstOrNull { it.key == key } ?: return
        changeTeams { if (activeKey != team.key || current == null) openTeam(team) }
    }

    /** Opens the team at [server], when this device is signed in to it. Whether it was. */
    fun switchTeamAt(server: String): Boolean {
        val team = teamList.find(server) ?: return false
        changeTeams { if (team.key != activeKey) openTeam(team) }
        return true
    }

    /** Whether the open team is the one at [server]. */
    fun isOpen(server: String): Boolean = teamList.load().activeTeam?.let { TeamList.sameServer(it.session.server, server) } == true

    /** Whether this device is signed in to the team at [server]. */
    fun knowsTeam(server: String): Boolean = teamList.find(server) != null

    /**
     * The web app: remembers [address], a team opened in another tab, so the switcher offers it again.
     * Nothing about signing in is kept for it; that tab has its own cookie.
     */
    fun rememberTeamTab(address: String) {
        val server = runCatching { SignIn.normalise(address) }.getOrNull() ?: return
        if (teamList.find(server) != null) return
        teamList.addLink(server, TeamList.hostOf(server))
        publishTeams()
    }

    /** The demo's dashboard while one is open; thrown away when it is left. */
    private var demoServer: DemoServer? = null

    /**
     * Opens the app on built-in sample data, with no dashboard and no network: "Try a demo" on
     * the sign-in screen, so anyone can look round before they have a server. Nothing is saved,
     * so the next launch is back at sign-in, and push, alerts and the watch never hear of it.
     */
    fun enterDemo() {
        hostedLogin?.startOver()
        val server = DemoServer()
        demoServer = server
        val demo = AppPlatform(
            sessions = MemorySessionStore(),
            readMarks = MemoryTextStore(),
            outbox = MemoryOutboxStorage(),
            httpEngine = { server.engine() },
            deviceName = platform.deviceName,
            imageCacheDir = null,
            settings = platform.settings,
            filePicker = platform.filePicker,
            voiceInput = platform.voiceInput,
            foreground = platform.foreground,
            sounds = platform.sounds,
            systemSounds = platform.systemSounds,
        )
        val session = DashboardSession(SavedSession(DemoAddress, "demo", platform.deviceName), demo, scope)
        session.start()
        // A project rather than "All projects", so there is somewhere to write straight away.
        session.handle(FeedEvent.SelectProject(DemoStartProject))
        mutableScreen.value = AppScreen.Signed(session, demo = true)
    }

    /** Leaves the demo for sign-in, forgetting everything done in it. */
    fun leaveDemo() {
        (mutableScreen.value as? AppScreen.Signed)?.session?.stop()
        demoServer?.close()
        demoServer = null
        mutableScreen.value = AppScreen.SigningIn()
    }

    /**
     * Signs out of the open team: tells its dashboard (unless [notice] says the dashboard ended it
     * already), forgets its key and files, and opens the most recently used team left, if any.
     */
    fun signOut(notice: String? = null) {
        if ((mutableScreen.value as? AppScreen.Signed)?.demo == true) return leaveDemo()
        val key = activeKey ?: teamList.load().activeTeam?.key
        if (key == null) {
            mutableScreen.value = AppScreen.SigningIn(notice)
            return
        }
        changeTeams { forgetTeam(key, endDevice = notice == null, notice = notice) }
    }

    /** Signs out of the team [key], open or not, as [signOut] does. */
    fun signOutOf(key: String) {
        if (key.startsWith(LinkPrefix)) {
            teamList.removeLink(key.removePrefix(LinkPrefix))
            publishTeams()
            return
        }
        changeTeams { forgetTeam(key, endDevice = true) }
    }

    private suspend fun forgetTeam(key: String, endDevice: Boolean, notice: String? = null) {
        val team = teamList.load().teams.firstOrNull { it.key == key } ?: return
        val wasOpen = key == activeKey || (activeKey == null && teamList.load().active == key)
        // Forgotten first: the key is gone from this device before anything else happens.
        teamList.remove(key)
        // The owner chose to sign out: tell the dashboard, so this device's key stops working and its
        // alerts stop. Nothing waits on the answer.
        if (endDevice) endDevice(team.session)
        if (wasOpen) {
            watcher?.cancel()
            watcher = null
            val closing = current
            current = null
            activeKey = null
            closing?.stopAndSettle()
            // What waited to send was that team's.
            runCatching { clearOutbox(platform.outbox) }
        }
        runCatching {
            val files = platform.teamFiles(key)
            clearOutbox(files.outbox)
            files.clear()
        }
        val left = teamList.load().teams
        publishTeams()
        when {
            !wasOpen -> Unit
            left.isNotEmpty() -> openTeam(left.maxBy { it.lastUsed })
            else -> {
                platform.clearLocalData()
                mutableScreen.value = AppScreen.SigningIn(notice)
            }
        }
    }

    /** Ends [saved]'s device on its dashboard, so its key stops working and its alerts stop. */
    private fun endDevice(saved: SavedSession) {
        scope.launch {
            val client = dev.wildware.agents.DashboardClient(saved.server, saved.token, platform.httpEngine())
            try {
                runCatching { client.signOut() }
            } finally {
                client.close()
            }
        }
    }

    private fun start(team: SavedTeam) {
        // Signed in, however it happened: a hosted "Log in" half done is over, and its account sign-in ends.
        hostedLogin?.startOver()
        val session = DashboardSession(team.session, platform, scope, teamFiles = platform.teamFiles(team.key))
        session.start()
        current = session
        mutableScreen.value = AppScreen.Signed(session)
        val key = team.key
        watcher = scope.launch {
            session.signedOut.first { it }
            if (activeKey == key) signOut(session.signOutNotice ?: "This device was signed out on the dashboard. Sign in again to carry on.")
        }
    }

    /** Tells the screens the teams changed, and watches the ones that aren't open. */
    private fun publishTeams() {
        val saved = teamList.load()
        savedTeams.value = saved
        watch.watch(saved.teams.filter { it.key != saved.active })
    }

    private companion object {
        const val LinkPrefix = "link:"
    }
}
