package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.Reply
import dev.wildware.agents.app.feed.ScreenPreview
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Replies read newest first: under a card (the few shown are the newest) and in the phone's
 * thread, below its card. The "N new" line sits under the new ones, and nothing unread is hidden.
 */
@OptIn(ExperimentalTestApi::class)
class ReplyOrderTest {
    private val fix = FakeFeed.state.openThread as FeedItem.Update
    private val agent = fix.replies.first { it.author !is dev.wildware.agents.app.feed.Author.Owner }.author

    /** Replies 1 to [count], oldest first as the engine gives them; the newest [unread] are new. */
    private fun replies(count: Int, unread: Int = 0) = (1..count).map { n ->
        Reply(id = "x$n", author = agent, time = "10:$n", body = "Reply number $n", unread = n > count - unread)
    }

    private fun only(card: FeedItem.Update): FeedState = FakeFeed.state.copy(
        sections = listOf(FakeFeed.state.sections.first().copy(items = listOf(card))),
        openThread = card,
    )

    private fun ComposeUiTest.desktop(state: FeedState) = setContent {
        AgentTheme(dark = false) { Box(Modifier.size(1440.dp, 4000.dp)) { FeedScreen(state) } }
    }

    private val inThread = hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Thread"))

    // Unclipped, so a reply below the fold still has its place.
    private fun SemanticsNodeInteraction.top() = fetchSemanticsNode().positionInRoot.y

    private fun ComposeUiTest.inOrder(vararg texts: String, within: SemanticsMatcher? = null) {
        val tops = texts.map { t ->
            // The "N new" line is read out as one phrase, so it is found by that.
            val m = if (t.endsWith(" new")) androidx.compose.ui.test.hasContentDescription("${t.removeSuffix(" new")} new replies", substring = true) else hasText(t, substring = true)
            onNode(if (within != null) m and within else m).top()
        }
        assertTrue(tops.zipWithNext().all { (a, b) -> a < b }, "expected ${texts.toList()} top to bottom, got $tops")
    }

    @Test
    fun underACardTheNewestRepliesShowFirst() = runComposeUiTest {
        desktop(only(fix.copy(replies = replies(8), unreadReplies = 0)))
        inOrder("Reply number 8", "Reply number 7", "Reply number 6", "Reply number 5", "Reply number 4", "Show 3 earlier replies")
        onNodeWithText("Reply number 3").assertDoesNotExist()
    }

    @Test
    fun underACardTheNewLineSitsUnderTheNewReplies() = runComposeUiTest {
        desktop(only(fix.copy(replies = replies(4, unread = 2), unreadReplies = 2)))
        inOrder("Reply number 4", "Reply number 3", "2 new", "Reply number 2", "Reply number 1")
    }

    @Test
    fun underACardNoNewReplyIsHidden() = runComposeUiTest {
        desktop(only(fix.copy(replies = replies(9, unread = 7), unreadReplies = 7)))
        inOrder("Reply number 9", "Reply number 3", "Show 2 earlier replies")
        onNodeWithText("Reply number 2").assertDoesNotExist()
    }

    private fun ComposeUiTest.phone(state: () -> FeedState) = setContent {
        AgentTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { FeedScreen(state(), preview = ScreenPreview(threadOpen = true)) } }
    }

    @Test
    fun thePhoneThreadShowsItsCardThenTheNewestReplies() = runComposeUiTest {
        phone { FakeFeed.state }
        inOrder(
            "no longer hides them from another",
            "Deployed to the box",
            "Confirmed from my side",
            "2 new",
            "The watch reads the same counter",
            "Does this also fix the badge count",
            within = inThread,
        )
    }

    @Test
    fun aReplyBeingSentShowsAtTheTopAtOnceAndComesIntoView() = runComposeUiTest {
        var state by mutableStateOf(FakeFeed.state)
        phone { state }
        waitForIdle()
        val sent = Reply(id = "x-new", author = FakeFeed.state.owner, time = "now", body = "On my way to check the watch", sending = true)
        val thread = fix.copy(replies = fix.replies + sent)
        state = FakeFeed.state.copy(
            openThread = thread,
            sections = FakeFeed.state.sections.map { s -> s.copy(items = s.items.map { if (it.id == thread.id) thread else it }) },
        )
        waitForIdle()
        inOrder("On my way to check the watch", "Deployed to the box", within = inThread)
        onNode(hasText("On my way to check the watch") and inThread).assertIsDisplayed()
    }
}
