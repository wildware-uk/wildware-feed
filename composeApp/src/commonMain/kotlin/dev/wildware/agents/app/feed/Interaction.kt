package dev.wildware.agents.app.feed

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import dev.wildware.agents.app.design.gesture.EdgeProgress

/**
 * Everything the owner can do on the feed, whichever way they did it (a tap, a swipe, a menu, a
 * key or a screen reader action). The data layer handles these; the screen only raises them.
 */
sealed interface FeedEvent {
    data class Answer(val questionId: String, val choice: String) : FeedEvent
    data class DismissQuestion(val questionId: String) : FeedEvent
    data class React(val targetId: String, val emoji: String) : FeedEvent
    data class Reply(val targetId: String) : FeedEvent
    data class CopyText(val targetId: String) : FeedEvent
    data class CopyLink(val targetId: String) : FeedEvent
    data class Delete(val targetId: String) : FeedEvent
    data class OpenThread(val updateId: String) : FeedEvent
    data class MoveProject(val from: Int, val to: Int) : FeedEvent
    data object Refresh : FeedEvent
}

/** The thing a context menu is open on. */
@Immutable
data class MenuTarget(
    val id: String,
    val author: String,
    val text: String,
    val own: Boolean,
    /** Where the pointer was, in the target's own coordinates, for menus that open at the cursor. */
    val at: Offset = Offset.Zero,
)

/** The emoji a double-tap adds. */
const val QuickReaction = "👍"
val MenuReactions = listOf("👍", "✅", "👀", "❤️", "🎉", "😂")

/**
 * Screens open in a given state, for screenshots and previews. A real launch leaves it empty.
 */
@Immutable
data class ScreenPreview(
    /** How far the first question is swiped, as a share of the commit distance; + is right. */
    val questionSwipe: Float = 0f,
    /** A reply to show swiped right, part-way to "Reply". */
    val replySwipe: String? = null,
    val menu: MenuTarget? = null,
    val viewer: Attachment? = null,
    val drawerOpen: Boolean = false,
    /** Phone only: the open thread is shown full screen. */
    val threadOpen: Boolean = false,
)

/** What the screen is showing on top of the feed, and what the owner has done locally. */
@Stable
class FeedUi(preview: ScreenPreview, val state: FeedState, val onEvent: (FeedEvent) -> Unit) {
    var menu by mutableStateOf(preview.menu)
    var viewer by mutableStateOf(preview.viewer)
    var phoneThread by mutableStateOf(if (preview.threadOpen) state.openThread else null)
    var replyingTo by mutableStateOf<Reply?>(null)
    var refreshing by mutableStateOf(false)
    val drawer = EdgeProgress(if (preview.drawerOpen) 1f else 0f)

    /** 0 while the phone thread is in place; rises towards 1 as it is swiped away. */
    val threadBack = EdgeProgress(0f)
    val projects = mutableStateListOf<Project>().apply { addAll(state.projects) }

    /** Reactions added here and not yet confirmed by the server, by target id. */
    val added = mutableStateMapOf<String, String>()

    fun emit(event: FeedEvent) {
        when (event) {
            is FeedEvent.React -> added[event.targetId] = event.emoji
            is FeedEvent.MoveProject -> projects.add(event.to, projects.removeAt(event.from))
            is FeedEvent.Reply -> replyingTo = state.openThread?.replies?.firstOrNull { it.id == event.targetId }
            else -> Unit
        }
        menu = null
        onEvent(event)
    }

    /** The reactions to draw: the item's own, plus any the owner just added here. */
    fun reactionsFor(id: String, base: List<Reaction>): List<Reaction> {
        val mine = added[id] ?: return base
        val existing = base.firstOrNull { it.emoji == mine }
        return if (existing != null) {
            base.map { if (it.emoji == mine && !it.mine) it.copy(count = it.count + 1, mine = true) else it }
        } else {
            base + Reaction(mine, 1, mine = true, spoken = mine)
        }
    }
}

val LocalFeedUi = staticCompositionLocalOf<FeedUi?> { null }

/** Whether the current layout is the phone one; menus become sheets there. */
val LocalIsPhone = staticCompositionLocalOf { false }

internal val LocalPreview = staticCompositionLocalOf { ScreenPreview() }
