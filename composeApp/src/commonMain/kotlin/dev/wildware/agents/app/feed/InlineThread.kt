package dev.wildware.agents.app.feed

import androidx.compose.runtime.key
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.pressable
import kotlinx.coroutines.delay

/**
 * Whether cards show their thread under themselves, as the website does, rather than behind a
 * "replies" button. The wide layout turns it on; the phone keeps its full-screen thread.
 */
val LocalInlineThreads = staticCompositionLocalOf { false }

/** How many of a long thread's newest replies show before "Show earlier replies". */
internal const val InlineReplies = 5

/**
 * Replies as they are shown: newest first. [replies] come oldest first, as the engine keeps them,
 * so a reply being sent (the newest) lands at the top at once. [shown] is how many of the newest
 * show before "Show earlier replies": [InlineReplies], or more so that no new reply is hidden.
 * [newLineAfter] is the index the "N new" line follows: the oldest new reply, when older ones sit
 * under it.
 */
internal class NewestFirst(replies: List<Reply>, all: Boolean) {
    val replies: List<Reply> = replies.asReversed()
    private val oldestNew = this.replies.indexOfLast { it.unread }
    val shown: Int = when {
        all || this.replies.size <= InlineReplies + 1 -> this.replies.size
        else -> maxOf(InlineReplies, oldestNew + 1)
    }
    val newLineAfter: Int = if (oldestNew in 0 until this.replies.lastIndex) oldestNew else -1
}

/**
 * A card's thread, under the card: its replies, newest first, and a reply box. New replies (the
 * owner's own at once, faded until sent, and agents' as they arrive) show here without opening
 * anything. A long thread shows its newest few, and never hides one the owner hasn't read.
 */
@Composable
fun InlineThread(
    item: Threaded,
    modifier: Modifier = Modifier,
    /** What adding to the thread is called: "Reply", or "Discuss" under a question, where replying would sound like answering. */
    action: String = "Reply",
) {
    val ui = LocalFeedUi.current
    val colors = AgentTheme.colors
    val replies = item.replies
    val expanded = ui?.expandedThreads?.contains(item.id) == true
    val order = remember(replies, expanded) { NewestFirst(replies, all = expanded) }
    val hidden = replies.size - order.shown
    val replying = ui?.threadId == item.id

    // Seen here is read: the badge clears once the new replies have been on screen a moment.
    if (item.unreadReplies > 0 && ui != null) {
        LaunchedEffect(item.id, item.unreadReplies) {
            delay(MarkReadAfterMillis)
            ui.onEvent(FeedEvent.OpenThread(item.id))
        }
    }

    Column(modifier.fillMaxWidth()) {
        if (replies.isNotEmpty() || replying) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            Spacer(Modifier.height(6.dp))
        }
        HeadQuestions(item)
        // Newest replies are on top, so the way to add one is too: a sent reply lands right under it.
        if (replying) {
            val focus = remember { FocusRequester() }
            ThreadComposer(ui, item.id, Modifier.padding(top = 8.dp, bottom = 4.dp), focus = focus)
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        } else {
            Spacer(Modifier.height(if (replies.isEmpty()) 10.dp else 4.dp))
            ReplyAction(action) { ui?.openThread(item.id, phone = false) }
        }
        for (i in 0 until order.shown) {
            // Keyed by id: a new reply arrives at the top, and the ones under it keep their place.
            val reply = order.replies[i]
            key(reply.id) { InteractiveReply(reply) }
            if (i == order.newLineAfter) NewDivider(item.unreadReplies, newAbove = true)
        }
        if (hidden > 0) {
            TextAction("Show $hidden earlier ${if (hidden == 1) "reply" else "replies"}") { ui?.expandedThreads?.add(item.id) }
        }
    }
}

private const val MarkReadAfterMillis = 1_500L

@Composable
private fun ReplyAction(label: String, onClick: () -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.pill
    Row(
        Modifier
            .clip(shape)
            .pressable(label, shape, labelIsShown = true, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Reply, null, tint = colors.textMuted, modifier = Modifier.size(15.dp))
        Text(label, Modifier.padding(start = 6.dp), color = colors.textMuted, style = AgentTheme.type.label)
    }
}

@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.pill
    Text(
        label,
        Modifier
            .clip(shape)
            .pressable(label, shape, labelIsShown = true, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        color = colors.accent,
        style = AgentTheme.type.label,
    )
}
