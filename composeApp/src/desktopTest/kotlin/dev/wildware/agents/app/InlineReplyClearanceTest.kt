package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedScreen
import kotlin.test.Test
import kotlin.test.assertTrue

/** On desktop, a thread's reply box never opens under the message box that floats over the feed's end. */
@OptIn(ExperimentalTestApi::class)
class InlineReplyClearanceTest {
    @Test
    fun aReplyBoxNearTheBottomScrollsClearOfTheFloatingComposer() = runComposeUiTest {
        setContent { AgentTheme(dark = true) { Box(Modifier.size(1440.dp, 900.dp)) { FeedScreen(FakeFeed.state) } } }
        waitForIdle()
        // The lowest "Reply" still on screen: its box opens where the floating composer sits.
        // Questions fill the top: scroll until the first card's "Reply" just comes into view, at the bottom.
        onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Reply") and hasClickAction())
        waitForIdle()
        val replies = onAllNodes(hasText("Reply") and hasClickAction())
        val lowest = replies.fetchSemanticsNodes().withIndex()
            .filter { it.value.boundsInRoot.bottom < 900f * density.density }
            .maxBy { it.value.boundsInRoot.top }.index
        replies[lowest].performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        val box = onNode(hasContentDescription("Reply") and hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val composer = onNode(hasContentDescription("Message") and hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        assertTrue(box.bottom <= composer.top - 12 * density.density, "reply box $box sits under the composer $composer")
    }
}
