package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.AppNavigator
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import kotlin.test.Test
import kotlin.test.assertTrue

/** Opening a card from elsewhere (search, a notification, a link) scrolls the feed to it. */
@OptIn(ExperimentalTestApi::class)
class RevealTest {
    private val events = mutableListOf<FeedEvent>()
    private val oldest = "Push alerts failed for 2 devices"

    private fun ComposeUiTest.feed(navigator: AppNavigator? = null) = setContent {
        AgentTheme(dark = true) {
            FeedScreen(FakeFeed.state.copy(openThread = null), navigator = navigator, onEvent = { events += it })
        }
    }

    /** Waits until [times] nodes showing [text] are on screen. */
    private fun ComposeUiTest.assertOnScreen(text: String, times: Int = 1) {
        waitUntil(timeoutMillis = 5_000) {
            val nodes = onAllNodes(hasText(text))
            nodes.fetchSemanticsNodes().indices.count { runCatching { nodes[it].assertIsDisplayed() }.isSuccess } >= times
        }
    }

    @Test
    fun aSearchResultFarDownIsScrolledTo() = runDesktopComposeUiTest(1440, 900) {
        feed()
        onAllNodes(hasText(oldest)).fetchSemanticsNodes().let { assertTrue(it.isEmpty(), "the oldest card starts off screen") }
        onNodeWithContentDescription("Search everything. Shortcut Command K").performClick()
        onNodeWithContentDescription("Search or run a command").performTextInput("push alerts")
        onNodeWithContentDescription("Search or run a command").performKeyInput { pressKey(Key.Enter) }
        // Scrolled to, with its reply box open under it.
        assertOnScreen(oldest)
        onNodeWithContentDescription("Reply").assertExists()
        assertTrue(FeedEvent.OpenThread("u4") in events, "got $events")
    }

    @Test
    fun aCardTheChipsHidShowsAgainWhenOpened() = runDesktopComposeUiTest(1440, 900) {
        feed()
        onNode(hasText("Waiting")).performClick()
        onNodeWithContentDescription("Search everything. Shortcut Command K").performClick()
        onNodeWithContentDescription("Search or run a command").performTextInput("push alerts")
        onNodeWithContentDescription("Search or run a command").performKeyInput { pressKey(Key.Enter) }
        assertOnScreen(oldest)
    }

    @Test
    fun aNotificationScrollsThePhoneFeedToItsCard() = runComposeUiTest {
        val navigator = AppNavigator()
        setContent {
            AgentTheme(dark = true) {
                Box(Modifier.size(390.dp, 844.dp)) {
                    FeedScreen(FakeFeed.state.copy(openThread = null), navigator = navigator, onEvent = { events += it })
                }
            }
        }
        waitForIdle()
        navigator.open("agent-dashboard", "u3")
        // The thread opens over the feed on a phone; close it to see where the feed went.
        waitUntil(timeoutMillis = 5_000) { FeedEvent.OpenThread("u3") in events }
        onNodeWithContentDescription("Back to agent-dashboard").performClick()
        assertOnScreen("Build box disk is 86% full")
    }
}
