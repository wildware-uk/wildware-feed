package dev.wildware.agents.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.Reply
import kotlin.test.Test
import kotlin.test.assertTrue

/** Newest replies are at the top, so the way to add one is there too: under the card, above the newest. */
@OptIn(ExperimentalTestApi::class)
class ReplyAtTopTest {
    private val fix = (FakeFeed.state.openThread as FeedItem.Update)
    private val newest = fix.replies.last().body.take(20)
    // Only this card, so there is one Reply action to find.
    private var state by mutableStateOf(
        FakeFeed.state.copy(openThread = null, sections = listOf(FakeFeed.state.sections.first().copy(items = listOf(fix)))),
    )

    private fun ComposeUiTest.desktop() {
        setContent { AgentTheme(dark = false) { FeedScreen(state) } }
        onNode(hasScrollToNodeAction()).performScrollToNode(hasText(newest, substring = true))
    }

    private fun ComposeUiTest.top(m: SemanticsMatcher) = onNode(m).fetchSemanticsNode().positionInRoot.y

    private val replyAction = hasText("Reply") and hasClickAction()
    private val replyBox = hasSetTextAction() and hasContentDescription("Reply")

    @Test
    fun theReplyActionSitsAboveTheNewestReply() = runDesktopComposeUiTest(width = 1440, height = 1600) {
        desktop()
        val action = top(replyAction)
        val reply = top(hasText(newest, substring = true))
        assertTrue(action < reply, "Reply ($action) above the newest reply ($reply)")
    }

    @Test
    fun theReplyBoxOpensAtTheTopAndASentReplyLandsRightUnderIt() = runDesktopComposeUiTest(width = 1440, height = 1600) {
        desktop()
        onNode(replyAction).performClick()
        waitForIdle()
        val box = top(replyBox)
        assertTrue(box < top(hasText(newest, substring = true)), "the box is above the newest reply")
        // What the owner sends shows at once, straight under the box.
        val sent = Reply("x-sent", FakeFeed.state.owner, "now", "On it, thanks", sending = true)
        val thread = fix.copy(replies = fix.replies + sent)
        state = state.copy(sections = state.sections.map { s -> s.copy(items = s.items.map { if (it.id == thread.id) thread else it }) })
        waitForIdle()
        val mine = top(hasText("On it, thanks"))
        assertTrue(mine > top(replyBox) && mine < top(hasText(newest, substring = true)), "sent reply between the box and the older replies")
    }
}
