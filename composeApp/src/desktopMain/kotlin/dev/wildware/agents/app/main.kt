package dev.wildware.agents.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.wildware.agents.app.design.AppIcon
import dev.wildware.agents.app.session.desktopConfigDir
import dev.wildware.agents.app.session.desktopPlatform
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

@OptIn(FlowPreview::class)
fun main() {
    val dir = desktopConfigDir()
    val prefsFile = File(dir, "window.json")
    val prefs = loadPrefs(prefsFile)
    val platform = desktopPlatform(dir)
    application {
        val state = rememberWindowState(
            placement = if (prefs.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = if (prefs.x != null && prefs.y != null) WindowPosition(prefs.x.dp, prefs.y.dp) else WindowPosition.PlatformDefault,
            size = DpSize(prefs.width.dp, prefs.height.dp),
        )
        LaunchedEffect(state) {
            snapshotFlow { Triple(state.size, state.position, state.placement) }
                .debounce(500)
                .collect { savePrefs(prefsFile, state) }
        }
        Window(
            onCloseRequest = {
                savePrefs(prefsFile, state)
                exitApplication()
            },
            title = "Agents",
            icon = rememberVectorPainter(AppIcon),
            state = state,
        ) {
            window.minimumSize = java.awt.Dimension(380, 560)
            AgentsApp(platform)
        }
    }
}
