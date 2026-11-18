package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A long body is cut to a screen's worth with a fade, until the owner asks for the rest. */
@OptIn(ExperimentalTestApi::class)
class ClampTest {
    private val long = (1..40).joinToString("\n\n") { "Paragraph $it of a very long report about the build box and its caches." }

    /** The fix update with a body far taller than a screen. */
    private val state: FeedState = FakeFeed.state.copy(
        openThread = null,
        sections = FakeFeed.state.sections.map { s ->
            s.copy(items = s.items.map { if (it is FeedItem.Update && it.id == "u1") it.copy(body = long) else it })
        },
    )

    private fun ComposeUiTest.feed(width: Int, s: FeedState = state) = setContent {
        AgentTheme(dark = true) {
            Box(Modifier.size(width.dp, 900.dp)) { FeedScreen(s, startAt = "u1") }
        }
    }

    @Test
    fun aLongUpdateIsCutShortUntilAskedForTheRest() = runComposeUiTest {
        feed(390)
        val toggle = onNodeWithText("Show the whole update").performScrollTo()
        toggle.assertIsDisplayed()
        // The end of the report is there, just folded away under the fade.
        onNodeWithText("Paragraph 40", substring = true).assertIsNotDisplayed()
        toggle.performClick()
        onNodeWithText("Paragraph 40", substring = true).performScrollTo().assertIsDisplayed()
        // Its click, not a tap: scrolled to the very bottom, the composer bar sits over it.
        onNodeWithText("Show less").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        // Folding from the bottom scrolls back to the card, so the button is right there.
        onNodeWithText("Show the whole update").assertIsDisplayed()
    }

    @Test
    fun theFoldIsAboutAScreenTall() = runComposeUiTest {
        feed(1440)
        val toggle = onNodeWithText("Show the whole update").performScrollTo().fetchSemanticsNode().boundsInRoot
        val first = onNodeWithText("Paragraph 1 of", substring = true).fetchSemanticsNode().boundsInRoot
        val shown = toggle.top - first.top
        assertTrue(shown in 380f..470f, "the folded body is $shown px tall")
    }

    @Test
    fun aShortUpdateHasNoToggle() = runComposeUiTest {
        feed(390, FakeFeed.state.copy(openThread = null))
        assertEquals(0, onAllNodesWithText("Show the whole", substring = true).fetchSemanticsNodes().size)
    }
}
