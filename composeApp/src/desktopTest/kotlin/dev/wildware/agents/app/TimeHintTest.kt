package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedSection
import dev.wildware.agents.app.feed.TimeText
import kotlin.test.Test

/** A card's time says how long ago; resting the pointer on it shows the whole instant, and screen readers hear it. */
@OptIn(ExperimentalTestApi::class)
class TimeHintTest {
    private val detail = "Posted Wednesday 18 November 2026 at 11:00 · updated Wednesday 18 November 2026 at 11:55"

    @Test
    fun restingOnTheTimeShowsTheWholeInstant() = runComposeUiTest {
        setContent { AgentTheme(dark = false) { Box(Modifier.size(600.dp, 200.dp).padding(20.dp)) { TimeText("updated 5m ago", detail, Modifier.testTag("time")) } } }
        onNodeWithText(detail).assertDoesNotExist()
        onNodeWithTag("time").performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(700)
        onNodeWithText(detail).assertExists()
        onNodeWithTag("time").performMouseInput { moveTo(androidx.compose.ui.geometry.Offset(-200f, -200f)) }
        mainClock.advanceTimeBy(100)
        onNodeWithText(detail).assertDoesNotExist()
    }

    @Test
    fun theCardReadsOutTheWholeInstant() = runComposeUiTest {
        val u = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Update>().first().copy(time = "updated 5m ago", timeDetail = detail)
        val state = FakeFeed.state.copy(sections = listOf(FeedSection("Today", listOf(u))))
        setContent { AgentTheme(dark = false) { Box(Modifier.size(1440.dp, 900.dp)) { FeedScreen(state) {} } } }
        onNodeWithContentDescription(detail, substring = true).assertExists()
        // And resting on it inside a card, past the card's own gestures, shows it too.
        onNodeWithText("updated 5m ago", useUnmergedTree = true).performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(700)
        onNodeWithText(detail).assertExists()
    }
}
