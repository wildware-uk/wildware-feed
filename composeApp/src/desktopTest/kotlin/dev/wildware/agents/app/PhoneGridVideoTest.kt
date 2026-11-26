package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.Attachment
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.NowPlaying
import dev.wildware.agents.app.feed.Scene
import dev.wildware.agents.app.feed.VideoSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * On a phone, a card with two or more videos shows each in a small tile: too small for the player's
 * own full-screen button (the iPhone drops it). A tap on one opens it full screen instead.
 */
@OptIn(ExperimentalTestApi::class)
class PhoneGridVideoTest {
    private val card = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Update>().first { it.attachments.isEmpty() }
    private fun clip(n: Int, length: String) =
        Attachment("Recording $n", "Recording $n", 16f / 9f, url = null, video = VideoSource("https://dash.test/media/v$n/video", length))
    private val picture = Attachment("Screenshot", "The screenshot", 4f / 3f, url = null, scene = Scene.Dusk)

    @AfterTest
    fun quiet() {
        NowPlaying.id.value = null
    }

    private fun ComposeUiTest.show(tiles: List<Attachment>, width: Int = 390, height: Int = 844, first: String) {
        val p = card.copy(attachments = tiles)
        val state = FakeFeed.state.copy(sections = FakeFeed.state.sections.map { s -> s.copy(items = s.items.map { if (it.id == p.id) p else it }) })
        setContent { AgentTheme(dark = false) { Box(Modifier.size(width.dp, height.dp)) { FeedScreen(state) } } }
        onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasContentDescription(first))
    }

    private fun ComposeUiTest.players() = onAllNodesWithContentDescription("Video").fetchSemanticsNodes().size

    private fun ComposeUiTest.opensFullScreen(label: String) {
        // The tile's own tap, wherever the scroll left it (the last one can sit under the bottom bar).
        onNodeWithContentDescription(label).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        onNodeWithContentDescription("Close video").assertExists()
        // The tile keeps its poster: the video plays full screen, not in the card as well.
        onNodeWithContentDescription(label).assertExists()
        onNodeWithContentDescription("Close video").performClick()
        waitForIdle()
        assertEquals(0, players(), "closed, nothing plays")
    }

    @Test
    fun eachOfTwoVideosOpensFullScreen() = runComposeUiTest {
        show(listOf(clip(1, "0:12"), clip(2, "0:30")), first = "Play video 1 of 2, 0:12")
        opensFullScreen("Play video 1 of 2, 0:12")
        opensFullScreen("Play video 2 of 2, 0:30")
    }

    @Test
    fun eachOfThreeVideosOpensFullScreen() = runComposeUiTest {
        show(listOf(clip(1, "0:12"), clip(2, "0:30"), clip(3, "1:05")), first = "Play video 1 of 3, 0:12")
        opensFullScreen("Play video 1 of 3, 0:12")
        opensFullScreen("Play video 2 of 3, 0:30")
        opensFullScreen("Play video 3 of 3, 1:05")
    }

    @Test
    fun aVideoBesidePicturesOpensFullScreen() = runComposeUiTest {
        show(listOf(picture, clip(1, "0:12")), first = "Play video 2 of 2, 0:12")
        opensFullScreen("Play video 2 of 2, 0:12")
    }

    @Test
    fun aVideoOnItsOwnStillPlaysInItsCard() = runComposeUiTest {
        show(listOf(clip(1, "0:12")), first = "Play video 1 of 1, 0:12")
        onNodeWithContentDescription("Play video 1 of 1, 0:12").performClick()
        waitForIdle()
        assertEquals(1, players())
        onNodeWithContentDescription("Close video").assertDoesNotExist()
        onNodeWithContentDescription("Full screen").assertExists()
    }

    @Test
    fun aComputerStillPlaysGridVideosInTheCard() = runComposeUiTest {
        show(listOf(clip(1, "0:12"), clip(2, "0:30")), width = 1440, height = 900, first = "Play video 2 of 2, 0:30")
        onNodeWithContentDescription("Play video 2 of 2, 0:30").performClick()
        waitForIdle()
        assertEquals(1, players())
        onNodeWithContentDescription("Close video").assertDoesNotExist()
        onNodeWithContentDescription("Full screen").assertExists()
    }
}
