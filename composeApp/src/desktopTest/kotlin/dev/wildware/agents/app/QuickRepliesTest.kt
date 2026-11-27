package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.FormFieldView
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FieldsForm
import dev.wildware.agents.app.feed.QuickAnswer
import dev.wildware.agents.app.feed.QuickReplies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Quick replies (docs/QUICK_REPLIES.md): buttons or a small form on an agent's message, answered with one tap. */
@OptIn(ExperimentalTestApi::class)
class QuickRepliesTest {
    private val events = mutableListOf<FeedEvent>()
    private val base = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Post>().first()

    private val buttons = QuickReplies(messageId = "m1", buttons = listOf("Ship it", "Try another"))

    private fun ComposeUiTest.show(quick: QuickReplies) {
        val post = base.copy(body = "Sounds are in 1.0.32", quickReplies = quick, replies = emptyList())
        val state = FakeFeed.state.copy(sections = listOf(FakeFeed.state.sections.first().copy(items = listOf(post))))
        setContent { AgentTheme(dark = false) { Box(Modifier.size(390.dp, 2000.dp)) { FeedScreen(state) { events += it } } } }
    }

    @Test
    fun aTapIsSentOnceAsYourReply() = runDesktopComposeUiTest(390, 2000) {
        show(buttons)
        onNodeWithText("Ship it").performScrollTo().performClick()
        onNodeWithText("Ship it").performClick()
        waitForIdle()
        val sent = events.filterIsInstance<FeedEvent.QuickReply>().single()
        assertEquals("m1", sent.messageId)
        assertEquals("Ship it", sent.button)
        assertNull(sent.values)
        onNodeWithText("Sending…").assertExists()
        onNodeWithText("Try another").assertIsNotEnabled()
    }

    @Test
    fun aRefusedTapCanBeTriedAgainWithTheSameKey() = runDesktopComposeUiTest(390, 2000) {
        show(buttons)
        onNodeWithText("Ship it").performScrollTo().performClick()
        waitForIdle()
        val first = events.filterIsInstance<FeedEvent.QuickReply>().single()
        first.sent(false)
        waitForIdle()
        onNodeWithText("That didn't send. Try again.").assertExists()
        onNodeWithText("Ship it").assertIsEnabled().performClick()
        waitForIdle()
        val again = events.filterIsInstance<FeedEvent.QuickReply>()
        assertEquals(2, again.size)
        assertEquals(first.sendKey, again[1].sendKey, "a retry of the same message is the same send")
    }

    @Test
    fun onceAnsweredTheButtonsGiveWayToWhoChoseWhat() = runDesktopComposeUiTest(390, 2000) {
        show(buttons.copy(mine = "Try another", answers = listOf(QuickAnswer("You", "Try another", mine = true), QuickAnswer("Kim", "Ship it", mine = false))))
        onNodeWithText("You chose Try another").assertExists()
        onNodeWithText("Kim chose Ship it").assertExists()
        onNodeWithText("Ship it").assertDoesNotExist()
        assertTrue(events.none { it is FeedEvent.QuickReply })
    }

    @Test
    fun aFormIsSentWithItsValues() = runDesktopComposeUiTest(390, 2000) {
        val fields = listOf(FormFieldView("name", "Name", "text", required = true), FormFieldView("plan", "Plan", "radio", options = listOf("Free", "Team")))
        show(QuickReplies(messageId = "m2", buttons = listOf("Send"), form = FieldsForm(fields, listOf("Send"))))
        onNodeWithText("Send").assertIsNotEnabled()
        onNode(hasSetTextAction() and hasContentDescription("Name", substring = true)).performScrollTo().performTextInput("Sam")
        onNodeWithText("Team").performScrollTo().performClick()
        onNodeWithText("Send").performScrollTo().performClick()
        waitForIdle()
        val sent = events.filterIsInstance<FeedEvent.QuickReply>().single()
        assertEquals("Send", sent.button)
        assertEquals("""{"name":"Sam","plan":"Team"}""", sent.values.toString())
    }
}
