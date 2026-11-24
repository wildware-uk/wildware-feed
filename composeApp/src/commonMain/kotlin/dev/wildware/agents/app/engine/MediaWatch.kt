package dev.wildware.agents.app.engine

import dev.wildware.agents.ChannelMediaItem
import dev.wildware.agents.ChannelMediaPage
import dev.wildware.agents.DashboardException
import dev.wildware.agents.app.feed.Attachment
import dev.wildware.agents.app.feed.FileAttachment
import dev.wildware.agents.app.feed.MediaAction
import dev.wildware.agents.app.feed.MediaFilter
import dev.wildware.agents.app.feed.MediaTabItem
import dev.wildware.agents.app.feed.MediaTabState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Media tab while it is on screen: a channel's pictures, videos and files, newest first. The
 * first page is read each time the tab shows or its filter changes; the next one when the list
 * nears its end. A page that comes back for a channel or filter no longer shown is dropped.
 */
class MediaWatch internal constructor(
    /** Reads one page: the channel (a slug), the filter, and the cursor from the page before. */
    private val read: suspend (channel: String, filter: MediaFilter, cursor: String?) -> ChannelMediaPage,
    private val scope: CoroutineScope,
    /** A picture or video as the viewer shows it, and as the grid's smaller thumbnail. */
    private val tile: (dev.wildware.agents.MediaView) -> Pair<Attachment, Attachment>,
    private val file: (dev.wildware.agents.MediaView) -> FileAttachment,
    /** "5m ago", for a time in epoch milliseconds. */
    private val ago: (Long) -> String,
) {
    private val mutable = MutableStateFlow(MediaTabState())
    val state: StateFlow<MediaTabState> = mutable.asStateFlow()

    private var reading: Job? = null

    fun handle(action: MediaAction) {
        when (action) {
            is MediaAction.Show -> show(action.channel)
            is MediaAction.Filter -> if (action.filter != mutable.value.filter) {
                mutable.value = mutable.value.copy(filter = action.filter, items = emptyList(), nextCursor = null, loaded = false)
                first()
            }
            MediaAction.More -> more()
            MediaAction.Retry -> first()
        }
    }

    /** Shows [channel]'s media, read afresh, or (null) stops: the tab is off screen. */
    private fun show(channel: String?) {
        if (channel == null) {
            reading?.cancel()
            reading = null
            return
        }
        // Another channel starts from All; the same one keeps its filter and what it showed while it reads again.
        if (channel != mutable.value.channel) mutable.value = MediaTabState(channel = channel)
        first()
    }

    private fun first() {
        val now = mutable.value
        val channel = now.channel ?: return
        reading?.cancel()
        mutable.value = now.copy(loading = true, loadingMore = false, problem = null)
        reading = scope.launch { load(channel, now.filter, cursor = null) }
    }

    private fun more() {
        val now = mutable.value
        val channel = now.channel ?: return
        val cursor = now.nextCursor ?: return
        if (now.loading || now.loadingMore) return
        mutable.value = now.copy(loadingMore = true, problem = null)
        reading = scope.launch { load(channel, now.filter, cursor) }
    }

    private suspend fun load(channel: String, filter: MediaFilter, cursor: String?) {
        try {
            val page = read(channel, filter, cursor)
            val now = mutable.value
            if (now.channel != channel || now.filter != filter) return
            val items = page.items.map(::itemOf)
            val all = if (cursor == null) items else (now.items + items).distinctBy { it.id }
            mutable.value = now.copy(items = all, nextCursor = page.nextCursor, loaded = true, loading = false, loadingMore = false, problem = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Throwable) {
            val now = mutable.value
            if (now.channel == channel && now.filter == filter) {
                mutable.value = now.copy(loaded = true, loading = false, loadingMore = false, problem = problemOf(failed))
            }
        }
    }

    private fun itemOf(item: ChannelMediaItem): MediaTabItem {
        val media = item.media
        val visual = media.kind == "image" || media.kind == "video"
        val (shown, thumb) = if (visual) tile(media) else null to null
        return MediaTabItem(
            id = media.id,
            cardKind = item.card.kind,
            cardId = item.card.id,
            sentBy = item.sentBy?.name?.ifBlank { null } ?: "Someone",
            sentByAgent = item.sentBy?.kind == "agent",
            ago = ago(item.atMillis),
            tile = shown,
            thumb = thumb,
            file = if (visual) null else file(media),
        )
    }

    private fun problemOf(failed: Throwable) =
        if (failed is DashboardException) "Couldn't read this channel's media. ${failed.message}" else "Couldn't read this channel's media. Check the connection and try again."
}
