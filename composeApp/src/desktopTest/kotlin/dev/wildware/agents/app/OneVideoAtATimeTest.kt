package dev.wildware.agents.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.Attachment
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedUi
import dev.wildware.agents.app.feed.LinkedCard
import dev.wildware.agents.app.feed.LinkedCards
import dev.wildware.agents.app.feed.LocalFeedUi
import dev.wildware.agents.app.feed.MediaGrid
import dev.wildware.agents.app.feed.NowPlaying
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.feed.VideoSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The same video can be on screen twice: on its card and in a preview on a question that links to
 * it, or on a card in the feed and again in the thread open over it. A tap plays only the copy that
 * was tapped, and only one video plays at a time.
 */
@OptIn(ExperimentalTestApi::class)
class OneVideoAtATimeTest {
    private val clip = Attachment("Screen recording", "Screen recording", 16f / 9f, url = null, video = VideoSource("https://dash.test/media/v1/video", "0:12"))
    private val other = Attachment("Other recording", "Other recording", 16f / 9f, url = null, video = VideoSource("https://dash.test/media/v2/video", "0:30"))
    private val ui = FeedUi(ScreenPreview(), mutableStateOf(FakeFeed.state)) {}

    @AfterTest
    fun quiet() {
        NowPlaying.id.value = null
    }

    private fun ComposeUiTest.show(vararg grids: List<Attachment>, linked: Attachment? = null) = setContent {
        CompositionLocalProvider(LocalFeedUi provides ui) {
            AgentTheme(dark = false) {
                Column {
                    for (grid in grids) MediaGrid(grid)
                    linked?.let { LinkedCards(listOf(LinkedCard("update", "u1", who = "forge", what = "update", title = "The recording", media = it))) }
                }
            }
        }
    }

    private fun ComposeUiTest.players() = onAllNodesWithContentDescription("Video").fetchSemanticsNodes().size

    @Test
    fun aVideoOnACardAndInAPreviewOfItPlaysOnce() = runComposeUiTest {
        show(listOf(clip), linked = clip)
        onAllNodesWithContentDescription("Play video 1 of 1, 0:12").onFirst().performClick()
        waitForIdle()
        assertEquals(1, players(), "only the copy that was tapped plays")
    }

    @Test
    fun theSameVideoOnTwoCardsPlaysOnce() = runComposeUiTest {
        show(listOf(clip), listOf(clip))
        onAllNodesWithContentDescription("Play video 1 of 1, 0:12").onFirst().performClick()
        waitForIdle()
        assertEquals(1, players())
    }

    @Test
    fun startingAnotherVideoStopsTheFirst() = runComposeUiTest {
        show(listOf(clip), listOf(other))
        onAllNodesWithContentDescription("Play video 1 of 1, 0:12").onFirst().performClick()
        waitForIdle()
        onAllNodesWithContentDescription("Play video 1 of 1, 0:30").onFirst().performClick()
        waitForIdle()
        assertEquals(1, players())
        assertEquals(0, onAllNodes(hasContentDescription("Play video 1 of 1, 0:30")).fetchSemanticsNodes().size, "the second one is the one playing")
    }

    @Test
    fun startingASoundStopsTheVideo() = runComposeUiTest {
        show(listOf(clip))
        onAllNodesWithContentDescription("Play video 1 of 1, 0:12").onFirst().performClick()
        waitForIdle()
        assertEquals(1, players())
        runOnIdle { NowPlaying.id.value = "sound-1" }
        waitForIdle()
        assertEquals(0, players(), "a sound starting stops the video")
    }

    @Test
    fun aFullScreenVideoStopsTheOneInItsCard() = runComposeUiTest {
        show(listOf(clip))
        onAllNodesWithContentDescription("Play video 1 of 1, 0:12").onFirst().performClick()
        waitForIdle()
        runOnIdle { ui.playing = other.video }
        waitForIdle()
        assertNull(ui.inlineVideo, "playing full screen (the Media tab) stops the card's video")
    }
}
