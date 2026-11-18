package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedScreen
import kotlin.test.Test
import kotlin.test.assertEquals

/** A high-priority update says so, on phones and desktop; others don't. */
@OptIn(ExperimentalTestApi::class)
class HighChipTest {
    private fun ComposeUiTest.feed(width: Int) = setContent {
        AgentTheme(dark = true) {
            Box(Modifier.size(width.dp, 900.dp)) { FeedScreen(FakeFeed.state.copy(openThread = null), startAt = "u3") }
        }
    }

    private fun ComposeUiTest.checkOnlyTheDiskUpdateIsHigh() {
        onNode(hasContentDescription("relay, Ops", substring = true) and hasContentDescription("high priority", substring = true))
            .performScrollTo().assertExists()
        assertEquals(1, onAllNodes(hasContentDescription("high priority", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun onAPhone() = runComposeUiTest {
        feed(390)
        checkOnlyTheDiskUpdateIsHigh()
    }

    @Test
    fun onDesktop() = runComposeUiTest {
        feed(1440)
        checkOnlyTheDiskUpdateIsHigh()
    }
}
