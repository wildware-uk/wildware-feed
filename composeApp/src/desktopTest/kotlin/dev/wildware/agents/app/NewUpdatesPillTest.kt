package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import kotlin.test.Test
import kotlin.test.assertEquals

/** New updates wait while the owner reads further down, and a pill says how many, as on the website. */
@OptIn(ExperimentalTestApi::class)
class NewUpdatesPillTest {
    private val away = mutableListOf<Boolean>()

    // Reduced motion, so going back to the top is a jump the test clock needn't play out.
    private fun ComposeUiTest.show(state: FeedState, startAt: String? = null) = setContent {
        AgentTheme(dark = false, accessibility = dev.wildware.agents.app.design.AccessibilityPrefs(reduceMotion = true)) {
            Box(Modifier.size(1440.dp, 900.dp)) { FeedScreen(state, startAt = startAt, onAwayFromTop = { away += it }) }
        }
    }

    @Test
    fun leavingTheTopHoldsArrivalsAndComingBackLetsThemIn() = runComposeUiTest {
        show(FakeFeed.state)
        waitForIdle()
        assertEquals(false, away.last())
        onNode(hasScrollToIndexAction()).performScrollToIndex(5)
        waitForIdle()
        assertEquals(true, away.last())
        onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        waitForIdle()
        assertEquals(false, away.last())
    }

    @Test
    fun whileAwayThePillCountsWhatIsWaitingAndGoesToTheTop() = runComposeUiTest {
        show(FakeFeed.state.copy(newCount = 3), startAt = "u3")
        onNodeWithText("3 new updates").performClick()
        // Back at the top, the held updates come in.
        waitUntil(timeoutMillis = 5_000) { away.last() == false }
    }

    @Test
    fun oneIsSaidAsOne() = runComposeUiTest {
        show(FakeFeed.state.copy(newCount = 1), startAt = "u3")
        onNodeWithText("1 new update").assertExists()
    }

    @Test
    fun atTheTopThereIsNoPill() = runComposeUiTest {
        show(FakeFeed.state.copy(newCount = 2))
        onNodeWithText("2 new updates").assertDoesNotExist()
    }
}
