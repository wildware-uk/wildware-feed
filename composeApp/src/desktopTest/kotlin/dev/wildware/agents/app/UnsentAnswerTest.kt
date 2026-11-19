package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FormField
import dev.wildware.agents.app.feed.QuestionKind
import dev.wildware.agents.app.feed.Unsent
import kotlin.test.Test
import kotlin.test.assertEquals

/** An answer the dashboard refused shows on its card, with its words kept, to try again or drop. */
@OptIn(ExperimentalTestApi::class)
class UnsentAnswerTest {
    private val events = mutableListOf<FeedEvent>()
    private val base = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Question>().first()

    private fun ComposeUiTest.show(unsent: Unsent?, sending: Boolean = false) {
        val q = base.copy(
            id = "qt", prompt = "What should the release notes say?", body = "", kind = QuestionKind.Text,
            choices = emptyList(), allowsText = true, text = FormField(placeholder = "A line or two"), unsent = unsent, sending = sending,
        )
        val state = FakeFeed.state.copy(sections = listOf(FakeFeed.state.sections.first().copy(items = listOf(q))))
        setContent { AgentTheme(dark = false) { Box(Modifier.size(1440.dp, 900.dp)) { FeedScreen(state) { events += it } } } }
    }

    private val refused = Unsent("o1", "That answer is too long.", "Faster sync", "Faster sync")

    @Test
    fun theCardSaysWhyAndKeepsTheWords() = runComposeUiTest {
        show(refused)
        onNodeWithText("Couldn't send your answer").assertExists()
        onNodeWithText("That answer is too long.").assertExists()
        onNodeWithText("You answered: Faster sync").assertExists()
        onNode(hasSetTextAction() and hasContentDescription("Your answer")).assertTextContains("Faster sync")
    }

    @Test
    fun tryAgainAndDropGoToTheOutbox() = runComposeUiTest {
        show(refused)
        onNodeWithText("Try again").performClick()
        onNodeWithText("Drop it").performClick()
        assertEquals(listOf(FeedEvent.RetrySend("o1"), FeedEvent.DropUnsent("o1")), events)
    }

    @Test
    fun nothingRefusedSaysNothing() = runComposeUiTest {
        show(null)
        onNodeWithText("Couldn't send your answer").assertDoesNotExist()
    }

    @Test
    fun whileSendingAgainItSaysWaitingInstead() = runComposeUiTest {
        show(refused, sending = true)
        onNodeWithText("Couldn't send your answer").assertDoesNotExist()
        onNodeWithText("Answer waiting to send").assertExists()
    }
}
