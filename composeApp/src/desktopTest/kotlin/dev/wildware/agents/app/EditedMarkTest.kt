package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import kotlin.test.Test

/** A rewritten card, question or reply says "edited", and reads out when. */
@OptIn(ExperimentalTestApi::class)
class EditedMarkTest {
    private val items = FakeFeed.state.sections.flatMap { it.items }

    @Test
    fun editedCardsSaySoAndReadOutWhen() = runComposeUiTest {
        val q = items.filterIsInstance<FeedItem.Question>().first().copy(edited = "Edited Wednesday 18 November 2026 at 10:05")
        val u = items.filterIsInstance<FeedItem.Update>().first().copy(edited = "Edited Wednesday 18 November 2026 at 10:20")
        val plain = items.filterIsInstance<FeedItem.Update>()[1]
        val state = FakeFeed.state.copy(sections = listOf(FakeFeed.state.sections.first().copy(items = listOf(q, u, plain))))
        setContent { AgentTheme(dark = false) { Box(Modifier.size(1440.dp, 1600.dp)) { FeedScreen(state) {} } } }
        onNodeWithContentDescription("Edited Wednesday 18 November 2026 at 10:05").assertExists()
        onNodeWithContentDescription("Edited Wednesday 18 November 2026 at 10:20", substring = true).assertExists()
    }
}
