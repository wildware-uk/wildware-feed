package dev.wildware.agents.app.feed

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.gesture.DismissDrag
import dev.wildware.agents.app.design.gesture.swipeDownToDismiss
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/*
 * A board card, opened from the board: the card and its comments in a modal over the board, which
 * stays just as it was under it (its scroll, its filters, its column). A sheet from the bottom on a
 * phone, a dialog in the middle wider. × closes it, as do Escape, a tap outside, system back and,
 * on a phone, a swipe down on its top. "Open full thread" opens the card on its own, and Back from
 * there comes back to the board.
 */

/** The open board card's modal, if one is open: over the board, under menus and pickers. */
@Composable
internal fun BoardCardModalHost(ui: FeedUi, phone: Boolean, safeArea: SafeArea) {
    val motion = AgentTheme.motion
    Presence(ui.boardCard, fadeIn(motion.fast()), fadeOut(motion.normal())) { id ->
        val panel = if (phone) {
            Modifier.animateEnterExit(enter = slideInVertically(motion.settle()) { it }, exit = slideOutVertically(motion.normal()) { it })
        } else {
            Modifier.animateEnterExit(enter = scaleIn(motion.settle(), initialScale = 0.96f), exit = scaleOut(motion.fast(), targetScale = 0.98f))
        }
        BoardCardModal(ui, id, phone, safeArea, panel)
    }
}

@Composable
private fun BoardCardModal(ui: FeedUi, id: String, phone: Boolean, safeArea: SafeArea, panel: Modifier) {
    val colors = AgentTheme.colors
    val close = { ui.closeBoardCard() }
    val focus = remember { FocusRequester() }
    // The keyboard and a screen reader go into the modal as it opens.
    LaunchedEffect(id) { runCatching { focus.requestFocus() } }
    val drag = remember { DismissDrag() }
    // Where a swipe let go, so the sheet sinks from there rather than jumping back up first.
    var letGoAt by remember { mutableFloatStateOf(0f) }
    val shape = if (phone) RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp) else AgentTheme.shapes.large
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tall = maxHeight
        Scrim({ 0.45f }, "Close card", close)
        Column(
            panel
                .align(if (phone) Alignment.BottomCenter else Alignment.Center)
                .then(
                    if (phone) {
                        Modifier.fillMaxWidth().heightIn(max = tall - safeArea.top - 24.dp)
                            .offset { IntOffset(0, maxOf(drag.offsetPx, letGoAt).roundToInt()) }
                    } else {
                        Modifier.padding(24.dp).widthIn(max = 760.dp).fillMaxWidth().heightIn(max = tall - 48.dp)
                    },
                )
                .lift(AgentTheme.elevation.overlay, shape)
                .clip(shape)
                .background(colors.surfaceRaised)
                .then(if (phone) Modifier else Modifier.border(1.dp, colors.borderStrong, shape))
                .semantics { paneTitle = "Card"; testTag = "board-card-modal" }
                .focusRequester(focus)
                .focusable()
                // After whatever has the keyboard inside: Escape in the reply box puts the box away first.
                .onKeyEvent { e -> if (e.key == Key.Escape && e.type == KeyEventType.KeyDown) { close(); true } else false },
        ) {
            val item = ui.cardOrLone(id) as? Threaded
            // The top: a handle to pull it down by (phone), the full-thread link, and ×.
            Column(
                if (phone) Modifier.fillMaxWidth().swipeDownToDismiss(drag) { letGoAt = drag.offsetPx; close() } else Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (phone) DragHandle()
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = if (phone) 0.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ui.state.board.card(id)?.title ?: "Card",
                        Modifier.weight(1f).semantics { heading() },
                        color = colors.text,
                        style = AgentTheme.type.bodyStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item != null) AppButton("Open full thread", ButtonTone.Quiet) { ui.openBoardCardThread() }
                    IconAction(Icons.Close, "Close card", onClick = close)
                }
            }
            if (item == null) {
                MetaText("Opening the card…", Modifier.padding(horizontal = 16.dp, vertical = 24.dp))
                Spacer(Modifier.height(if (phone) safeArea.bottom else 0.dp))
                return@Column
            }
            CompositionLocalProvider(LocalDocChannel provides item.channelSlug) {
                BoardCardBody(ui, item, phone, Modifier.weight(1f, fill = false))
                ThreadComposer(
                    ui,
                    item.id,
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp + if (phone) safeArea.bottom else 0.dp),
                )
            }
        }
    }
}

/** The card itself, then its comments oldest first, so the newest sits just above the reply box. */
@Composable
private fun BoardCardBody(ui: FeedUi, item: Threaded, phone: Boolean, modifier: Modifier) {
    val motion = AgentTheme.motion
    val scroll = rememberScrollState()
    // A new comment (the owner's own, or one arriving) brings the end into view, as a chat does.
    val newest = item.replies.lastOrNull()?.id
    LaunchedEffect(newest) {
        if (newest == null) return@LaunchedEffect
        if (motion.reduced) scroll.scrollTo(scroll.maxValue) else scroll.animateScrollTo(scroll.maxValue)
    }
    // Seen here is read: the badge clears once the new comments have been on screen a moment.
    if (item.unreadReplies > 0) {
        LaunchedEffect(item.id, item.unreadReplies) {
            delay(MarkReadAfterMillis)
            ui.emit(FeedEvent.OpenThread(item.id))
        }
    }
    Column(modifier.fillMaxWidth().verticalScroll(scroll).closesComposer(ui).padding(horizontal = if (phone) 12.dp else 16.dp)) {
        Spacer(Modifier.height(4.dp))
        key(item.id) {
            FeedItemView(item, selected = false, density = if (phone) Density.Compact else Density.Comfortable, inThread = true)
        }
        HeadQuestions(item)
        Spacer(Modifier.height(14.dp))
        SectionLabel(
            when (item.replies.size) {
                0 -> "No comments yet"
                1 -> "1 comment"
                else -> "${item.replies.size} comments"
            },
        )
        Spacer(Modifier.height(6.dp))
        val firstNew = item.replies.indexOfFirst { it.unread }
        item.replies.forEachIndexed { i, reply ->
            if (i == firstNew && firstNew > 0) NewDivider(item.unreadReplies)
            key(reply.id) { InteractiveReply(reply) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** How long new comments are on screen before they count as read. */
private const val MarkReadAfterMillis = 1_500L
