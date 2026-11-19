package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.CardField
import dev.wildware.agents.app.feed.CardTag
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedSection
import dev.wildware.agents.app.feed.Reaction
import kotlin.test.Test
import kotlin.test.assertTrue

/** A question wears tags, details and reactions, and pins, as every other card does on the website. */
@OptIn(ExperimentalTestApi::class)
class QuestionExtrasTest {
    private val events = mutableListOf<FeedEvent>()
    private val q = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Question>().first().copy(
        tags = listOf(CardTag("t1", "release")),
        fields = listOf(CardField("branch", "fix/sounds")),
        reactions = listOf(Reaction("👀", 1, mine = true, spoken = "eyes")),
    )

    private fun androidx.compose.ui.test.ComposeUiTest.show(item: FeedItem.Question = q) = setContent {
        AgentTheme(dark = false) {
            Box(Modifier.size(1440.dp, 900.dp)) { FeedScreen(FakeFeed.state.copy(openThread = null, sections = listOf(FeedSection("Waiting on you", listOf(item))))) { events += it } }
        }
    }

    @Test
    fun itShowsItsTagsDetailsAndReactions() = runDesktopComposeUiTest(1440, 900) {
        show()
        onNodeWithText("#release", useUnmergedTree = true).assertExists()
        onNodeWithText("Details (1)", substring = true).assertExists()
        onNodeWithText("👀", substring = true).performClick()
        assertTrue(FeedEvent.React(q.id, "👀") in events, "got $events")
    }

    @Test
    fun theCardMenuPinsTagsAndOpensDetails() = runDesktopComposeUiTest(1440, 900) {
        show()
        onNodeWithText(q.prompt).performMouseInput { rightClick(center) }
        onNodeWithText("Tags…").assertExists()
        onNodeWithText("Details…").assertExists()
        onNodeWithText("Delete").assertDoesNotExist()
        onNodeWithText("Pin").performClick()
        assertTrue(FeedEvent.Pin(q.id, pinned = true) in events, "got $events")
        onNodeWithContentDescription("Pinned").assertExists()
    }
}
