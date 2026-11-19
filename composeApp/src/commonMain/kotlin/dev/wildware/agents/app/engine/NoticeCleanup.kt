package dev.wildware.agents.app.engine

import dev.wildware.agents.Dashboard
import dev.wildware.agents.MaxClearTags
import dev.wildware.agents.StreamStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The notifications this device put on the system's screen (the iPhone's Notification Center, the
 * Android shade, the desktop's own), each known by its tag: `request-<id>`, `pairing-<id>`,
 * `update-<id>` or `message-<id>`, the same tag the dashboard's push carried.
 */
interface SystemNotices {
    /** The tags of those still showing. */
    suspend fun shown(): Set<String>

    /** Takes these down. A tag not showing is ignored. */
    fun remove(tags: Set<String>)
}

/**
 * Takes notifications down once the owner has dealt with them on any device: read, answered,
 * allowed or denied, withdrawn or deleted.
 *
 * Two ways in. The live connection's `notifications.cleared` events name them as it happens. And
 * [sync] asks the dashboard about everything still showing, which catches whatever happened while
 * the app was closed or offline: it runs whenever the connection comes up, and on [foreground].
 */
class NoticeCleanup(
    private val notices: SystemNotices,
    private val handled: suspend (List<String>) -> List<String>,
    private val cleared: Flow<List<String>>,
    private val live: Flow<Boolean>,
    private val foreground: Flow<Unit> = emptyFlow(),
) {
    constructor(dashboard: Dashboard, notices: SystemNotices, foreground: Flow<Unit> = emptyFlow()) : this(
        notices,
        dashboard.client::handledNotifications,
        dashboard.clearedNotices,
        dashboard.status.map { it == StreamStatus.Live },
        foreground,
    )

    /** Follows the dashboard until cancelled. */
    suspend fun run() = coroutineScope {
        launch { cleared.collect { tags -> notices.remove(tags.filterTo(HashSet(), ::isDashboardTag)) } }
        launch { live.filter { it }.collect { sync() } }
        launch { foreground.collect { sync() } }
    }

    /** Asks the dashboard which of the notifications still showing are done with, and takes those down. */
    suspend fun sync() {
        val shown = notices.shown().filter(::isDashboardTag)
        if (shown.isEmpty()) return
        val done = try {
            shown.chunked(MaxClearTags).flatMap { handled(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Offline, or the dashboard is too old to say: the next sync tries again.
            return
        }
        if (done.isNotEmpty()) notices.remove(done.toSet())
    }

    companion object {
        private val DashboardTag = Regex("^(request|pairing|update|message)-.+$")

        /** The app's own notices, such as `update-check` for its updater, are never the dashboard's to clear. */
        private val OwnTags = setOf("update-check")

        fun isDashboardTag(tag: String): Boolean = tag !in OwnTags && DashboardTag.matches(tag)
    }
}
