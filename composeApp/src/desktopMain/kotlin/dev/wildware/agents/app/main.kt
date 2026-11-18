package dev.wildware.agents.app

import androidx.compose.runtime.LaunchedEffect
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

    var visible by mutableStateOf(true)
    var window: ComposeWindow? = null
    var quitting: (() -> Unit)? = null

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
            title = "Agents",
            icon = appIconBitmap()?.let(::BitmapPainter),
            state = state,
        ) {
            window = this.window
            this.window.minimumSize = java.awt.Dimension(380, 560)
            AgentsApp(platform, model, navigator = navigator)
        }
    }
}
