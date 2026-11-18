package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.AppNavigator
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.PendingFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Android's share sheet hands words and pictures to the navigator; they land in the project's composer to check and send. */
@OptIn(ExperimentalTestApi::class)
class ShareTest {
    @Test
    fun aShareWaitsInTheComposerUntilSent() = runComposeUiTest {
        val events = mutableListOf<FeedEvent>()
        val navigator = AppNavigator()
        val picture = PendingFile("shot.png", "image/png", byteArrayOf(1, 2, 3))
        // Shared before the feed is on screen, as when the share sheet launches the app.
        navigator.share("Look at this https://example.com", listOf(picture))
        setContent {
            AgentTheme(dark = true) {
                Box(Modifier.size(390.dp, 844.dp)) {
                    FeedScreen(FakeFeed.state, navigator = navigator, onEvent = { events += it })
                }
            }
        }
        waitForIdle()
        assertNull(navigator.shares.value)
        onNodeWithText("Look at this https://example.com").assertExists()
        onNodeWithContentDescription("Attached: shot.png").assertExists()
        // Nothing goes until the owner sends it.
        assertEquals(emptyList(), events.filterIsInstance<FeedEvent.Post>())

        onNodeWithContentDescription("Send").performClick()
        waitForIdle()
        val post = events.filterIsInstance<FeedEvent.Post>().single()
        assertEquals("Look at this https://example.com", post.text)
        assertEquals(listOf("shot.png"), post.files.map { it.name })
    }
}
