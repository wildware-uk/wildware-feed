package dev.wildware.agents

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/*
 * A channel's Media tab: every picture, video and file on its cards (updates, messages and
 * requests), newest first, a page at a time. Read on the channel reads it.
 */

/** Which media a page holds. [wire] is the dashboard's `kind`; files include sounds. */
enum class ChannelMediaKind(val wire: String) { All("all"), Images("image"), Videos("video"), Files("file") }

/** The card a file is on: [kind] is "update", "message" or "request". */
@Serializable
data class ChannelMediaCard(val kind: String = "", val id: String = "")

/** One file in a channel, the card it is on, who sent it and when (an ISO time). */
@Serializable
data class ChannelMediaItem(
    val media: MediaView,
    val card: ChannelMediaCard = ChannelMediaCard(),
    val sentBy: DocAuthor? = null,
    val at: String = "",
) {
    val atMillis: Long get() = runCatching { Instant.parse(at).toEpochMilliseconds() }.getOrDefault(0L)
}

/** One page of a channel's media; [nextCursor] reads the page after it, and is null at the end. */
@Serializable
data class ChannelMediaPage(
    val projectId: String = "",
    val kind: String = "all",
    val items: List<ChannelMediaItem> = emptyList(),
    val nextCursor: String? = null,
)

/** A page of [channel]'s media of [kind], newest first, after [cursor] (from the page before). */
@Throws(Exception::class)
suspend fun DashboardClient.channelMedia(
    channel: String,
    kind: ChannelMediaKind = ChannelMediaKind.All,
    cursor: String? = null,
    limit: Int = 60,
): ChannelMediaPage =
    http.get("api/projects/${channel.encodeURLPathPart()}/media") {
        authorise()
        if (kind != ChannelMediaKind.All) parameter("kind", kind.wire)
        if (cursor != null) parameter("cursor", cursor)
        parameter("limit", limit)
    }.decoded()
