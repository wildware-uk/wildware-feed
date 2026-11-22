package dev.wildware.agents

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The board, as `GET /api/board` answers it (server migration 070): a column per work state, each
 * with its newest-moved cards first, and the team's auto-remove age (null: never).
 */
@Serializable
data class BoardSnapshot(val columns: List<BoardColumnView> = emptyList(), val autoRemoveDays: Int? = null)

/** One column: [status] is the work state ("done" also holds cancelled cards). [after] reads the next page. */
@Serializable
data class BoardColumnView(val status: String, val cards: List<BoardCardView> = emptyList(), val more: Boolean = false, val after: String? = null)

/** One card on the board. Times are epoch milliseconds. */
@Serializable
data class BoardCardView(
    val card: CardRef,
    val projectId: String? = null,
    val channel: BoardChannel = BoardChannel(),
    val status: String,
    val statusAt: Long? = null,
    val createdAt: Long = 0,
    val movedAt: Long = 0,
    val title: String = "",
    val author: String? = null,
    /** Who is on it: agents thinking about it, else its agent, else whoever wrote it. */
    val on: List<BoardWho> = emptyList(),
    val replies: Int = 0,
    val lastReplyAt: Long? = null,
    /** Questions waiting on someone in its thread. */
    val waiting: Int = 0,
    /** Off the board (only listed when removed ones are asked for): [removedBy] is "someone" or "age". */
    val removed: Boolean = false,
    val removedBy: String? = null,
    /** The viewer may change its work state: Post on its channel. */
    val canMove: Boolean = true,
)

@Serializable
data class BoardChannel(val slug: String? = null, val name: String? = null)

/** [kind] is "agent" or "person". */
@Serializable
data class BoardWho(val kind: String, val id: String, val name: String)

@Serializable
internal data class BoardSettingBody(val autoRemoveDays: Int? = null)

/** Where a board looks: one channel, a project (group of channels), or everything. */
sealed interface BoardScope {
    data class Channel(val slug: String) : BoardScope
    data class Group(val slug: String) : BoardScope
    data object Everything : BoardScope
}

/**
 * The board for [scope]. With [column], only that column's next page after [after]. [removed] also
 * lists the finished cards taken off the board, marked as removed.
 */
@Throws(Exception::class)
suspend fun DashboardClient.board(scope: BoardScope, removed: Boolean = false, column: String? = null, after: String? = null, limit: Int = 50): BoardSnapshot =
    http.get("api/board") {
        authorise()
        when (scope) {
            is BoardScope.Channel -> parameter("channel", scope.slug)
            is BoardScope.Group -> parameter("group", scope.slug)
            BoardScope.Everything -> Unit
        }
        if (removed) parameter("removed", "1")
        if (column != null) parameter("column", column)
        if (after != null) parameter("after", after)
        parameter("limit", limit)
    }.decoded()

/** Takes a finished card off the board for everyone in the team ([removed] true), or puts it back. */
@Throws(Exception::class)
suspend fun DashboardClient.setBoardRemoved(card: CardRef, removed: Boolean) {
    http.post("api/cards/${card.kind.encodeURLPathPart()}/${card.id.encodeURLPathPart()}/board") {
        authorise()
        jsonBodyOf(buildJsonObject { put("removed", removed) })
    }.requireSuccess()
}

/** Sets how many days a finished card stays on the board: 1, 3, 7 or 30, or null for never. Admins only. */
@Throws(Exception::class)
suspend fun DashboardClient.setBoardAutoRemove(days: Int?): Int? =
    http.put("api/settings/board") {
        authorise()
        jsonBodyOf(buildJsonObject { put("autoRemoveDays", days) })
    }.decoded<BoardSettingBody>().autoRemoveDays

private fun HttpRequestBuilder.jsonBodyOf(body: JsonObject) {
    contentType(ContentType.Application.Json)
    setBody(body)
}
