package dev.wildware.agents.app

import androidx.compose.ui.graphics.toArgb
import dev.wildware.agents.app.design.colorsFor
import dev.wildware.agents.app.design.systemDarkPreference
import dev.wildware.agents.app.design.resolveDark
import dev.wildware.agents.app.desktop.WindowsTitleBar
import kotlinx.coroutines.flow.combine
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.painter.BitmapPainter
import dev.wildware.agents.app.design.appIconBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.wildware.agents.app.desktop.DesktopOs
import dev.wildware.agents.app.desktop.DesktopSettingsStore
import dev.wildware.agents.app.desktop.DesktopShell
import dev.wildware.agents.app.desktop.OpenAt
import dev.wildware.agents.app.desktop.NotifySendNotifier
import dev.wildware.agents.app.desktop.StartAtLogin
import dev.wildware.agents.app.desktop.TrayNotifier
import dev.wildware.agents.app.engine.AppModel
import dev.wildware.agents.app.engine.AppScreen
import dev.wildware.agents.app.desktop.DesktopSettings
import dev.wildware.agents.app.feed.AppCommand
import dev.wildware.agents.app.local.ThisComputer
import dev.wildware.agents.app.local.ThisComputerLayer
import dev.wildware.agents.app.update.AppVersion
import dev.wildware.agents.app.update.Installation
import dev.wildware.agents.app.update.Release
import dev.wildware.agents.app.update.ReleaseSource
import dev.wildware.agents.app.update.SemVer
import dev.wildware.agents.app.update.GitHubReleaseSource
import dev.wildware.agents.app.update.SystemInstaller
import dev.wildware.agents.app.update.UpdateChecker
import dev.wildware.agents.app.update.UpdateController
import dev.wildware.agents.app.update.UpdateLayer
import dev.wildware.agents.app.update.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.system.exitProcess
import dev.wildware.agents.app.feed.AppNavigator
import dev.wildware.agents.app.session.desktopConfigDir
import dev.wildware.agents.app.session.desktopPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.desktop.AppReopenedListener
import java.awt.Frame
import java.io.File

/** Where the window was and how big, so it opens the same way next time. */
@Serializable
private data class WindowPrefs(val x: Float? = null, val y: Float? = null, val width: Float = 1440f, val height: Float = 900f, val maximized: Boolean = false)

private val prefsJson = Json { ignoreUnknownKeys = true }

private fun loadPrefs(file: File): WindowPrefs =
    runCatching { prefsJson.decodeFromString(WindowPrefs.serializer(), file.readText()) }.getOrDefault(WindowPrefs())

private fun savePrefs(file: File, state: WindowState) {
    val position = state.position
    val prefs = WindowPrefs(
        x = position.takeIf { it.isSpecified }?.x?.value,
        y = position.takeIf { it.isSpecified }?.y?.value,
        width = state.size.width.value,
        height = state.size.height.value,
        maximized = state.placement == WindowPlacement.Maximized,
    )
    runCatching { file.writeText(prefsJson.encodeToString(WindowPrefs.serializer(), prefs)) }
}

/**
 * The desktop app. The engine and the tray live outside the window, so closing the window (with
 * "Keep running" on) leaves the app in the tray, still counting questions and notifying.
 * Started at login it passes [StartAtLogin.BackgroundFlag] and opens in the tray only.
 */
@OptIn(FlowPreview::class)
fun main(args: Array<String>) {
    val dir = desktopConfigDir()
    val prefsFile = File(dir, "window.json")
    val prefs = loadPrefs(prefsFile)
    val platform = desktopPlatform(dir)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val model = AppModel(platform, scope)
    // A clicked notification asks the feed to open its card, the same way a tapped iPhone push does.
    val navigator = AppNavigator()
    // Settings for the server this app runs ("This computer"): phone access and Claude Code.
    val thisComputer = platform.localServer?.let { ThisComputer(it, scope) }

    var visible by mutableStateOf(true)
    var window: ComposeWindow? = null
    var quitting: (() -> Unit)? = null
    var exitWhenClosed = false
    // Public GitHub releases: no sign-in needed, so checking starts with the app.
    val releases = MutableStateFlow<ReleaseSource?>(GitHubReleaseSource(platform.httpEngine()))

    fun openWindow(card: OpenAt?) {
        if (card != null) navigator.open(card.projectSlug, card.cardId)
        EventQueue.invokeLater {
            visible = true
            window?.let {
                if (it.extendedState and Frame.ICONIFIED != 0) it.extendedState = it.extendedState and Frame.ICONIFIED.inv()
                it.toFront()
                it.requestFocus()
            }
        }
    }

    val shell = DesktopShell(
        model = model,
        scope = scope,
        settingsStore = DesktopSettingsStore(File(dir, "desktop.json")),
        startAtLogin = StartAtLogin(),
        openWindow = ::openWindow,
        windowFocused = { window?.let { it.isVisible && it.isActive } == true },
        quit = { EventQueue.invokeLater { quitting?.invoke() } },
        notifierFor = { tray ->
            val viaTray = TrayNotifier(tray)
            if (DesktopOs.current() == DesktopOs.Linux) NotifySendNotifier(NotifySendNotifier.iconFile(dir), viaTray) else viaTray
        },
        updatesFor = { settings ->
            updateController(
                settings = settings,
                // The demo is for a look round with nothing sent anywhere, update checks included.
                inDemo = model.screen.map { (it as? AppScreen.Signed)?.demo == true }
                    .stateIn(scope, SharingStarted.Eagerly, false),
                releases = releases,
                workDir = File(dir, "updates"),
                scope = scope,
                beforeQuit = { (model.screen.value as? AppScreen.Signed)?.session?.flushOutbox() },
                quit = {
                    // The stream closes properly, rather than being cut off when the process ends.
                    (model.screen.value as? AppScreen.Signed)?.session?.stop()
                    exitWhenClosed = true
                    EventQueue.invokeLater { quitting?.invoke() }
                },
            )
        },
        thisComputer = { thisComputer?.open() },
    )
    shell.start()
    // Mac: a click on the Dock icon brings the hidden window back, and Cmd-Q quits properly.
    runCatching {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) desktop.addAppEventListener(AppReopenedListener { openWindow(null) })
        if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
            desktop.setQuitHandler { _, response ->
                EventQueue.invokeLater { quitting?.invoke() ?: response.performQuit() }
            }
        }
    }
    // Started at login: stay in the tray, unless there is no tray to stay in.
    visible = StartAtLogin.BackgroundFlag !in args || !shell.tray.supported

    application {
        val state = rememberWindowState(
            placement = if (prefs.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = if (prefs.x != null && prefs.y != null) WindowPosition(prefs.x.dp, prefs.y.dp) else WindowPosition.PlatformDefault,
            size = DpSize(prefs.width.dp, prefs.height.dp),
        )
        quitting = {
            savePrefs(prefsFile, state)
            shell.stop()
            exitApplication()
        }
        LaunchedEffect(state) {
            snapshotFlow { Triple(state.size, state.position, state.placement) }
                .debounce(500)
                .collect { savePrefs(prefsFile, state) }
        }
        Window(
            onCloseRequest = {
                savePrefs(prefsFile, state)
                if (shell.keepRunningOnClose) visible = false else quitting?.invoke()
            },
            visible = visible,
            title = ProductName,
            icon = appIconBitmap()?.let(::BitmapPainter),
            state = state,
        ) {
            window = this.window
            this.window.minimumSize = java.awt.Dimension(380, 560)
            // Windows draws a white title bar unless asked: match the app's light or dark, and its colour.
            val themeChoice by model.theme.collectAsState()
            val dark = resolveDark(themeChoice, systemDarkPreference())
            val frame = this.window
            LaunchedEffect(dark) { WindowsTitleBar.apply(frame, dark, colorsFor(dark).background.toArgb()) }
            val updates = shell.updates
            val available = updates?.available?.collectAsState()?.value
            val screen by model.screen.collectAsState()
            val local = thisComputer?.takeIf { (screen as? AppScreen.Signed)?.session?.saved?.local == true }
            AgentsApp(
                platform,
                model,
                navigator = navigator,
                commands = updates?.let { updateCommands(it, available) }.orEmpty() + local?.let(::thisComputerCommands).orEmpty(),
                overlay = {
                    updates?.let { UpdateLayer(it, showFolder = ::showFolder) }
                    local?.let { ThisComputerLayer(it) }
                },
                openSettings = local?.let { it::open },
            )
        }
    }
    // The update helper waits for this process to end; make sure nothing keeps it alive.
    if (exitWhenClosed) exitProcess(0)
}

/** The update checker and installer, or null when this build's version can't be read. */
private fun updateController(
    settings: StateFlow<DesktopSettings>,
    inDemo: StateFlow<Boolean>,
    releases: StateFlow<ReleaseSource?>,
    workDir: File,
    scope: CoroutineScope,
    beforeQuit: suspend () -> Unit,
    quit: () -> Unit,
): UpdateController? {
    val current = SemVer.parse(AppVersion) ?: return null
    val automatic = combine(settings, inDemo) { s, demo -> s.checkForUpdates && !demo }
        .stateIn(scope, SharingStarted.Eagerly, settings.value.checkForUpdates && !inDemo.value)
    val updater = Updater(Installation.current(), SystemInstaller(), workDir, beforeQuit, quit)
    // Clear out the last update's download once the app has settled after it.
    scope.launch(Dispatchers.IO) {
        delay(60_000)
        if (!updater.busy) runCatching { workDir.deleteRecursively() }
    }
    return UpdateController(AppVersion, UpdateChecker(current, releases, automatic), updater, releases, scope)
}

/** "Check for updates", and "Update to …" while there is one, for the command bar. */
private fun updateCommands(updates: UpdateController, available: Release?): List<AppCommand> = listOfNotNull(
    available?.let { AppCommand("Update to ${it.version}", "Install it and restart", listOf("update", "upgrade", "install")) { updates.showOffer() } },
    AppCommand("Check for updates", "You have $ProductName ${updates.current}", listOf("update", "upgrade", "version")) { updates.checkByHand() },
)

/** "This computer" and "Connect Claude Code", while signed in to the server this app runs. */
private fun thisComputerCommands(settings: ThisComputer): List<AppCommand> = listOf(
    AppCommand("This computer", "Let your phone connect, or connect Claude Code", listOf("settings", "phone", "server", "local")) { settings.open() },
    AppCommand("Connect Claude Code", "Give an agent on this computer its own key", listOf("agent", "token", "claude", "plugin")) {
        settings.open()
        settings.connectAgent()
    },
)

/** Opens [path] in Finder, Explorer or the file manager. */
private fun showFolder(path: String) {
    val folder = File(path)
    runCatching { Desktop.getDesktop().open(folder) }.onFailure {
        if (DesktopOs.current() == DesktopOs.Linux) runCatching { ProcessBuilder("xdg-open", folder.absolutePath).start() }
    }
}
