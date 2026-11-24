package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.FeedUi
import dev.wildware.agents.app.feed.Highlighted
import dev.wildware.agents.app.feed.LocalFeedUi
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.feed.ThreadComposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Up arrow in an empty message box opens your own last message there to change: Enter saves, Esc cancels. */
@OptIn(ExperimentalTestApi::class)
class UpArrowEditTest {
    private val events = mutableListOf<FeedEvent>()
    private val asked = "Can someone find out why the Mac build takes 9 minutes? It used to be 4."
    private val strip = "Editing message · Esc to cancel"
    private val edits get() = events.filterIsInstance<FeedEvent.SaveEdit>()

    private fun ComposeUiTest.feed(state: FeedState = FakeFeed.state.copy(openThread = null)) {
        setContent {
            AgentTheme(dark = true) {
                Box(Modifier.size(1440.dp, 900.dp)) { FeedScreen(state, onEvent = { events += it }) }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.box(label: String = "Message"): SemanticsNodeInteraction = onNode(hasSetTextAction() and hasContentDescription(label))

    private fun ComposeUiTest.press(node: SemanticsNodeInteraction, key: Key) {
        node.performKeyInput { pressKey(key) }
        waitForIdle()
    }

    private fun ComposeUiTest.openEmptyBoxAndPressUp(): SemanticsNodeInteraction {
        val box = box()
        box.performClick()
        press(box, Key.DirectionUp)
        return box
    }

    private val ownPostLit = SemanticsMatcher.expectValue(Highlighted, true)

    @Test
    fun upInAnEmptyBoxOpensYourLastMessageToEdit() = runDesktopComposeUiTest(1440, 900) {
        feed()
        val box = openEmptyBoxAndPressUp()
        box.assertTextEquals(asked, includeEditableText = true)
        onNodeWithText(strip).assertExists()
        assertEquals(1, onAllNodes(ownPostLit).fetchSemanticsNodes().size, "the post being edited is marked")
        // Up again while editing is the caret's, not another message.
        press(box, Key.DirectionUp)
        box.assertTextEquals(asked, includeEditableText = true)
    }

    @Test
    fun enterSavesTheEdit() = runDesktopComposeUiTest(1440, 900) {
        feed()
        val box = openEmptyBoxAndPressUp()
        box.performTextReplacement("Why does the Mac build take 9 minutes?")
        press(box, Key.Enter)
        assertEquals(listOf(FeedEvent.SaveEdit("o1", "Why does the Mac build take 9 minutes?")), edits)
        assertTrue(events.none { it is FeedEvent.Post }, "an edit is not a new post: $events")
        box.assertTextEquals("", includeEditableText = true)
        onNodeWithText(strip).assertDoesNotExist()
        assertEquals(0, onAllNodes(ownPostLit).fetchSemanticsNodes().size)
    }

    @Test
    fun escapeCancelsAndEmptiesTheBox() = runDesktopComposeUiTest(1440, 900) {
        feed()
        val box = openEmptyBoxAndPressUp()
        box.performTextReplacement("never mind")
        press(box, Key.Escape)
        assertTrue(edits.isEmpty(), "got $events")
        box.assertTextEquals("", includeEditableText = true)
        onNodeWithText(strip).assertDoesNotExist()
        assertEquals(0, onAllNodes(ownPostLit).fetchSemanticsNodes().size)
    }

    @Test
    fun upInABoxWithWordsMovesTheCaretAsUsual() = runDesktopComposeUiTest(1440, 900) {
        feed()
        val box = box()
        box.performClick()
        box.performTextInput("half a thought")
        press(box, Key.DirectionUp)
        box.assertTextEquals("half a thought", includeEditableText = true)
        onNodeWithText(strip).assertDoesNotExist()
    }

    @Test
    fun withNoMessageOfYoursUpDoesNothing() = runDesktopComposeUiTest(1440, 900) {
        val noneOfMine = FakeFeed.state.sections.map { s -> s.copy(items = s.items.filterNot { it is FeedItem.Post }) }
        feed(FakeFeed.state.copy(openThread = null, sections = noneOfMine))
        val box = openEmptyBoxAndPressUp()
        box.assertTextEquals("", includeEditableText = true)
        onNodeWithText(strip).assertDoesNotExist()
    }

    @Test
    fun aVoiceNoteIsNotOpenedToEdit() = runDesktopComposeUiTest(1440, 900) {
        val spoken = FakeFeed.state.sections.map { s -> s.copy(items = s.items.map { if (it is FeedItem.Post) it.copy(voice = true) else it }) }
        feed(FakeFeed.state.copy(openThread = null, sections = spoken))
        val box = openEmptyBoxAndPressUp()
        box.assertTextEquals("", includeEditableText = true)
        onNodeWithText(strip).assertDoesNotExist()
    }

    @Test
    fun itWorksInAThreadsReplyBoxToo() = runDesktopComposeUiTest(1440, 900) {
        val ui = FeedUi(ScreenPreview(), mutableStateOf(FakeFeed.state)) { events += it }
        setContent {
            AgentTheme(dark = true) {
                CompositionLocalProvider(LocalFeedUi provides ui) { ThreadComposer(ui, "u1") }
            }
        }
        val box = box("Reply")
        box.performClick()
        press(box, Key.DirectionUp)
        // Your newest reply in the thread, not the channel's post.
        box.assertTextEquals("Nice. Does this also fix the badge count on the watch?", includeEditableText = true)
        assertEquals("r1", ui.boxEditing)
        box.performTextReplacement("Does this fix the watch's badge too?")
        press(box, Key.Enter)
        assertEquals(listOf(FeedEvent.SaveEdit("r1", "Does this fix the watch's badge too?")), edits)
        assertTrue(events.none { it is FeedEvent.SendReply }, "an edit is not a new reply: $events")
        assertEquals(null, ui.boxEditing)
    }
}
