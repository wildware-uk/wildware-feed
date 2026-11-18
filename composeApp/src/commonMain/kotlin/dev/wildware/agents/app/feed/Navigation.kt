package dev.wildware.agents.app.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A request to show one project, and maybe one card in it. [project] null means every project.
 * [serial] tells two taps on the same notification apart, so the second one opens it again.
 */
data class OpenCard(val project: String?, val cardId: String?, val serial: Long = 0) {
    companion object {
        /**
         * Reads a dashboard page path the way notifications, widgets and search carry it:
         * `/projects/garden-game?focus=abc` is card `abc` in Garden Game; `/` is every project.
         */
        fun fromPath(path: String): OpenCard {
            val route = path.substringBefore('#').substringBefore('?')
            val query = path.substringBefore('#').substringAfter('?', "")
            val parts = route.split('/').filter { it.isNotEmpty() }
            val project = if (parts.size >= 2 && parts[0] == "projects") parts[1] else null
            val focus = query.split('&')
                .map { it.split('=', limit = 2) }
                .firstOrNull { it.size == 2 && it[0] == "focus" }
                ?.get(1)
                ?.takeIf { it.isNotEmpty() }
            return OpenCard(project, focus)
        }
    }
}

/**
 * How things outside the feed (a tapped notification, a widget, a search result, Siri) ask it to
 * show a card. A request made before the feed is on screen, such as the one that launched the app,
 * waits until it is, then is used once.
 */
class AppNavigator {
    private val pending = MutableStateFlow<OpenCard?>(null)
    private var serial = 0L

    val requests: StateFlow<OpenCard?> = pending.asStateFlow()

    fun open(project: String?, cardId: String?) {
        pending.value = OpenCard(project, cardId, ++serial)
    }

    /** Opens a dashboard page path, such as `/projects/garden-game?focus=abc`. */
    fun openPath(path: String) {
        pending.value = OpenCard.fromPath(path).copy(serial = ++serial)
    }

    /** Marks [request] as handled, unless a newer one has already replaced it. */
    internal fun handled(request: OpenCard) {
        pending.compareAndSet(request, null)
    }
}

/** How long to wait for a card to load before leaving the project open without it. */
private const val CardWaitMillis = 10_000L

/** Follows the navigator's requests on this feed: picks the project, then opens the card's thread. */
@Composable
internal fun FollowNavigation(ui: FeedUi, navigator: AppNavigator?) {
    if (navigator == null) return
    LaunchedEffect(ui, navigator) {
        navigator.requests.filterNotNull().collect { open ->
            navigator.handled(open)
            ui.menu = null
            ui.viewer = null
            ui.drawer.snap(false)
            if (open.project != ui.state.current.slug) ui.emit(FeedEvent.SelectProject(open.project))
            val id = open.cardId ?: return@collect
            val item = withTimeoutOrNull(CardWaitMillis) { snapshotFlow { ui.state.item(id) }.filterNotNull().first() }
            if (item != null) ui.reveal(id)
            if (item is Threaded) {
                ui.openThread(id, phone = ui.isPhone)
                ui.emit(FeedEvent.OpenThread(id))
            }
        }
    }
}
