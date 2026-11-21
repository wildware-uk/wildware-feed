package dev.wildware.agents.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replying on a card further down makes it the newest, so it jumps to the top of the feed. The
 * feed goes with it, so the card and its open reply box don't vanish from under the owner.
 */
@OptIn(ExperimentalTestApi::class)
class FollowReplyTest {
    private val events = mutableListOf<FeedEvent>()
    private val base = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Update>().first()
    private val cards = (1..8).map { n ->
        base.copy(id = "c$n", title = "Card number $n", body = "What card $n says.", replies = emptyList(), unreadReplies = 0, attachments = emptyList())
    }
    private fun feed(order: List<FeedItem.Update>, open: FeedItem.Update? = null): FeedState =
        FakeFeed.state.copy(openThread = open, sections = listOf(FakeFeed.state.sections.first().copy(items = order)))

    /** Card [n] moved to the top, as its newest reply puts it once the server has it. */
    private fun bumped(n: Int) = feed(listOf(cards[n - 1]) + cards.filter { it.id != "c$n" })

    private var state by mutableStateOf(feed(cards, open = cards[4]))
    private val replyBox = hasSetTextAction() and hasContentDescription("Reply")

    private fun ComposeUiTest.show() {
        // Opened at card 4, so card 5 with its reply box sits under it, away from the top.
        setContent { AgentTheme(dark = false) { FeedScreen(state, startAt = "c4", onEvent = { events += it }) } }
        waitForIdle()
    }

    private fun ComposeUiTest.reply(text: String) {
        onNode(replyBox).performTextInput(text)
        val field = onNode(replyBox).fetchSemanticsNode().boundsInRoot
        val sends = onAllNodes(hasContentDescription("Send"))
        val inThread = sends.fetchSemanticsNodes().indexOfFirst { it.boundsInRoot.top == field.top }
        sends[inThread].performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    private fun ComposeUiTest.top(title: String) = onNodeWithText(title).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun replyingOnTheFifthCardFollowsItToTheTop() = runDesktopComposeUiTest(width = 1440, height = 900) {
        show()
        onNodeWithText("Card number 5").assertIsDisplayed()
        onNodeWithText("Card number 1").assertDoesNotExist()
        reply("on it")
        assertEquals(FeedEvent.SendReply("c5", "on it", answers = null), events.filterIsInstance<FeedEvent.SendReply>().single())
        // Nothing moves until the reply lands and the card takes its new place.
        onNodeWithText("Card number 4").assertIsDisplayed()
        state = bumped(5)
        waitForIdle()
        onNodeWithText("Card number 5").assertIsDisplayed()
        onNodeWithText("Card number 1").assertIsDisplayed()
        assertTrue(top("Card number 5") < top("Card number 1"), "card 5 is first now, and the feed is at its top")
        // The reply box is still open on it, ready for more.
        onNode(replyBox).assertIsDisplayed()
    }

    @Test
    fun anAgentsPostWhileTheReplyIsOnItsWayDoesNotUseUpTheFollow() = runDesktopComposeUiTest(width = 1440, height = 900) {
        show()
        reply("on it")
        // An agent posts on card 7 first: it jumps to the top and card 5 slips one place down.
        state = feed(listOf(cards[6]) + cards.filter { it.id != "c7" })
        waitForIdle()
        onNodeWithText("Card number 1").assertDoesNotExist()
        // Then the owner's reply lands and card 5 goes first: the feed still follows it.
        state = feed(listOf(cards[4], cards[6]) + cards.filter { it.id != "c5" && it.id != "c7" })
        waitForIdle()
        onNodeWithText("Card number 5").assertIsDisplayed()
        onNodeWithText("Card number 7").assertIsDisplayed()
        assertTrue(top("Card number 5") < top("Card number 7"), "card 5 is first and the feed is at its top")
    }

    @Test
    fun someoneElsesReplyLeavesTheViewAlone() = runDesktopComposeUiTest(width = 1440, height = 900) {
        show()
        // An agent replies on card 6: it moves to the top, and the owner stays where they were.
        state = bumped(6)
        waitForIdle()
        onNodeWithText("Card number 4").assertIsDisplayed()
        onNodeWithText("Card number 1").assertDoesNotExist()
    }
}
