package dev.wildware.agents.app

import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.onRoot
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.Attachment
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.VideoSource
import dev.wildware.agents.app.feed.playsVideoInApp
import kotlin.test.Test
import kotlin.test.assertTrue

/** A tap on a video plays it right there in the card, in the app, with a way to fill the window. */
@OptIn(ExperimentalTestApi::class)
class InlineVideoTest {
    // An update: media on posts arrives separately (#65).
    private val card = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Update>().first { it.attachments.isEmpty() }
    private val clip = Attachment("Screen recording", "Screen recording", 16f / 9f, url = null, video = VideoSource("https://dash.test/media/v1/video", "0:12"))

    private fun ComposeUiTest.show() {
        val p = card.copy(attachments = listOf(clip))
        val state = FakeFeed.state.copy(sections = FakeFeed.state.sections.map { s -> s.copy(items = s.items.map { if (it.id == p.id) p else it }) })
        setContent { AgentTheme(dark = false) { Box(Modifier.size(1440.dp, 900.dp)) { FeedScreen(state) } } }
        onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Play video 1 of 1, 0:12"))
    }

    @Test
    fun desktopsPlayVideosInTheApp() {
        assertTrue(playsVideoInApp, "no download, no system player")
    }

    @Test
    fun aTapPlaysTheVideoInItsCard() = runComposeUiTest {
        show()
        onNodeWithContentDescription("Play video 1 of 1, 0:12").performClick()
        // The player takes the tile's place, with its own controls and a way to fill the window.
        onNodeWithContentDescription("Video").assertExists()
        onNodeWithContentDescription("Full screen").assertExists()
    }

    @Test
    fun aVideoOnAReplyPlaysInTheAppToo() = runComposeUiTest {
        val post = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Post>().first()
        val reply = dev.wildware.agents.app.feed.Reply("rv", dev.wildware.agents.app.feed.Author.Owner("Sam"), "9:00", "The recording", attachments = listOf(clip))
        val p = post.copy(replies = listOf(reply))
        val state = FakeFeed.state.copy(openThread = null, sections = FakeFeed.state.sections.map { s -> s.copy(items = s.items.map { if (it.id == p.id) p else it }) })
        setContent { AgentTheme(dark = false) { Box(Modifier.size(1440.dp, 900.dp)) { FeedScreen(state) } } }
        onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Play video 1 of 1, 0:12"))
        onNodeWithContentDescription("Play video 1 of 1, 0:12").performClick()
        onNodeWithContentDescription("Video").assertExists()
    }

    @Test
    fun fullScreenFillsTheWindow() = runComposeUiTest {
        show()
        onNodeWithContentDescription("Play video 1 of 1, 0:12").performClick()
        onNodeWithContentDescription("Full screen").performClick()
        onNodeWithContentDescription("Close video").assertExists()
    }

    @Test
    fun escapeClosesTheFullWindowVideo() = runComposeUiTest {
        show()
        onNodeWithContentDescription("Play video 1 of 1, 0:12").performClick()
        onNodeWithContentDescription("Full screen").performClick()
        onNodeWithContentDescription("Close video").assertExists()
        onRoot().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Escape) }
        onNodeWithContentDescription("Close video").assertDoesNotExist()
    }
}
