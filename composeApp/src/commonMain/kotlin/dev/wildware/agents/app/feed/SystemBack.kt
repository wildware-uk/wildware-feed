package dev.wildware.agents.app.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import dev.wildware.agents.app.design.AgentTheme
import kotlinx.coroutines.launch

/** One moment of the system's back swipe: how far the platform says it has gone (0 to 1), and where the finger is. */
class BackSwipe(val progress: Float, val touchX: Float)

/**
 * The platform's own back gesture. Android has predictive back. On the iPhone it is UIKit's
 * screen-edge pan, which Compose installs while a handler is enabled. Desktop has none.
 */
interface SystemBack {
    /**
     * The platform's gesture owns the left edge of the screen, so every left-edge swipe goes to it,
     * the feed's "open the drawer" included, and the app's own edge drag stands aside. True on the
     * iPhone. iOS holds back a touch that starts at the very edge of the screen, so a drag measured
     * by the app began too far in to count as an edge swipe, and nothing happened.
     */
    val ownsLeftEdge: Boolean

    /** While [enabled]: [onProgress] follows the swipe, then [onBack] (let go) or [onCancel] (slid back). */
    @Composable
    fun Handler(enabled: Boolean, onProgress: (BackSwipe) -> Unit, onCancel: () -> Unit, onBack: () -> Unit)
}

/** This platform's back gesture. */
internal expect val PlatformSystemBack: SystemBack

/** The back gesture the feed listens to. Tests provide a stand-in. */
val LocalSystemBack = staticCompositionLocalOf<SystemBack> { PlatformSystemBack }

/** How much of the screen's width the phone's projects drawer covers. */
internal const val DrawerWidthFraction = 0.84f

/** What a back swipe is doing, fixed when it starts so it can't change meaning halfway through. */
private enum class BackMode { Overlay, CloseDrawer, OpenDrawer, Thread }

/**
 * What system back does on the feed, topmost thing first. It closes the picture viewer, search or
 * a menu. It slides the projects drawer shut, or the phone thread away, under the finger, the same
 * motion as the app's own swipes. Where the platform owns the left edge (the iPhone), a swipe at
 * the feed itself opens the drawer under the finger. Otherwise, with nothing open, back is left to
 * the system (Android leaves the app).
 */
@Composable
internal fun FollowSystemBack(ui: FeedUi, widthPx: Float) {
    val system = LocalSystemBack.current
    val motion = AgentTheme.motion
    val scope = rememberCoroutineScope()
    val gesture = remember { arrayOfNulls<BackMode>(1) }

    fun modeNow(): BackMode? = when {
        ui.viewer != null || ui.commandOpen || ui.menu != null -> BackMode.Overlay
        ui.drawer.value > 0f -> BackMode.CloseDrawer
        ui.phoneThread != null -> BackMode.Thread
        system.ownsLeftEdge && ui.isPhone -> BackMode.OpenDrawer
        else -> null
    }

    // The finger's place where the platform reports it (UIKit); otherwise the platform's own progress.
    fun BackSwipe.across(width: Float): Float =
        (if (system.ownsLeftEdge) touchX / width.coerceAtLeast(1f) else progress).coerceIn(0f, 1f)

    fun finish(mode: BackMode?, back: Boolean) {
        gesture[0] = null
        when (mode) {
            BackMode.Overlay -> if (back) when {
                ui.viewer != null -> ui.viewer = null
                ui.commandOpen -> ui.commandOpen = false
                else -> ui.menu = null
            }
            BackMode.CloseDrawer -> scope.launch { ui.drawer.settle(!back, motion.normal()) }
            BackMode.OpenDrawer -> scope.launch { ui.drawer.settle(back, motion.normal()) }
            BackMode.Thread -> scope.launch {
                ui.threadBack.settle(back, motion.normal())
                if (back) {
                    ui.phoneThreadOpen = false
                    ui.threadBack.snap(false)
                }
            }
            null -> Unit
        }
    }

    system.Handler(
        enabled = gesture[0] != null || modeNow() != null,
        onProgress = { swipe ->
            val mode = gesture[0] ?: modeNow().also { gesture[0] = it }
            when (mode) {
                BackMode.CloseDrawer -> ui.drawer.value = 1f - swipe.progress
                BackMode.OpenDrawer -> ui.drawer.value = swipe.across(widthPx * DrawerWidthFraction)
                BackMode.Thread -> ui.threadBack.value = swipe.across(widthPx)
                BackMode.Overlay, null -> Unit
            }
        },
        onCancel = { finish(gesture[0], back = false) },
        onBack = { finish(gesture[0] ?: modeNow(), back = true) },
    )
}
