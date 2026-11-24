package dev.wildware.agents.app.feed

import androidx.compose.runtime.Immutable
import dev.wildware.agents.ChannelMediaKind

/*
 * What the Media tab draws: every picture, video and file on a channel's cards, newest first, a
 * page at a time. Mapped from the dashboard's answers in `engine/MediaWatch.kt`.
 */

/** The chips at the top of the Media tab. */
enum class MediaFilter(val label: String, val kind: ChannelMediaKind) {
    All("All", ChannelMediaKind.All),
    Images("Images", ChannelMediaKind.Images),
    Videos("Videos", ChannelMediaKind.Videos),
    Files("Files", ChannelMediaKind.Files),
}

/**
 * One file in the channel. A picture or video has a [tile] (what the viewer shows) and a smaller
 * [thumb] for the grid; anything else has a [file] to download. [cardId] is the card it is on, to
 * jump to its message or thread.
 */
@Immutable
data class MediaTabItem(
    val id: String,
    val cardKind: String,
    val cardId: String,
    /** "Sam", "forge": who sent it. */
    val sentBy: String,
    val sentByAgent: Boolean,
    /** "5m ago". */
    val ago: String,
    val tile: Attachment? = null,
    val thumb: Attachment? = null,
    val file: FileAttachment? = null,
)

/** The Media tab for [channel] (a slug), filtered to [filter]: the pages read so far. */
@Immutable
data class MediaTabState(
    val channel: String? = null,
    val filter: MediaFilter = MediaFilter.All,
    val items: List<MediaTabItem> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    /** Reading the next page, after [items]. */
    val loadingMore: Boolean = false,
    /** Reads the page after [items]; null when there is no more. */
    val nextCursor: String? = null,
    val problem: String? = null,
) {
    val hasMore: Boolean get() = nextCursor != null

    /** The pictures and videos, in order, for the grid. */
    val tiles: List<MediaTabItem> get() = items.filter { it.tile != null }

    /** The files (and sounds), in order, for the list. */
    val files: List<MediaTabItem> get() = items.filter { it.tile == null }
}

/** What the owner does in the Media tab. Each goes to the engine as [FeedEvent.Media]. */
sealed interface MediaAction {
    /** The tab is on screen for [channel] (a slug), or (null) put away. */
    data class Show(val channel: String?) : MediaAction
    data class Filter(val filter: MediaFilter) : MediaAction
    /** The end of the list is near: read the next page. */
    data object More : MediaAction
    /** Read it again from the top, after a problem. */
    data object Retry : MediaAction
}
