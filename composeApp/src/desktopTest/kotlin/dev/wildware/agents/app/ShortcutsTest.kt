package dev.wildware.agents.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.Highlighted
import dev.wildware.agents.app.feed.keyboardShortcuts
import dev.wildware.agents.app.feed.shortcutsUseCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Back and Forward (keys and the mouse's side buttons) and the feed's keyboard shortcuts, on a desktop. */
@OptIn(ExperimentalTestApi::class)
class ShortcutsTest {
    private val events = mutableListOf<FeedEvent>()
    private val mac = shortcutsUseCommand

    /** The desktop feed over a live state that switches channel as the engine would. */
    private fun ComposeUiTest.desktop(start: FeedState = FakeFeed.state.copy(openThread = null)) {
        val state = mutableStateOf(start)
        setContent {
            AgentTheme(dark = true) {
                FeedScreen(state, onEvent = { e ->
                    events += e
                    if (e is FeedEvent.SelectProject) {
                        state.value.projects.firstOrNull { it.slug == e.slug }?.let { state.value = state.value.copy(current = it) }
                    }
                })
            }
        }
        waitForIdle()
    }

    private val channelsPicked get() = events.filterIsInstance<FeedEvent.SelectProject>().map { it.slug }

    private fun ComposeUiTest.keys(block: KeyInjectionScope.() -> Unit) {
        onRoot().performKeyInput(block)
        waitForIdle()
    }

    private fun KeyInjectionScope.command(key: Key) = withKeyDown(if (mac) Key.MetaLeft else Key.CtrlLeft) { pressKey(key) }
    private fun KeyInjectionScope.back() = if (mac) withKeyDown(Key.MetaLeft) { pressKey(Key.LeftBracket) } else withKeyDown(Key.AltLeft) { pressKey(Key.DirectionLeft) }
    private fun KeyInjectionScope.forward() = if (mac) withKeyDown(Key.MetaLeft) { pressKey(Key.RightBracket) } else withKeyDown(Key.AltLeft) { pressKey(Key.DirectionRight) }

    private fun ComposeUiTest.mouse(button: MouseButton) {
        onRoot().performMouseInput {
            press(button)
            release(button)
        }
        waitForIdle()
    }

    private val highlighted = SemanticsMatcher.expectValue(Highlighted, true)

    @Test
    fun numberKeysOpenChannelsAndBackAndForwardWalkThem() = runDesktopComposeUiTest(1440, 900) {
        desktop()
        keys { command(Key.Two) }
        keys { command(Key.Three) }
        assertEquals(listOf<String?>("ios-companion", "rust-engine"), channelsPicked)
        keys { back() }
        assertEquals("ios-companion", channelsPicked.last())
        keys { back() }
        assertEquals("agent-dashboard", channelsPicked.last())
        keys { forward() }
        assertEquals("ios-companion", channelsPicked.last())
        // A new channel from the middle drops what was ahead: Forward has nowhere to go.
        keys { command(Key.Four) }
        val before = channelsPicked.size
        keys { forward() }
        assertEquals(before, channelsPicked.size)
    }

    @Test
    fun theMouseBackAndForwardButtonsWalkChannels() = runDesktopComposeUiTest(1440, 900) {
        desktop()
        keys { command(Key.Two) }
        keys { command(Key.Five) }
        mouse(MouseButton(3))
        assertEquals("ios-companion", channelsPicked.last())
        mouse(MouseButton(4))
        assertEquals("infra", channelsPicked.last())
    }

    @Test
    fun altUpAndDownStepThroughTheChannelList() = runDesktopComposeUiTest(1440, 900) {
        desktop()
        keys { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionDown) } }
        assertEquals("ios-companion", channelsPicked.last())
        keys { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionUp) } }
        keys { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionUp) } }
        // Above the first channel is every channel.
        assertEquals(null, channelsPicked.last())
    }

    @Test
    fun questionMarkOpensTheShortcutsAndEscapeClosesThem() = runDesktopComposeUiTest(1440, 900) {
        desktop()
        keys { withKeyDown(Key.ShiftLeft) { pressKey(Key.Slash) } }
        onNodeWithText("Keyboard shortcuts").assertExists()
        onNodeWithContentDescription("Next card: J  or  ↓").assertExists()
        keys { pressKey(Key.Escape) }
        onNodeWithText("Keyboard shortcuts").assertDoesNotExist()
        // Ctrl+/ (⌘/) opens them too.
        keys { command(Key.Slash) }
        onNodeWithText("Keyboard shortcuts").assertExists()
    }

    @Test
    fun slashAndCtrlFOpenSearch() = runDesktopComposeUiTest(1440, 900) {
        desktop()
        keys { pressKey(Key.Slash) }
        onNodeWithContentDescription("Search or run a command").assertExists()
        onNodeWithContentDescription("Search or run a command").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        onNodeWithContentDescription("Search or run a command").assertDoesNotExist()
        keys { command(Key.F) }
        onNodeWithContentDescription("Search or run a command").assertExists()
    }

    @Test
    fun jAndKMoveThroughTheCardsAndRRepliesToOne() = runDesktopComposeUiTest(1440, 900) {
        desktop()
        keys { pressKey(Key.J) }
        waitUntil { onAllNodes(highlighted).fetchSemanticsNodes().size == 1 }
        val first = onAllNodes(highlighted).fetchSemanticsNodes().single().id
        keys { pressKey(Key.J) }
        waitUntil { onAllNodes(highlighted).fetchSemanticsNodes().singleOrNull()?.id.let { it != null && it != first } }
        keys { pressKey(Key.K) }
        waitUntil { onAllNodes(highlighted).fetchSemanticsNodes().singleOrNull()?.id == first }
        keys { pressKey(Key.R) }
        assertTrue(events.any { it is FeedEvent.Reply }, "got $events")
        // R opened the card's reply box with the cursor in it.
        onNodeWithContentDescription("Reply").assertExists()
    }

    @Test
    fun singleKeysNeverFireWhileWriting() = runDesktopComposeUiTest(1440, 900) {
        desktop()
        val box = onNodeWithContentDescription("Message")
        box.performClick()
        box.performTextInput("jk/")
        box.performKeyInput { pressKey(Key.J) }
        waitForIdle()
        onAllNodes(highlighted).fetchSemanticsNodes().let { assertTrue(it.isEmpty(), "no card should be ringed") }
        onNodeWithContentDescription("Search or run a command").assertDoesNotExist()
        // Escape puts the box away, and the single keys work again.
        box.performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        keys { pressKey(Key.J) }
        waitUntil { onAllNodes(highlighted).fetchSemanticsNodes().size == 1 }
    }

    @Test
    fun escapeClosesAThreadAndForwardOpensItAgain() = runDesktopComposeUiTest(1440, 900) {
        desktop()
        keys { pressKey(Key.J) }
        keys { pressKey(Key.R) }
        onNodeWithContentDescription("Reply").assertExists()
        // The first Escape puts the reply box away, the second closes the thread.
        onNodeWithContentDescription("Reply").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        keys { pressKey(Key.Escape) }
        onNodeWithContentDescription("Reply").assertDoesNotExist()
        // Closing it was a step back: Forward opens it again.
        keys { forward() }
        onNodeWithContentDescription("Reply").assertExists()
        keys { back() }
        onNodeWithContentDescription("Reply").assertDoesNotExist()
    }

    @Test
    fun theListNamesEveryShortcutForThisPlatform() {
        val mac = keyboardShortcuts(mac = true, web = false).flatMap { it.shortcuts }
        val pc = keyboardShortcuts(mac = false, web = true).flatMap { it.shortcuts }
        assertEquals("⌘[, the mouse's Back button", mac.first { it.does == "Back" }.keys)
        assertEquals("Alt+←, the browser's Back, the mouse's Back button", pc.first { it.does == "Back" }.keys)
        assertEquals("Alt+→, the browser's Forward, the mouse's Forward button", pc.first { it.does == "Forward" }.keys)
        // In a browser Ctrl+N is the browser's: the list doesn't offer it.
        assertEquals("N", pc.first { it.does == "New post in this channel" }.keys)
    }
}
