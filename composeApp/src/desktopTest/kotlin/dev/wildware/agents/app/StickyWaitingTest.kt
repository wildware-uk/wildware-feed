package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import kotlin.test.Test
import kotlin.test.assertTrue

/** The "Waiting on you" heading says how many, and stays at the top while its questions scroll by. */
@OptIn(ExperimentalTestApi::class)
class StickyWaitingTest {
    private fun ComposeUiTest.feed(width: Int, state: FeedState, startAt: String? = null) = setContent {
        AgentTheme(dark = true) {
            Box(Modifier.size(width.dp, 844.dp)) { FeedScreen(state, startAt = startAt) }
        }
    }

    @Test
    fun theHeadingCountsTheQuestions() = runComposeUiTest {
        feed(390, FakeFeed.allProjects)
        onNodeWithText("WAITING ON YOU (3)").assertIsDisplayed()
    }

    @Test
    fun oneQuestionNeedsNoCount() = runComposeUiTest {
        val one = FakeFeed.state.copy(
            sections = FakeFeed.state.sections.map { s -> s.copy(items = s.items.filter { it.id != "q2" }) },
        )
        feed(390, one)
        onNodeWithText("WAITING ON YOU").assertIsDisplayed()
    }

    @Test
    fun theHeadingStaysWhileItsQuestionsScrollUnder() = runComposeUiTest {
        feed(390, FakeFeed.allProjects)
        // Down to the last question: the first two have scrolled away under the heading.
        val last = FakeFeed.allProjects.sections.first().items.last() as FeedItem.Question
        // Not composed yet on a phone, so the list scrolls until it is.
        onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(last.prompt))
        onNodeWithText(last.prompt).performScrollTo()
        val heading = onNodeWithText("WAITING ON YOU (3)").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(heading.top < 120f, "the heading is at the top, at ${heading.top}")
    }

    @Test
    fun onDesktopToo() = runComposeUiTest {
        feed(1440, FakeFeed.allProjects)
        val last = FakeFeed.allProjects.sections.first().items.last() as FeedItem.Question
        // Scrolls the list itself: the last question may not be composed yet, so it can't be asked to scroll.
        onNode(androidx.compose.ui.test.hasScrollToNodeAction()).performScrollToNode(androidx.compose.ui.test.hasText(last.prompt))
        onNodeWithText("WAITING ON YOU (3)").assertIsDisplayed()
    }
}
