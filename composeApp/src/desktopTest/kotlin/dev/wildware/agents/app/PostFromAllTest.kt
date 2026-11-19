package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import kotlin.test.Test
import kotlin.test.assertEquals

/** On "All projects" the composer is there too, with the project it posts to. */
@OptIn(ExperimentalTestApi::class)
class PostFromAllTest {
    private val events = mutableListOf<FeedEvent>()

    private fun ComposeUiTest.feed(width: Int) = setContent {
        AgentTheme(dark = true) {
            Box(Modifier.size(width.dp, 900.dp)) { FeedScreen(FakeFeed.allProjects, onEvent = { events += it }) }
        }
    }

    private fun ComposeUiTest.write(text: String) {
        onNodeWithContentDescription("Message").performTextInput(text)
        onNodeWithContentDescription("Send").performClick()
        waitForIdle()
    }

    @Test
    fun itPostsToTheFirstProjectUntilAnotherIsPicked() = runComposeUiTest {
        feed(1440)
        onNode(hasContentDescription("Posting to agent-dashboard. Choose a channel")).assertExists()
        write("Morning, everyone")
        assertEquals(listOf<FeedEvent>(FeedEvent.Post("Morning, everyone", project = "agent-dashboard")), events.filterIsInstance<FeedEvent.Post>())
    }

    @Test
    fun itPostsToTheProjectTheAppRemembers() = runComposeUiTest {
        setContent {
            AgentTheme(dark = true) {
                Box(Modifier.size(1440.dp, 900.dp)) { FeedScreen(FakeFeed.allProjects.copy(postTarget = "infra"), onEvent = { events += it }) }
            }
        }
        onNode(hasContentDescription("Posting to infra. Choose a channel")).assertExists()
        write("Disk alarm again")
        assertEquals(listOf<FeedEvent>(FeedEvent.Post("Disk alarm again", project = "infra")), events.filterIsInstance<FeedEvent.Post>())
    }

    @Test
    fun pickingAProjectPostsThere() = runComposeUiTest {
        feed(1440)
        onNode(hasContentDescription("Posting to", substring = true)).performClick()
        onNode(hasContentDescription("Post to website")).performClick()
        onNode(hasContentDescription("Posting to website. Choose a channel")).assertExists()
        write("New colours are up")
        assertEquals(listOf<FeedEvent>(FeedEvent.Post("New colours are up", project = "website")), events.filterIsInstance<FeedEvent.Post>())
    }

    @Test
    fun onAPhoneToo() = runComposeUiTest {
        feed(390)
        onNode(hasContentDescription("Posting to", substring = true)).performClick()
        onNode(hasContentDescription("Post to infra")).performClick()
        write("Disk is fine now")
        assertEquals(listOf<FeedEvent>(FeedEvent.Post("Disk is fine now", project = "infra")), events.filterIsInstance<FeedEvent.Post>())
    }
}
