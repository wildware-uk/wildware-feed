package dev.wildware.agents.app.engine

import dev.wildware.agents.HostCheck
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

/** Where the demo's sessions think they are. Nothing is ever sent there: the demo answers itself. */
internal const val DemoAddress = "https://demo.example"

/** The project the demo opens on. */
internal const val DemoStartProject = "garden-game"

/** Which screen the app is on. */
sealed interface AppScreen {
    /** Signed out. [notice] says why, when the app signed itself out. */
    data class SigningIn(val notice: String? = null) : AppScreen

    /** The feed. [demo] when it runs on the built-in sample data rather than a dashboard. */
    data class Signed(val session: DashboardSession, val demo: Boolean = false) : AppScreen
}

/**
 * The app's top: signed in or not. A saved key opens the feed straight away; signing in saves
 * one; signing out (or the dashboard refusing the key) forgets it and everything kept for it.
 */
class AppModel(private val platform: AppPlatform, private val scope: CoroutineScope) {
    private val mutableScreen = MutableStateFlow<AppScreen>(AppScreen.SigningIn())
    val screen: StateFlow<AppScreen> = mutableScreen.asStateFlow()

    val deviceName: String get() = platform.deviceName

    private var watcher: Job? = null

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

    init {
        platform.sessions.load()?.let { saved ->
            val host = platform.localServer
            if (saved.local && host != null) scope.launch {
                runCatching { host.ensureRunning() }
                open(saved)
            } else {
                open(saved)
            }
        }
    }

    /** Whether this app can run a server itself ("This computer"). */
    val canHostLocally: Boolean get() = platform.localServer != null

    /** Starts the server on this computer and signs in to it, with no password. */
    suspend fun signInHere(deviceName: String) {
        val host = platform.localServer ?: error("this app can't run a server")
        val signed = host.signInHere(deviceName)
        val saved = SavedSession(signed.server, signed.token, signed.deviceName, signed.deviceId, local = true)
        platform.sessions.save(saved)
        open(saved)
    }

    /** "Connect to a host", step 1: whether [address] is a Wildware Feed server, and how it signs in. */
    suspend fun checkHost(address: String): HostCheck = HostCheck.check(address, platform.httpEngine())

    /** Signs in and opens the feed. Throws [SignInException] with what went wrong. */
    @Throws(SignInException::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun signIn(server: String, password: String, deviceName: String) {
        val signed = if (platform.homeServer != null) SignIn.signInWithCookie(server, password, deviceName, platform.httpEngine())
        else SignIn.signIn(server, password, deviceName, platform.httpEngine())
        val saved = SavedSession(signed.server, signed.token, signed.device.name, signed.device.id)
        platform.sessions.save(saved)
        open(saved)
    }

    /** The demo's dashboard while one is open; thrown away when it is left. */
    private var demoServer: DemoServer? = null

    /**
     * Opens the app on built-in sample data, with no dashboard and no network: "Try a demo" on
     * the sign-in screen, so anyone can look round before they have a server. Nothing is saved,
     * so the next launch is back at sign-in, and push, alerts and the watch never hear of it.
     */
    fun enterDemo() {
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

    fun signOut(notice: String? = null) {
        if ((mutableScreen.value as? AppScreen.Signed)?.demo == true) return leaveDemo()
        watcher?.cancel()
        (mutableScreen.value as? AppScreen.Signed)?.session?.stop()
        platform.sessions.clear()
        platform.clearLocalData()
        mutableScreen.value = AppScreen.SigningIn(notice)
    }

    private fun open(saved: SavedSession) {
        val session = DashboardSession(saved, platform, scope)
        session.start()
        mutableScreen.value = AppScreen.Signed(session)
        watcher = scope.launch {
            session.signedOut.first { it }
            signOut("This device was signed out on the dashboard. Sign in again to carry on.")
        }
    }
}
