package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** On a phone, every card in "All projects" says which project it is from. */
@OptIn(ExperimentalTestApi::class)
class ProjectLabelTest {
    private val events = mutableListOf<FeedEvent>()

    private fun ComposeUiTest.phone(state: FeedState, startAt: String? = null, width: Int = 390) = setContent {
        AgentTheme(dark = true) {
            Box(Modifier.size(width.dp, 844.dp)) {
                FeedScreen(state, startAt = startAt, onEvent = { events += it })
            }
        }
    }

    private fun ComposeUiTest.labels(project: String) = onAllNodesWithContentDescription("In project $project")

    @Test
    fun aQuestionCardShowsItsProject() = runComposeUiTest {
        phone(FakeFeed.allProjects)
        labels("agent-dashboard").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty(), "the question from agent-dashboard has no project label") }
    }

    @Test
    fun anUpdateCardShowsItsProject() = runComposeUiTest {
        phone(FakeFeed.allProjects, startAt = "u3")
        onNode(hasContentDescription("In project infra")).performScrollTo().assertExists()
    }

    @Test
    fun anOlderCardFarDownShowsItsProject() = runComposeUiTest {
        phone(FakeFeed.allProjects, startAt = "u4")
        onNode(hasContentDescription("In project ios-companion")).performScrollTo().assertExists()
    }

    @Test
    fun tappingTheLabelOpensThatProject() = runComposeUiTest {
        phone(FakeFeed.allProjects, startAt = "u3")
        onNode(hasContentDescription("In project infra")).performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.SelectProject("infra")), events)
    }

    @Test
    fun aVeryNarrowPhoneStillShowsTheLabel() = runComposeUiTest {
        phone(FakeFeed.allProjects, startAt = "u3", width = 320)
        onNode(hasContentDescription("In project infra")).performScrollTo().assertExists()
    }

    @Test
    fun insideOneProjectTheLabelIsLeftOut() = runComposeUiTest {
        phone(FakeFeed.state)
        assertEquals(0, onAllNodes(hasContentDescription("In project", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun onAPhoneAQuestionShowsTheWholeProjectNameInsteadOfRepeatingWaitingOnYou() = runComposeUiTest {
        phone(FakeFeed.allProjects)
        // Only the section heading says it; the cards under it don't repeat it.
        onAllNodes(hasText("WAITING ON YOU")).assertCountEquals(1)
        onAllNodes(hasContentDescription("waiting on you", substring = true, ignoreCase = true)).onFirst().assertExists()
        // The question's label is as wide as the same label on a post, where nothing squeezes it.
        val onQuestion = onAllNodes(hasContentDescription("In project agent-dashboard")).onFirst().fetchSemanticsNode().size.width
        onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Can someone find out", substring = true))
        val all = onAllNodes(hasContentDescription("In project agent-dashboard")).fetchSemanticsNodes()
        val onPost = all.last().size.width
        assertEquals(onPost, onQuestion, "the project name on the question is cut short")
    }
}
