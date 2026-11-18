package dev.wildware.agents.app.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.wildware.agents.app.design.AgentTheme
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

    private val pendingShare = MutableStateFlow<SharedDraft?>(null)

    /** Something shared into the app, waiting for the feed. */
    val shares: StateFlow<SharedDraft?> = pendingShare.asStateFlow()

    /**
     * Puts [text] and [files] shared from another app (Android's share sheet) into a project's
     * composer, to check and send. With every project showing, the projects open to pick one.
     */
    fun share(text: String, files: List<PendingFile>) {
        pendingShare.value = SharedDraft(text, files, ++serial)
    }

    internal fun handled(draft: SharedDraft) {
        pendingShare.compareAndSet(draft, null)
    }
}

/** Words and files shared in from another app. [serial] tells two shares of the same thing apart. */
class SharedDraft(val text: String, val files: List<PendingFile>, val serial: Long)

/** How long to wait for a card to load before leaving the project open without it. */
private const val CardWaitMillis = 10_000L

/** Follows the navigator's requests on this feed: picks the project, then opens the card's thread. */
@Composable
internal fun FollowNavigation(ui: FeedUi, navigator: AppNavigator?) {
    if (navigator == null) return
    val motion = AgentTheme.motion
    LaunchedEffect(ui, navigator) {
        navigator.shares.filterNotNull().collect { draft ->
            navigator.handled(draft)
            ui.menu = null
            ui.viewer = null
            ui.commandOpen = false
            ui.phoneThreadOpen = false
            ui.incoming = draft
            // Every project is showing, so there is no composer yet: offer the projects to pick one.
            if (ui.state.current.slug == null && ui.isPhone) ui.drawer.settle(true, motion.normal())
        }
    }
    LaunchedEffect(ui, navigator) {
        navigator.requests.filterNotNull().collect { open ->
            navigator.handled(open)
            ui.goTo(open)
        }
    }
}

/** Shows [open]'s project, then its card with the thread open, waiting a while for the card to load. */
internal suspend fun FeedUi.goTo(open: OpenCard) {
    menu = null
    viewer = null
    bellOpen = false
    drawer.snap(false)
    if (open.project != state.current.slug) emit(FeedEvent.SelectProject(open.project))
    val id = open.cardId ?: return
    val item = withTimeoutOrNull(CardWaitMillis) { snapshotFlow { state.item(id) }.filterNotNull().first() }
    if (item != null) reveal(id)
    if (item is Threaded) {
        openThread(id, phone = isPhone)
        emit(FeedEvent.OpenThread(id))
    }
}
