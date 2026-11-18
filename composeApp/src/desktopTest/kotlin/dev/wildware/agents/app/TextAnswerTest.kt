package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FormField
import dev.wildware.agents.app.feed.QuestionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A text question's box follows what the agent asked for: its placeholder, its draft, one line or several, and how long. */
@OptIn(ExperimentalTestApi::class)
class TextAnswerTest {
    private val events = mutableListOf<FeedEvent>()
    private val base = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Question>().first()

    private fun ComposeUiTest.show(field: FormField) {
        val q = base.copy(id = "qt", prompt = "What should the release be called?", body = "", kind = QuestionKind.Text, choices = emptyList(), allowsText = true, text = field)
        val state = FakeFeed.state.copy(sections = listOf(FakeFeed.state.sections.first().copy(items = listOf(q))))
        setContent { AgentTheme(dark = false) { Box(Modifier.size(1440.dp, 900.dp)) { FeedScreen(state) { events += it } } } }
    }

    private val box = hasSetTextAction() and hasContentDescription("Your answer")

    private fun answers() = events.filterIsInstance<FeedEvent.AnswerText>().map { it.text }

    // The placeholder is drawn but kept from screen readers (the box is read by its label), so the
    // screenshot test shows it rather than this one.
    @Test
    fun theAgentsDraftIsInTheBoxReadyToSend() = runComposeUiTest {
        show(FormField(placeholder = "A one-word name", initial = "Juniper", min = 1))
        onNodeWithText("Juniper").assertExists()
        onNodeWithContentDescription("Send answer").performClick()
        assertEquals(listOf("Juniper"), answers())
    }

    @Test
    fun aMultilineBoxTakesNewLinesAndSendsOnCommandEnter() = runComposeUiTest {
        show(FormField(multiline = true, min = 1))
        onNode(box).performTextInput("First line")
        onNode(box).performKeyInput { pressKey(Key.Enter) }
        onNode(box).performTextInput("Second line")
        assertEquals(emptyList(), answers(), "Enter adds a line")
        onNode(box).performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Enter) } }
        waitForIdle()
        assertEquals(listOf("First line\nSecond line"), answers())
    }

    @Test
    fun tooShortSaysSoAndWontSend() = runComposeUiTest {
        show(FormField(min = 10))
        onNode(box).performTextInput("short")
        onNodeWithText("At least 10 characters (5 so far).").assertExists()
        onNodeWithContentDescription("Send answer").performClick()
        assertTrue(answers().isEmpty())
        onNode(box).performTextInput(" but now long enough")
        onNodeWithContentDescription("Send answer").performClick()
        assertEquals(listOf("short but now long enough"), answers())
    }

    @Test
    fun tooLongSaysSoAndWontSend() = runComposeUiTest {
        show(FormField(min = 1, max = 5))
        onNode(box).performTextInput("toolong")
        onNodeWithText("2 characters too long (at most 5).").assertExists()
        onNode(box).performKeyInput { pressKey(Key.Enter) }
        assertTrue(answers().isEmpty())
    }

    @Test
    fun withNoSettingsItIsTheUsualOneLineBox() = runComposeUiTest {
        show(FormField(min = 1))
        onNodeWithContentDescription("Send answer").performClick()
        assertTrue(answers().isEmpty(), "nothing to send")
        onNode(box).performTextInput("Juniper")
        onNode(box).performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("Juniper"), answers())
    }
}
