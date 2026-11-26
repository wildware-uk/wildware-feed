package dev.wildware.agents.app.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import dev.wildware.agents.NavHistory

/**
 * Somewhere the owner can be, for Back and Forward: a channel ([project] by slug, null for every
 * channel) and maybe a card's thread open in it.
 */
data class Place(val project: String?, val thread: String?) {
    /** As a dashboard page path, the way links carry it: `/projects/launch?focus=abc`. */
    val path: String get() = (if (project == null) "/" else "/projects/$project") + (thread?.let { "?focus=$it" } ?: "")

    companion object {
        fun of(path: String): Place = OpenCard.fromPath(path).let { Place(it.project, it.cardId) }
    }
}

/** A channel the owner asked for: [slug] null is every channel. */
internal data class AskedProject(val slug: String?)

/**
 * A browser tab's own history, so its Back and Forward (the buttons, the mouse's side buttons, the
 * keys) walk the feed's places too. The feed tells it each move it makes itself.
 */
interface BrowserHistory {
    fun push(place: Place)
    fun replace(place: Place)
    fun back()
    fun forward()
}

/** Where the owner is now: the channel showing, or the one just asked for, and its open thread. */
val FeedUi.place: Place
    get() {
        val asked = askedProject
        val project = if (asked != null) asked.slug else state.current.slug
        // A board card's modal isn't a place: closing it leaves the owner where they were.
        val open = threadId?.takeIf { (!isPhone || phoneThreadOpen) && it != boardCard }
        return Place(project, open)
    }

/** Back one place: in a browser, through its history; elsewhere through the feed's own. */
fun FeedUi.goBack() {
    // A board card's modal closes first, the board under it just as it was.
    if (boardCard != null) return closeBoardCard()
    browser?.let { it.back(); return }
    history.back()?.let(::show)
    noteHistory()
}

/** Forward one place, as [goBack] goes back. */
fun FeedUi.goForward() {
    browser?.let { it.forward(); return }
    history.forward()?.let(::show)
    noteHistory()
}

/** The browser went to [place] (its Back or Forward): show it, and keep the feed's history with it. */
fun FeedUi.arriveFromBrowser(place: Place) {
    history.visit(place)
    noteHistory()
    if (place != this.place) show(place)
}

/** Shows [place] as it was: its channel, and its thread open or closed. */
internal fun FeedUi.show(place: Place) {
    menu = null
    cursor = null
    closeBoardCard(refocus = false)
    if (place.project != this.place.project) emit(FeedEvent.SelectProject(place.project))
    val thread = place.thread
    if (thread == null) {
        threadId = null
        phoneThreadOpen = false
        aloneId = null
    } else if (cardFor(thread) == null && place.project == this.place.project) {
        // A thread the feed hasn't paged to (one that was open on its own) opens on its own again.
        if (aloneId != thread) openAlone(thread)
    } else {
        aloneId = null
        openThread(thread, phone = isPhone)
        reveal(thread)
    }
}

internal fun FeedUi.noteHistory() {
    canGoBack = history.canGoBack
    canGoForward = history.canGoForward
}

/**
 * Follows where the owner goes, however they get there (the sidebar, search, a link, a thread
 * opened or closed), into [FeedUi.history], and into the browser's history in a browser tab. Where
 * it opens, and anywhere it settles before it has loaded, is the start: nowhere to go Back to.
 */
@Composable
internal fun FollowHistory(ui: FeedUi) {
    LaunchedEffect(ui) {
        snapshotFlow { ui.state.current.slug }.collect { slug ->
            if (ui.askedProject?.slug == slug) ui.askedProject = null
        }
    }
    LaunchedEffect(ui) {
        // Until the feed's first read lands it is still opening. Later, a channel not read before is
        // unloaded for a moment too, but going there is a step like any other.
        var opening = true
        snapshotFlow { ui.place to ui.state.loaded }.collect { (place, loaded) ->
            // Where the feed opens (its channel, a thread it opened with) is the start, not a step.
            if (opening) {
                if (loaded) opening = false
                ui.history.replace(place)
                ui.browser?.replace(place)
            } else {
                when (ui.history.visit(place)) {
                    NavHistory.Move.Added -> ui.browser?.push(place)
                    NavHistory.Move.Back -> ui.browser?.back()
                    NavHistory.Move.Forward -> ui.browser?.forward()
                    NavHistory.Move.Stayed -> Unit
                }
            }
            ui.noteHistory()
        }
    }
}
