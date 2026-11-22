package dev.wildware.agents.app

import androidx.compose.ui.test.onNodeWithText
import dev.wildware.agents.app.feed.LocalCardFolds
import dev.wildware.agents.app.feed.CardFolds
import dev.wildware.agents.MemoryTextStore
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.AppNavigator
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.Highlighted
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A link to a card (a notification, a widget, search) finds it: through a reply to its card, and
 * rings it for a moment. One the feed hasn't loaded opens on its own, with no older page read; when
 * the dashboard has no such card, it says so instead of doing nothing.
 */
@OptIn(ExperimentalTestApi::class)
class DeepLinkFocusTest {
    private val events = mutableListOf<FeedEvent>()
    private val navigator = AppNavigator()
    private val fix = FakeFeed.state.openThread as FeedItem.Update
    private var state by mutableStateOf(FakeFeed.state.copy(openThread = null))

    private fun ComposeUiTest.desktop(folds: CardFolds = CardFolds(MemoryTextStore()), onEvent: (FeedEvent) -> Unit = {}) {
        setContent {
            CompositionLocalProvider(LocalCardFolds provides folds) {
                AgentTheme(dark = false) {
                    Box(Modifier.size(1440.dp, 900.dp)) {
                        FeedScreen(state, navigator = navigator) { events += it; onEvent(it) }
                    }
                }
            }
        }
        waitForIdle()
    }

    private val ringed = SemanticsMatcher.expectValue(Highlighted, true)

    @Test
    fun aLinkToAReplyOpensTheCardItIsIn() = runComposeUiTest {
        desktop()
        navigator.open("agent-dashboard", fix.replies.last().id)
        waitUntil(timeoutMillis = 5_000) { FeedEvent.OpenThread(fix.id) in events }
    }

    @Test
    fun theCardIsRingedForAMomentThenNot() = runComposeUiTest {
        desktop()
        navigator.open("agent-dashboard", fix.id)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(ringed).fetchSemanticsNodes().isNotEmpty() }
        mainClock.advanceTimeBy(4_500)
        waitForIdle()
        assertTrue(onAllNodes(ringed).fetchSemanticsNodes().isEmpty(), "the ring goes after a few seconds")
    }

    @Test
    fun aCardNotLoadedOpensOnItsOwnWithoutReadingOlderPages() = runComposeUiTest {
        state = state.copy(hasMore = true)
        val old = fix.copy(id = "u-old", title = "Something from last month", replies = emptyList(), unreadReplies = 0)
        desktop { e ->
            // The engine reads the card by itself and hands it back.
            if (e is FeedEvent.OpenAlone) {
                state = state.copy(lone = old)
                e.result("u-old")
            }
        }
        navigator.open("agent-dashboard", "u-old")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Something from last month")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Back to feed").assertExists()
        assertTrue(events.any { it is FeedEvent.OpenAlone && it.id == "u-old" }, "got $events")
        assertTrue(events.none { it is FeedEvent.LoadOlder }, "no page is read to find it")
    }

    @Test
    fun aCardTheDashboardDoesNotHaveSaysSo() = runComposeUiTest {
        state = state.copy(hasMore = true)
        desktop { e -> if (e is FeedEvent.OpenAlone) e.result(null) }
        navigator.open("agent-dashboard", "u-gone")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Couldn't find what that link pointed at", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(events.none { it is FeedEvent.LoadOlder })
    }

    @Test
    fun aFoldedCardOpensWhenLinkedTo() = runComposeUiTest {
        val folds = CardFolds(MemoryTextStore()).also { it.set(fix.id, true) }
        desktop(folds)
        navigator.open("agent-dashboard", fix.id)
        waitUntil(timeoutMillis = 5_000) { !folds.isFolded(fix.id) }
        onNodeWithText("Unread state is now kept", substring = true).assertExists()
    }
}
