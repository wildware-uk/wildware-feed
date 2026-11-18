package dev.wildware.agents.app.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import dev.wildware.agents.app.design.AgentTheme
import kotlinx.coroutines.launch

/**
 * The system's own back gesture, where the platform has one: Android's predictive back. [onProgress]
 * follows the finger from 0 to 1; then either [onBack] (let go) or [onCancel] (slid back). Desktop and
 * iPhone have no system back, so there it does nothing and the app's own edge swipe and Escape stay.
 */
@Composable
internal expect fun SystemBackHandler(enabled: Boolean, onProgress: (Float) -> Unit, onCancel: () -> Unit, onBack: () -> Unit)

/**
 * What system back does on the feed, topmost thing first: closes the picture viewer, search or a
 * menu; slides the projects drawer shut or the phone thread away under the finger, the same
 * motion as the app's own swipes. With nothing open it is left to the system (leave the app).
 */
@Composable
internal fun FollowSystemBack(ui: FeedUi) {
    val motion = AgentTheme.motion
    val scope = rememberCoroutineScope()
    val overlay = ui.viewer != null || ui.commandOpen || ui.menu != null
    val drawer = !overlay && ui.drawer.value > 0f
    val thread = !overlay && !drawer && ui.phoneThread != null
    SystemBackHandler(
        enabled = overlay || drawer || thread,
        onProgress = { p ->
            when {
                drawer -> ui.drawer.value = 1f - p
                thread -> ui.threadBack.value = p
            }
        },
        onCancel = {
            when {
                drawer -> scope.launch { ui.drawer.settle(true, motion.normal()) }
                thread -> scope.launch { ui.threadBack.settle(false, motion.normal()) }
            }
        },
        onBack = {
            when {
                ui.viewer != null -> ui.viewer = null
                ui.commandOpen -> ui.commandOpen = false
                ui.menu != null -> ui.menu = null
                drawer -> scope.launch { ui.drawer.settle(false, motion.normal()) }
                thread -> scope.launch {
                    ui.threadBack.settle(true, motion.normal())
                    ui.phoneThreadOpen = false
                    ui.threadBack.snap(false)
                }
            }
        },
    )
}
