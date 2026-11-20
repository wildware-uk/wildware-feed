package dev.wildware.agents.app.feed

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

internal actual val shortcutsUseCommand: Boolean =
    window.navigator.platform.startsWith("Mac") || "Macintosh" in window.navigator.userAgent

/** The tab's own history, each place in the address's fragment: `#/projects/launch?focus=abc`. */
private object TabHistory : BrowserHistory {
    override fun push(place: Place) = window.history.pushState(null, "", "#" + place.path)
    override fun replace(place: Place) = window.history.replaceState(null, "", "#" + place.path)
    override fun back() = window.history.back()
    override fun forward() = window.history.forward()
}

/** The place the address says, or null for an address that names none. */
private fun placeInAddress(): Place? = window.location.hash.removePrefix("#").takeIf { it.startsWith("/") }?.let(Place::of)

/**
 * The browser's Back and Forward (its buttons, the mouse's side buttons, Alt+← and Alt+→ or ⌘[ and
 * ⌘]) walk the feed's places: each one the owner goes to is an entry in the tab's history. An
 * address that names a place (a reload, a copied link) opens it.
 */
@Composable
internal actual fun FollowBrowserHistory(ui: FeedUi) {
    val scope = rememberCoroutineScope()
    DisposableEffect(ui) {
        val start = placeInAddress()
        ui.browser = TabHistory
        TabHistory.replace(start ?: ui.history.current)
        if (start != null && start != ui.place) scope.launch { ui.goTo(OpenCard(start.project, start.thread)) }
        val popped: (Event) -> Unit = { ui.arriveFromBrowser(placeInAddress() ?: Place(null, null)) }
        // The keys go through the tab's history too, so the browser never does them as well.
        val keys: (Event) -> Unit = { event ->
            val e = event.unsafeCast<KeyboardEvent>()
            val back = if (shortcutsUseCommand) e.metaKey && e.key == "[" else e.altKey && !e.ctrlKey && e.key == "ArrowLeft"
            val forward = if (shortcutsUseCommand) e.metaKey && e.key == "]" else e.altKey && !e.ctrlKey && e.key == "ArrowRight"
            if (back || forward) {
                e.preventDefault()
                if (back) window.history.back() else window.history.forward()
            }
        }
        window.addEventListener("popstate", popped)
        window.addEventListener("keydown", keys, true)
        onDispose {
            window.removeEventListener("popstate", popped)
            window.removeEventListener("keydown", keys, true)
            if (ui.browser === TabHistory) ui.browser = null
        }
    }
}

/** Not seen here: the browser does them, through its own history. */
internal actual fun Modifier.mouseBackForward(ui: FeedUi): Modifier = this
